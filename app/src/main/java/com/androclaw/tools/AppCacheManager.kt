package com.androclaw.tools

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
    val labelLower: String, // pre-lowered for fast search
    val isSystemApp: Boolean
)

@Singleton
class AppCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cache: List<CachedApp> = emptyList()
    private val mutex = Mutex()
    private var lastRefresh = 0L

    /** Refresh interval: 5 minutes */
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

    /** Force refresh — call after app install/uninstall */
    suspend fun refresh() {
        mutex.withLock {
            cache = loadInstalledApps()
            lastRefresh = System.currentTimeMillis()
        }
    }

    private suspend fun loadInstalledApps(): List<CachedApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        apps.mapNotNull { appInfo ->
            // Only include apps that have a launch intent (real apps)
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            if (launchIntent != null || isUsefulSystemApp(appInfo)) {
                val label = pm.getApplicationLabel(appInfo).toString()
                CachedApp(
                    packageName = appInfo.packageName,
                    label = label,
                    labelLower = label.lowercase(),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            } else null
        }.sortedBy { it.labelLower }
    }

    private fun isUsefulSystemApp(appInfo: ApplicationInfo): Boolean {
        val pkg = appInfo.packageName
        return pkg.contains("settings") ||
                pkg.contains("dialer") ||
                pkg.contains("contacts") ||
                pkg.contains("camera") ||
                pkg.contains("messaging") ||
                pkg.contains("calculator") ||
                pkg.contains("calendar") ||
                pkg.contains("clock") ||
                pkg.contains("files")
    }

    /**
     * Find best matching app by name.
     * Returns list of matches ranked by relevance.
     */
    suspend fun findApp(query: String): List<CachedApp> {
        val apps = getApps()
        val q = query.lowercase().trim()

        // Exact match
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

        // Fuzzy: check if all words in query appear in label
        val queryWords = q.split(" ", "-", "_")
        val fuzzy = apps.filter { app ->
            queryWords.all { word -> app.labelLower.contains(word) || app.packageName.contains(word) }
        }
        return fuzzy
    }

    /** Get all apps formatted as a list string */
    suspend fun listAllApps(): String {
        val apps = getApps().filter { !it.isSystemApp }
        return apps.joinToString("\n") { "- ${it.label} (${it.packageName})" }
    }
}
