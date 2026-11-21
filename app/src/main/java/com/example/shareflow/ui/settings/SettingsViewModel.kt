package com.example.shareflow.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.core.security.KeystoreCipher
import com.example.shareflow.data.update.CloudUpdateManager
import com.example.shareflow.data.update.CloudUpdateManifest
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.UpdateInstallLaunchResult
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.DiscoveryVisibility
import com.example.shareflow.domain.model.FileOverwritePolicy
import com.example.shareflow.domain.model.HistoryRetentionPolicy
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PreferredConnectionMode
import com.example.shareflow.domain.model.SettingsValidator
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TransferHistoryRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.security.DeviceIdentityManager
import com.example.shareflow.utilities.UtilitiesRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsFeedbackType {
    DEVICE_NAME_SAVED,
    PORT_SAVED,
    RESET_DONE,
    FOLDER_SAVED,
    CACHE_CLEARED,
    HISTORY_CLEARED,
    HISTORY_PRUNED,
    FAVORITES_CLEARED,
    TRUSTED_REVOKED,
    UPDATE_URL_SAVED,
    UPDATE_INSTALL_PERMISSION_REQUIRED,
    UPDATE_INSTALL_LAUNCH_FAILED
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val deviceNameDraft: String = AppSettings.DEFAULT_DEVICE_NAME,
    val portDraft: String = AppSettings.DEFAULT_TRANSFER_PORT.toString(),
    val cloudUpdateUrlDraft: String = "",
    val deviceNameError: Boolean = false,
    val portError: Boolean = false,
    val cloudUpdateUrlError: Boolean = false,
    val isKeystoreAvailable: Boolean = false,
    val localIdentity: LocalDeviceIdentity? = null,
    val trustedDeviceCount: Int = 0,
    val temporaryCacheBytes: Long = 0L,
    val cloudUpdateState: CloudUpdateState = CloudUpdateState.Idle,
    val isSaving: Boolean = false,
    val feedbackEvent: SettingsFeedbackType? = null,
    val errorMessage: String? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val keystoreCipher: KeystoreCipher,
    private val deviceIdentityManager: DeviceIdentityManager? = null,
    private val transferHistoryRepository: TransferHistoryRepository? = null,
    private val trustedDeviceRepository: TrustedDeviceRepository? = null,
    private val utilitiesRepository: UtilitiesRepository? = null,
    private val cloudUpdateManager: CloudUpdateManager? = null,
    private val clock: Clock = SystemClock()
) : ViewModel() {

    private var activeUpdateJob: Job? = null

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            isKeystoreAvailable = keystoreCipher.isKeystoreAvailable(),
            localIdentity = runCatching { deviceIdentityManager?.getLocalIdentity() }.getOrNull()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collectLatest { persisted ->
                _uiState.update { current ->
                    current.copy(
                        settings = persisted,
                        deviceNameDraft = persisted.deviceName,
                        portDraft = persisted.transferPort.toString(),
                        cloudUpdateUrlDraft = persisted.cloudUpdateManifestUrl,
                        deviceNameError = false,
                        portError = false,
                        cloudUpdateUrlError = false
                    )
                }
            }
        }
        trustedDeviceRepository?.let { repo ->
            viewModelScope.launch {
                repo.observeTrustedCount().collectLatest { count ->
                    _uiState.update { it.copy(trustedDeviceCount = count) }
                }
            }
        }
        cloudUpdateManager?.let { updater ->
            viewModelScope.launch {
                updater.state.collectLatest { updateState ->
                    _uiState.update { it.copy(cloudUpdateState = updateState) }
                }
            }
        }
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        val utilRepo = utilitiesRepository ?: return
        viewModelScope.launch {
            val bytes = utilRepo.computeTemporaryCacheBytes()
            _uiState.update { it.copy(temporaryCacheBytes = bytes) }
        }
    }

    fun onDeviceNameDraftChange(value: String) {
        _uiState.update {
            it.copy(
                deviceNameDraft = value,
                deviceNameError = false,
                errorMessage = null
            )
        }
    }

    fun saveDeviceName() {
        val draft = _uiState.value.deviceNameDraft
        if (!SettingsValidator.isValidDeviceName(draft)) {
            _uiState.update { it.copy(deviceNameError = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = settingsRepository.updateDeviceName(draft)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        deviceNameError = false,
                        feedbackEvent = SettingsFeedbackType.DEVICE_NAME_SAVED,
                        errorMessage = null
                    )
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        deviceNameError = true,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun onPortDraftChange(value: String) {
        val digitsOnly = value.filter { it.isDigit() }.take(5)
        _uiState.update {
            it.copy(
                portDraft = digitsOnly,
                portError = false,
                errorMessage = null
            )
        }
    }

    fun saveTransferPort() {
        val parsed = _uiState.value.portDraft.toIntOrNull()
        if (parsed == null || !SettingsValidator.isValidTransferPort(parsed)) {
            _uiState.update { it.copy(portError = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = settingsRepository.updateTransferPort(parsed)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        portError = false,
                        feedbackEvent = SettingsFeedbackType.PORT_SAVED,
                        errorMessage = null
                    )
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        portError = true,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun onDefaultSaveDirectorySelected(uriString: String?) {
        viewModelScope.launch {
            when (val res = settingsRepository.updateDefaultSaveDirectoryUri(uriString)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(feedbackEvent = SettingsFeedbackType.FOLDER_SAVED, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.updateThemeMode(mode) }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateDynamicColor(enabled) }
    }

    fun onAccentColorSelected(accentColor: AccentColor) {
        viewModelScope.launch { settingsRepository.updateAccentColor(accentColor) }
    }

    fun onAppLanguageSelected(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.updateAppLanguage(language) }
    }

    fun onPreferWifiDirectChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updatePreferWifiDirect(enabled) }
    }

    fun onVerifySha256Changed(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateVerifySha256AfterTransfer(enabled) }
    }

    fun onAutoAcceptTrustedChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAutoAcceptTrustedDevices(enabled) }
    }

    fun onRequireVaultAuthChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateRequireVaultAuthentication(enabled) }
    }

    fun onMaxConcurrentTransfersSelected(count: Int) {
        if (!SettingsValidator.isValidConcurrentTransfers(count)) return
        viewModelScope.launch { settingsRepository.updateMaxConcurrentTransfers(count) }
    }

    fun onFileOverwritePolicySelected(policy: FileOverwritePolicy) {
        viewModelScope.launch { settingsRepository.updateFileOverwritePolicy(policy) }
    }

    fun onTransferNotificationsChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateTransferNotificationsEnabled(enabled) }
    }

    fun onHistoryRetentionSelected(policy: HistoryRetentionPolicy) {
        viewModelScope.launch { settingsRepository.updateHistoryRetentionPolicy(policy) }
    }

    fun onDiscoveryVisibilitySelected(visibility: DiscoveryVisibility) {
        viewModelScope.launch { settingsRepository.updateDiscoveryVisibility(visibility) }
    }

    fun onPreferredConnectionModeSelected(mode: PreferredConnectionMode) {
        viewModelScope.launch { settingsRepository.updatePreferredConnectionMode(mode) }
    }

    fun onBiometricUnlockPreferredChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateBiometricUnlockPreferred(enabled) }
    }

    fun pruneExpiredHistoryNow() {
        val repo = transferHistoryRepository ?: return
        val days = _uiState.value.settings.historyRetentionPolicy.days ?: return
        viewModelScope.launch {
            val cutoff = clock.nowEpochMillis() - (days.toLong() * 86_400_000L)
            when (val res = repo.pruneRecordsOlderThan(cutoff)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(feedbackEvent = SettingsFeedbackType.HISTORY_PRUNED, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun clearTemporaryCache() {
        val utilRepo = utilitiesRepository ?: return
        viewModelScope.launch {
            when (val res = utilRepo.clearTemporaryCache()) {
                is AppResult.Success -> {
                    val remaining = utilRepo.computeTemporaryCacheBytes()
                    _uiState.update {
                        it.copy(
                            temporaryCacheBytes = remaining,
                            feedbackEvent = SettingsFeedbackType.CACHE_CLEARED,
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun clearTransferHistory() {
        val repo = transferHistoryRepository ?: return
        viewModelScope.launch {
            when (val res = repo.clearAllRecords()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(feedbackEvent = SettingsFeedbackType.HISTORY_CLEARED, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun clearFavorites() {
        val utilRepo = utilitiesRepository ?: return
        viewModelScope.launch {
            when (val res = utilRepo.clearAllFavorites()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(feedbackEvent = SettingsFeedbackType.FAVORITES_CLEARED, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun revokeAllTrustedDevices() {
        val repo = trustedDeviceRepository ?: return
        viewModelScope.launch {
            when (val res = repo.clearAllTrustedDevices()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(feedbackEvent = SettingsFeedbackType.TRUSTED_REVOKED, errorMessage = null)
                }
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val result = settingsRepository.resetToDefaults()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        feedbackEvent = SettingsFeedbackType.RESET_DONE,
                        errorMessage = null
                    )
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = result.error.message
                    )
                }
            }
        }
    }

    fun onCloudUpdateUrlDraftChange(value: String) {
        _uiState.update {
            it.copy(
                cloudUpdateUrlDraft = value,
                cloudUpdateUrlError = false,
                errorMessage = null
            )
        }
    }

    fun saveCloudUpdateUrl() {
        val draft = _uiState.value.cloudUpdateUrlDraft.trim()
        if (!SettingsValidator.isValidOptionalHttpsUpdateUrl(draft)) {
            _uiState.update { it.copy(cloudUpdateUrlError = true) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            when (val res = settingsRepository.updateCloudUpdateManifestUrl(draft)) {
                is AppResult.Success -> {
                    cloudUpdateManager?.resetState()
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            cloudUpdateUrlError = false,
                            feedbackEvent = SettingsFeedbackType.UPDATE_URL_SAVED,
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isSaving = false,
                        cloudUpdateUrlError = true,
                        errorMessage = res.error.message
                    )
                }
            }
        }
    }

    fun checkForCloudUpdates() {
        val updater = cloudUpdateManager ?: return
        val draftUrl = _uiState.value.cloudUpdateUrlDraft.trim()
        val persistedUrl = _uiState.value.settings.cloudUpdateManifestUrl.trim()
        val targetUrl = draftUrl.ifEmpty { persistedUrl }
        if (draftUrl.isNotEmpty() && !SettingsValidator.isValidOptionalHttpsUpdateUrl(draftUrl)) {
            _uiState.update { it.copy(cloudUpdateUrlError = true) }
            return
        }
        activeUpdateJob?.cancel()
        activeUpdateJob = viewModelScope.launch {
            if (draftUrl != persistedUrl && SettingsValidator.isValidOptionalHttpsUpdateUrl(draftUrl)) {
                settingsRepository.updateCloudUpdateManifestUrl(draftUrl)
            }
            updater.checkForUpdates(targetUrl)
        }
    }

    fun downloadCloudUpdate(manifest: CloudUpdateManifest) {
        val updater = cloudUpdateManager ?: return
        activeUpdateJob?.cancel()
        activeUpdateJob = viewModelScope.launch {
            updater.downloadAndVerifyUpdate(manifest)
        }
    }

    fun cancelCloudUpdateDownload() {
        activeUpdateJob?.cancel()
        activeUpdateJob = null
        cloudUpdateManager?.resetState()
    }

    fun installVerifiedCloudUpdate(apkPath: String, onNeedInstallPermission: () -> Unit) {
        val updater = cloudUpdateManager ?: return
        when (updater.launchVerifiedUpdateInstaller(apkPath)) {
            UpdateInstallLaunchResult.LAUNCHED -> Unit
            UpdateInstallLaunchResult.PERMISSION_REQUIRED -> {
                _uiState.update { it.copy(feedbackEvent = SettingsFeedbackType.UPDATE_INSTALL_PERMISSION_REQUIRED) }
                onNeedInstallPermission()
            }
            UpdateInstallLaunchResult.FAILED -> {
                _uiState.update { it.copy(feedbackEvent = SettingsFeedbackType.UPDATE_INSTALL_LAUNCH_FAILED) }
            }
        }
    }

    fun consumeFeedbackEvent() {
        _uiState.update { it.copy(feedbackEvent = null) }
    }
}
