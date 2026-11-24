package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.DiscoveryVisibility
import com.example.shareflow.domain.model.FileOverwritePolicy
import com.example.shareflow.domain.model.HistoryRetentionPolicy
import com.example.shareflow.domain.model.PreferredConnectionMode
import com.example.shareflow.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading and mutating persistent local ShareFlow application settings.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

    /**
     * Synchronously returns the last persisted appearance settings (theme, accent, dynamic color, language)
     * so MainActivity and ShareFlowApp render Frame 1 in the exact user-selected theme without flashing.
     */
    fun peekInitialSettings(): AppSettings = AppSettings()

    suspend fun updateDeviceName(deviceName: String): AppResult<Unit>
    suspend fun updateThemeMode(themeMode: ThemeMode): AppResult<Unit>
    suspend fun updateDynamicColor(enabled: Boolean): AppResult<Unit>
    suspend fun updateAccentColor(accentColor: AccentColor): AppResult<Unit>
    suspend fun updateAppLanguage(language: AppLanguage): AppResult<Unit>
    suspend fun updateDefaultSaveDirectoryUri(uriString: String?): AppResult<Unit>
    suspend fun updateTransferPort(port: Int): AppResult<Unit>
    suspend fun updatePreferWifiDirect(enabled: Boolean): AppResult<Unit>
    suspend fun updateVerifySha256AfterTransfer(enabled: Boolean): AppResult<Unit>
    suspend fun updateAutoAcceptTrustedDevices(enabled: Boolean): AppResult<Unit>
    suspend fun updateRequireVaultAuthentication(enabled: Boolean): AppResult<Unit>
    suspend fun updateMaxConcurrentTransfers(count: Int): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateFileOverwritePolicy(policy: FileOverwritePolicy): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateTransferNotificationsEnabled(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateHistoryRetentionPolicy(policy: HistoryRetentionPolicy): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateDiscoveryVisibility(visibility: DiscoveryVisibility): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updatePreferredConnectionMode(mode: PreferredConnectionMode): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateBiometricUnlockPreferred(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateCloudUpdateManifestUrl(url: String): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun updateAutoCheckUpdatesOnLaunch(enabled: Boolean): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun markOnboardingCompleted(): AppResult<Unit> = AppResult.Success(Unit)
    suspend fun resetToDefaults(): AppResult<Unit>
}
