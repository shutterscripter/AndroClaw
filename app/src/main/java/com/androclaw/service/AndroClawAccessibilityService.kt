package com.androclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.CompletableDeferred

class AndroClawAccessibilityService : AccessibilityService() {

    @Volatile
    var autoScrollRunning = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        autoScrollRunning = false
        hideHighlight()
        instance = null
    }

    // --- Navigation highlight overlay (user-guided flows) ---

    private var highlightView: HighlightView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideHighlightRunnable = Runnable { hideHighlight() }

    /**
     * Draw a pulsing highlight ring (and optional caption card) over the given
     * on-screen bounds. Auto-dismisses after [durationMs]. Safe to call
     * repeatedly — the previous highlight is torn down first.
     */
    fun showHighlight(
        bounds: Rect,
        caption: String? = null,
        durationMs: Long = 6000L,
        stepLabel: String? = null
    ) {
        mainHandler.post {
            hideHighlightInternal()
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return@post
            val view = HighlightView(this, bounds, caption, stepLabel)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            try {
                wm.addView(view, params)
                highlightView = view
                mainHandler.removeCallbacks(hideHighlightRunnable)
                if (durationMs > 0) mainHandler.postDelayed(hideHighlightRunnable, durationMs)
            } catch (_: Exception) {
                // TYPE_ACCESSIBILITY_OVERLAY failed — swallow; handler will report no highlight.
            }
        }
    }

    fun hideHighlight() {
        mainHandler.post { hideHighlightInternal() }
    }

    private fun hideHighlightInternal() {
        mainHandler.removeCallbacks(hideHighlightRunnable)
        val v = highlightView ?: return
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        try { wm?.removeView(v) } catch (_: Exception) {}
        v.stop()
        highlightView = null
    }

    private class HighlightView(
        ctx: android.content.Context,
        private val target: Rect,
        private val caption: String?,
        private val stepLabel: String?
    ) : View(ctx) {

        private val accent = Color.parseColor("#FF2F80ED")
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
            strokeWidth = 10f
        }
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#552F80ED")
            strokeWidth = 28f
        }
        private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(24f, 0f, 6f, Color.parseColor("#55000000"))
        }
        private val stepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF9AA0A6")
            textSize = 26f
            isFakeBoldText = true
            letterSpacing = 0.12f
        }
        private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF1F1F1F")
            textSize = 40f
            isFakeBoldText = true
        }
        private val nextBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = accent
        }
        private val nextTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            isFakeBoldText = true
            letterSpacing = 0.1f
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(12f, 0f, 4f, Color.parseColor("#44000000"))
        }

        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)  // setShadowLayer needs software
        }

        fun stop() {
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // ── 1. pulsing ring around the target ──
            val cx = target.exactCenterX()
            val cy = target.exactCenterY()
            val baseRadius = maxOf(target.width(), target.height()) / 2f + 20f
            val pulse = baseRadius + (1f - phase) * 46f
            val alpha = (255 * (1f - phase)).toInt().coerceIn(0, 255)
            glowPaint.alpha = alpha
            canvas.drawCircle(cx, cy, pulse, glowPaint)
            canvas.drawCircle(cx, cy, baseRadius, ringPaint)

            if (caption.isNullOrBlank()) return

            // ── 2. caption card: "STEP N" + action + NEXT pill ──
            val padH = 28f
            val padV = 20f
            val innerGap = 20f

            val stepText = stepLabel?.uppercase()
            val stepWidth = if (stepText != null) stepPaint.measureText(stepText) else 0f
            val actionWidth = actionPaint.measureText(caption)
            val textColWidth = maxOf(stepWidth, actionWidth)

            // NEXT pill
            val nextLabel = "NEXT"
            val nextTextWidth = nextTextPaint.measureText(nextLabel)
            val nextPadH = 22f
            val nextPadV = 14f
            val nextWidth = nextTextWidth + nextPadH * 2
            val nextHeight = (nextTextPaint.descent() - nextTextPaint.ascent()) + nextPadV * 2

            val stepLineH = if (stepText != null) stepPaint.descent() - stepPaint.ascent() else 0f
            val actionLineH = actionPaint.descent() - actionPaint.ascent()
            val textColHeight = stepLineH + (if (stepText != null) 6f else 0f) + actionLineH

            val contentHeight = maxOf(textColHeight, nextHeight)
            val boxWidth = (textColWidth + innerGap + nextWidth + padH * 2)
                .coerceAtMost(width - 48f)
            val boxHeight = contentHeight + padV * 2

            // Position: above the target if room, else below. Center on target X,
            // but clamp to screen with a 24px margin. Arrow tip lands on target.
            val placeAbove = target.top - boxHeight - 64f > 0
            val boxLeft = (cx - boxWidth / 2f).coerceIn(24f, width - boxWidth - 24f)
            val boxTop = if (placeAbove) target.top - boxHeight - 56f else target.bottom + 56f
            val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)

            canvas.drawRoundRect(boxRect, 28f, 28f, cardPaint)

            // Text column (left side of card)
            val textLeft = boxRect.left + padH
            val textTop = boxRect.top + padV
            var cursorY = textTop
            if (stepText != null) {
                canvas.drawText(stepText, textLeft, cursorY - stepPaint.ascent(), stepPaint)
                cursorY += stepLineH + 6f
            }
            canvas.drawText(caption, textLeft, cursorY - actionPaint.ascent(), actionPaint)

            // NEXT pill (right side of card)
            val pillRight = boxRect.right - padH
            val pillLeft = pillRight - nextWidth
            val pillTop = boxRect.top + (boxHeight - nextHeight) / 2f
            val pillRect = RectF(pillLeft, pillTop, pillRight, pillTop + nextHeight)
            canvas.drawRoundRect(pillRect, nextHeight / 2f, nextHeight / 2f, nextBgPaint)
            canvas.drawText(
                nextLabel,
                pillLeft + nextPadH,
                pillTop + nextPadV - nextTextPaint.ascent(),
                nextTextPaint
            )

            // ── 3. arrow from card to target ──
            val arrow = Path()
            val tipY: Float
            val baseY: Float
            if (placeAbove) {
                tipY = target.top.toFloat() - 12f
                baseY = boxRect.bottom - 2f
            } else {
                tipY = target.bottom.toFloat() + 12f
                baseY = boxRect.top + 2f
            }
            arrow.moveTo(cx, tipY)
            arrow.lineTo(cx - 22f, baseY)
            arrow.lineTo(cx + 22f, baseY)
            arrow.close()
            canvas.drawPath(arrow, arrowPaint)
        }
    }

    // --- Tap ---

    fun tapByText(text: String): String {
        val rootNode = rootInActiveWindow ?: return "No active window found"
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)

        if (nodes.isNullOrEmpty()) {
            return "Could not find element with text: \"$text\""
        }

        val target = nodes.first()
        return if (target.isClickable) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            "Tapped on \"$text\""
        } else {
            var parent = target.parent
            while (parent != null) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return "Tapped on parent of \"$text\""
                }
                parent = parent.parent
            }
            "Found \"$text\" but it's not clickable"
        }
    }

    fun tapById(viewId: String): String {
        val rootNode = rootInActiveWindow ?: return "No active window found"
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)

        if (nodes.isNullOrEmpty()) {
            return "Could not find element with ID: $viewId"
        }

        nodes.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return "Tapped on element $viewId"
    }

    fun tapAtCoordinates(x: Float, y: Float): String {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        return "Tapped at ($x, $y)"
    }

    // --- Type ---

    fun typeText(text: String): String {
        val rootNode = rootInActiveWindow ?: return "No active window found"
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return findAndFocusEditText(rootNode, text)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return "Typed: \"$text\""
    }

    private fun findAndFocusEditText(rootNode: AccessibilityNodeInfo, text: String): String {
        val editText = findFirstEditText(rootNode)
        if (editText != null) {
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            return "Found text field and typed: \"$text\""
        }
        return "No text field found to type in"
    }

    private fun findFirstEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstEditText(child)?.let { return it }
        }
        return null
    }

    // --- Scroll ---

    fun scroll(direction: String): String {
        val rootNode = rootInActiveWindow ?: return "No active window found"
        val scrollable = findFirstScrollable(rootNode)

        if (scrollable != null) {
            val action = when (direction.lowercase()) {
                "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            scrollable.performAction(action)
            return "Scrolled $direction"
        }

        return performSwipeGesture(direction)
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFirstScrollable(child)?.let { return it }
        }
        return null
    }

    // --- Swipe gestures (full-screen, proper for reels/shorts/tiktok) ---

    fun performSwipeGesture(direction: String, durationMs: Long = 250): String {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        val centerX = screenW / 2f

        // Use 75% of screen height for a full-screen snap swipe
        val startMargin = screenH * 0.15f
        val endMargin = screenH * 0.85f

        val path = Path()
        when (direction.lowercase()) {
            // Swipe UP = next reel (finger moves from bottom to top)
            "up", "next" -> {
                path.moveTo(centerX, endMargin)
                path.lineTo(centerX, startMargin)
            }
            // Swipe DOWN = previous reel (finger moves from top to bottom)
            "down", "previous", "prev" -> {
                path.moveTo(centerX, startMargin)
                path.lineTo(centerX, endMargin)
            }
            "left" -> {
                path.moveTo(screenW * 0.85f, screenH / 2f)
                path.lineTo(screenW * 0.15f, screenH / 2f)
            }
            "right" -> {
                path.moveTo(screenW * 0.15f, screenH / 2f)
                path.lineTo(screenW * 0.85f, screenH / 2f)
            }
            else -> {
                path.moveTo(centerX, endMargin)
                path.lineTo(centerX, startMargin)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        dispatchGesture(gesture, null, null)
        return "Swiped $direction"
    }

    /**
     * Perform a swipe and wait for it to complete via callback.
     */
    suspend fun performSwipeAndWait(direction: String, durationMs: Long = 250): String {
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        val centerX = screenW / 2f
        val startMargin = screenH * 0.15f
        val endMargin = screenH * 0.85f

        val path = Path()
        when (direction.lowercase()) {
            "up", "next" -> {
                path.moveTo(centerX, endMargin)
                path.lineTo(centerX, startMargin)
            }
            "down", "previous", "prev" -> {
                path.moveTo(centerX, startMargin)
                path.lineTo(centerX, endMargin)
            }
            else -> {
                path.moveTo(centerX, endMargin)
                path.lineTo(centerX, startMargin)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val deferred = CompletableDeferred<Boolean>()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }

        dispatchGesture(gesture, callback, null)
        val success = deferred.await()
        return if (success) "Swiped $direction" else "Swipe cancelled"
    }

    /**
     * Double-tap (like a reel / post)
     */
    fun doubleTap(): String {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f

        // First tap
        val tap1 = Path().apply { moveTo(centerX, centerY) }
        val tap2 = Path().apply { moveTo(centerX, centerY) }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(tap1, 0, 50))
            .addStroke(GestureDescription.StrokeDescription(tap2, 100, 50))
            .build()

        dispatchGesture(gesture, null, null)
        return "Double-tapped center of screen"
    }

    // --- Auto-scroll for reels/shorts/tiktok ---

    fun startAutoScroll() {
        autoScrollRunning = true
    }

    fun stopAutoScroll() {
        autoScrollRunning = false
    }

    // --- Navigation ---

    fun pressBack(): String {
        performGlobalAction(GLOBAL_ACTION_BACK)
        return "Pressed back"
    }

    fun pressHome(): String {
        performGlobalAction(GLOBAL_ACTION_HOME)
        return "Pressed home"
    }

    fun openRecents(): String {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        return "Opened recent apps"
    }

    fun openQuickSettings(): String {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        return "Opened quick settings"
    }

    // --- Screenshot capture to Bitmap (Android 11+) ---

    suspend fun captureScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val deferred = CompletableDeferred<Bitmap?>()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    deferred.complete(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    deferred.complete(null)
                }
            }
        )

        return deferred.await()
    }

    companion object {
        @Volatile
        var instance: AndroClawAccessibilityService? = null
    }
}
