package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrightnessToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "set"

        return when (action.lowercase()) {
            "set" -> setBrightness(input)
            "get" -> getBrightness()
            "auto_on" -> setAutoBrightness(true)
            "auto_off" -> setAutoBrightness(false)
            "increase", "up" -> adjustBrightness(25)
            "decrease", "down" -> adjustBrightness(-25)
            "max" -> setBrightnessValue(255)
            "min" -> setBrightnessValue(10)
            else -> "Unknown action: $action. Use: set, get, auto_on, auto_off, increase, decrease, max, min"
        }
    }

    private fun setBrightness(input: Map<String, Any>): String {
        val percentage = (input["percentage"] as? Number)?.toInt()
            ?: (input["level"] as? Number)?.toInt()
            ?: return "Missing brightness percentage (0-100)"

        val value = (percentage * 255 / 100).coerceIn(1, 255)
        return setBrightnessValue(value)
    }

    private fun setBrightnessValue(value: Int): String {
        return if (Settings.System.canWrite(context)) {
            // Disable auto-brightness first
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value
            )
            val pct = (value * 100) / 255
            "Brightness set to $pct%"
        } else {
            openWriteSettingsPermission()
            "Write settings permission needed. Opening settings..."
        }
    }

    private fun getBrightness(): String {
        val brightness = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        val mode = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            0
        )
        val pct = (brightness * 100) / 255
        val modeStr = if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) "Auto" else "Manual"
        return "Brightness: $pct% ($brightness/255), Mode: $modeStr"
    }

    private fun setAutoBrightness(enable: Boolean): String {
        return if (Settings.System.canWrite(context)) {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (enable) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            "Auto-brightness ${if (enable) "enabled" else "disabled"}"
        } else {
            openWriteSettingsPermission()
            "Write settings permission needed. Opening settings..."
        }
    }

    private fun adjustBrightness(delta: Int): String {
        val current = Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128
        )
        val newValue = (current + delta).coerceIn(1, 255)
        return setBrightnessValue(newValue)
    }

    private fun openWriteSettingsPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
