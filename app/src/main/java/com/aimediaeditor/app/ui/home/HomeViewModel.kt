package com.aimediaeditor.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val items: List<MediaItem>) : HomeUiState
    data object NoPermission : HomeUiState
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.NoPermission)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun onPermissionGranted() {
        viewModelScope.launch {
            _uiState.value = HomeUiState.Loading
            val items = repository.loadAllMedia()
            _uiState.value = HomeUiState.Loaded(items)
        }
    }

    fun onPermissionDenied() {
        _uiState.value = HomeUiState.NoPermission
    }
}
