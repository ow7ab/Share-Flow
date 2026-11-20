package com.example.shareflow.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.security.KeystoreCipher
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.SettingsValidator
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SettingsFeedbackType {
    DEVICE_NAME_SAVED,
    PORT_SAVED,
    RESET_DONE
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val deviceNameDraft: String = AppSettings.DEFAULT_DEVICE_NAME,
    val portDraft: String = AppSettings.DEFAULT_TRANSFER_PORT.toString(),
    val deviceNameError: Boolean = false,
    val portError: Boolean = false,
    val isKeystoreAvailable: Boolean = false,
    val isSaving: Boolean = false,
    val feedbackEvent: SettingsFeedbackType? = null,
    val errorMessage: String? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val keystoreCipher: KeystoreCipher
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(isKeystoreAvailable = keystoreCipher.isKeystoreAvailable())
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
                        deviceNameError = false,
                        portError = false
                    )
                }
            }
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

    fun onThemeModeSelected(mode: ThemeMode) {
        viewModelScope.launch {
            settingsRepository.updateThemeMode(mode)
        }
    }

    fun onDynamicColorChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDynamicColor(enabled)
        }
    }

    fun onAccentColorSelected(accentColor: AccentColor) {
        viewModelScope.launch {
            settingsRepository.updateAccentColor(accentColor)
        }
    }

    fun onAppLanguageSelected(language: AppLanguage) {
        viewModelScope.launch {
            settingsRepository.updateAppLanguage(language)
        }
    }

    fun onPreferWifiDirectChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updatePreferWifiDirect(enabled)
        }
    }

    fun onVerifySha256Changed(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateVerifySha256AfterTransfer(enabled)
        }
    }

    fun onAutoAcceptTrustedChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoAcceptTrustedDevices(enabled)
        }
    }

    fun onRequireVaultAuthChanged(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateRequireVaultAuthentication(enabled)
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

    fun consumeFeedbackEvent() {
        _uiState.update { it.copy(feedbackEvent = null) }
    }
}
