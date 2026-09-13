package com.aimediaeditor.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediaeditor.app.data.media.MediaItem
import com.aimediaeditor.app.data.media.MediaRepository
import com.aimediaeditor.app.data.project.ProjectRepository
import com.aimediaeditor.app.data.project.ProjectSummary
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
    private val projectRepository = ProjectRepository(application)

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.NoPermission)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Projects are app-private state, unrelated to the media-library permission above,
    // so this loads regardless of whether that permission is granted.
    private val _recentProjects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val recentProjects: StateFlow<List<ProjectSummary>> = _recentProjects.asStateFlow()

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

    /** Safe to call every time Home re-enters composition -- cheap, and projects can change elsewhere. */
    fun refreshRecentProjects() {
        viewModelScope.launch {
            _recentProjects.value = projectRepository.listSummaries().take(MAX_RECENT_PROJECTS)
        }
    }

    private companion object {
        const val MAX_RECENT_PROJECTS = 6
    }
}
