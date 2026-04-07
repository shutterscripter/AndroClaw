package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.androclaw.service.AndroClawAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoScrollToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appCache: AppCacheManager
) {
    // Known feed entry points
    // navTab = accessibility content-description of the bottom nav tab to tap after launch
    private val feedApps = mapOf(
        "instagram_reels" to FeedConfig("com.instagram.android", null, "Instagram Reels", navTab = "Reels"),
        "instagram reels" to FeedConfig("com.instagram.android", null, "Instagram Reels", navTab = "Reels"),
        "reels" to FeedConfig("com.instagram.android", null, "Instagram Reels", navTab = "Reels"),
        "instagram" to FeedConfig("com.instagram.android", null, "Instagram"),
        "youtube_shorts" to FeedConfig("com.google.android.youtube", null, "YouTube Shorts", navTab = "Shorts"),
        "youtube shorts" to FeedConfig("com.google.android.youtube", null, "YouTube Shorts", navTab = "Shorts"),
        "shorts" to FeedConfig("com.google.android.youtube", null, "YouTube Shorts", navTab = "Shorts"),
        "youtube" to FeedConfig("com.google.android.youtube", null, "YouTube"),
        "tiktok" to FeedConfig("com.zhiliaoapp.musically", null, "TikTok"),
        "snapchat_spotlight" to FeedConfig("com.snapchat.android", null, "Snapchat Spotlight"),
        "snapchat" to FeedConfig("com.snapchat.android", null, "Snapchat"),
        "facebook_reels" to FeedConfig("com.facebook.katana", null, "Facebook Reels", navTab = "Reels"),
        "facebook reels" to FeedConfig("com.facebook.katana", null, "Facebook Reels", navTab = "Reels"),
        "facebook" to FeedConfig("com.facebook.katana", null, "Facebook"),
        "reddit" to FeedConfig("com.reddit.frontpage", null, "Reddit"),
        "twitter" to FeedConfig("com.twitter.android", null, "Twitter/X"),
        "x" to FeedConfig("com.twitter.android", null, "X"),
    )

    suspend fun execute(input: Map<String, Any>): String {
        val action = input["action"] as? String ?: "scroll"

        return when (action.lowercase()) {
            "scroll", "start" -> startScrolling(input)
            "stop" -> stopScrolling()
            "next" -> nextItem()
            "previous", "prev" -> previousItem()
            "like" -> likeCurrentItem()
            "open_feed" -> openFeed(input)
            else -> "Unknown action: $action. Use: scroll, stop, next, previous, like, open_feed"
        }
    }

    private suspend fun startScrolling(input: Map<String, Any>): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled. Please enable it in Settings > Accessibility > AndroClaw."

        val app = (input["app"] as? String)?.lowercase()
        val count = (input["count"] as? Number)?.toInt() ?: 10
        val intervalSeconds = (input["interval_seconds"] as? Number)?.toDouble() ?: 5.0
        val direction = input["direction"] as? String ?: "up"

        // Open the app/feed if specified
        if (app != null) {
            val openResult = openFeedApp(app)
            if (openResult.contains("not installed") || openResult.contains("not found")) {
                return openResult
            }
            // Wait for feed content to load after tab navigation
            delay(1500)
        }

        // Start auto-scrolling
        service.startAutoScroll()
        val intervalMs = (intervalSeconds * 1000).toLong()
        var scrolled = 0

        try {
            for (i in 1..count) {
                if (!service.autoScrollRunning) {
                    return "Auto-scroll stopped after $scrolled swipes."
                }

                service.performSwipeAndWait(direction)
                scrolled++

                if (i < count) {
                    delay(intervalMs)
                }
            }
        } finally {
            service.stopAutoScroll()
        }

        val appName = app?.let { resolveAppName(it) } ?: "current app"
        return "Auto-scrolled through $scrolled items in $appName (${intervalSeconds}s interval)."
    }

    private fun stopScrolling(): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not running."

        service.stopAutoScroll()
        return "Auto-scroll stopped."
    }

    private suspend fun nextItem(): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled."

        return service.performSwipeAndWait("up")
    }

    private suspend fun previousItem(): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled."

        return service.performSwipeAndWait("down")
    }

    private fun likeCurrentItem(): String {
        val service = AndroClawAccessibilityService.instance
            ?: return "Accessibility service not enabled."

        return service.doubleTap()
    }

    private suspend fun openFeed(input: Map<String, Any>): String {
        val app = (input["app"] as? String)?.lowercase()
            ?: return "Missing app name. Use: tiktok, instagram_reels, youtube_shorts, etc."

        return openFeedApp(app)
    }

    private suspend fun openFeedApp(appKey: String): String {
        // Try exact match first
        val config = feedApps[appKey]
        if (config != null) {
            return launchFeedApp(config)
        }

        // Try fuzzy matching — match "instagram reels" to "instagram_reels", "reels" to "instagram_reels" etc.
        val normalized = appKey.replace("_", " ").trim()
        for ((key, cfg) in feedApps) {
            val keyNorm = key.replace("_", " ")
            if (keyNorm.contains(normalized) || normalized.contains(keyNorm) ||
                keyNorm.split(" ").first() == normalized.split(" ").first()) {
                return launchFeedApp(cfg)
            }
        }

        // Fall back to app cache lookup
        val matches = appCache.findApp(appKey)
        if (matches.isNotEmpty()) {
            val pkg = matches.first().packageName
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Opened ${matches.first().label}. Navigate to the feed/reels section, then ask me to scroll."
            }
        }

        return "App \"$appKey\" not found or not installed."
    }

    private suspend fun launchFeedApp(config: FeedConfig): String {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(config.packageName)
                ?: return "${config.displayName} is not installed."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // If this feed has a specific tab to navigate to (Reels, Shorts, etc.)
            if (config.navTab != null) {
                delay(2500) // Wait for app to fully load
                val navigated = navigateToTab(config.navTab)
                if (navigated) {
                    "Opened ${config.displayName} and navigated to ${config.navTab} tab."
                } else {
                    "Opened ${config.displayName} but could not find the ${config.navTab} tab automatically. The app may need to be on the main screen."
                }
            } else {
                "Opened ${config.displayName}."
            }
        } catch (e: Exception) {
            "Failed to open ${config.displayName}: ${e.message}"
        }
    }

    /**
     * Navigate to a specific tab (Reels, Shorts, etc.) in the bottom nav bar
     * by finding and tapping the element matching the tab's content description.
     */
    private fun navigateToTab(tabName: String): Boolean {
        val service = AndroClawAccessibilityService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false

        // Strategy 1: Find by content description (most reliable for nav tabs)
        val nodeByDesc = findNodeByContentDescription(root, tabName)
        if (nodeByDesc != null) {
            return clickNode(nodeByDesc)
        }

        // Strategy 2: Find by text
        val nodesByText = root.findAccessibilityNodeInfosByText(tabName)
        if (!nodesByText.isNullOrEmpty()) {
            return clickNode(nodesByText.first())
        }

        // Strategy 3: Try common variations
        val variations = when (tabName) {
            "Reels" -> listOf("Reels", "reels", "Reels Tab", "reel")
            "Shorts" -> listOf("Shorts", "shorts", "Shorts Tab")
            else -> listOf(tabName)
        }
        for (variant in variations) {
            val nodes = root.findAccessibilityNodeInfosByText(variant)
            if (!nodes.isNullOrEmpty()) {
                return clickNode(nodes.first())
            }
            val descNode = findNodeByContentDescription(root, variant)
            if (descNode != null) {
                return clickNode(descNode)
            }
        }

        return false
    }

    private fun findNodeByContentDescription(
        node: AccessibilityNodeInfo,
        description: String
    ): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString() ?: ""
        if (desc.equals(description, ignoreCase = true) || desc.contains(description, ignoreCase = true)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByContentDescription(child, description)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // Walk up to find a clickable parent
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            parent = parent.parent
        }
        return false
    }

    private fun resolveAppName(key: String): String {
        return feedApps[key]?.displayName ?: key.replaceFirstChar { it.uppercase() }
    }

    private data class FeedConfig(
        val packageName: String,
        val deepLink: String?,
        val displayName: String,
        val navTab: String? = null
    )
}
