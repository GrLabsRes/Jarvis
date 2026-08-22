package com.example.control

import java.util.Locale

data class ParsedCommand(
    val originalText: String,
    val isJarvisTriggered: Boolean,
    val actionType: ActionType,
    val argument: String = ""
)

enum class ActionType {
    WIFI_ON, WIFI_OFF,
    DATA_ON, DATA_OFF,
    BLUETOOTH_ON, BLUETOOTH_OFF,
    FLASHLIGHT_ON, FLASHLIGHT_OFF,
    OPEN_APP,
    CALL,
    SMS,
    ALARM,
    WRITE_NOTE,
    READ_NOTES,
    WEB_SEARCH,
    VOLUME_UP, VOLUME_DOWN,
    BRIGHTNESS_UP, BRIGHTNESS_DOWN,
    HELP,
    UNKNOWN
}

class CommandParser {

    fun parse(text: String): ParsedCommand {
        val trimmed = text.trim()
        val lowercase = trimmed.lowercase(Locale.ROOT)
        
        // Match wake word: Check if "jarvis" is the first word
        val words = lowercase.split("\\s+".toRegex())
        val isJarvis = words.isNotEmpty() && words[0] == "jarvis"
        
        // Remove "jarvis" wake word from command content if present
        val commandBody = if (isJarvis) {
            lowercase.substringAfter("jarvis").trim()
        } else {
            lowercase
        }

        if (commandBody.isEmpty()) {
            return ParsedCommand(trimmed, isJarvis, ActionType.UNKNOWN, "")
        }

        // 1. Wifi Toggles
        if (commandBody.contains("turn on wifi") || commandBody.contains("enable wifi") || commandBody.contains("wifi on")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.WIFI_ON)
        }
        if (commandBody.contains("turn off wifi") || commandBody.contains("disable wifi") || commandBody.contains("wifi off")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.WIFI_OFF)
        }

        // 2. Mobile Data Toggles
        if (commandBody.contains("turn on data") || commandBody.contains("enable mobile data") || commandBody.contains("data on")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.DATA_ON)
        }
        if (commandBody.contains("turn off data") || commandBody.contains("disable mobile data") || commandBody.contains("data off")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.DATA_OFF)
        }

        // 3. Bluetooth Toggles
        if (commandBody.contains("turn on bluetooth") || commandBody.contains("enable bluetooth") || commandBody.contains("bluetooth on")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.BLUETOOTH_ON)
        }
        if (commandBody.contains("turn off bluetooth") || commandBody.contains("disable bluetooth") || commandBody.contains("bluetooth off")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.BLUETOOTH_OFF)
        }

        // 4. Flashlight Toggles
        if (commandBody.contains("turn on flashlight") || commandBody.contains("flashlight on") || commandBody.contains("enable flashlight")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.FLASHLIGHT_ON)
        }
        if (commandBody.contains("turn off flashlight") || commandBody.contains("flashlight off") || commandBody.contains("disable flashlight")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.FLASHLIGHT_OFF)
        }

        // 5. Volume Adjustments
        if (commandBody.contains("volume up") || commandBody.contains("increase volume") || commandBody.contains("louder")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.VOLUME_UP)
        }
        if (commandBody.contains("volume down") || commandBody.contains("decrease volume") || commandBody.contains("softer")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.VOLUME_DOWN)
        }

        // 6. Brightness Adjustments
        if (commandBody.contains("brightness up") || commandBody.contains("increase brightness")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.BRIGHTNESS_UP)
        }
        if (commandBody.contains("brightness down") || commandBody.contains("decrease brightness")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.BRIGHTNESS_DOWN)
        }

        // 7. Write Note (e.g. "write note hello my friend", "create note text content")
        if (commandBody.startsWith("write note") || commandBody.startsWith("create note") || commandBody.startsWith("take note")) {
            val content = when {
                commandBody.startsWith("write note") -> commandBody.substringAfter("write note").trim()
                commandBody.startsWith("create note") -> commandBody.substringAfter("create note").trim()
                else -> commandBody.substringAfter("take note").trim()
            }
            return ParsedCommand(trimmed, isJarvis, ActionType.WRITE_NOTE, content)
        }

        // 8. Read Notes
        if (commandBody.contains("read notes") || commandBody.contains("show notes") || commandBody.contains("get notes")) {
            return ParsedCommand(trimmed, isJarvis, ActionType.READ_NOTES)
        }

        // 9. Call (e.g., "call mom", "call 555-0199")
        if (commandBody.startsWith("call")) {
            val contact = commandBody.substringAfter("call").trim()
            return ParsedCommand(trimmed, isJarvis, ActionType.CALL, contact)
        }

        // 10. SMS / Text (e.g., "text mom hello", "sms john we are here")
        if (commandBody.startsWith("text") || commandBody.startsWith("sms") || commandBody.startsWith("send sms to")) {
            val targetStr = when {
                commandBody.startsWith("send sms to") -> commandBody.substringAfter("send sms to").trim()
                commandBody.startsWith("text") -> commandBody.substringAfter("text").trim()
                else -> commandBody.substringAfter("sms").trim()
            }
            // Simple space separation: target message
            val parts = targetStr.split("\\s+".toRegex(), 2)
            val recipient = parts.getOrNull(0) ?: ""
            val message = parts.getOrNull(1) ?: ""
            return ParsedCommand(trimmed, isJarvis, ActionType.SMS, "$recipient|$message")
        }

        // 11. Alarm (e.g., "set alarm 7 am", "set alarm")
        if (commandBody.contains("set alarm") || commandBody.contains("create alarm") || commandBody.contains("wake me up")) {
            val details = when {
                commandBody.contains("set alarm") -> commandBody.substringAfter("set alarm").trim()
                commandBody.contains("create alarm") -> commandBody.substringAfter("create alarm").trim()
                else -> commandBody.substringAfter("wake me up").trim()
            }
            return ParsedCommand(trimmed, isJarvis, ActionType.ALARM, details)
        }

        // 12. Open App (e.g., "open camera", "open youtube", "open maps")
        if (commandBody.startsWith("open") || commandBody.startsWith("launch")) {
            val appName = when {
                commandBody.startsWith("open") -> commandBody.substringAfter("open").trim()
                else -> commandBody.substringAfter("launch").trim()
            }
            return ParsedCommand(trimmed, isJarvis, ActionType.OPEN_APP, appName)
        }

        // 13. Web Search (e.g., "search samsung", "google galaxy s24")
        if (commandBody.startsWith("search") || commandBody.startsWith("google") || commandBody.startsWith("find")) {
            val query = when {
                commandBody.startsWith("search") -> commandBody.substringAfter("search").trim()
                commandBody.startsWith("google") -> commandBody.substringAfter("google").trim()
                else -> commandBody.substringAfter("find").trim()
            }
            return ParsedCommand(trimmed, isJarvis, ActionType.WEB_SEARCH, query)
        }

        // 14. Help
        if (commandBody == "help" || commandBody == "what can i say" || commandBody == "commands") {
            return ParsedCommand(trimmed, isJarvis, ActionType.HELP)
        }

        return ParsedCommand(trimmed, isJarvis, ActionType.UNKNOWN, commandBody)
    }
}
