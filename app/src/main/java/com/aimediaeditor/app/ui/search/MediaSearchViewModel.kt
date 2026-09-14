package com.aimediaeditor.app.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediaeditor.app.data.index.MediaIndexRepository
import com.aimediaeditor.app.data.media.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val query: String, val items: List<MediaItem>) : SearchUiState
}

/** Spec section 3's "AI Search" -- "Stage 1" of the search chain (deterministic
 *  keyword/date/label matching over the local index, see [MediaIndexRepository] and
 *  [com.aimediaeditor.app.data.index.MediaSearchQuery]), not yet an LLM-parsed query. */
class MediaSearchViewModel(application: Application) : AndroidViewModel(application) {

    private val indexRepository = MediaIndexRepository(application)

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

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
}
