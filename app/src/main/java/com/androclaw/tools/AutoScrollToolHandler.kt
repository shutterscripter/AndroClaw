package com.androclaw.tools

import android.content.Context
import android.content.Intent
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
    private val feedApps = mapOf(
        "instagram_reels" to FeedConfig("com.instagram.android", "instagram://reels_tab/", "Instagram Reels"),
        "instagram" to FeedConfig("com.instagram.android", null, "Instagram"),
        "youtube_shorts" to FeedConfig("com.google.android.youtube", "https://www.youtube.com/shorts/", "YouTube Shorts"),
        "youtube" to FeedConfig("com.google.android.youtube", null, "YouTube"),
        "tiktok" to FeedConfig("com.zhiliaoapp.musically", null, "TikTok"),
        "snapchat_spotlight" to FeedConfig("com.snapchat.android", null, "Snapchat Spotlight"),
        "snapchat" to FeedConfig("com.snapchat.android", null, "Snapchat"),
        "facebook_reels" to FeedConfig("com.facebook.katana", null, "Facebook Reels"),
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
            // Wait for app to load
            delay(2000)
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
        // Try known feed configs first
        val config = feedApps[appKey]
        if (config != null) {
            return launchFeedApp(config)
        }

        // Try fuzzy matching against known feeds
        for ((key, cfg) in feedApps) {
            if (key.contains(appKey) || appKey.contains(key.split("_").first())) {
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

    private fun launchFeedApp(config: FeedConfig): String {
        return try {
            if (config.deepLink != null) {
                // Try deep link to reels/shorts section directly
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(config.deepLink)).apply {
                        `package` = config.packageName
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return "Opened ${config.displayName} via deep link."
                } catch (e: Exception) {
                    // Fall through to regular launch
                }
            }

            val intent = context.packageManager.getLaunchIntentForPackage(config.packageName)
                ?: return "${config.displayName} is not installed."
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Opened ${config.displayName}."
        } catch (e: Exception) {
            "Failed to open ${config.displayName}: ${e.message}"
        }
    }

    private fun resolveAppName(key: String): String {
        return feedApps[key]?.displayName ?: key.replaceFirstChar { it.uppercase() }
    }

    private data class FeedConfig(
        val packageName: String,
        val deepLink: String?,
        val displayName: String
    )
}
