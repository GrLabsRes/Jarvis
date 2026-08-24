package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.control.*
import com.example.data.AppDatabase
import com.example.data.JarvisRepository
import kotlinx.coroutines.*
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.IOException
import java.util.Locale

class VoiceService : Service(), TextToSpeech.OnInitListener, RecognitionListener {

    private val TAG = "JarvisVoiceService"
    private val CHANNEL_ID = "JarvisVoiceServiceChannel"
    private val NOTIFICATION_ID = 2026

    // Service Binder
    private val binder = VoiceServiceBinder()

    // Engines
    private var tts: TextToSpeech? = null
    private var speechService: SpeechService? = null
    private var voskModel: Model? = null

    // State Flow Helpers
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: JarvisRepository
    private lateinit var connectivityController: ConnectivityController
    private lateinit var actionExecutor: ActionExecutor
    private val commandParser = CommandParser()

    // Selected permission tier
    private var currentTier = PermissionTier.BASIC

    // Callbacks to UI
    private var onTranscriptCallback: ((String) -> Unit)? = null
    private var onStatusCallback: ((String) -> Unit)? = null

    inner class VoiceServiceBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate service triggered")
        
        // Setup repositories and controllers
        val db = AppDatabase.getDatabase(this)
        repository = JarvisRepository(db)
        connectivityController = ConnectivityController(this)
        actionExecutor = ActionExecutor(this, repository, connectivityController, serviceScope)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Show foreground notification if we have mic permission
        tryStartForeground("Jarvis: Starting up...")

        // Async Vosk model setup
        initVoskModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand triggered")
        val tierName = intent?.getStringExtra("tier") ?: PermissionTier.BASIC.name
        currentTier = try {
            PermissionTier.valueOf(tierName)
        } catch (e: Exception) {
            PermissionTier.BASIC
        }

        // Elevate to foreground if permission has been granted now
        tryStartForeground("Jarvis: Active and running")

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        
        speechService?.stop()
        speechService?.shutdown()
        
        tts?.stop()
        tts?.shutdown()
        Log.d(TAG, "VoiceService destroyed")
    }

    fun setPermissionTier(tier: PermissionTier) {
        currentTier = tier
        updateStatus("Active: ${tier.name} Permission Tier")
    }

    fun setCallbacks(transcript: (String) -> Unit, status: (String) -> Unit) {
        onTranscriptCallback = transcript
        onStatusCallback = status
        
        // Initial state update
        val modelState = if (voskModel != null) "Ready" else "Offline Fallback (No Model)"
        status("Status: $modelState | Tier: ${currentTier.name}")
    }

    // TTS Initializer
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "English language package is not supported or missing")
            } else {
                Log.d(TAG, "TTS Engine initialized successfully")
                tts?.speak("Jarvis offline assistant is ready.", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } else {
            Log.e(TAG, "Failed to initialize TTS engine")
        }
    }

    /**
     * Public method to let UI speak manually.
     */
    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * Public method to simulate spoken command entry from the UI textbox.
     */
    fun processManualCommand(text: String) {
        onTranscript(text)
    }

    // --- VOSK STT IMPLEMENTATION ---

    private fun initVoskModel() {
        serviceScope.launch(Dispatchers.IO) {
            updateStatus("Model: Checking assets...")
            
            // Check if model exists in assets before trying to unpack
            val assetList = assets.list("") ?: emptyArray()
            val hasModelInAssets = assetList.contains("model-en-us") || assetList.contains("model")

            if (!hasModelInAssets) {
                withContext(Dispatchers.Main) {
                    updateStatus("Offline Fallback (Vosk model-en-us missing in assets)")
                    Log.w(TAG, "Vosk English model not found in assets directory. Direct vocal wake-word is offline. Enabling simulated control.")
                }
                return@launch
            }

            updateStatus("Model: Unpacking speech files...")
            StorageService.unpack(
                this@VoiceService,
                "model-en-us",
                "model",
                { model ->
                    voskModel = model
                    serviceScope.launch(Dispatchers.Main) {
                        startSpeechService()
                    }
                },
                { exception ->
                    Log.e(TAG, "Vosk Model Unpack error", exception)
                    updateStatus("Model Error: Unpack failed")
                }
            )
        }
    }

    private fun startSpeechService() {
        val model = voskModel ?: return
        try {
            speechService = SpeechService(Recognizer(model, 16000.0f), 16000.0f)
            speechService?.startListening(this)
            updateStatus("Active (Vosk listening for Jarvis...)")
            startForegroundNotification("Jarvis offline listening active...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speech recognition service", e)
            updateStatus("Service Error: Mic blocked")
        }
    }

    // --- Vosk RecognitionListener Overrides ---

    override fun onPartialResult(hypothesis: String?) {
        // We look for direct wake word trigger on partials for instantaneous response
        Log.d(TAG, "Partial Result: $hypothesis")
    }

    override fun onResult(hypothesis: String?) {
        Log.d(TAG, "Full Result: $hypothesis")
        if (hypothesis == null) return
        
        // Result is in JSON format: {"text" : "jarvis turn on wifi"}
        val textValue = parseVoskJsonText(hypothesis)
        if (textValue.isNotEmpty()) {
            onTranscript(textValue)
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        Log.d(TAG, "Final Result: $hypothesis")
        if (hypothesis == null) return
        val textValue = parseVoskJsonText(hypothesis)
        if (textValue.isNotEmpty()) {
            onTranscript(textValue)
        }
    }

    override fun onError(exception: Exception?) {
        Log.e(TAG, "Vosk STT Error", exception)
        updateStatus("STT Error: Check mic permission")
    }

    override fun onTimeout() {
        Log.d(TAG, "Vosk Speech Timeout")
    }

    // Helpers

    private fun onTranscript(text: String) {
        serviceScope.launch(Dispatchers.Main) {
            onTranscriptCallback?.invoke(text)
            
            // Parse and execute intent
            val parsed = commandParser.parse(text)
            if (parsed.isJarvisTriggered) {
                actionExecutor.execute(parsed, currentTier) { response ->
                    speak(response)
                }
            }
        }
    }

    private fun updateStatus(status: String) {
        serviceScope.launch(Dispatchers.Main) {
            onStatusCallback?.invoke(status)
            startForegroundNotification(status)
        }
    }

    private fun parseVoskJsonText(json: String): String {
        return try {
            // Simple string extraction to avoid importing bulky json parser libraries
            // Example json: {"text" : "jarvis turn on wifi"}
            if (json.contains("\"text\" : \"")) {
                json.substringAfter("\"text\" : \"").substringBefore("\"")
            } else if (json.contains("\"text\": \"")) {
                json.substringAfter("\"text\": \"").substringBefore("\"")
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun tryStartForeground(text: String) {
        val hasMicPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasMicPermission) {
            try {
                startForegroundNotification(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        } else {
            Log.w(TAG, "RECORD_AUDIO permission not granted. Running as standard background service.")
        }
    }

    private fun startForegroundNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Offline Assistant",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Voice Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
