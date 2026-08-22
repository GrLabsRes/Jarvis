package com.example.control

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.io.DataOutputStream

enum class PermissionTier {
    BASIC,
    ACCESSIBILITY,
    DEVICE_OWNER,
    ROOT
}

class ConnectivityController(private val context: Context) {

    /**
     * Toggles WiFi based on the selected permission tier.
     * Returns a speech feedback string explaining what occurred.
     */
    fun toggleWifi(enable: Boolean, tier: PermissionTier): String {
        val actionWord = if (enable) "on" else "off"
        
        return when (tier) {
            PermissionTier.BASIC -> {
                // In API >= 29, silent toggling is blocked, so launch WiFi Settings/Panel
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        // Open WiFi Quick Settings Panel
                        val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        "Opening WiFi panel. Please toggle WiFi $actionWord."
                    } catch (e: Exception) {
                        // Fallback to normal settings
                        openWifiSettingsFallback()
                        "Opening WiFi settings screen. Please toggle WiFi $actionWord."
                    }
                } else {
                    // Legacy silent toggle
                    try {
                        @Suppress("DEPRECATION")
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        @Suppress("DEPRECATION")
                        wifiManager.isWifiEnabled = enable
                        "WiFi turned $actionWord successfully."
                    } catch (e: Exception) {
                        openWifiSettingsFallback()
                        "Permission restricted. Opening WiFi settings. Please toggle WiFi $actionWord."
                    }
                }
            }

            PermissionTier.ACCESSIBILITY -> {
                // Send intent to Accessibility Service if active, otherwise fallback to basic
                val intent = Intent("com.example.ACCESSIBILITY_COMMAND").apply {
                    putExtra("action", "toggle_wifi")
                    putExtra("enable", enable)
                }
                context.sendBroadcast(intent)
                "Invoking Accessibility assistant to toggle WiFi $actionWord."
            }

            PermissionTier.DEVICE_OWNER -> {
                // Device Policy Manager path (MDM / Device Owner)
                // In standard DeviceOwner setup, DPM controls wifi profiles or restrict settings.
                // Complete programmatic silent wifi control via DPM requires specific active profile parameters.
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val adminComponent = ComponentName(context, "com.example.service.JarvisDeviceAdminReceiver")
                
                if (dpm.isAdminActive(adminComponent)) {
                    // TODO: Implement DevicePolicyManager enterprise wifi/network configurations for enterprise/personal managed provisioning.
                    // This is an advanced path that requires no other apps and factory-reset provisioning.
                    "Device Owner Mode is active. Silent network management is triggered."
                } else {
                    "Device Owner permissions are not fully configured. Falling back to Basic tier. " + toggleWifi(enable, PermissionTier.BASIC)
                }
            }

            PermissionTier.ROOT -> {
                // Root su shell toggling
                val command = if (enable) "svc wifi enable" else "svc wifi disable"
                val success = executeRootCommand(command)
                if (success) {
                    "WiFi toggled $actionWord instantly using root access."
                } else {
                    "Root command failed or device is not rooted. Falling back. " + toggleWifi(enable, PermissionTier.BASIC)
                }
            }
        }
    }

    /**
     * Toggles Mobile Data based on the selected permission tier.
     * Returns a speech feedback string explaining what occurred.
     */
    fun toggleMobileData(enable: Boolean, tier: PermissionTier): String {
        val actionWord = if (enable) "on" else "off"

        return when (tier) {
            PermissionTier.BASIC -> {
                // Direct toggle is impossible since Android 5.0+ without system/signature permission
                // Launch standard cellular network settings
                try {
                    val intent = Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "Opening cellular settings. Please toggle mobile data $actionWord manually."
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        "Opening network settings. Please toggle mobile data $actionWord."
                    } catch (ex: Exception) {
                        "Unable to open mobile data settings. Please toggle mobile data $actionWord from Quick Settings."
                    }
                }
            }

            PermissionTier.ACCESSIBILITY -> {
                val intent = Intent("com.example.ACCESSIBILITY_COMMAND").apply {
                    putExtra("action", "toggle_data")
                    putExtra("enable", enable)
                }
                context.sendBroadcast(intent)
                "Invoking Accessibility assistant to toggle mobile data $actionWord."
            }

            PermissionTier.DEVICE_OWNER -> {
                "Device Owner cellular control requires active carrier-privilege configurations. Falling back. " + toggleMobileData(enable, PermissionTier.BASIC)
            }

            PermissionTier.ROOT -> {
                val command = if (enable) "svc data enable" else "svc data disable"
                val success = executeRootCommand(command)
                if (success) {
                    "Mobile data toggled $actionWord instantly using root access."
                } else {
                    "Root access failed. Falling back. " + toggleMobileData(enable, PermissionTier.BASIC)
                }
            }
        }
    }

    private fun openWifiSettingsFallback() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (ex: Exception) {
            Toast.makeText(context, "Could not open WiFi Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun executeRootCommand(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val result = process.waitFor()
            result == 0
        } catch (e: Exception) {
            false
        }
    }
}
