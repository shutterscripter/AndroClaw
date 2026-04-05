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
            // Suggest similar apps
            val allApps = appCache.getApps().filter { !it.isSystemApp }
            val suggestions = allApps.take(5).joinToString(", ") { it.label }
            return "Could not find app \"$appName\". Some installed apps: $suggestions"
        }

        val bestMatch = matches.first()
        return try {
            val intent = if (action != null) {
                createActionIntent(bestMatch.packageName, action)
            } else {
                context.packageManager.getLaunchIntentForPackage(bestMatch.packageName)
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                if (matches.size > 1) {
                    "Opened ${bestMatch.label}. (Also found: ${matches.drop(1).take(3).joinToString { it.label }})"
                } else {
                    "Opened ${bestMatch.label} successfully."
                }
            } else {
                "Could not create launch intent for ${bestMatch.label}"
            }
        } catch (e: Exception) {
            "Failed to open ${bestMatch.label}: ${e.message}"
        }
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
                try {
                    if (action.startsWith("http") || action.contains("://")) {
                        Intent(Intent.ACTION_VIEW, Uri.parse(action)).apply {
                            `package` = packageName
                        }
                    } else {
                        context.packageManager.getLaunchIntentForPackage(packageName)
                    }
                } catch (e: Exception) {
                    context.packageManager.getLaunchIntentForPackage(packageName)
                }
            }
        }
    }
}
