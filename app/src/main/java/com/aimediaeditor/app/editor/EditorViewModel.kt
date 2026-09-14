package com.aimediaeditor.app.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aimediaeditor.app.ai.AiEditResult
import com.aimediaeditor.app.ai.LocalEditCommandService
import com.aimediaeditor.app.ai.LocalLlmModelManager
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
    val availableAudio: List<AudioItem> = emptyList(),
    val isAiLoading: Boolean = false,
    // Set after a request completes -- a fallback note when the local model's response
    // didn't contain a recognizable JSON array, and/or a request-level failure message
    // (model failed to load, generation threw). Cleared by the caller once shown; see
    // EditorViewModel.clearAiFeedback.
    val aiMessage: String? = null,
    val aiError: String? = null,
    // On-device model readiness -- see LocalLlmModelManager. isModelDownloading and
    // modelDownloadProgress (0f..1f, null while the server hasn't reported a total
    // size yet) only matter while a download is in flight.
    val isModelReady: Boolean = false,
    val isModelDownloading: Boolean = false,
    val modelDownloadProgress: Float? = null,
    val modelDownloadError: String? = null
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
    private val modelManager = LocalLlmModelManager(application)
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
     * Sends [prompt] to the on-device model alongside the current project's state,
     * then applies every [EditCommand] it comes back with through the SAME
     * [ProjectHistory] -> [com.aimediaeditor.app.editor.model.ProjectSanitizer] path
     * every manual edit goes through -- the AI layer never touches [ProjectState]
     * directly (architecture notes section 7), regardless of whether the commands
     * came from a cloud call (the original design) or local inference (this round).
     * Multiple commands from one response are applied as a single history step's
     * worth of state updates (one [syncFromHistory] call at the end, not one per
     * command), so a multi-command AI edit doesn't flash through intermediate states
     * on screen.
     */
    fun submitAiPrompt(prompt: String) {
        val h = history ?: return
        if (_uiState.value.isAiLoading) return // one request in flight at a time
        if (!modelManager.isModelReady()) return
        _uiState.value = _uiState.value.copy(isAiLoading = true, aiMessage = null, aiError = null)
        viewModelScope.launch {
            val result = LocalEditCommandService.requestEdit(
                getApplication(), modelManager.modelFilePath(), h.current, prompt
            )
            when (result) {
                is AiEditResult.Success -> {
                    result.commands.forEach { h.apply(it) }
                    syncFromHistory(h)
                    _uiState.value = _uiState.value.copy(isAiLoading = false, aiMessage = result.assistantMessage)
                }
                is AiEditResult.Failure -> {
                    _uiState.value = _uiState.value.copy(isAiLoading = false, aiError = result.message)
                }
            }
        }
    }

    fun clearAiFeedback() {
        _uiState.value = _uiState.value.copy(aiMessage = null, aiError = null)
    }

    /** Safe to call every time EditorScreen enters composition -- cheap local file check. */
    fun refreshModelReady() {
        _uiState.value = _uiState.value.copy(isModelReady = modelManager.isModelReady())
    }

    /**
     * Downloads the on-device model using the user's own Hugging Face token (see
     * [LocalLlmModelManager]'s doc comment for why one is needed -- the model repo is
     * gated behind the Gemma license). This is the app's only network call; once it
     * succeeds, [submitAiPrompt] and every other AI feature run fully offline.
     */
    fun downloadModel(hfToken: String) {
        if (_uiState.value.isModelDownloading) return
        _uiState.value = _uiState.value.copy(isModelDownloading = true, modelDownloadProgress = null, modelDownloadError = null)
        viewModelScope.launch {
            val result = modelManager.downloadModel(hfToken) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else null
                _uiState.value = _uiState.value.copy(modelDownloadProgress = progress)
            }
            _uiState.value = _uiState.value.copy(
                isModelDownloading = false,
                isModelReady = modelManager.isModelReady(),
                modelDownloadError = result.exceptionOrNull()?.message
            )
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
