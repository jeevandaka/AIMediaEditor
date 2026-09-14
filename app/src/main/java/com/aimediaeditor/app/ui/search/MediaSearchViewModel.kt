package com.aimediaeditor.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediaeditor.app.ai.LocalLlmModelManager
import com.aimediaeditor.app.data.index.AiSearchOutcome
import com.aimediaeditor.app.data.index.MediaIndexRepository
import com.aimediaeditor.app.data.media.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val query: String, val items: List<MediaItem>, val assistantMessage: String? = null) : SearchUiState
}

data class ModelUiState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float? = null,
    val error: String? = null
)

/**
 * Spec section 3's "AI Search". [search] is "Stage 1" of the search chain
 * (deterministic keyword/date/label matching over the local index, see
 * [MediaIndexRepository] and [com.aimediaeditor.app.data.index.MediaSearchQuery]) --
 * instant, free, works with no model downloaded. [aiSearch] is "Stage 2" (the on-device
 * model reasoning over a Stage-1-narrowed candidate list, see
 * [MediaIndexRepository.aiSearch]) -- slower, needs the model downloaded first, but
 * understands phrasing Stage 1's keyword matching can't (a named event, a general
 * vibe/mood). Both write to the same [uiState] since the results screen doesn't care
 * which pass produced them.
 */
class MediaSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val indexRepository = MediaIndexRepository(application)
    private val modelManager = LocalLlmModelManager(application)

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _modelState = MutableStateFlow(ModelUiState())
    val modelState: StateFlow<ModelUiState> = _modelState.asStateFlow()

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.value = SearchUiState.Idle
            return
        }
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            val results = indexRepository.search(trimmed)
            _uiState.value = SearchUiState.Results(trimmed, results)
        }
    }

    fun aiSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !modelManager.isModelReady()) return
        viewModelScope.launch {
            _uiState.value = SearchUiState.Loading
            when (val outcome = indexRepository.aiSearch(getApplication(), modelManager.modelFilePath(), trimmed)) {
                is AiSearchOutcome.Success ->
                    _uiState.value = SearchUiState.Results(trimmed, outcome.items, outcome.assistantMessage)
                is AiSearchOutcome.Failure ->
                    _uiState.value = SearchUiState.Results(trimmed, emptyList(), outcome.message)
            }
        }
    }

    /** Safe to call every time MediaSearchScreen enters composition -- cheap local file check. */
    fun refreshModelReady() {
        _modelState.value = _modelState.value.copy(isReady = modelManager.isModelReady())
    }

    fun downloadModel(hfToken: String) {
        if (_modelState.value.isDownloading) return
        _modelState.value = _modelState.value.copy(isDownloading = true, progress = null, error = null)
        viewModelScope.launch {
            val result = modelManager.downloadModel(hfToken) { downloaded, total ->
                val progress = if (total > 0) downloaded.toFloat() / total.toFloat() else null
                _modelState.value = _modelState.value.copy(progress = progress)
            }
            _modelState.value = _modelState.value.copy(
                isDownloading = false,
                isReady = modelManager.isModelReady(),
                error = result.exceptionOrNull()?.message
            )
        }
    }
}
