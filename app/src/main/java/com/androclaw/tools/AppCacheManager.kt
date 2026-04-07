package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class CachedApp(
    val packageName: String,
    val label: String,
    val labelLower: String,
    val isSystemApp: Boolean
)

@Singleton
class AppCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cache: List<CachedApp> = emptyList()
    private val mutex = Mutex()
    private var lastRefresh = 0L
    private val refreshIntervalMs = 5 * 60 * 1000L

    suspend fun getApps(): List<CachedApp> {
        mutex.withLock {
            if (cache.isEmpty() || System.currentTimeMillis() - lastRefresh > refreshIntervalMs) {
                cache = loadInstalledApps()
                lastRefresh = System.currentTimeMillis()
            }
        }
        return cache
    }

    suspend fun refresh() {
        mutex.withLock {
            cache = loadInstalledApps()
            lastRefresh = System.currentTimeMillis()
        }
    }

    /**
     * Loads all launchable apps using the MAIN/LAUNCHER intent query.
     * This is more reliable than getInstalledApplications() on Android 11+
     * because it queries via intent resolution, which has broader visibility.
     */
    private suspend fun loadInstalledApps(): List<CachedApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = mutableMapOf<String, CachedApp>()

        // Method 1: Query all MAIN/LAUNCHER activities — most reliable on Android 11+
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launchableApps: List<ResolveInfo> = pm.queryIntentActivities(launcherIntent, 0)

        for (resolveInfo in launchableApps) {
            val pkg = resolveInfo.activityInfo.packageName
            if (pkg == context.packageName) continue // Skip self
            val label = resolveInfo.loadLabel(pm).toString()
            val isSystem = (resolveInfo.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            apps[pkg] = CachedApp(pkg, label, label.lowercase(), isSystem)
        }

        // Method 2: Also try getInstalledApplications as a supplement
        try {
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in installed) {
                if (appInfo.packageName in apps) continue
                val hasLauncher = pm.getLaunchIntentForPackage(appInfo.packageName) != null
                if (hasLauncher) {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    apps[appInfo.packageName] = CachedApp(appInfo.packageName, label, label.lowercase(), isSystem)
                }
            }
        } catch (_: Exception) {}

        // Method 3: Add well-known apps that might be hidden by package visibility
        // Try to resolve them explicitly — if they exist, they'll resolve
        WELL_KNOWN_APPS.forEach { (name, pkg) ->
            if (pkg !in apps) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    apps[pkg] = CachedApp(pkg, name, name.lowercase(), false)
                } catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        apps.values.sortedBy { it.labelLower }.toList()
    }

    suspend fun findApp(query: String): List<CachedApp> {
        val apps = getApps()
        val q = query.lowercase().trim()

        // Check well-known aliases first (e.g. "insta" -> Instagram)
        val aliasMatch = resolveAlias(q)
        if (aliasMatch != null) {
            val found = apps.filter { it.packageName == aliasMatch }
            if (found.isNotEmpty()) return found
            // Try resolving directly even if not in cache
            try {
                context.packageManager.getPackageInfo(aliasMatch, 0)
                val label = try {
                    val ai = context.packageManager.getApplicationInfo(aliasMatch, 0)
                    context.packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) { query }
                return listOf(CachedApp(aliasMatch, label, label.lowercase(), false))
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        // Exact label match
        val exact = apps.filter { it.labelLower == q }
        if (exact.isNotEmpty()) return exact

        // Starts with
        val startsWith = apps.filter { it.labelLower.startsWith(q) }
        if (startsWith.isNotEmpty()) return startsWith

        // Contains
        val contains = apps.filter { it.labelLower.contains(q) || q.contains(it.labelLower) }
        if (contains.isNotEmpty()) return contains

        // Package name match
        val pkgMatch = apps.filter { it.packageName.lowercase().contains(q) }
        if (pkgMatch.isNotEmpty()) return pkgMatch

        // Multi-word fuzzy
        val queryWords = q.split(" ", "-", "_")
        val fuzzy = apps.filter { app ->
            queryWords.all { word -> app.labelLower.contains(word) || app.packageName.contains(word) }
        }
        return fuzzy
    }

    private fun resolveAlias(query: String): String? {
        for ((aliases, pkg) in APP_ALIASES) {
            if (query in aliases) return pkg
        }
        return null
    }

    suspend fun listAllApps(): String {
        val apps = getApps().filter { !it.isSystemApp }
        return apps.joinToString("\n") { "- ${it.label} (${it.packageName})" }
    }

    companion object {
        /** Well-known apps to explicitly check even if package visibility hides them */
        val WELL_KNOWN_APPS = listOf(
            "Instagram" to "com.instagram.android",
            "WhatsApp" to "com.whatsapp",
            "YouTube" to "com.google.android.youtube",
            "Twitter" to "com.twitter.android",
            "Facebook" to "com.facebook.katana",
            "Messenger" to "com.facebook.orca",
            "Snapchat" to "com.snapchat.android",
            "Telegram" to "org.telegram.messenger",
            "TikTok" to "com.zhiliaoapp.musically",
            "Spotify" to "com.spotify.music",
            "Netflix" to "com.netflix.mediaclient",
            "Chrome" to "com.android.chrome",
            "Gmail" to "com.google.android.gm",
            "Google Maps" to "com.google.android.apps.maps",
            "Google Photos" to "com.google.android.apps.photos",
            "Google Drive" to "com.google.android.apps.docs",
            "Google Calendar" to "com.google.android.calendar",
            "Phone" to "com.google.android.dialer",
            "Messages" to "com.google.android.apps.messaging",
            "Camera" to "com.android.camera2",
            "Settings" to "com.android.settings",
            "Clock" to "com.google.android.deskclock",
            "Calculator" to "com.google.android.calculator",
            "Files" to "com.google.android.apps.nbu.files",
            "Play Store" to "com.android.vending",
            "Amazon" to "com.amazon.mShop.android.shopping",
            "Reddit" to "com.reddit.frontpage",
            "Discord" to "com.discord",
            "Slack" to "com.Slack",
            "Zoom" to "us.zoom.videomeetings",
            "Uber" to "com.ubercab",
            "LinkedIn" to "com.linkedin.android",
            "Pinterest" to "com.pinterest",
            "Threads" to "com.instagram.barcelona",
            "Signal" to "org.thoughtcrime.securesms",
            "Brave" to "com.brave.browser",
            "Firefox" to "org.mozilla.firefox",
            "Opera" to "com.opera.browser",
            "Samsung Internet" to "com.sec.android.app.sbrowser",
            "Google Pay" to "com.google.android.apps.nbu.paisa.user",
            "PhonePe" to "com.phonepe.app",
            "Paytm" to "net.one97.paytm",
        )

        /** Common aliases/short names people use */
        val APP_ALIASES: List<Pair<List<String>, String>> = listOf(
            listOf("insta", "ig", "instagram") to "com.instagram.android",
            listOf("whatsapp", "wa") to "com.whatsapp",
            listOf("youtube", "yt") to "com.google.android.youtube",
            listOf("twitter", "x") to "com.twitter.android",
            listOf("fb", "facebook") to "com.facebook.katana",
            listOf("snap", "snapchat") to "com.snapchat.android",
            listOf("tg", "telegram") to "org.telegram.messenger",
            listOf("tiktok", "tt") to "com.zhiliaoapp.musically",
            listOf("spotify") to "com.spotify.music",
            listOf("netflix") to "com.netflix.mediaclient",
            listOf("chrome") to "com.android.chrome",
            listOf("gmail", "mail", "email") to "com.google.android.gm",
            listOf("maps", "google maps") to "com.google.android.apps.maps",
            listOf("photos", "google photos") to "com.google.android.apps.photos",
            listOf("drive", "google drive") to "com.google.android.apps.docs",
            listOf("phone", "dialer") to "com.google.android.dialer",
            listOf("messages", "sms") to "com.google.android.apps.messaging",
            listOf("camera") to "com.android.camera2",
            listOf("settings") to "com.android.settings",
            listOf("clock", "alarm") to "com.google.android.deskclock",
            listOf("calculator", "calc") to "com.google.android.calculator",
            listOf("files") to "com.google.android.apps.nbu.files",
            listOf("play store", "playstore") to "com.android.vending",
            listOf("reddit") to "com.reddit.frontpage",
            listOf("discord") to "com.discord",
            listOf("slack") to "com.Slack",
            listOf("zoom") to "us.zoom.videomeetings",
            listOf("uber") to "com.ubercab",
            listOf("linkedin") to "com.linkedin.android",
            listOf("threads") to "com.instagram.barcelona",
            listOf("signal") to "org.thoughtcrime.securesms",
            listOf("gpay", "google pay") to "com.google.android.apps.nbu.paisa.user",
            listOf("phonepe") to "com.phonepe.app",
            listOf("paytm") to "net.one97.paytm",
        )
    }
}
