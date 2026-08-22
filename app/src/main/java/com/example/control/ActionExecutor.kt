package com.example.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.example.data.CommandLog
import com.example.data.JarvisRepository
import com.example.data.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class ActionExecutor(
    private val context: Context,
    private val repository: JarvisRepository,
    private val connectivityController: ConnectivityController,
    private val coroutineScope: CoroutineScope
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Executes the parsed command and speaks feedback.
     */
    fun execute(parsed: ParsedCommand, tier: PermissionTier, speakCallback: (String) -> Unit) {
        if (!parsed.isJarvisTriggered) {
            // Ignored, not addressed to Jarvis
            return
        }

        coroutineScope.launch(Dispatchers.Main) {
            var feedback = ""
            var success = true

            when (parsed.actionType) {
                ActionType.WIFI_ON -> {
                    feedback = connectivityController.toggleWifi(true, tier)
                }
                ActionType.WIFI_OFF -> {
                    feedback = connectivityController.toggleWifi(false, tier)
                }
                ActionType.DATA_ON -> {
                    feedback = connectivityController.toggleMobileData(true, tier)
                }
                ActionType.DATA_OFF -> {
                    feedback = connectivityController.toggleMobileData(false, tier)
                }
                ActionType.BLUETOOTH_ON -> {
                    feedback = toggleBluetooth(true, tier)
                }
                ActionType.BLUETOOTH_OFF -> {
                    feedback = toggleBluetooth(false, tier)
                }
                ActionType.FLASHLIGHT_ON -> {
                    feedback = toggleFlashlight(true)
                }
                ActionType.FLASHLIGHT_OFF -> {
                    feedback = toggleFlashlight(false)
                }
                ActionType.VOLUME_UP -> {
                    feedback = adjustVolume(true)
                }
                ActionType.VOLUME_DOWN -> {
                    feedback = adjustVolume(false)
                }
                ActionType.BRIGHTNESS_UP -> {
                    feedback = adjustBrightness(true)
                }
                ActionType.BRIGHTNESS_DOWN -> {
                    feedback = adjustBrightness(false)
                }
                ActionType.WRITE_NOTE -> {
                    feedback = saveNoteToDb(parsed.argument)
                }
                ActionType.READ_NOTES -> {
                    feedback = readNotesFromDb()
                }
                ActionType.OPEN_APP -> {
                    feedback = openApp(parsed.argument)
                }
                ActionType.CALL -> {
                    feedback = makeCall(parsed.argument)
                }
                ActionType.SMS -> {
                    feedback = sendSms(parsed.argument)
                }
                ActionType.ALARM -> {
                    feedback = createAlarm(parsed.argument)
                }
                ActionType.WEB_SEARCH -> {
                    feedback = performWebSearch(parsed.argument, tier)
                }
                ActionType.HELP -> {
                    feedback = "I can turn on and off wifi, data, bluetooth, and flashlight. " +
                            "I can also take notes, read notes, open apps, make phone calls, send text messages, set alarms, increase volume, or perform Google searches. Try saying: Jarvis, write note hello."
                }
                ActionType.UNKNOWN -> {
                    success = false
                    feedback = "I heard you say: ${parsed.originalText}. Say, Jarvis, write note, or Jarvis, turn on flashlight."
                }
            }

            // Speak feedback to the user
            speakCallback(feedback)

            // Log command to Room Database
            try {
                repository.insertLog(
                    CommandLog(
                        command = parsed.originalText,
                        actionType = parsed.actionType.name,
                        success = success,
                        feedback = feedback
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleBluetooth(enable: Boolean, tier: PermissionTier): String {
        val actionWord = if (enable) "on" else "off"
        return when (tier) {
            PermissionTier.BASIC -> {
                // In API >= 33, silent Bluetooth toggling is highly restricted.
                // Best basic practice is opening Bluetooth Settings.
                try {
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening Bluetooth settings. Please turn Bluetooth $actionWord."
                } catch (e: Exception) {
                    "Bluetooth toggle restricted. Please turn Bluetooth $actionWord from Quick Settings."
                }
            }
            PermissionTier.ACCESSIBILITY -> {
                val intent = Intent("com.example.ACCESSIBILITY_COMMAND").apply {
                    putExtra("action", "toggle_bluetooth")
                    putExtra("enable", enable)
                }
                context.sendBroadcast(intent)
                "Invoking Accessibility assistant to toggle Bluetooth $actionWord."
            }
            else -> {
                // Root su fallback
                val command = if (enable) "service call bluetooth_manager 6" else "service call bluetooth_manager 8"
                val success = executeShellCommand(command)
                if (success) "Bluetooth toggled $actionWord using shell access."
                else "Root command failed. Opening settings. " + toggleBluetooth(enable, PermissionTier.BASIC)
            }
        }
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enable)
                val state = if (enable) "on" else "off"
                "Flashlight turned $state."
            } else {
                "No camera flash found on this device."
            }
        } catch (e: Exception) {
            "Failed to toggle flashlight: ${e.localizedMessage}"
        }
    }

    private fun adjustVolume(increase: Boolean): String {
        return try {
            val streamType = AudioManager.STREAM_MUSIC
            val current = audioManager.getStreamVolume(streamType)
            val max = audioManager.getStreamMaxVolume(streamType)
            val step = if (increase) 1 else -1
            val newVolume = (current + step).coerceIn(0, max)
            audioManager.setStreamVolume(streamType, newVolume, AudioManager.FLAG_SHOW_UI)
            val direction = if (increase) "increased" else "decreased"
            "Volume $direction."
        } catch (e: Exception) {
            "Could not adjust volume: ${e.localizedMessage}"
        }
    }

    private fun adjustBrightness(increase: Boolean): String {
        // Changing brightness requires Settings.System.WRITE_SETTINGS permissions, which is highly restricted.
        // If we have it, change it. Otherwise, explain how to do it or open settings.
        if (Settings.System.canWrite(context)) {
            return try {
                val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                val step = if (increase) 40 else -40
                val newBrightness = (current + step).coerceIn(10, 255)
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, newBrightness)
                val state = if (increase) "increased" else "decreased"
                "Brightness $state."
            } catch (e: Exception) {
                "Failed to write brightness settings."
            }
        } else {
            // Open display settings
            return try {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "I need Write Settings permission to adjust brightness. Opening Display settings so you can toggle it."
            } catch (e: Exception) {
                "Permission restricted. Please adjust screen brightness manually."
            }
        }
    }

    private suspend fun saveNoteToDb(content: String): String {
        if (content.isEmpty()) {
            return "Note content is empty. Please say, Jarvis write note, followed by the text."
        }
        val title = if (content.length > 20) content.take(17) + "..." else content
        repository.insertNote(Note(title = title, content = content))
        return "Saved note: $content"
    }

    private suspend fun readNotesFromDb(): String {
        val notes = repository.allNotes.first()
        if (notes.isEmpty()) {
            return "You have no saved notes."
        }
        val notesText = notes.take(5).mapIndexed { index, note ->
            "Note ${index + 1}: ${note.content}"
        }.joinToString(". ")
        return "Here are your 5 latest notes. $notesText"
    }

    private fun openApp(appName: String): String {
        if (appName.isEmpty()) return "Please specify an app name to open."
        
        val appNameLower = appName.lowercase()
        val pkg = when {
            appNameLower.contains("camera") -> "android.media.action.IMAGE_CAPTURE" // Intent based
            appNameLower.contains("map") -> "com.google.android.apps.maps"
            appNameLower.contains("youtube") -> "com.google.android.youtube"
            appNameLower.contains("setting") -> Settings.ACTION_SETTINGS
            appNameLower.contains("chrome") -> "com.android.chrome"
            appNameLower.contains("phone") || appNameLower.contains("dialer") -> "android.intent.action.DIAL"
            else -> null
        }

        return try {
            if (pkg == "android.media.action.IMAGE_CAPTURE") {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    // Start camera application directly
                    action = android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Camera."
            } else if (pkg == "android.intent.action.DIAL") {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening phone dialer."
            } else if (pkg?.startsWith("android.settings") == true || pkg?.startsWith("android.intent") == true) {
                val intent = Intent(pkg).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening settings."
            } else if (pkg != null) {
                val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    context.startActivity(intent)
                    "Opening $appName."
                } else {
                    // Try to search play store or launch general settings
                    val searchIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(searchIntent)
                    "App not installed. Opening Play Store to search for $appName."
                }
            } else {
                // Search for any installed app containing name
                val pm = context.packageManager
                val packages = pm.getInstalledApplications(0)
                var launched = false
                for (app in packages) {
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    if (label.contains(appNameLower)) {
                        val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                            launched = true
                            break
                        }
                    }
                }
                if (launched) {
                    "Opening $appName."
                } else {
                    "I couldn't find an app named $appName installed on this device."
                }
            }
        } catch (e: Exception) {
            "Failed to open $appName."
        }
    }

    private fun makeCall(argument: String): String {
        if (argument.isEmpty()) return "Who would you like to call? Say, Jarvis call, followed by a number."
        
        // Check for runtime CALL_PHONE permission or fall back to dialer intent (DIAL does not require permission)
        return try {
            val number = argument.filter { it.isDigit() || it == '+' }
            if (number.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$number")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Check CALL_PHONE permission
                if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    context.startActivity(intent)
                    "Calling $number."
                } else {
                    // Fall back to dialer which is safer and doesn't require dangerous permission
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:$number")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(dialIntent)
                    "Dialing $number. Please tap call."
                }
            } else {
                // Search contacts or dial empty
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                "Opening dialer to call $argument."
            }
        } catch (e: Exception) {
            "Could not initiate call."
        }
    }

    private fun sendSms(argument: String): String {
        if (argument.isEmpty()) return "Please specify recipient and message text. Say, Jarvis, text John, followed by the message."
        
        val parts = argument.split("|")
        val recipient = parts.getOrNull(0) ?: ""
        val message = parts.getOrNull(1) ?: ""

        if (recipient.isEmpty() || message.isEmpty()) {
            return "Invalid text format. Please say, Jarvis, text John, followed by the message."
        }

        return try {
            if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    android.telephony.SmsManager.getDefault()
                }
                smsManager.sendTextMessage(recipient, null, message, null, null)
                "Text sent to $recipient: $message"
            } else {
                // Fall back to launching SMS composer
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$recipient")
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening message composer to send text."
            }
        } catch (e: Exception) {
            "Failed to compose SMS: ${e.localizedMessage}"
        }
    }

    private fun createAlarm(argument: String): String {
        // Parse time (e.g., "7 am", "8:30 pm") or default to 10 mins from now
        val cal = Calendar.getInstance()
        var hour = 8
        var minute = 0
        var gotTime = false

        if (argument.isNotEmpty()) {
            try {
                // Very simple natural language parsing for alarms
                val clean = argument.lowercase()
                val isPm = clean.contains("pm")
                val isAm = clean.contains("am")
                val digits = clean.filter { it.isDigit() || it == ':' }
                
                if (digits.contains(":")) {
                    val parts = digits.split(":")
                    hour = parts[0].toInt()
                    minute = parts[1].toInt()
                    gotTime = true
                } else if (digits.isNotEmpty()) {
                    hour = digits.toInt()
                    minute = 0
                    gotTime = true
                }

                if (gotTime) {
                    if (isPm && hour < 12) hour += 12
                    if (isAm && hour == 12) hour = 0
                }
            } catch (e: Exception) {
                // fallback to 8:00 AM
            }
        }

        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis Alarm")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Alarm scheduled for ${if (hour < 10) "0$hour" else hour}:${if (minute < 10) "0$minute" else minute}."
        } catch (e: Exception) {
            "Alarm manager permission restricted."
        }
    }

    private fun performWebSearch(query: String, tier: PermissionTier): String {
        if (query.isEmpty()) return "What would you like to search for?"

        // Check if online
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(activeNetwork)
        val isOnline = capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )

        return if (isOnline) {
            try {
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", query)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Searching the web for $query."
            } catch (e: Exception) {
                // Fall back to web browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$query")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opening Google search for $query."
            }
        } else {
            // Offline! Offer settings toggle using the settings panel flow
            connectivityController.toggleWifi(true, tier)
            "You are currently offline. Opening WiFi panel so you can connect and search for $query."
        }
    }

    private fun executeShellCommand(cmd: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            val result = process.waitFor()
            result == 0
        } catch (e: Exception) {
            false
        }
    }
}
