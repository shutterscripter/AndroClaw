package com.androclaw.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.androclaw.utils.Constants

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val floatingEnabled = prefs.getBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, true)

            if (floatingEnabled && Settings.canDrawOverlays(context)) {
                FloatingButtonService.start(context)
            }
        }
    }
}
