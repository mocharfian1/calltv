package com.mocharfian.tvphone

import android.content.Context

private const val PREFS_NAME = "tv_phone_prefs"
private const val KEY_SERVER_URL = "server_url"

class CallSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServerUrl(): String = prefs.getString(KEY_SERVER_URL, "")?.trim().orEmpty()

    fun saveServerUrl(serverUrl: String) {
        prefs.edit().putString(KEY_SERVER_URL, serverUrl.trim()).apply()
    }
}

