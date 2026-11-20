package com.example.shareflow.testutil

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.EncryptedPayload
import com.example.shareflow.core.security.KeystoreCipher
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.SettingsValidator
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val main: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}

class FakeSettingsRepository(
    initial: AppSettings = AppSettings()
) : SettingsRepository {

    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state

    override suspend fun updateDeviceName(deviceName: String): AppResult<Unit> {
        val sanitized = SettingsValidator.sanitizeDeviceName(deviceName)
        if (!SettingsValidator.isValidDeviceName(sanitized)) {
            return AppResult.Failure(AppError.Validation("Invalid device name"))
        }
        state.update { it.copy(deviceName = sanitized) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode): AppResult<Unit> {
        state.update { it.copy(themeMode = themeMode) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateDynamicColor(enabled: Boolean): AppResult<Unit> {
        state.update { it.copy(useDynamicColor = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateAccentColor(accentColor: AccentColor): AppResult<Unit> {
        state.update { it.copy(accentColor = accentColor) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateAppLanguage(language: AppLanguage): AppResult<Unit> {
        state.update { it.copy(appLanguage = language) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateDefaultSaveDirectoryUri(uriString: String?): AppResult<Unit> {
        state.update { it.copy(defaultSaveDirectoryUri = uriString) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateTransferPort(port: Int): AppResult<Unit> {
        if (!SettingsValidator.isValidTransferPort(port)) {
            return AppResult.Failure(AppError.Validation("Invalid transfer port"))
        }
        state.update { it.copy(transferPort = port) }
        return AppResult.Success(Unit)
    }

    override suspend fun updatePreferWifiDirect(enabled: Boolean): AppResult<Unit> {
        state.update { it.copy(preferWifiDirect = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateVerifySha256AfterTransfer(enabled: Boolean): AppResult<Unit> {
        state.update { it.copy(verifySha256AfterTransfer = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateAutoAcceptTrustedDevices(enabled: Boolean): AppResult<Unit> {
        state.update { it.copy(autoAcceptTrustedDevices = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun updateRequireVaultAuthentication(enabled: Boolean): AppResult<Unit> {
        state.update { it.copy(requireVaultAuthentication = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun resetToDefaults(): AppResult<Unit> {
        state.value = AppSettings()
        return AppResult.Success(Unit)
    }
}

class FakeKeystoreCipher(
    private val available: Boolean = true
) : KeystoreCipher {
    override fun isKeystoreAvailable(): Boolean = available

    override fun encrypt(keyAlias: String, plainBytes: ByteArray): AppResult<EncryptedPayload> =
        AppResult.Success(EncryptedPayload(iv = ByteArray(12) { 1 }, cipherText = plainBytes.copyOf()))

    override fun decrypt(keyAlias: String, payload: EncryptedPayload): AppResult<ByteArray> =
        AppResult.Success(payload.cipherText.copyOf())

    override fun deleteKey(keyAlias: String): AppResult<Unit> = AppResult.Success(Unit)
}
