package com.example.shareflow.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.shareFlowSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shareflow_settings"
)

object SettingsPreferenceKeys {
    val DEVICE_NAME = stringPreferencesKey("device_name")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val APP_LANGUAGE = stringPreferencesKey("app_language")
    val DEFAULT_SAVE_DIRECTORY_URI = stringPreferencesKey("default_save_directory_uri")
    val TRANSFER_PORT = intPreferencesKey("transfer_port")
    val PREFER_WIFI_DIRECT = booleanPreferencesKey("prefer_wifi_direct")
    val VERIFY_SHA256 = booleanPreferencesKey("verify_sha256_after_transfer")
    val AUTO_ACCEPT_TRUSTED = booleanPreferencesKey("auto_accept_trusted_devices")
    val REQUIRE_VAULT_AUTH = booleanPreferencesKey("require_vault_authentication")
}
