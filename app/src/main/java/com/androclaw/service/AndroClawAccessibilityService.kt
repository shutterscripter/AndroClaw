package com.androclaw.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        instance = null
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

    companion object {
        @Volatile
        var instance: AndroClawAccessibilityService? = null
    }
}
