package com.androclaw.tools

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WifiToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val setting = input["setting"] as? String ?: return "Missing setting name"
        val enable = input["enable"]

        val shouldEnable = when (enable) {
            is Boolean -> enable
            is Number -> enable.toInt() == 1
            is String -> enable.lowercase() == "true"
            else -> return "Missing enable/disable value"
        }

        return when (setting) {
            "wifi" -> toggleWifi(shouldEnable)
            "bluetooth" -> toggleBluetooth(shouldEnable)
            "dnd" -> toggleDnd(shouldEnable)
            "flashlight" -> toggleFlashlight(shouldEnable)
            "airplane_mode" -> toggleAirplaneMode(shouldEnable)
            else -> "Unknown setting: $setting"
        }
    }

    private fun toggleWifi(enable: Boolean): String {
        // Android 10+ doesn't allow direct WiFi toggle — open settings panel
        return try {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened WiFi settings panel. Please ${if (enable) "enable" else "disable"} WiFi."
        } catch (e: Exception) {
            "Failed to open WiFi settings: ${e.message}"
        }
    }

    private fun toggleBluetooth(enable: Boolean): String {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter ?: return "Bluetooth not available on this device"

            if (enable) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Bluetooth enable request sent."
            } else {
                // On Android 13+, we can't directly disable. Open settings instead.
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Bluetooth settings. Please disable Bluetooth."
            }
        } catch (e: Exception) {
            "Failed to toggle Bluetooth: ${e.message}"
        }
    }

    private fun toggleDnd(enable: Boolean): String {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!notificationManager.isNotificationPolicyAccessGranted) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "DND access not granted. Opened settings — please grant access to AndroClaw."
            }

            notificationManager.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            "Do Not Disturb ${if (enable) "enabled" else "disabled"} successfully."
        } catch (e: Exception) {
            "Failed to toggle DND: ${e.message}"
        }
    }

    private fun toggleFlashlight(enable: Boolean): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight available on this device"

            cameraManager.setTorchMode(cameraId, enable)
            "Flashlight ${if (enable) "turned on" else "turned off"}."
        } catch (e: Exception) {
            "Failed to toggle flashlight: ${e.message}"
        }
    }

    private fun toggleAirplaneMode(enable: Boolean): String {
        return try {
            val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened airplane mode settings. Please ${if (enable) "enable" else "disable"} it."
        } catch (e: Exception) {
            "Failed to open airplane mode settings: ${e.message}"
        }
    }
}
