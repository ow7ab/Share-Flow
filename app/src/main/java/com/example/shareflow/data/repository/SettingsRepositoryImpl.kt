package com.example.shareflow.data.repository

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.preferences.SettingsPreferenceKeys
import com.example.shareflow.data.local.preferences.StartupAppearanceCache
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.DiscoveryVisibility
import com.example.shareflow.domain.model.FileOverwritePolicy
import com.example.shareflow.domain.model.HistoryRetentionPolicy
import com.example.shareflow.domain.model.PreferredConnectionMode
import com.example.shareflow.domain.model.SettingsValidator
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.repository.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Persistent DataStore-backed implementation of [SettingsRepository].
 * Performs strict input validation before persisting any user configuration and mirrors
 * appearance settings into [StartupAppearanceCache] to prevent startup theme flash.
 */
class SettingsRepositoryImpl(
    private val dataStore: DataStore<Preferences>,
    private val dispatchers: DispatcherProvider,
    private val appearanceCache: StartupAppearanceCache? = null
) : SettingsRepository {

    override fun peekInitialSettings(): AppSettings {
        val cache = appearanceCache ?: return AppSettings()
        cache.readOrNull()?.let { return it }
        // One-time migration seed for existing installs upgrading from DataStore-only persistence
        val migrated = runCatching {
            runBlocking(dispatchers.io) { settings.first() }
        }.getOrDefault(AppSettings())
        cache.write(migrated)
        return migrated
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException || exception is CorruptionException) {
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

            val storedConcurrent = prefs[SettingsPreferenceKeys.MAX_CONCURRENT_TRANSFERS]
                ?.takeIf { SettingsValidator.isValidConcurrentTransfers(it) }
                ?: AppSettings.DEFAULT_CONCURRENT_TRANSFERS

            val storedOverwrite = prefs[SettingsPreferenceKeys.FILE_OVERWRITE_POLICY]
                ?.let { raw -> runCatching { FileOverwritePolicy.valueOf(raw) }.getOrNull() }
                ?: FileOverwritePolicy.KEEP_BOTH_RENAME

            val storedRetention = prefs[SettingsPreferenceKeys.HISTORY_RETENTION_POLICY]
                ?.let { raw -> runCatching { HistoryRetentionPolicy.valueOf(raw) }.getOrNull() }
                ?: HistoryRetentionPolicy.DAYS_30

            val storedVisibility = prefs[SettingsPreferenceKeys.DISCOVERY_VISIBILITY]
                ?.let { raw -> runCatching { DiscoveryVisibility.valueOf(raw) }.getOrNull() }
                ?: DiscoveryVisibility.EVERYONE_NEARBY

            val storedConnectionMode = prefs[SettingsPreferenceKeys.PREFERRED_CONNECTION_MODE]
                ?.let { raw -> runCatching { PreferredConnectionMode.valueOf(raw) }.getOrNull() }
                ?: PreferredConnectionMode.AUTO

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
                requireVaultAuthentication = prefs[SettingsPreferenceKeys.REQUIRE_VAULT_AUTH] ?: true,
                maxConcurrentTransfers = storedConcurrent,
                fileOverwritePolicy = storedOverwrite,
                transferNotificationsEnabled = prefs[SettingsPreferenceKeys.TRANSFER_NOTIFICATIONS_ENABLED] ?: true,
                historyRetentionPolicy = storedRetention,
                discoveryVisibility = storedVisibility,
                preferredConnectionMode = storedConnectionMode,
                biometricUnlockPreferred = prefs[SettingsPreferenceKeys.BIOMETRIC_UNLOCK_PREFERRED] ?: true,
                cloudUpdateManifestUrl = prefs[SettingsPreferenceKeys.CLOUD_UPDATE_MANIFEST_URL]
                    ?.takeIf { SettingsValidator.isValidOptionalHttpsUpdateUrl(it) }
                    .orEmpty(),
                hasCompletedOnboarding = prefs[SettingsPreferenceKeys.HAS_COMPLETED_ONBOARDING] ?: false
            )
        }
        .onEach { resolved ->
            appearanceCache?.write(resolved)
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

    override suspend fun updateThemeMode(themeMode: ThemeMode): AppResult<Unit> {
        appearanceCache?.updateThemeMode(themeMode)
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.THEME_MODE] = themeMode.name
        }
    }

    override suspend fun updateDynamicColor(enabled: Boolean): AppResult<Unit> {
        appearanceCache?.updateDynamicColor(enabled)
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.USE_DYNAMIC_COLOR] = enabled
        }
    }

    override suspend fun updateAccentColor(accentColor: AccentColor): AppResult<Unit> {
        appearanceCache?.updateAccentColor(accentColor)
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.ACCENT_COLOR] = accentColor.name
        }
    }

    override suspend fun updateAppLanguage(language: AppLanguage): AppResult<Unit> {
        appearanceCache?.updateAppLanguage(language)
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.APP_LANGUAGE] = language.name
        }
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

    override suspend fun updateMaxConcurrentTransfers(count: Int): AppResult<Unit> {
        if (!SettingsValidator.isValidConcurrentTransfers(count)) {
            return AppResult.Failure(
                AppError.Validation(
                    "Concurrent transfers must be between ${AppSettings.MIN_CONCURRENT_TRANSFERS} and ${AppSettings.MAX_CONCURRENT_TRANSFERS}."
                )
            )
        }
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.MAX_CONCURRENT_TRANSFERS] = count
        }
    }

    override suspend fun updateFileOverwritePolicy(policy: FileOverwritePolicy): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.FILE_OVERWRITE_POLICY] = policy.name
        }

    override suspend fun updateTransferNotificationsEnabled(enabled: Boolean): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.TRANSFER_NOTIFICATIONS_ENABLED] = enabled
        }

    override suspend fun updateHistoryRetentionPolicy(policy: HistoryRetentionPolicy): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.HISTORY_RETENTION_POLICY] = policy.name
        }

    override suspend fun updateDiscoveryVisibility(visibility: DiscoveryVisibility): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.DISCOVERY_VISIBILITY] = visibility.name
        }

    override suspend fun updatePreferredConnectionMode(mode: PreferredConnectionMode): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.PREFERRED_CONNECTION_MODE] = mode.name
        }

    override suspend fun updateBiometricUnlockPreferred(enabled: Boolean): AppResult<Unit> =
        safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.BIOMETRIC_UNLOCK_PREFERRED] = enabled
        }

    override suspend fun updateCloudUpdateManifestUrl(url: String): AppResult<Unit> {
        val trimmed = url.trim()
        if (!SettingsValidator.isValidOptionalHttpsUpdateUrl(trimmed)) {
            return AppResult.Failure(
                AppError.Validation("Update manifest URL must be a valid public HTTPS URL.")
            )
        }
        return safeEdit { prefs ->
            if (trimmed.isEmpty()) {
                prefs.remove(SettingsPreferenceKeys.CLOUD_UPDATE_MANIFEST_URL)
            } else {
                prefs[SettingsPreferenceKeys.CLOUD_UPDATE_MANIFEST_URL] = trimmed
            }
        }
    }

    override suspend fun markOnboardingCompleted(): AppResult<Unit> {
        appearanceCache?.markOnboardingCompleted()
        return safeEdit { prefs ->
            prefs[SettingsPreferenceKeys.HAS_COMPLETED_ONBOARDING] = true
        }
    }

    override suspend fun resetToDefaults(): AppResult<Unit> {
        appearanceCache?.resetToDefaults()
        return safeEdit { prefs ->
            val completedOnboarding = prefs[SettingsPreferenceKeys.HAS_COMPLETED_ONBOARDING] ?: false
            prefs.clear()
            if (completedOnboarding) {
                prefs[SettingsPreferenceKeys.HAS_COMPLETED_ONBOARDING] = true
            }
        }
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
