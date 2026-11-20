package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Contract for reading and mutating persistent local ShareFlow application settings.
 */
interface SettingsRepository {
    val settings: Flow<AppSettings>

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
    suspend fun resetToDefaults(): AppResult<Unit>
}
