package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.androclaw.service.AndroClawAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `navigate_guide` — user-guided navigation tool.
 *
 * Unlike `control_app_ui` (which blindly taps), this tool:
 *   1. Walks the accessibility tree of whatever app is in the foreground.
 *   2. Fuzzy-matches a target element by text / contentDescription.
 *   3. Draws a pulsing highlight ring with an arrow + caption over it,
 *      so the user can see exactly where to tap next.
 *   4. Optionally auto-taps when `auto_tap=true`.
 *
 * Typical use: the model is asked "how do I send 100 to Rucha?" and walks
 * the user step by step — on each step it calls navigate_guide with the
 * next hint ("Pay", then "Rucha", then "100", then "Proceed"…), and the
 * user physically taps the highlighted element. The model can chain with
 * `screen_observe` between steps to verify the next screen.
 */
@Singleton
class NavigateToolHandler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun execute(input: Map<String, Any>): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service is not enabled. Please enable AndroClaw under " +
                "Settings → Accessibility so navigation guidance can read and highlight UI."

        val action = (input["action"] as? String)?.lowercase() ?: "highlight"

        if (action == "dismiss" || action == "hide") {
            service.hideHighlight()
            return "Highlight dismissed."
        }

        val hint = (input["hint"] as? String)?.trim().orEmpty()
        if (hint.isEmpty()) {
            return "Missing 'hint'. Provide the text/label of the element to highlight " +
                "(e.g. \"Pay\", \"Rucha\", \"Proceed to Pay\")."
        }

        val appPackage = (input["app_package"] as? String)?.takeIf { it.isNotBlank() }
        if (appPackage != null) {
            try {
                context.packageManager.getLaunchIntentForPackage(appPackage)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    delay(1500)
                }
            } catch (_: Exception) { /* fall through — best-effort */ }
        }

        val root = service.rootInActiveWindow
            ?: return "No active window to inspect. Make sure the target app is in the foreground."

        val match = findBestMatch(root, hint)
            ?: return "Couldn't find anything matching \"$hint\" on the current screen. " +
                "Try calling screen_observe to see what's actually visible, or use a different hint."

        val bounds = Rect().also { match.node.getBoundsInScreen(it) }
        if (bounds.isEmpty) {
            return "Matched element \"${match.label}\" has empty bounds — probably off-screen. " +
                "Scroll first or pick a different hint."
        }

        // Caption priority:
        //   1. Explicit `instruction` from the model (it knows user intent).
        //   2. The `hint` the model searched for (what the user should look for).
        //   3. Matched node label as a last resort, trimmed to something sane.
        val instruction = (input["instruction"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val caption = instruction ?: "Tap \"${hint.take(40)}\""

        val stepNumber = (input["step"] as? Number)?.toInt()
        val totalSteps = (input["total_steps"] as? Number)?.toInt()
        val stepLabel = when {
            stepNumber != null && totalSteps != null -> "STEP $stepNumber OF $totalSteps"
            stepNumber != null -> "STEP $stepNumber"
            else -> null
        }

        val durationMs = (input["duration_ms"] as? Number)?.toLong()?.coerceIn(500L, 30_000L) ?: 6000L
        service.showHighlight(bounds, caption, durationMs, stepLabel)

        val autoTap = input["auto_tap"] == true
        val tapResult = if (autoTap && match.node.isClickable) {
            match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            " (auto-tapped)"
        } else if (autoTap) {
            val parentClickable = climbToClickable(match.node)
            if (parentClickable != null) {
                parentClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                " (auto-tapped parent)"
            } else {
                " (auto_tap requested but element is not clickable — highlight shown instead)"
            }
        } else ""

        return buildString {
            append("Highlighted \"${match.label}\" (${match.source}) ")
            append("at [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
            append(tapResult).append(". ")
            if (!autoTap) append("Caption: \"$caption\". User should tap the highlighted element now. ")
            append("Call navigate_guide again with the next hint once they're on the next screen, ")
            append("or screen_observe to verify what changed.")
        }
    }

    private data class Match(val node: AccessibilityNodeInfo, val label: String, val score: Int, val source: String)

    private fun findBestMatch(root: AccessibilityNodeInfo, hint: String): Match? {
        val needle = hint.lowercase()
        var best: Match? = null

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hay = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                else -> ""
            }
            if (hay.isNotEmpty()) {
                val score = scoreMatch(hay.lowercase(), needle, node.isClickable)
                if (score > 0 && (best == null || score > best!!.score)) {
                    val source = if (text.isNotEmpty()) "text" else "contentDescription"
                    best = Match(node, hay, score, source)
                }
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }
        visit(root)
        return best
    }

    /**
     * Score rubric:
     *   exact match       → 100
     *   starts-with       →  70
     *   contains          →  40
     *   all tokens present →  25
     * Plus +15 if the node itself is clickable (strong preference for
     * tappable targets over labels inside cards).
     */
    private fun scoreMatch(hay: String, needle: String, clickable: Boolean): Int {
        var s = when {
            hay == needle -> 100
            hay.startsWith(needle) -> 70
            hay.contains(needle) -> 40
            else -> {
                val tokens = needle.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (tokens.isNotEmpty() && tokens.all { hay.contains(it) }) 25 else 0
            }
        }
        if (s > 0 && clickable) s += 15
        return s
    }

    private fun climbToClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node.parent
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }
}
