package com.androclaw.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import com.androclaw.service.AndroClawAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    fun execute(input: Map<String, Any>): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled. Please enable it in Settings > Accessibility to take screenshots."

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                "Screenshot taken. Check your Screenshots folder or notification."
            } catch (e: Exception) {
                "Failed to take screenshot: ${e.message}"
            }
        } else {
            "Screenshot requires Android 9+."
        }
    }
}
