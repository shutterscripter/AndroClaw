package com.androclaw.ui

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androclaw.db.MessageDao
import com.androclaw.utils.Constants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @Named("encrypted") private val encryptedPrefs: SharedPreferences,
    @Named("regular") private val prefs: SharedPreferences,
    private val messageDao: MessageDao
) : ViewModel() {

    fun getApiKey(): String =
        encryptedPrefs.getString(Constants.PREF_API_KEY, "") ?: ""

    fun setApiKey(key: String) {
        encryptedPrefs.edit().putString(Constants.PREF_API_KEY, key).apply()
    }

    fun getModel(): String {
        val saved = prefs.getString(Constants.PREF_MODEL, Constants.DEFAULT_MODEL) ?: Constants.DEFAULT_MODEL
        return if (saved in Constants.MODEL_OPTIONS.keys) saved else Constants.DEFAULT_MODEL
    }

    fun setModel(model: String) {
        prefs.edit().putString(Constants.PREF_MODEL, model).apply()
    }

    fun getEnabledTools(): Set<String> =
        prefs.getStringSet(Constants.PREF_ENABLED_TOOLS, Constants.ALL_TOOL_NAMES.toSet())
            ?: Constants.ALL_TOOL_NAMES.toSet()

    fun toggleTool(tool: String) {
        val current = getEnabledTools().toMutableSet()
        if (tool in current) current.remove(tool) else current.add(tool)
        prefs.edit().putStringSet(Constants.PREF_ENABLED_TOOLS, current).apply()
    }

    fun isFloatingButtonEnabled(): Boolean =
        prefs.getBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, true)

    fun setFloatingButtonEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Constants.PREF_FLOATING_BUTTON_ENABLED, enabled).apply()
    }

    fun clearHistory() {
        viewModelScope.launch {
            messageDao.clearAll()
        }
    }
}
