package com.androclaw.tools

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One numbered, tappable element on a screen observation. The agent picks one
 * of these by `mark` number and the gesture executor uses [bounds] to compute a
 * tap target — no text-matching against the (often broken) accessibility tree.
 */
data class ScreenElement(
    val mark: Int,
    val role: String,             // "button", "edittext", "scrollable", "text", …
    val label: String,            // text or contentDescription, may be blank
    val bounds: Rect,             // pixel bounds in the original (unscaled) screen
    val source: ElementSource     // where this candidate came from
) {
    val centerX: Int get() = (bounds.left + bounds.right) / 2
    val centerY: Int get() = (bounds.top + bounds.bottom) / 2
}

enum class ElementSource { ACCESSIBILITY, OCR }

/**
 * Result of an observation pass:
 * - [annotated]    A scaled JPEG-friendly bitmap with numbered colored boxes drawn on top.
 * - [elements]     The full element list, in mark-id order.
 * - [originalSize] The original (pre-scale) screen dimensions, so callers can
 *                  map back from a marked-up image to real device coordinates.
 */
data class ScreenObservation(
    val annotated: Bitmap,
    val elements: List<ScreenElement>,
    val originalWidth: Int,
    val originalHeight: Int
)

/**
 * Builds a Set-of-Mark style overlay for an Android screen:
 *  1. Walks the accessibility tree from the active window root and collects
 *     every node that is clickable / focusable / editable / scrollable.
 *  2. If the tree yields fewer than [OCR_FALLBACK_THRESHOLD] candidates
 *     (common on Compose / React Native / Flutter / custom canvas UIs),
 *     additionally runs ML Kit Latin text recognition over the screenshot
 *     and adds each detected text block as a synthetic element.
 *  3. Draws a numbered colored rectangle over each element on a copy of the
 *     screenshot — green for clickable, orange for editable, blue for scrollable,
 *     gray for OCR-only text. The number matches the mark id the agent uses.
 *
 * Pure-ish helper: takes the bitmap and root node as inputs and returns the
 * observation. Cache lifetime is owned by [ScreenObservationCache].
 */
@Singleton
class ScreenAnnotator @Inject constructor() {

    companion object {
        /** If a11y tree gives fewer interactive nodes than this, fall back to OCR. */
        private const val OCR_FALLBACK_THRESHOLD = 5

        /** Hard cap on marks drawn — keeps the image legible and the legend small. */
        private const val MAX_ELEMENTS = 60

        /** Resize the long edge of the marked-up image to this for upload. */
        private const val MAX_DIMENSION = 1280
    }

    /**
     * Build a screen observation from a captured bitmap and (optionally) the
     * current accessibility root. Either source can be null/empty; if both are
     * empty the result still contains the (un-annotated) image.
     */
    suspend fun annotate(
        original: Bitmap,
        root: AccessibilityNodeInfo?
    ): ScreenObservation {
        val originalW = original.width
        val originalH = original.height

        // ── 1. Collect candidates from the a11y tree ──────────────────
        val a11yCandidates = mutableListOf<RawCandidate>()
        if (root != null) {
            walk(root, a11yCandidates)
        }

        // ── 2. OCR fallback if tree is too sparse ─────────────────────
        val ocrCandidates = if (a11yCandidates.size < OCR_FALLBACK_THRESHOLD) {
            runOcr(original)
        } else emptyList()

        // ── 3. Merge, dedupe, cap, assign mark numbers ────────────────
        val merged = mergeAndDedupe(a11yCandidates, ocrCandidates).take(MAX_ELEMENTS)
        val elements = merged.mapIndexed { idx, raw ->
            ScreenElement(
                mark = idx + 1,
                role = raw.role,
                label = raw.label,
                bounds = raw.bounds,
                source = raw.source
            )
        }

        // ── 4. Draw the overlay onto a software copy ──────────────────
        val drawable = original.copy(Bitmap.Config.ARGB_8888, true)
        drawMarks(drawable, elements)

        // ── 5. Scale down for token efficiency ────────────────────────
        val scaled = scaleDown(drawable, MAX_DIMENSION)
        if (scaled !== drawable) drawable.recycle()

        return ScreenObservation(
            annotated = scaled,
            elements = elements,
            originalWidth = originalW,
            originalHeight = originalH
        )
    }

    // ── Internals ─────────────────────────────────────────────────────

    private data class RawCandidate(
        val role: String,
        val label: String,
        val bounds: Rect,
        val source: ElementSource
    )

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<RawCandidate>) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip zero-area or off-screen nodes — they pollute the overlay.
        if (bounds.width() > 4 && bounds.height() > 4) {
            val role = roleOf(node)
            if (role != null) {
                val label = (node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: "").trim()
                out.add(RawCandidate(role, label, Rect(bounds), ElementSource.ACCESSIBILITY))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out)
        }
    }

    private fun roleOf(node: AccessibilityNodeInfo): String? {
        return when {
            node.isEditable -> "edittext"
            node.isClickable -> "button"
            node.isScrollable -> "scrollable"
            node.isFocusable && !node.text.isNullOrBlank() -> "text"
            else -> null
        }
    }

    private suspend fun runOcr(bitmap: Bitmap): List<RawCandidate> {
        // ML Kit needs a software bitmap; original may be hardware-backed.
        val soft = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap

        return try {
            val image = InputImage.fromBitmap(soft, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = suspendCancellableCoroutine<List<RawCandidate>> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        val out = mutableListOf<RawCandidate>()
                        for (block in text.textBlocks) {
                            for (line in block.lines) {
                                val box = line.boundingBox ?: continue
                                if (box.width() < 4 || box.height() < 4) continue
                                out.add(
                                    RawCandidate(
                                        role = "text",
                                        label = line.text,
                                        bounds = Rect(box),
                                        source = ElementSource.OCR
                                    )
                                )
                            }
                        }
                        cont.resume(out)
                    }
                    .addOnFailureListener { cont.resume(emptyList()) }
            }
            result
        } catch (_: Exception) {
            emptyList()
        } finally {
            if (soft !== bitmap) soft.recycle()
        }
    }

    /**
     * Drop near-duplicate candidates (same bounds within a few pixels). When the
     * a11y tree and OCR both report the same element, prefer the a11y entry
     * because its bounds are more accurate and its role is more informative.
     */
    private fun mergeAndDedupe(
        a11y: List<RawCandidate>,
        ocr: List<RawCandidate>
    ): List<RawCandidate> {
        val merged = mutableListOf<RawCandidate>()
        merged.addAll(a11y)
        outer@ for (cand in ocr) {
            for (existing in merged) {
                if (overlapsHeavily(cand.bounds, existing.bounds)) continue@outer
            }
            merged.add(cand)
        }
        return merged
    }

    private fun overlapsHeavily(a: Rect, b: Rect): Boolean {
        if (!Rect.intersects(a, b)) return false
        val inter = Rect(a)
        if (!inter.intersect(b)) return false
        val interArea = inter.width().toLong() * inter.height().toLong()
        val minArea = minOf(a.width().toLong() * a.height().toLong(),
                            b.width().toLong() * b.height().toLong())
        return minArea > 0 && interArea * 2 >= minArea  // ≥50% overlap of the smaller
    }

    private fun drawMarks(bitmap: Bitmap, elements: List<ScreenElement>) {
        val canvas = Canvas(bitmap)
        val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
        }

        for (e in elements) {
            val color = colorFor(e)
            rectPaint.color = color
            canvas.drawRect(e.bounds, rectPaint)

            val tag = e.mark.toString()
            val padX = 8f
            val padY = 4f
            val textW = labelTextPaint.measureText(tag)
            val textH = labelTextPaint.textSize
            val tagLeft = e.bounds.left.toFloat()
            val tagTop = e.bounds.top.toFloat()
            labelBgPaint.color = color
            canvas.drawRect(
                tagLeft, tagTop,
                tagLeft + textW + padX * 2,
                tagTop + textH + padY * 2,
                labelBgPaint
            )
            canvas.drawText(tag, tagLeft + padX, tagTop + textH + padY - 4f, labelTextPaint)
        }
    }

    private fun colorFor(e: ScreenElement): Int = when (e.role) {
        "button"     -> Color.rgb(40, 200, 80)    // green
        "edittext"   -> Color.rgb(255, 140, 0)    // orange
        "scrollable" -> Color.rgb(40, 120, 220)   // blue
        else         -> if (e.source == ElementSource.OCR) Color.rgb(160, 160, 160)  // gray
                       else Color.rgb(180, 60, 200)  // purple = focusable text
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val ratio = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        return Bitmap.createScaledBitmap(
            bitmap,
            (w * ratio).toInt(),
            (h * ratio).toInt(),
            true
        )
    }
}
