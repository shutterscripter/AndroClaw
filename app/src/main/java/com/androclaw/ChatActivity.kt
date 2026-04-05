package com.androclaw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.hilt.navigation.compose.hiltViewModel
import com.androclaw.ui.ChatScreen
import com.androclaw.ui.ChatViewModel
import com.androclaw.ui.theme.AndroClawTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChatActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AndroClawTheme {
                val viewModel: ChatViewModel = hiltViewModel()

                // If no API key, redirect to settings
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

    private fun navigateToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
