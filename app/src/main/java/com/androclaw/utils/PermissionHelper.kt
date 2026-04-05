package com.androclaw.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionHelper @Inject constructor() {

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasSmsPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.SEND_SMS)

    fun hasCallPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.CALL_PHONE)

    fun hasContactsPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CONTACTS)

    fun hasCalendarPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.READ_CALENDAR) &&
                hasPermission(context, Manifest.permission.WRITE_CALENDAR)

    fun hasBluetoothPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasMicrophonePermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.RECORD_AUDIO)

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                    hasPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                    hasPermission(context, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            hasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else true
    }

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "${context.packageName}/${context.packageName}.service.AndroClawAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    fun getRequiredPermissionsForTool(toolName: String): List<String> {
        return when (toolName) {
            "send_sms" -> listOf(Manifest.permission.SEND_SMS)
            "make_phone_call" -> listOf(Manifest.permission.CALL_PHONE)
            "get_contacts", "send_whatsapp" -> listOf(Manifest.permission.READ_CONTACTS)
            "create_calendar_event" -> listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
            "toggle_setting" -> listOf(Manifest.permission.BLUETOOTH_CONNECT)
            "file_manager" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            else -> emptyList()
        }
    }

    fun getMissingPermissions(context: Context, toolName: String): List<String> {
        return getRequiredPermissionsForTool(toolName).filter {
            !hasPermission(context, it)
        }
    }

    /**
     * Check if tool has required permissions. If not, request them via the RuntimePermissionManager
     * which bridges to the active Activity's permission dialog.
     *
     * Returns null if all permissions are granted (proceed with tool execution).
     * Returns an error message string if permissions were denied after asking.
     */
    suspend fun ensurePermissionsForTool(context: Context, toolName: String): String? {
        val missing = getMissingPermissions(context, toolName)
        if (missing.isEmpty()) return null // All good

        // Request permissions via the Activity bridge
        val results = RuntimePermissionManager.request(missing, toolName)

        // Check if all were granted
        val stillMissing = missing.filter { results[it] != true }
        return if (stillMissing.isEmpty()) {
            null // All granted now
        } else {
            val permNames = stillMissing.map { it.substringAfterLast('.').lowercase().replace('_', ' ') }
            "Permission denied: ${permNames.joinToString(", ")}. Please grant ${permNames.first()} permission in your device Settings to use this feature."
        }
    }

    fun requestPermissions(activity: Activity, permissions: List<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), requestCode)
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001

        fun getAllRuntimePermissions(): Array<String> {
            val perms = mutableListOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
                perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            return perms.toTypedArray()
        }
    }
}
