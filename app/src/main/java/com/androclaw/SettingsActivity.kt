package com.androclaw

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.androclaw.service.ScreenCaptureService
import com.androclaw.ui.SettingsScreen
import com.androclaw.ui.SettingsViewModel
import com.androclaw.ui.theme.AndroClawTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    /**
     * Launcher for the MediaProjection consent dialog. On grant, we start
     * [ScreenCaptureService] as a foreground service with the consent token —
     * the service then owns the projection until it's explicitly stopped or
     * the system kills the process.
     */
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = ScreenCaptureService.startIntent(this, result.resultCode, result.data!!)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AndroClawTheme {
                val viewModel: SettingsViewModel = hiltViewModel()
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onRequestScreenCapture = { requestScreenCapture() },
                    onStopScreenCapture = { stopScreenCapture() }
                )
            }
        }
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // createScreenCaptureIntent() must be called fresh every time — the
        // returned intent embeds a one-shot consent token.
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopScreenCapture() {
        ContextCompat.startForegroundService(this, ScreenCaptureService.stopIntent(this))
    }
}
