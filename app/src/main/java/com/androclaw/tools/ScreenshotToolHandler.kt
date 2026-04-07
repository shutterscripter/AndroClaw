package com.androclaw.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.androclaw.service.AndroClawAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun execute(input: Map<String, Any>): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled. Please enable it in Settings > Accessibility to take screenshots."

        val analyze = input["analyze"] as? Boolean ?: false

        if (analyze && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return captureAndEncode(service)
        }

        // Default: trigger system screenshot action
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

    private suspend fun captureAndEncode(service: AndroClawAccessibilityService): String {
        val bitmap = service.captureScreenshot()
            ?: return "Failed to capture screenshot for analysis. Try without analyze=true."

        return try {
            // Convert hardware bitmap to software bitmap for compression
            val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            bitmap.recycle()

            // Scale down if too large (max 1280px on longest side to save tokens)
            val scaled = scaleDown(softBitmap, 1280)
            if (scaled !== softBitmap) softBitmap.recycle()

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            scaled.recycle()

            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "[IMAGE_BASE64:image/jpeg]$base64"
        } catch (e: Exception) {
            "Failed to encode screenshot: ${e.message}"
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxDimension && height <= maxDimension) return bitmap

        val ratio = minOf(maxDimension.toFloat() / width, maxDimension.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
