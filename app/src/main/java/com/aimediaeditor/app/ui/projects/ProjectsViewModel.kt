package com.aimediaeditor.app.ui.projects

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aimediaeditor.app.data.project.ProjectRepository
import com.aimediaeditor.app.data.project.ProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProjectsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Call whenever this screen (re)enters composition -- projects may have changed elsewhere. */
    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            _projects.value = repository.listSummaries()
            _loading.value = false
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            _projects.value = repository.listSummaries()
        }
    }
}
