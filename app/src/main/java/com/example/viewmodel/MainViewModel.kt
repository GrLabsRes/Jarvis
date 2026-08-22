package com.example.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.control.PermissionTier
import com.example.data.AppDatabase
import com.example.data.CommandLog
import com.example.data.JarvisRepository
import com.example.data.Note
import com.example.service.VoiceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: JarvisRepository

    // Database Streams
    val allNotes: StateFlow<List<Note>>
    val recentLogs: StateFlow<List<CommandLog>>

    // Jarvis Service Connection States
    private val _isServiceBound = MutableStateFlow(false)
    val isServiceBound = _isServiceBound.asStateFlow()

    private val _serviceStatus = MutableStateFlow("Service: Disconnected")
    val serviceStatus = _serviceStatus.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript = _lastTranscript.asStateFlow()

    private val _selectedTier = MutableStateFlow(PermissionTier.BASIC)
    val selectedTier = _selectedTier.asStateFlow()

    private var voiceService: VoiceService? = null

    init {
        val database = AppDatabase.getDatabase(application)
        repository = JarvisRepository(database)

        allNotes = repository.allNotes.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        recentLogs = repository.recentLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? VoiceService.VoiceServiceBinder
            if (binder != null) {
                voiceService = binder.getService()
                _isServiceBound.value = true
                voiceService?.setPermissionTier(_selectedTier.value)
                
                // Connect listeners
                voiceService?.setCallbacks(
                    transcript = { text ->
                        _lastTranscript.value = text
                    },
                    status = { status ->
                        _serviceStatus.value = status
                    }
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            _isServiceBound.value = false
            _serviceStatus.value = "Service: Disconnected"
        }
    }

    fun startAndBindService() {
        val intent = Intent(getApplication(), VoiceService::class.java).apply {
            putExtra("tier", _selectedTier.value.name)
        }
        getApplication<Application>().startService(intent)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (_isServiceBound.value) {
            getApplication<Application>().unbindService(serviceConnection)
            _isServiceBound.value = false
            voiceService = null
            _serviceStatus.value = "Service: Unbound"
        }
    }

    fun setPermissionTier(tier: PermissionTier) {
        _selectedTier.value = tier
        voiceService?.setPermissionTier(tier)
    }

    fun submitSimulatedCommand(text: String) {
        if (text.isNotBlank()) {
            _lastTranscript.value = text
            if (_isServiceBound.value) {
                voiceService?.processManualCommand(text)
            } else {
                // If service not active, temporarily start and trigger
                viewModelScope.launch {
                    val intent = Intent(getApplication(), VoiceService::class.java)
                    getApplication<Application>().startService(intent)
                    _serviceStatus.value = "Starting service to handle command..."
                }
            }
        }
    }

    fun speakFeedback(text: String) {
        voiceService?.speak(text)
    }

    fun addNote(title: String, content: String) {
        viewModelScope.launch {
            repository.insertNote(Note(title = title, content = content))
        }
    }

    fun deleteNoteById(id: Int) {
        viewModelScope.launch {
            repository.deleteNoteById(id)
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
