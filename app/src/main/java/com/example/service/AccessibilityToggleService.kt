package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AccessibilityToggleService : AccessibilityService() {

    private var commandReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        registerCommandReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterCommandReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not tracking user input; we only perform active automation on demand
    }

    override fun onInterrupt() {
        // Handle interruptions if needed
    }

    private fun registerCommandReceiver() {
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.getStringExtra("action") ?: return
                val enable = intent.getBooleanExtra("enable", true)

                when (action) {
                    "toggle_wifi" -> {
                        executeWifiAutomation(enable)
                    }
                    "toggle_data" -> {
                        executeMobileDataAutomation(enable)
                    }
                    "toggle_bluetooth" -> {
                        executeBluetoothAutomation(enable)
                    }
                }
            }
        }
        val filter = IntentFilter("com.example.ACCESSIBILITY_COMMAND")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(commandReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
    }

    private fun unregisterCommandReceiver() {
        commandReceiver?.let {
            unregisterReceiver(it)
            commandReceiver = null
        }
    }

    /**
     * Tries to toggle WiFi programmatically by pulling down Quick Settings
     * and finding the WiFi / Internet tile to toggle.
     */
    private fun executeWifiAutomation(enable: Boolean) {
        Toast.makeText(this, "Jarvis Accessibility: Toggling WiFi...", Toast.LENGTH_SHORT).show()
        
        // 1. Pull down Quick Settings
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        
        // 2. Wait slightly for panel to draw, then search and tap the WiFi tile
        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            
            // Search for typical tile names
            val targets = listOf("wifi", "wi-fi", "internet", "wlan", "network")
            val foundNode = findNodeByKeywords(root, targets)
            
            if (foundNode != null) {
                // Perform click
                val clicked = foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    // Try parent clicking
                    foundNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                // On modern Android (12+), clicking "Internet" opens a bottom sheet.
                // We'll perform another search to click the specific Wi-Fi toggle switch inside that sheet.
                android.os.Handler(mainLooper).postDelayed({
                    val subRoot = rootInActiveWindow ?: return@postDelayed
                    val wifiSwitch = findWifiSwitchInDialog(subRoot)
                    wifiSwitch?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    
                    // Collapse settings when done
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000)
            } else {
                Toast.makeText(this, "Jarvis: WiFi Quick Setting tile not found in layout.", Toast.LENGTH_LONG).show()
            }
        }, 1200)
    }

    /**
     * Tries to toggle Mobile Data programmatically via Quick Settings.
     */
    private fun executeMobileDataAutomation(enable: Boolean) {
        Toast.makeText(this, "Jarvis Accessibility: Toggling Mobile Data...", Toast.LENGTH_SHORT).show()
        
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        
        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            
            val targets = listOf("mobile data", "cellular data", "data connection", "mobile network")
            val foundNode = findNodeByKeywords(root, targets)
            
            if (foundNode != null) {
                val clicked = foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    foundNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                
                // Wait and close
                android.os.Handler(mainLooper).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000)
            } else {
                Toast.makeText(this, "Jarvis: Mobile Data tile not found in Quick Settings.", Toast.LENGTH_LONG).show()
            }
        }, 1200)
    }

    private fun executeBluetoothAutomation(enable: Boolean) {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        
        android.os.Handler(mainLooper).postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val targets = listOf("bluetooth")
            val foundNode = findNodeByKeywords(root, targets)
            
            if (foundNode != null) {
                val clicked = foundNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!clicked) {
                    foundNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                android.os.Handler(mainLooper).postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 1000)
            }
        }, 1200)
    }

    /**
     * Helper to recursively search for nodes matching text keywords.
     */
    private fun findNodeByKeywords(node: AccessibilityNodeInfo, keywords: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        
        for (keyword in keywords) {
            if (text.contains(keyword) || contentDesc.contains(keyword)) {
                if (node.isClickable) {
                    return node
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByKeywords(child, keywords)
            if (found != null) {
                return found
            }
        }
        return null
    }

    /**
     * Tries to find the Wi-Fi toggle switch in bottom sheet dialogs (Android 12+).
     */
    private fun findWifiSwitchInDialog(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("Switch") || className.contains("ToggleButton") || node.isCheckable) {
            return node
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findWifiSwitchInDialog(child)
            if (found != null) {
                return found
            }
        }
        return null
    }
}
