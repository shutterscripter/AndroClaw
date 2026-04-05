package com.androclaw

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.androclaw.service.FloatingButtonService
import com.androclaw.ui.OnboardingScreen
import com.androclaw.ui.onboardingPages
import com.androclaw.ui.theme.AndroClawTheme
import com.androclaw.utils.Constants
import com.androclaw.utils.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
    }

    private var currentPage by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Move to next page after permission dialog
        advancePage()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (prefs.getBoolean(Constants.PREF_ONBOARDING_DONE, false)) {
            navigateToChat()
            return
        }

        setContent {
            AndroClawTheme {
                OnboardingScreen(
                    currentPage = currentPage,
                    onNextPage = { advancePage() },
                    onRequestPermission = { type -> handlePermission(type) },
                    onSkip = { advancePage() },
                    onFinish = {
                        prefs.edit().putBoolean(Constants.PREF_ONBOARDING_DONE, true).apply()
                        navigateToChat()
                    }
                )
            }
        }
    }

    private fun advancePage() {
        if (currentPage < onboardingPages.lastIndex) {
            currentPage++
        }
    }

    private fun handlePermission(type: String) {
        when (type) {
            "core" -> {
                val perms = mutableListOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                permissionLauncher.launch(perms.toTypedArray())
            }
            "storage" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO
                        )
                    )
                } else {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }
            }
            "overlay" -> {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                advancePage()
            }
        }
    }

    private fun navigateToChat() {
        startActivity(Intent(this, ChatActivity::class.java))
        if (prefs.getBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, true) &&
            Settings.canDrawOverlays(this)
        ) {
            FloatingButtonService.start(this)
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
