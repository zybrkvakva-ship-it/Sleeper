package com.sleeper.app.ui.screen.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleeper.app.data.local.AppDatabase
import com.sleeper.app.data.local.TaskEntity
import com.sleeper.app.data.repository.MiningRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TasksViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = MiningRepository(AppDatabase.getInstance(application))
    
    val tasks: StateFlow<List<TaskEntity>> = repository.tasks
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    fun completeTask(taskId: String) {
        viewModelScope.launch {
            repository.completeTask(taskId)
        }
    }
}
