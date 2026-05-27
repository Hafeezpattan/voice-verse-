package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.VoiceActionLog
import com.example.data.VoiceActionLogRepository
import com.example.security.SecureStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceTriggerService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var secureStorageManager: SecureStorageManager
    private lateinit var repository: VoiceActionLogRepository
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening = false
    
    private var restartCount = 0
    private val maxRestartsBeforeCooldown = 5
    private var isCooldown = false
    private var consecutiveErrorCount = 0

    // TextToSpeech for actual verbal response system
    private var tts: TextToSpeech? = null
    private var isTtsInitialized = false

    companion object {
        private const val TAG = "VoiceTriggerService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_trigger_channel"
        
        // Static flow for easy compose state tracking even if unbound
        val serviceStatus = MutableStateFlow("Stopped") // "Stopped", "Idle (Monitoring)", "Listening", "Processing", "Verified Match", "Blocked"
        val lastDetectedOperation = MutableStateFlow<String?>(null)
        val lastTranscribedText = MutableStateFlow<String?>(null)
        val lastJarvisReply = MutableStateFlow<String?>("Awaiting your voice command, sir.")

        // Static flow tracking holographic hardware status states
        val activeHardwareStates = MutableStateFlow<Map<String, Boolean>>(mapOf(
            "Bluetooth" to false,
            "WiFi" to true,
            "Flashlight" to false,
            "Alarms" to false,
            "Music" to false,
            "SOS Mode" to false,
            "Translation" to false
        ))

        // Custom action keys
        const val ACTION_START_SERVICE = "com.example.service.START"
        const val ACTION_STOP_SERVICE = "com.example.service.STOP"
        const val ACTION_SIMULATE_COMMAND = "com.example.service.SIMULATE"
        const val EXTRA_SIMULATED_SPEECH = "simulated_speech"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceTriggerService = this@VoiceTriggerService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
        secureStorageManager = SecureStorageManager(applicationContext)
        val database = AppDatabase.getDatabase(applicationContext)
        repository = VoiceActionLogRepository(database.voiceActionLogDao())
        
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildStatusNotification("Secure Monitoring Active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildStatusNotification("Secure Monitoring Active"))
        }
        serviceStatus.value = "Idle (Monitoring)"
        
        initializeSpeechRecognizer()
        initializeTextToSpeech()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildStatusNotification("Secure Monitoring Active"),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildStatusNotification("Secure Monitoring Active"))
        }

        when (intent?.action) {
            ACTION_START_SERVICE -> {
                Log.d(TAG, "Start Service Action")
                consecutiveErrorCount = 0
                restartCount = 0
                isCooldown = false
                startListening()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stop Service Action")
                stopListening()
                stopSelf()
            }
            ACTION_SIMULATE_COMMAND -> {
                val simulatedText = intent.getStringExtra(EXTRA_SIMULATED_SPEECH) ?: ""
                Log.d(TAG, "Simulating Command text: $simulatedText")
                processSpeechInput(simulatedText, 0.95f)
            }
        }
        return START_STICKY
    }

    private fun initializeSpeechRecognizer() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SpeechRecognizer initialization deferred: RECORD_AUDIO permission not granted")
            serviceStatus.value = "Active (Simulated Only - Permission Required)"
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(VoiceRecognitionListener())
                }
                
                recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                Log.d(TAG, "SpeechRecognizer initialized successfully")
                startListening()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SpeechRecognizer", e)
                serviceStatus.value = "Hardware Unavailable (Using Simulation)"
            }
        } else {
            Log.w(TAG, "SpeechRecognizer is NOT available on this device configuration")
            serviceStatus.value = "Hardware Unavailable (Using Simulation)"
        }
    }

    /**
     * Set up TextToSpeech with British butler-like pitch configurations standard for J.A.R.V.I.S.
     */
    private fun initializeTextToSpeech() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.UK) // Butler-like British accent model
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsInitialized = true
                    tts?.setPitch(0.92f) // Slightly lower, charming baritone voice
                    tts?.setSpeechRate(1.02f)
                    speakJarvisVoice("Shield and sensory matrix fully online, sir.")
                } else {
                    // Fall back to US English
                    tts?.setLanguage(Locale.US)
                    isTtsInitialized = true
                    speakJarvisVoice("Shield online, sir.")
                }
            } else {
                Log.e(TAG, "Failed to initialize TextToSpeech engine")
            }
        }
    }

    /**
     * Synthesizes and vocalizes JARVIS speech responses safely.
     */
    fun speakJarvisVoice(text: String) {
        if (isTtsInitialized && tts != null) {
            Log.d(TAG, "Speaking voice response: $text")
            lastJarvisReply.value = text
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_tts_id")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        } else {
            // Flow visual response text to HUD anyway
            lastJarvisReply.value = text
            Log.w(TAG, "Speech requested but TTS was not fully ready. Text: $text")
        }
    }

    private fun startListening() {
        if (isListening || isCooldown) return
        
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "startListening canceled: RECORD_AUDIO permission not granted")
            serviceStatus.value = "Active (Simulated Only - Permission Required)"
            return
        }

        if (speechRecognizer == null) {
            initializeSpeechRecognizer()
            return
        }

        try {
            speechRecognizer?.let { recognizer ->
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                })
                isListening = true
                serviceStatus.value = "Active (Listening)"
                updateNotification("Active (Listening)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SpeechRecognizer", e)
            handleRestartOnError(isFatal = true)
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping listener", e)
        }
        isListening = false
        serviceStatus.value = "Stopped"
        updateNotification("Stopped")
    }

    private fun handleRestartOnError(isFatal: Boolean = false) {
        isListening = false
        if (isFatal) {
            consecutiveErrorCount = 3
        } else {
            consecutiveErrorCount++
        }

        if (consecutiveErrorCount >= 3) {
            Log.w(TAG, "Consecutive or fatal voice activation failures. Pausing active physical microphone to guard system resources.")
            serviceStatus.value = "Idle (Monitoring Standby)"
            updateNotification("Voice Monitoring on standby")
            return
        }

        restartCount++
        if (restartCount > maxRestartsBeforeCooldown) {
            isCooldown = true
            serviceStatus.value = "Cooldown Status (Idle)"
            updateNotification("Cooldown active")
            Handler(Looper.getMainLooper()).postDelayed({
                isCooldown = false
                restartCount = 0
                startListening()
            }, 8000) // 8 seconds of cool-down
        } else {
            Handler(Looper.getMainLooper()).postDelayed({
                if (serviceStatus.value != "Stopped" && !isCooldown) {
                    startListening()
                }
            }, 1500)
        }
    }

    /**
     * Process recognized speech input securely on device with real-time AI and fallback logic.
     */
    private fun processSpeechInput(text: String, confidence: Float) {
        serviceScope.launch {
            Log.d(TAG, "Processing secure input: $text with confidence: $confidence")
            serviceStatus.value = "Verifying Voiceprint..."
            lastTranscribedText.value = text
            updateNotification("Evaluating Voice command...")

            // 1. Verify voiceprint using the secure storage manager
            // If the user hasn't set any signature yet, the manager permits sandbox access of general commands,
            // otherwise, a strict voice passphrase match is carried out.
            val verification = secureStorageManager.verifyVoiceprint(text, null)

            if (!verification.isMatched) {
                // Access Blocked! Speak response
                val blockReply = "Access denied: voice signature verification mismatch, sir."
                serviceStatus.value = "Blocked Signature!"
                lastJarvisReply.value = blockReply
                updateNotification("Blocked: Voice mismatch")
                speakJarvisVoice(blockReply)

                // Log blockage inside database
                val log = VoiceActionLog(
                    spokenText = text,
                    confidence = confidence,
                    detectedAction = "BLOCKED",
                    status = "BLOCKED_PASS"
                )
                repository.insertLog(log)

                // Resume monitoring
                Handler(Looper.getMainLooper()).postDelayed({
                    if (serviceStatus.value != "Stopped" && !isCooldown) {
                        startListening()
                    }
                }, 4000)
                return@launch
            }

            // 2. Authentication passed! Let's call the intelligent JarvisBrain to extract intents and conversational chat
            serviceStatus.value = "Reasoning..."
            updateNotification("JARVIS reasoning complete...")

            // Retrieve conversational verbal reply and actions chain
            val queryResults = JarvisBrain.queryJarvis(text)
            val jarvisReplyText = queryResults.first
            val actionsToTrigger = queryResults.second

            serviceStatus.value = "Vocalizing..."
            speakJarvisVoice(jarvisReplyText)

            // 3. Log event into Room DB locally as APPROVED
            val finalActionStr = if (actionsToTrigger.isEmpty()) "CHAT_REPLY" else actionsToTrigger.joinToString(", ")
            val log = VoiceActionLog(
                spokenText = text,
                confidence = confidence,
                detectedAction = finalActionStr,
                status = "SUCCESS"
            )
            repository.insertLog(log)

            // Update status flow of last action
            if (actionsToTrigger.isNotEmpty()) {
                lastDetectedOperation.value = actionsToTrigger.joinToString(", ")
            }

            // 4. Chain compile system commands and hardware operations
            for (action in actionsToTrigger) {
                executeSystemAction(action)
            }

            // Go back to listening after speech concludes
            Handler(Looper.getMainLooper()).postDelayed({
                if (serviceStatus.value != "Stopped" && !isCooldown) {
                    startListening()
                }
            }, 5000)
        }
    }

    private fun executeSystemAction(action: String) {
        val currentStates = activeHardwareStates.value.toMutableMap()
        
        when (action) {
            "FLASHLIGHT" -> {
                val newState = !(currentStates["Flashlight"] ?: false)
                currentStates["Flashlight"] = newState
                togglePhysicalFlashlight(newState)
            }
            "BLUETOOTH" -> {
                val newState = !(currentStates["Bluetooth"] ?: false)
                currentStates["Bluetooth"] = newState
            }
            "WIFI" -> {
                val newState = !(currentStates["WiFi"] ?: false)
                currentStates["WiFi"] = newState
            }
            "ALARM" -> {
                currentStates["Alarms"] = true
            }
            "MUSIC" -> {
                val newState = !(currentStates["Music"] ?: false)
                currentStates["Music"] = newState
            }
            "SOS" -> {
                currentStates["SOS Mode"] = true
            }
            "TRANSLATE" -> {
                currentStates["Translation"] = true
            }
        }
        
        activeHardwareStates.value = currentStates

        // Signal UI to update or display sandboxed action card overlays
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("triggered_action", action)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "System alert or background activity launch constraint: unable to start activity directly from context", e)
        }
    }

    /**
     * Toggles physical camera camera flash torch
     */
    private fun togglePhysicalFlashlight(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            val cameraId = cm?.cameraIdList?.firstOrNull()
            if (cameraId != null) {
                cm.setTorchMode(cameraId, on)
                Log.d(TAG, "Flashlight set to: $on")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle phone flashlight hardware", e)
        }
    }

    // Speech Recognition Listener
    private inner class VoiceRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.v(TAG, "onReadyForSpeech")
        }

        override fun onBeginningOfSpeech() {
            Log.v(TAG, "onBeginningOfSpeech")
            serviceStatus.value = "Speech Detected..."
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.v(TAG, "onEndOfSpeech")
            serviceStatus.value = "Evaluating Voice..."
        }

        override fun onError(error: Int) {
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
                else -> "Unknown error: $error"
            }
            Log.w(TAG, "SpeechRecognizer warning: $message")
            val isFatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || 
                          error == SpeechRecognizer.ERROR_AUDIO || 
                          error == SpeechRecognizer.ERROR_CLIENT
            handleRestartOnError(isFatal)
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            
            if (!matches.isNullOrEmpty()) {
                val inputStr = matches[0]
                val confidenceScore = confidences?.getOrNull(0) ?: 0.8f
                processSpeechInput(inputStr, confidenceScore)
                restartCount = 0 // Reset error restart count
                consecutiveErrorCount = 0 // Reset consecutive errors
            } else {
                handleRestartOnError()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                lastTranscribedText.value = matches[0]
                serviceStatus.value = "Speech Detected..."
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Authentication Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps active device protection listening in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildStatusNotification(statusText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceShield Local Actions")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildStatusNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        serviceStatus.value = "Stopped"
        speechRecognizer?.let {
            it.destroy()
            speechRecognizer = null
        }
        tts?.let {
            it.stop()
            it.shutdown()
            tts = null
        }
        super.onDestroy()
    }
}
