package com.androclaw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.androclaw.ui.ChatScreen
import com.androclaw.ui.ChatViewModel
import com.androclaw.ui.theme.AndroClawTheme
import com.androclaw.utils.RuntimePermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    private var currentPermissionRequest: RuntimePermissionManager.PermissionRequest? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        currentPermissionRequest?.let { request ->
            RuntimePermissionManager.completeRequest(request, results)
            currentPermissionRequest = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Poll for permission requests from tool handlers
        startPermissionPoller()

        setContent {
            AndroClawTheme {
                val viewModel: ChatViewModel = hiltViewModel()

                if (!viewModel.hasApiKey()) {
                    navigateToSettings()
                }

                ChatScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { navigateToSettings() }
                )
            }
        }
    }

    private fun startPermissionPoller() {
        lifecycleScope.launch {
            while (isActive) {
                val request = RuntimePermissionManager.pollRequest()
                if (request != null) {
                    currentPermissionRequest = request
                    permissionLauncher.launch(request.permissions.toTypedArray())
                }
                delay(200) // Check every 200ms
            }
        }
    }

    private fun navigateToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
