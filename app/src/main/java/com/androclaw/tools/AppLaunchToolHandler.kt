package com.androclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLaunchToolHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appCache: AppCacheManager
) {

    suspend fun execute(input: Map<String, Any>): String {
        val appName = input["app_name"] as? String ?: return "Missing app_name"
        val action = input["action"] as? String

        val matches = appCache.findApp(appName)
        if (matches.isEmpty()) {
            val suggestions = appCache.getApps().filter { !it.isSystemApp }.take(5)
                .joinToString(", ") { it.label }
            return "Could not find app \"$appName\". Some installed apps: $suggestions"
        }

        val bestMatch = matches.first()
        return launchApp(bestMatch.packageName, bestMatch.label, action, matches)
    }

    private fun launchApp(
        packageName: String,
        label: String,
        action: String?,
        allMatches: List<CachedApp>
    ): String {
        return try {
            val intent = if (action != null) {
                createActionIntent(packageName, action)
            } else {
                context.packageManager.getLaunchIntentForPackage(packageName)
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                if (allMatches.size > 1) {
                    "Opened $label. (Also found: ${allMatches.drop(1).take(3).joinToString { it.label }})"
                } else {
                    "Opened $label successfully."
                }
            } else {
                // Fallback: try launching via package directly
                tryFallbackLaunch(packageName, label)
            }
        } catch (e: Exception) {
            // Fallback on any exception
            tryFallbackLaunch(packageName, label)
        }
    }

    /**
     * Fallback launch methods when getLaunchIntentForPackage returns null
     * (can happen with package visibility restrictions).
     */
    private fun tryFallbackLaunch(packageName: String, label: String): String {
        // Try 1: Explicit intent to the main activity via package manager
        try {
            val pm = context.packageManager
            val launchIntent = pm.getLeanbackLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return "Opened $label successfully."
            }
        } catch (_: Exception) {}

        // Try 2: monkey activity (common pattern for main activity)
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                `package` = packageName
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "Opened $label successfully."
        } catch (_: Exception) {}

        // Try 3: Open via Play Store deep link (at least gets them to the app page)
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "Couldn't launch $label directly — opened its Play Store page instead."
        } catch (_: Exception) {}

        return "Failed to open $label ($packageName). It may not be installed."
    }

    suspend fun listApps(input: Map<String, Any>): String {
        val filter = input["filter"] as? String
        val apps = appCache.getApps()

        val filtered = if (filter != null) {
            val q = filter.lowercase()
            apps.filter { it.labelLower.contains(q) || it.packageName.contains(q) }
        } else {
            apps.filter { !it.isSystemApp }
        }

        return if (filtered.isEmpty()) {
            "No apps found matching \"$filter\"."
        } else {
            "Installed apps (${filtered.size}):\n" +
                    filtered.joinToString("\n") { "- ${it.label} (${it.packageName})" }
        }
    }

    private fun createActionIntent(packageName: String, action: String): Intent? {
        return when (action.lowercase()) {
            "camera" -> Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                `package` = packageName
            }
            else -> {
                if (action.startsWith("http") || action.contains("://")) {
                    Intent(Intent.ACTION_VIEW, Uri.parse(action)).apply {
                        `package` = packageName
                    }
                } else {
                    context.packageManager.getLaunchIntentForPackage(packageName)
                }
            }
        }
    }
}
