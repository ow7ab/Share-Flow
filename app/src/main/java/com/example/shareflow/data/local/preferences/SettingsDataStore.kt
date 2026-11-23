package com.example.shareflow.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.shareFlowSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shareflow_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
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
    val MAX_CONCURRENT_TRANSFERS = intPreferencesKey("max_concurrent_transfers")
    val FILE_OVERWRITE_POLICY = stringPreferencesKey("file_overwrite_policy")
    val TRANSFER_NOTIFICATIONS_ENABLED = booleanPreferencesKey("transfer_notifications_enabled")
    val HISTORY_RETENTION_POLICY = stringPreferencesKey("history_retention_policy")
    val DISCOVERY_VISIBILITY = stringPreferencesKey("discovery_visibility")
    val PREFERRED_CONNECTION_MODE = stringPreferencesKey("preferred_connection_mode")
    val BIOMETRIC_UNLOCK_PREFERRED = booleanPreferencesKey("biometric_unlock_preferred")
    val CLOUD_UPDATE_MANIFEST_URL = stringPreferencesKey("cloud_update_manifest_url")
    val HAS_COMPLETED_ONBOARDING = booleanPreferencesKey("has_completed_onboarding")
}
