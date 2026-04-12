package com.androclaw.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androclaw.api.SystemPromptManager
import com.androclaw.api.provider.ModelInfo
import com.androclaw.api.provider.ProviderRegistry
import com.androclaw.db.ConversationDao
import com.androclaw.db.MessageDao
import com.androclaw.service.ScreenCaptureManager
import com.androclaw.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    val providerRegistry: ProviderRegistry,
    val systemPromptManager: SystemPromptManager,
    private val screenCaptureManager: ScreenCaptureManager
) : ViewModel() {

    fun isScreenCaptureRunning(): Boolean = screenCaptureManager.isRunning()

    // ── Provider ──

    fun getProvider(): String =
        prefs.getString(Constants.PREF_PROVIDER, "claude") ?: "claude"

    fun setProvider(providerId: String) {
        prefs.edit().putString(Constants.PREF_PROVIDER, providerId).apply()
        // Auto-select first model of the new provider if current model isn't in it
        val provider = providerRegistry.getProvider(providerId) ?: return
        val currentModel = getModel()
        val modelExists = provider.supportedModels.any { it.id == currentModel }
        if (!modelExists && provider.supportedModels.isNotEmpty()) {
            setModel(provider.supportedModels.first().id)
        }
    }

    // ── API Key (per-provider) ──

    fun getApiKey(): String = getApiKeyForProvider(getProvider())

    fun getApiKeyForProvider(providerId: String): String =
        encryptedPrefs.getString("api_key_$providerId", "") ?: ""

    fun setApiKey(key: String) = setApiKeyForProvider(getProvider(), key)

    fun setApiKeyForProvider(providerId: String, key: String) {
        encryptedPrefs.edit().putString("api_key_$providerId", key).apply()
        // Also keep the legacy key in sync for backward compat
        if (providerId == getProvider()) {
            encryptedPrefs.edit().putString(Constants.PREF_API_KEY, key).apply()
        }
    }

    // ── Exa API Key (web search provider) ──

    fun getExaApiKey(): String =
        (encryptedPrefs.getString(Constants.PREF_EXA_API_KEY, "") ?: "").trim()

    fun setExaApiKey(key: String) {
        encryptedPrefs.edit().putString(Constants.PREF_EXA_API_KEY, key.trim()).apply()
    }

    // ── GitHub Token ──

    fun getGitHubToken(): String =
        (encryptedPrefs.getString(Constants.PREF_GITHUB_TOKEN, "") ?: "").trim()

    fun setGitHubToken(token: String) {
        // Trim to defeat paste-induced whitespace/newlines from mobile keyboards.
        encryptedPrefs.edit().putString(Constants.PREF_GITHUB_TOKEN, token.trim()).apply()
    }

    // ── Model ──

    fun getModel(): String {
        val saved = prefs.getString(Constants.PREF_MODEL, Constants.DEFAULT_MODEL) ?: Constants.DEFAULT_MODEL
        return saved
    }

    fun setModel(model: String) {
        prefs.edit().putString(Constants.PREF_MODEL, model).apply()
    }

    fun getModelsForCurrentProvider(): List<ModelInfo> {
        val providerId = getProvider()
        return providerRegistry.getProvider(providerId)?.supportedModels ?: emptyList()
    }

    // ── Tools ──

    fun getEnabledTools(): Set<String> =
        prefs.getStringSet(Constants.PREF_ENABLED_TOOLS, Constants.ALL_TOOL_NAMES.toSet())
            ?: Constants.ALL_TOOL_NAMES.toSet()

    fun toggleTool(tool: String) {
        val current = getEnabledTools().toMutableSet()
        if (tool in current) current.remove(tool) else current.add(tool)
        prefs.edit().putStringSet(Constants.PREF_ENABLED_TOOLS, current).apply()
    }

    // ── Floating Button ──

    fun isFloatingButtonEnabled(): Boolean =
        prefs.getBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, false)

    fun setFloatingButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, enabled).apply()
    }

    // ── Prompt Customization ──

    fun getPersona(): String = systemPromptManager.getPersona()
    fun setPersona(text: String) = systemPromptManager.setPersona(text)
    fun resetPersona() = systemPromptManager.resetPersona()
    fun getDefaultPersona(): String = systemPromptManager.getDefaultPersona()

    fun getUserProfile(): String = systemPromptManager.getUserProfile()
    fun setUserProfile(text: String) = systemPromptManager.setUserProfile(text)

    fun getCustomInstructions(): String = systemPromptManager.getCustomInstructions()
    fun setCustomInstructions(text: String) = systemPromptManager.setCustomInstructions(text)

    // ── Data ──

    fun clearHistory() {
        viewModelScope.launch {
            messageDao.clearAll()
            conversationDao.deleteAll()
        }
    }
}
