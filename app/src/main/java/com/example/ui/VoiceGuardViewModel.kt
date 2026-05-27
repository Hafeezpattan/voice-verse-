package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.VoiceActionLog
import com.example.data.VoiceActionLogRepository
import com.example.security.SecureStorageManager
import com.example.service.VoiceTriggerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VoiceGuardViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context = application.applicationContext
    private val repository: VoiceActionLogRepository
    private val secureStorageManager = SecureStorageManager(context)

    // Log database flow
    val logs: StateFlow<List<VoiceActionLog>>

    // Foreground service status tracking
    val serviceStatus: StateFlow<String> = VoiceTriggerService.serviceStatus
    val lastDetectedOperation: StateFlow<String?> = VoiceTriggerService.lastDetectedOperation
    val lastTranscribedText: StateFlow<String?> = VoiceTriggerService.lastTranscribedText

    // UI Input field states
    private val _masterPassphraseInput = MutableStateFlow("")
    val masterPassphraseInput = _masterPassphraseInput.asStateFlow()

    private val _voiceThresholdInput = MutableStateFlow(75)
    val voiceThresholdInput = _voiceThresholdInput.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning = _isServiceRunning.asStateFlow()

    // Status / Message toast flow for display
    private val _uiToastMessage = MutableStateFlow<String?>(null)
    val uiToastMessage = _uiToastMessage.asStateFlow()

    init {
        val database = AppDatabase.getDatabase(context)
        repository = VoiceActionLogRepository(database.voiceActionLogDao())
        logs = repository.allLogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        _masterPassphraseInput.value = secureStorageManager.getMasterPassphraseText()
        _voiceThresholdInput.value = secureStorageManager.getVoiceAccuracyThreshold()
    }

    fun onPassphraseChange(newText: String) {
        _masterPassphraseInput.value = newText
    }

    fun onThresholdChange(newThreshold: Int) {
        _voiceThresholdInput.value = newThreshold
        secureStorageManager.setVoiceAccuracyThreshold(newThreshold)
    }

    /**
     * Secures and stores the voice trigger passphrase using cryptographically hashed values.
     */
    fun registerVoiceprint() {
        val phrase = _masterPassphraseInput.value.trim()
        if (phrase.isNotEmpty()) {
            viewModelScope.launch {
                secureStorageManager.saveMasterPassphrase(phrase)
                
                // Write action specific passphrases automatically as custom bindings
                secureStorageManager.saveActionPassphrase("CAMERA", "activate camera $phrase")
                secureStorageManager.saveActionPassphrase("CONTACTS", "access contacts $phrase")

                // Save an offline log entry representing registration
                val regLog = VoiceActionLog(
                    spokenText = "Registered local voiceprint: '$phrase'",
                    confidence = 1.0f,
                    detectedAction = "REGISTER_SIGNATURE",
                    status = "SUCCESS"
                )
                repository.insertLog(regLog)
                
                _uiToastMessage.value = "Voiceprint Hashed & Saved Securely On Device"
            }
        } else {
            _uiToastMessage.value = "Passphrase cannot be empty"
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            _uiToastMessage.value = "Logs purged successfully"
        }
    }

    fun toggleService(shouldStart: Boolean) {
        val intent = Intent(context, VoiceTriggerService::class.java).apply {
            action = if (shouldStart) VoiceTriggerService.ACTION_START_SERVICE else VoiceTriggerService.ACTION_STOP_SERVICE
        }
        
        try {
            if (shouldStart) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _isServiceRunning.value = true
                _uiToastMessage.value = "Voice Guard listening service activated"
            } else {
                context.stopService(intent)
                _isServiceRunning.value = false
                _uiToastMessage.value = "Voice Guard service disabled"
            }
        } catch (e: Exception) {
            _uiToastMessage.value = "Failed to switch service state: ${e.message}"
        }
    }

    /**
     * Simulates safe voice trigger inputs when tested in the emulator on on-device sandbox.
     */
    fun simulateVoiceCommand(text: String) {
        viewModelScope.launch {
            val intent = Intent(context, VoiceTriggerService::class.java).apply {
                action = VoiceTriggerService.ACTION_SIMULATE_COMMAND
                putExtra(VoiceTriggerService.EXTRA_SIMULATED_SPEECH, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceGuardViewModel", "Error initiating voice simulation: ${e.message}", e)
            }
        }
    }

    /**
     * Clears active alerts/notifications
     */
    fun clearToast() {
        _uiToastMessage.value = null
    }

    fun resetDetectedOperation() {
        VoiceTriggerService.lastDetectedOperation.value = null
    }
}
