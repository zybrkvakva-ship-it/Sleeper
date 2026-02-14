package com.seekerminer.app.ui.screen.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seekerminer.app.data.local.AppDatabase
import com.seekerminer.app.data.local.TaskEntity
import com.seekerminer.app.data.repository.MiningRepository
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
