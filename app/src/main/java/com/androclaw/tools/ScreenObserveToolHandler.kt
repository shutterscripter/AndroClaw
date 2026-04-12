package com.androclaw.tools

import android.content.Context
import android.content.Intent
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

/**
 * `screen_observe` tool — the perception half of the Set-of-Mark UI loop.
 *
 * Steps:
 *  1. Optionally launch a target app first (`app_package`) and wait for it to settle.
 *  2. Capture a fresh screenshot via the accessibility service.
 *  3. Walk the a11y tree (and OCR fallback when sparse) to gather interactive elements.
 *  4. Draw numbered colored boxes on the screenshot — one per element.
 *  5. Cache the elements (mark → bounds) so a follow-up `control_app_ui`
 *     `tap_mark` call can act on them.
 *  6. Return the marked-up image as a base64 JPEG plus a textual legend.
 *
 * The text legend is appended *after* the IMAGE_BASE64 marker via a special
 * `||LEGEND||` separator that ClaudeRepository.buildToolResultBlock recognises
 * and turns into a paired image+text content block.
 */
@Singleton
class ScreenObserveToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val annotator: ScreenAnnotator,
    private val cache: ScreenObservationCache,
    private val screenCaptureManager: ScreenCaptureManager
) {

    suspend fun execute(input: Map<String, Any>): String {
        // Capture sources, in order of preference:
        //   1. MediaProjection foreground service (preferred — fast, no rate
        //      limit, works on Android 10, doesn't even need a11y to read).
        //   2. AccessibilityService.takeScreenshot (Android 11+, throttled).
        //
        // The accessibility service is still required as a fallback AND for
        // performing taps/swipes/typing — we just don't insist on it being
        // there when MediaProjection is on, so users can grant capture-only
        // permission if they want.
        val a11yService = AndroClawAccessibilityService.instance
        val canUseMediaProjection = screenCaptureManager.isRunning()
        val canUseA11yScreenshot = a11yService != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        if (!canUseMediaProjection && !canUseA11yScreenshot) {
            return "Cannot capture the screen. Either:\n" +
                "  1. Enable MediaProjection in Settings → Screen capture (recommended — works on Android 10+, faster, no rate limit), OR\n" +
                "  2. Enable the AndroClaw accessibility service AND run on Android 11+."
        }

        val appPackage = (input["app_package"] as? String)?.takeIf { it.isNotBlank() }
        if (appPackage != null) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(appPackage)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    delay(1500)  // let the app settle
                }
            } catch (_: Exception) {
                // Best-effort launch — fall through and observe whatever is on screen
            }
        }

        // Pick the best available capture path. MediaProjection wins when on.
        val bitmap: Bitmap = if (canUseMediaProjection) {
            screenCaptureManager.captureFrame()
                ?: a11yService?.captureScreenshot()
                ?: return "MediaProjection capture returned no frame and no accessibility fallback is available."
        } else {
            a11yService!!.captureScreenshot()
                ?: return "Failed to capture screenshot via the accessibility service."
        }

        // Take a software copy so we can hand it to OCR + Canvas, then recycle
        // the hardware-buffer-backed original.
        val soft = try {
            if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            else bitmap
        } catch (e: Exception) {
            return "Failed to convert screenshot: ${e.message}"
        } finally {
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                try { bitmap.recycle() } catch (_: Exception) {}
            }
        }

        val root = a11yService?.rootInActiveWindow  // may be null when running capture-only
        val observation = annotator.annotate(soft, root)
        try { soft.recycle() } catch (_: Exception) {}

        // Persist for the follow-up tap_mark call.
        cache.store(observation)

        // Encode the marked-up image to base64 JPEG.
        val base64 = try {
            val baos = ByteArrayOutputStream()
            observation.annotated.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            return "Failed to encode marked screenshot: ${e.message}"
        } finally {
            try { observation.annotated.recycle() } catch (_: Exception) {}
        }

        val source = if (canUseMediaProjection) "MediaProjection" else "AccessibilityService"
        val legend = buildLegend(observation, appPackage, source)
        return "[IMAGE_BASE64:image/jpeg]$base64||LEGEND||$legend"
    }

    private fun buildLegend(
        observation: ScreenObservation,
        appPackage: String?,
        source: String
    ): String {
        val sb = StringBuilder()
        sb.append("Capture source: $source\n")
        if (appPackage != null) sb.append("Foreground app: $appPackage\n")
        sb.append("Screen size: ${observation.originalWidth}x${observation.originalHeight}\n")
        sb.append("Marked ${observation.elements.size} interactive elements. ")
        sb.append("Pick a mark and call control_app_ui with action ")
        sb.append("{\"type\":\"tap_mark\",\"mark\":N}.\n\n")

        if (observation.elements.isEmpty()) {
            sb.append("(No interactive elements detected. Try `swipe` or `tap_at` with explicit pixel coordinates.)")
            return sb.toString().trim()
        }

        // Group: a11y first, then OCR.
        val a11y = observation.elements.filter { it.source == ElementSource.ACCESSIBILITY }
        val ocr = observation.elements.filter { it.source == ElementSource.OCR }

        if (a11y.isNotEmpty()) {
            sb.append("Accessibility-tree elements:\n")
            for (e in a11y) sb.append(formatElement(e)).append('\n')
        }
        if (ocr.isNotEmpty()) {
            sb.append("\nOCR-detected text (likely tappable, no a11y info):\n")
            for (e in ocr) sb.append(formatElement(e)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun formatElement(e: ScreenElement): String {
        val labelPart = if (e.label.isNotBlank()) "\"${e.label.take(40)}\"" else "(no label)"
        return "  ${e.mark}: ${e.role} $labelPart  @ (${e.centerX},${e.centerY})  " +
            "[${e.bounds.left},${e.bounds.top},${e.bounds.right},${e.bounds.bottom}]"
    }
}
