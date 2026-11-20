package com.example.shareflow.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.shareflow.data.local.preferences.SettingsPreferenceKeys
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.SettingsValidator
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Persistent DataStore-backed implementation of [SettingsRepository].
 * Performs strict input validation before persisting any user configuration.
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val dispatchers: DispatcherProvider
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val storedName = prefs[SettingsPreferenceKeys.DEVICE_NAME]
                ?.takeIf { SettingsValidator.isValidDeviceName(it) }
                ?: AppSettings.DEFAULT_DEVICE_NAME

            val storedTheme = prefs[SettingsPreferenceKeys.THEME_MODE]
                ?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() }
                ?: ThemeMode.SYSTEM

            val storedAccent = prefs[SettingsPreferenceKeys.ACCENT_COLOR]
                ?.let { raw -> runCatching { AccentColor.valueOf(raw) }.getOrNull() }
                ?: AccentColor.COBALT

            val storedLanguage = prefs[SettingsPreferenceKeys.APP_LANGUAGE]
                ?.let { raw -> runCatching { AppLanguage.valueOf(raw) }.getOrNull() }
                ?: AppLanguage.SYSTEM

            val storedPort = prefs[SettingsPreferenceKeys.TRANSFER_PORT]
                ?.takeIf { SettingsValidator.isValidTransferPort(it) }
                ?: AppSettings.DEFAULT_TRANSFER_PORT

            AppSettings(
                deviceName = storedName,
                themeMode = storedTheme,
                useDynamicColor = prefs[SettingsPreferenceKeys.USE_DYNAMIC_COLOR] ?: false,
                accentColor = storedAccent,
                appLanguage = storedLanguage,
                defaultSaveDirectoryUri = prefs[SettingsPreferenceKeys.DEFAULT_SAVE_DIRECTORY_URI],
                transferPort = storedPort,
                preferWifiDirect = prefs[SettingsPreferenceKeys.PREFER_WIFI_DIRECT] ?: true,
                verifySha256AfterTransfer = prefs[SettingsPreferenceKeys.VERIFY_SHA256] ?: true,
                autoAcceptTrustedDevices = prefs[SettingsPreferenceKeys.AUTO_ACCEPT_TRUSTED] ?: false,
                requireVaultAuthentication = prefs[SettingsPreferenceKeys.REQUIRE_VAULT_AUTH] ?: true
            )
        }

    override suspend fun updateDeviceName(deviceName: String): AppResult<Unit> {
        val sanitized = SettingsValidator.sanitizeDeviceName(deviceName)
        if (!SettingsValidator.isValidDeviceName(sanitized)) {
            return AppResult.Failure(
                AppError.Validation(
                    "Device name must be between ${AppSettings.MIN_DEVICE_NAME_LENGTH} and ${AppSettings.MAX_DEVICE_NAME_LENGTH} printable characters."
                )
            )
        }
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.DEVICE_NAME] = sanitized
        }
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode): AppResult<Unit> = safeEdit { prefs ->
        prefs[SettingsPreferenceKeys.THEME_MODE] = themeMode.name
    }

    override suspend fun updateDynamicColor(enabled: Boolean): AppResult<Unit> = safeEdit { prefs ->
        prefs[SettingsPreferenceKeys.USE_DYNAMIC_COLOR] = enabled
    }

    override suspend fun updateAccentColor(accentColor: AccentColor): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.ACCENT_COLOR] = accentColor.name
        }

    override suspend fun updateAppLanguage(language: AppLanguage): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.APP_LANGUAGE] = language.name
        }

    override suspend fun updateDefaultSaveDirectoryUri(uriString: String?): AppResult<Unit> =
        safeEdit { prefs ->
            if (uriString.isNullOrBlank()) {
                prefs.remove(SettingsPreferenceKeys.DEFAULT_SAVE_DIRECTORY_URI)
            } else {
                prefs[SettingsPreferenceKeys.DEFAULT_SAVE_DIRECTORY_URI] = uriString.trim()
            }
        }

    override suspend fun updateTransferPort(port: Int): AppResult<Unit> {
        if (!SettingsValidator.isValidTransferPort(port)) {
            return AppResult.Failure(
                AppError.Validation(
                    "Transfer port must be between ${AppSettings.MIN_TRANSFER_PORT} and ${AppSettings.MAX_TRANSFER_PORT}."
                )
            )
        }
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.TRANSFER_PORT] = port
        }
    }

    override suspend fun updatePreferWifiDirect(enabled: Boolean): AppResult<Unit> = safeEdit { prefs ->
        prefs[SettingsPreferenceKeys.PREFER_WIFI_DIRECT] = enabled
    }

    override suspend fun updateVerifySha256AfterTransfer(enabled: Boolean): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.VERIFY_SHA256] = enabled
        }

    override suspend fun updateAutoAcceptTrustedDevices(enabled: Boolean): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.AUTO_ACCEPT_TRUSTED] = enabled
        }

    override suspend fun updateRequireVaultAuthentication(enabled: Boolean): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.REQUIRE_VAULT_AUTH] = enabled
        }

    override suspend fun resetToDefaults(): AppResult<Unit> = safeEdit { prefs ->
        prefs.clear()
    }

    private suspend fun safeEdit(
        transform: (androidx.datastore.preferences.core.MutablePreferences) -> Unit
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { prefs -> transform(prefs) }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to persist settings", it)) }
        )
    }
}
