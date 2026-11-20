package com.example.shareflow.ui.vault

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.DecryptedVaultItem
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.model.VaultCategory
import com.example.shareflow.domain.model.VaultStatusSnapshot
import com.example.shareflow.vault.BiometricAuthOutcome
import com.example.shareflow.vault.BiometricCapability
import com.example.shareflow.vault.VaultBiometricAuthenticator
import com.example.shareflow.vault.VaultManager
import java.io.IOException
import java.util.Arrays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VaultPreviewContent(
    val item: DecryptedVaultItem,
    val bytes: ByteArray
)

data class VaultUiState(
    val isBusy: Boolean = false,
    val biometricCapability: BiometricCapability = BiometricCapability.UNAVAILABLE,
    val selectedCategory: VaultCategory? = null,
    val selectedItemForDetails: DecryptedVaultItem? = null,
    val previewContent: VaultPreviewContent? = null,
    val pendingExportConfirmation: DecryptedVaultItem? = null,
    val itemReadyForExportPicker: DecryptedVaultItem? = null,
    val pendingDeleteConfirmation: DecryptedVaultItem? = null,
    val oneTimeRecoveryKey: String? = null,
    val showRecoveryDialog: Boolean = false,
    val showSecuritySettingsDialog: Boolean = false,
    val showChangePinDialog: Boolean = false,
    val errorMessage: String? = null,
    val statusMessage: String? = null
)

class VaultViewModel(
    context: Context,
    private val vaultManager: VaultManager
) : ViewModel() {

    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    val vaultStatus: StateFlow<VaultStatusSnapshot> = vaultManager.status
    val decryptedItems: StateFlow<List<DecryptedVaultItem>> = vaultManager.decryptedItems

    private val _uiState = MutableStateFlow(
        VaultUiState(
            biometricCapability = VaultBiometricAuthenticator.checkCapability(appContext)
        )
    )
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vaultManager.refreshStatus()
            vaultManager.checkAutoLock()
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            vaultManager.refreshStatus()
            vaultManager.checkAutoLock()
            _uiState.update {
                it.copy(biometricCapability = VaultBiometricAuthenticator.checkCapability(appContext))
            }
        }
    }

    fun createVault(
        pin: String,
        confirmPin: String,
        enableBiometric: Boolean,
        autoLockTimeout: VaultAutoLockTimeout
    ) {
        if (pin != confirmPin) {
            _uiState.update { it.copy(errorMessage = "PIN entries do not match.") }
            return
        }
        if (!VaultManager.isValidPin(pin)) {
            _uiState.update {
                it.copy(errorMessage = "PIN must be ${VaultManager.MIN_PIN_LENGTH} to ${VaultManager.MAX_PIN_LENGTH} digits.")
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.createVault(pin, enableBiometric, autoLockTimeout)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            oneTimeRecoveryKey = res.data.recoveryKey,
                            statusMessage = "Private Vault created with AES-256-GCM encryption."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun dismissRecoveryKeyReceipt() {
        _uiState.update { it.copy(oneTimeRecoveryKey = null) }
    }

    fun unlockWithPin(pin: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.unlockWithPin(pin)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = null) }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun onBiometricOutcome(outcome: BiometricAuthOutcome) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (outcome) {
                is BiometricAuthOutcome.Succeeded -> {
                    when (val res = vaultManager.unlockWithBiometricAfterPromptSuccess()) {
                        is AppResult.Success -> {
                            _uiState.update { it.copy(isBusy = false, errorMessage = null) }
                        }
                        is AppResult.Failure -> {
                            _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                        }
                    }
                }
                is BiometricAuthOutcome.Failed -> {
                    val res = vaultManager.handleBiometricFailure(outcome.message)
                    val msg = (res as? AppResult.Failure)?.error?.message ?: outcome.message
                    _uiState.update { it.copy(isBusy = false, errorMessage = msg) }
                }
            }
        }
    }

    fun changePin(currentPin: String, newPin: String, confirmNewPin: String) {
        if (newPin != confirmNewPin) {
            _uiState.update { it.copy(errorMessage = "New PIN confirmation does not match.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.changePin(currentPin, newPin)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            showChangePinDialog = false,
                            statusMessage = "Vault PIN updated cleanly."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun recoverWithKey(recoveryKey: String, newPin: String, confirmNewPin: String) {
        if (newPin != confirmNewPin) {
            _uiState.update { it.copy(errorMessage = "New PIN confirmation does not match.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.recoverWithRecoveryKey(recoveryKey, newPin)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            showRecoveryDialog = false,
                            statusMessage = "Vault recovered and PIN reset."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.setBiometricEnabled(enabled)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = if (enabled) "Biometric unlock enabled." else "Biometric unlock disabled."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun setAutoLockTimeout(timeout: VaultAutoLockTimeout) {
        viewModelScope.launch {
            when (val res = vaultManager.setAutoLockTimeout(timeout)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(statusMessage = "Auto-lock policy updated.") }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(errorMessage = res.error.message) }
                }
            }
        }
    }

    fun lockNow() {
        clearPreviewBuffer()
        viewModelScope.launch {
            vaultManager.lock()
            _uiState.update {
                it.copy(
                    selectedItemForDetails = null,
                    pendingExportConfirmation = null,
                    itemReadyForExportPicker = null,
                    pendingDeleteConfirmation = null,
                    showSecuritySettingsDialog = false,
                    showChangePinDialog = false
                )
            }
        }
    }

    fun selectCategory(category: VaultCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun importUris(uris: List<Uri>, categoryOverride: VaultCategory? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            var imported = 0
            var failed = 0
            for (uri in uris) {
                val (name, mime) = resolveUriDisplayNameAndMime(uri, categoryOverride)
                val res = vaultManager.importFile(
                    displayName = name,
                    mimeType = mime,
                    categoryOverride = categoryOverride,
                    openInputStream = {
                        contentResolver.openInputStream(uri)
                            ?: throw IOException("Unable to open selected file stream.")
                    }
                )
                if (res is AppResult.Success) imported++ else failed++
            }
            _uiState.update {
                it.copy(
                    isBusy = false,
                    statusMessage = when {
                        failed == 0 -> "Encrypted $imported file(s) into Private Vault."
                        imported > 0 -> "Encrypted $imported file(s); $failed failed."
                        else -> "Failed to import selected file(s)."
                    }
                )
            }
        }
    }

    fun openItemDetails(item: DecryptedVaultItem) {
        _uiState.update { it.copy(selectedItemForDetails = item) }
    }

    fun closeItemDetails() {
        _uiState.update { it.copy(selectedItemForDetails = null) }
    }

    fun openVerifiedPreview(item: DecryptedVaultItem) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            when (val res = vaultManager.decryptToMemory(item.id)) {
                is AppResult.Success -> {
                    clearPreviewBuffer()
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            selectedItemForDetails = null,
                            previewContent = VaultPreviewContent(item = item, bytes = res.data)
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun closePreview() {
        clearPreviewBuffer()
    }

    fun requestExportConfirmation(item: DecryptedVaultItem) {
        _uiState.update {
            it.copy(
                selectedItemForDetails = null,
                pendingExportConfirmation = item
            )
        }
    }

    fun dismissExportConfirmation() {
        _uiState.update { it.copy(pendingExportConfirmation = null) }
    }

    fun confirmExportLaunchPicker() {
        val target = _uiState.value.pendingExportConfirmation ?: return
        _uiState.update {
            it.copy(
                pendingExportConfirmation = null,
                itemReadyForExportPicker = target
            )
        }
    }

    fun consumeExportPickerRequest() {
        _uiState.update { it.copy(itemReadyForExportPicker = null) }
    }

    fun exportToDestinationUri(item: DecryptedVaultItem, destinationUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, errorMessage = null) }
            val res = vaultManager.exportFile(item.id) {
                contentResolver.openOutputStream(destinationUri)
                    ?: throw IOException("Unable to open export destination stream.")
            }
            when (res) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "Exported '${item.displayName}' after cryptographic verification."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun requestSecureDelete(item: DecryptedVaultItem) {
        _uiState.update {
            it.copy(
                selectedItemForDetails = null,
                pendingDeleteConfirmation = item
            )
        }
    }

    fun dismissSecureDelete() {
        _uiState.update { it.copy(pendingDeleteConfirmation = null) }
    }

    fun confirmSecureDelete() {
        val target = _uiState.value.pendingDeleteConfirmation ?: return
        _uiState.update { it.copy(pendingDeleteConfirmation = null, isBusy = true) }
        viewModelScope.launch {
            when (val res = vaultManager.secureDeleteItem(target.id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "Securely overwritten and deleted '${target.displayName}'."
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isBusy = false, errorMessage = res.error.message) }
                }
            }
        }
    }

    fun setShowRecoveryDialog(show: Boolean) {
        _uiState.update { it.copy(showRecoveryDialog = show, errorMessage = null) }
    }

    fun setShowSecuritySettingsDialog(show: Boolean) {
        _uiState.update { it.copy(showSecuritySettingsDialog = show, errorMessage = null) }
    }

    fun setShowChangePinDialog(show: Boolean) {
        _uiState.update { it.copy(showChangePinDialog = show, errorMessage = null) }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, statusMessage = null) }
    }

    private fun clearPreviewBuffer() {
        val current = _uiState.value.previewContent
        if (current != null) {
            Arrays.fill(current.bytes, 0)
            _uiState.update { it.copy(previewContent = null) }
        }
    }

    override fun onCleared() {
        clearPreviewBuffer()
        super.onCleared()
    }

    private fun resolveUriDisplayNameAndMime(
        uri: Uri,
        categoryOverride: VaultCategory?
    ): Pair<String, String> {
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "vault_file"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                }
            }
        }
        val detectedMime = runCatching { contentResolver.getType(uri) }.getOrNull()
        val fallbackMime = when (categoryOverride) {
            VaultCategory.PHOTOS -> "image/*"
            VaultCategory.VIDEOS -> "video/*"
            VaultCategory.DOCUMENTS, null -> "application/octet-stream"
        }
        return displayName to (detectedMime ?: fallbackMime)
    }
}
