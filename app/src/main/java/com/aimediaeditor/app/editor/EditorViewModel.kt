package com.aimediaeditor.app.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aimediaeditor.app.data.media.AudioItem
import com.aimediaeditor.app.data.media.AudioRepository
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.project.ProjectRecord
import com.aimediaeditor.app.data.project.ProjectRepository
import com.aimediaeditor.app.editor.export.ExportWorker
import com.aimediaeditor.app.editor.export.PendingExportHolder
import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.ProjectHistory
import com.aimediaeditor.app.editor.model.ProjectState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class EditorUiState(
    val project: ProjectState,
    val projectName: String,
    val selectedClipId: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val isLoading: Boolean = false,
    val availableAudio: List<AudioItem> = emptyList()
)

/** How long to let rapid edits (e.g. a trim drag) settle before writing to disk. */
private const val AUTOSAVE_DEBOUNCE_MS = 800L

/**
 * Selection and "what's on screen" live here as plain StateFlow updates.
 * Every actual EDIT goes through [ProjectHistory] -> [com.aimediaeditor.app.editor.model.ProjectSanitizer],
 * same as everywhere else in this app -- this class never mutates [ProjectState] directly.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val audioRepository = AudioRepository(application)
    private val projectRepository = ProjectRepository(application)
    private var history: ProjectHistory? = null
    private var initialized = false
    private var createdAtMs = System.currentTimeMillis()
    private var autosaveJob: Job? = null

    private val _uiState = MutableStateFlow(freshEmptyState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** Safe to call every time EditorScreen recomposes -- only takes effect once per project. */
    fun initializeProject(items: List<MediaItem>) {
        if (initialized) return
        initialized = true
        createdAtMs = System.currentTimeMillis()
        val initial = ProjectState(id = "project_${Random.nextInt()}", clips = items.toVideoClips())
        history = ProjectHistory(initial)
        _uiState.value = _uiState.value.copy(
            project = initial,
            projectName = defaultProjectName(createdAtMs),
            selectedClipId = initial.clips.firstOrNull()?.id
        )
        // Save immediately so a brand-new project shows up in the Projects list and
        // survives a crash even before the first edit (spec section 22).
        saveNow()
    }

    /**
     * Loads a previously-saved project by id (reopening from the Projects list, or the
     * app relaunching onto an in-progress draft). Falls through to an empty project if
     * the id is unknown or its file is unreadable, rather than leaving the screen stuck
     * loading -- the sanitizer/history below never sees a null project either way.
     */
    fun loadProject(projectId: String) {
        if (initialized) return
        initialized = true
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch {
            val record = projectRepository.load(projectId)
            val project = record?.project ?: ProjectState(id = projectId)
            createdAtMs = record?.createdAtMs ?: System.currentTimeMillis()
            history = ProjectHistory(project)
            _uiState.value = _uiState.value.copy(
                project = project,
                projectName = record?.name ?: defaultProjectName(createdAtMs),
                selectedClipId = project.clips.firstOrNull()?.id,
                isLoading = false
            )
        }
    }

    fun onCommand(command: EditCommand) {
        history?.let { h ->
            h.apply(command)
            syncFromHistory(h)
        }
    }

    fun undo() = history?.let { it.undo(); syncFromHistory(it) } ?: Unit
    fun redo() = history?.let { it.redo(); syncFromHistory(it) } ?: Unit

    fun selectClip(clipId: String) {
        _uiState.value = _uiState.value.copy(selectedClipId = clipId)
    }

    fun renameProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.value = _uiState.value.copy(projectName = trimmed)
        scheduleAutosave()
    }

    fun loadAvailableAudio() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(availableAudio = audioRepository.loadAudio())
        }
    }

    /**
     * Enqueues the export and returns its WorkManager request ID so the UI
     * can observe progress. The project itself travels via PendingExportHolder
     * (see that file for why), not as WorkManager input data.
     */
    fun exportProject(): UUID {
        PendingExportHolder.project = _uiState.value.project
        val request = OneTimeWorkRequestBuilder<ExportWorker>().build()
        WorkManager.getInstance(getApplication()).enqueue(request)
        return request.id
    }

    /**
     * Cancels any pending debounced write and saves immediately. Call this from a
     * lifecycle boundary (the app backgrounding, the user navigating back) where losing
     * up to [AUTOSAVE_DEBOUNCE_MS] of debounce would otherwise risk losing an edit.
     */
    fun saveNow() {
        if (history == null) return
        autosaveJob?.cancel()
        viewModelScope.launch { persist() }
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DEBOUNCE_MS)
            persist()
        }
    }

    private suspend fun persist() {
        val state = _uiState.value
        val h = history ?: return
        projectRepository.save(
            ProjectRecord(
                project = h.current,
                name = state.projectName,
                createdAtMs = createdAtMs,
                updatedAtMs = System.currentTimeMillis()
            )
        )
    }

    private fun syncFromHistory(h: ProjectHistory) {
        val current = _uiState.value
        val stillExists = current.selectedClipId != null && h.current.clips.any { it.id == current.selectedClipId }
        _uiState.value = current.copy(
            project = h.current,
            canUndo = h.canUndo,
            canRedo = h.canRedo,
            selectedClipId = if (stillExists) current.selectedClipId else h.current.clips.firstOrNull()?.id
        )
        scheduleAutosave()
    }

    private companion object {
        fun freshEmptyState() = EditorUiState(
            project = ProjectState(id = "project_${Random.nextInt()}"),
            projectName = "Untitled Project",
            selectedClipId = null,
            canUndo = false,
            canRedo = false
        )

        fun defaultProjectName(atMs: Long): String =
            "Project ${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(atMs))}"
    }
}
