package com.aimediaeditor.app.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aimediaeditor.app.data.media.AudioItem
import com.aimediaeditor.app.data.media.AudioRepository
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.editor.export.ExportWorker
import com.aimediaeditor.app.editor.export.PendingExportHolder
import com.aimediaeditor.app.editor.model.EditCommand
import com.aimediaeditor.app.editor.model.ProjectHistory
import com.aimediaeditor.app.editor.model.ProjectState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.random.Random

data class EditorUiState(
    val project: ProjectState,
    val selectedClipId: String?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val availableAudio: List<AudioItem> = emptyList()
)

/**
 * Selection and "what's on screen" live here as plain StateFlow updates.
 * Every actual EDIT goes through [ProjectHistory] -> [com.aimediaeditor.app.editor.model.ProjectSanitizer],
 * same as everywhere else in this app -- this class never mutates [ProjectState] directly.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val audioRepository = AudioRepository(application)
    private var history: ProjectHistory? = null
    private var initialized = false

    private val _uiState = MutableStateFlow(freshEmptyState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    /** Safe to call every time EditorScreen recomposes -- only takes effect once per project. */
    fun initializeProject(items: List<MediaItem>) {
        if (initialized) return
        initialized = true
        val initial = ProjectState(id = "project_${Random.nextInt()}", clips = items.toVideoClips())
        history = ProjectHistory(initial)
        _uiState.value = _uiState.value.copy(project = initial, selectedClipId = initial.clips.firstOrNull()?.id)
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

    private fun syncFromHistory(h: ProjectHistory) {
        val current = _uiState.value
        val stillExists = current.selectedClipId != null && h.current.clips.any { it.id == current.selectedClipId }
        _uiState.value = current.copy(
            project = h.current,
            canUndo = h.canUndo,
            canRedo = h.canRedo,
            selectedClipId = if (stillExists) current.selectedClipId else h.current.clips.firstOrNull()?.id
        )
    }

    private companion object {
        fun freshEmptyState() = EditorUiState(
            project = ProjectState(id = "project_${Random.nextInt()}"),
            selectedClipId = null,
            canUndo = false,
            canRedo = false
        )
    }
}
