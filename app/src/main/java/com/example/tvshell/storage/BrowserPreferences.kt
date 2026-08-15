package com.example.tvshell.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.tvshell.LocaleHelper
import com.example.tvshell.browser.UserAgentPreset
import java.util.UUID

class BrowserPreferences(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var lastSuccessfulUrl: String?
        get() = prefs.getString(KEY_LAST_SUCCESSFUL_URL, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_LAST_SUCCESSFUL_URL)
                } else {
                    putString(KEY_LAST_SUCCESSFUL_URL, value.trim())
                }
            }
        }

    val remoteToken: String
        get() {
            var token = prefs.getString(KEY_REMOTE_TOKEN, null)
            if (token.isNullOrBlank()) {
                token = generateNewToken()
            }
            return token
        }

    fun regenerateRemoteToken(): String {
        return generateNewToken()
    }

    private fun generateNewToken(): String {
        val newToken = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "")
        prefs.edit { putString(KEY_REMOTE_TOKEN, newToken) }
        return newToken
    }

    var userAgentPreset: String
        get() = prefs.getString(KEY_USER_AGENT_PRESET, null)?.takeIf { it.isNotBlank() }
            ?: UserAgentPreset.DEFAULT.id
        set(value) {
            prefs.edit { putString(KEY_USER_AGENT_PRESET, value) }
        }

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, null)?.takeIf { it.isNotBlank() }
            ?: LocaleHelper.SYSTEM
        set(value) {
            prefs.edit { putString(KEY_APP_LANGUAGE, value) }
        }

    var preferredNetworkInterface: String?
        get() = prefs.getString(KEY_PREFERRED_NIC, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) {
                    remove(KEY_PREFERRED_NIC)
                } else {
                    putString(KEY_PREFERRED_NIC, value)
                }
            }
        }

    fun clearLastUrl() {
        lastSuccessfulUrl = null
    }

    companion object {
        private const val PREFS_NAME = "tv_browser_prefs"
        private const val KEY_LAST_SUCCESSFUL_URL = "key_last_successful_url"
        private const val KEY_REMOTE_TOKEN = "key_remote_token"
        private const val KEY_USER_AGENT_PRESET = "key_user_agent_preset"
        private const val KEY_PREFERRED_NIC = "key_preferred_network_interface"
        private const val KEY_APP_LANGUAGE = "key_app_language"

        @Volatile
        private var INSTANCE: BrowserPreferences? = null

        fun getInstance(context: Context): BrowserPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BrowserPreferences(context).also { INSTANCE = it }
            }
        }
    }
}
