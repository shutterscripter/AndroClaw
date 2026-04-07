package com.androclaw.api

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Manages the first-run "getting to know you" bootstrap experience.
 * Inspired by OpenClaw's BOOTSTRAP.md — a one-time initialization ritual
 * that collects user info to personalize future interactions.
 *
 * After onboarding (permissions + API key), the bootstrap sends an initial
 * AI message that gathers context about the user and auto-populates memory
 * and user profile. Runs once and never repeats.
 */
@Singleton
class BootstrapManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("regular") private val prefs: SharedPreferences
) {
    companion object {
        const val PREF_BOOTSTRAP_DONE = "bootstrap_done"
    }

    /**
     * Whether bootstrap has already run.
     */
    fun isBootstrapDone(): Boolean =
        prefs.getBoolean(PREF_BOOTSTRAP_DONE, false)

    /**
     * Mark bootstrap as complete.
     */
    fun markBootstrapDone() {
        prefs.edit().putBoolean(PREF_BOOTSTRAP_DONE, true).apply()
    }

    /**
     * Whether bootstrap should run — only after onboarding is done
     * and bootstrap hasn't run yet.
     */
    fun shouldRunBootstrap(): Boolean =
        !isBootstrapDone()

    /**
     * Build the bootstrap prompt that the AI will use to introduce itself
     * and learn about the user. Includes auto-detected device context.
     */
    fun buildBootstrapPrompt(): String {
        val deviceContext = gatherDeviceContext()

        return """This is our very first conversation! I'd like to get to know you so I can be more helpful.

Here's what I already know about your device:
$deviceContext

Please do the following:
1. Introduce yourself briefly as AndroClaw — the user's personal AI phone assistant
2. Ask the user a few quick questions to personalize the experience (keep it conversational, not like a form):
   - What should I call you?
   - What kind of things would you most like help with? (messaging, productivity, browsing, device control, etc.)
   - Any preferences for how I should communicate? (brief vs detailed, casual vs formal)
3. Once they answer, save what you learn using the memory tool:
   - Save their name as memory key "user_name"
   - Save their preferences as memory key "user_preferences"
   - Save their communication style preference as memory key "communication_style"
4. After saving, confirm what you've learned and suggest 2-3 things you can help with right away

Keep it warm and brief — this should feel like meeting a helpful friend, not filling out a survey."""
    }

    /**
     * Gather auto-detectable device context to pre-populate the bootstrap.
     */
    private fun gatherDeviceContext(): String {
        return buildString {
            appendLine("- Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("- Language: ${context.resources.configuration.locales[0].displayLanguage}")
            appendLine("- Region: ${context.resources.configuration.locales[0].displayCountry}")

            try {
                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .size
                appendLine("- Installed apps: $installedApps")
            } catch (_: Exception) {}

            try {
                val timeZone = java.util.TimeZone.getDefault()
                appendLine("- Timezone: ${timeZone.displayName} (${timeZone.id})")
            } catch (_: Exception) {}
        }.trimEnd()
    }
}
