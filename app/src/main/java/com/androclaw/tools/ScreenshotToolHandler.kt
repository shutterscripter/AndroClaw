package com.androclaw.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import com.androclaw.service.AndroClawAccessibilityService
import com.androclaw.service.ScreenCaptureManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val screenCaptureManager: ScreenCaptureManager
) {

    // Throttle: a11y screenshot API on Android 11+ is rate-limited (~1/sec)
    // and hammering it can lock up the foreground app. Enforce a minimum
    // gap between every capture path so navigation tools that auto-attach
    // a screenshot don't trip the limiter.
    @Volatile
    private var lastCaptureMs: Long = 0L

    suspend fun execute(input: Map<String, Any>): String {
        val service = AndroClawAccessibilityService.instance
        val analyze = input["analyze"] as? Boolean ?: false

        // analyze=true → return base64 image for vision analysis. Prefer
        // MediaProjection (no rate limit) and fall back to a11y on R+.
        if (analyze) {
            return captureForAi()
                ?: "Failed to capture screenshot for analysis."
        }

        if (service == null) {
            return "Accessibility service not enabled. Please enable it in Settings > Accessibility to take screenshots."
        }

        // Default: trigger system screenshot action (saves to Screenshots folder)
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

    /**
     * Capture the current screen and return it formatted for the LLM as
     * `[IMAGE_BASE64:image/jpeg]<b64>||LEGEND||<legend>`. Used both by the
     * `take_screenshot` tool (analyze=true) and by ToolExecutor when it
     * auto-attaches a screenshot to navigation tool results.
     *
     * Returns null if no capture path is available or capture fails.
     */
    suspend fun captureForAi(legend: String? = null): String? {
        // Throttle: enforce a minimum gap between captures.
        val now = System.currentTimeMillis()
        val gap = now - lastCaptureMs
        if (gap < MIN_CAPTURE_GAP_MS) {
            delay(MIN_CAPTURE_GAP_MS - gap)
        }
        lastCaptureMs = System.currentTimeMillis()

        val bitmap: Bitmap = when {
            screenCaptureManager.isRunning() -> {
                // MediaProjection: fast, no rate limit. Falls back to a11y
                // if it returns no frame.
                screenCaptureManager.captureFrame()
                    ?: tryA11yScreenshot()
            }
            else -> tryA11yScreenshot()
        } ?: return null

        return encodeBitmap(bitmap, legend)
    }

    private suspend fun tryA11yScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val service = AndroClawAccessibilityService.instance ?: return null
        return try {
            service.captureScreenshot()
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, legend: String?): String? {
        return try {
            // HARDWARE bitmaps must be copied to ARGB_8888 before compress().
            val soft: Bitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                try { bitmap.recycle() } catch (_: Exception) {}
                copy
            } else bitmap

            val scaled = scaleDown(soft, MAX_DIMENSION)
            if (scaled !== soft) {
                try { soft.recycle() } catch (_: Exception) {}
            }

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
            try { scaled.recycle() } catch (_: Exception) {}

            val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            val header = "[IMAGE_BASE64:image/jpeg]$base64"
            if (legend != null) "$header||LEGEND||$legend" else header
        } catch (_: Exception) {
            null
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

    companion object {
        // Smaller dimension + lower quality = fewer tokens, faster encode,
        // and less memory pressure on the device. 900px is plenty for the
        // model to read UI elements clearly.
        private const val MAX_DIMENSION = 900
        private const val JPEG_QUALITY = 70

        // Minimum delay between consecutive captures. The Android 11 a11y
        // screenshot API throws TOO_MANY_REQUESTS if called more than once
        // per second; 600ms gives some headroom while keeping the agent
        // loop snappy.
        private const val MIN_CAPTURE_GAP_MS = 600L
    }
}
