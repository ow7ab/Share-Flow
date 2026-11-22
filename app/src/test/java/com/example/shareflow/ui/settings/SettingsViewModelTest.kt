package com.example.shareflow.ui.settings

import com.example.BuildConfig
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.testutil.FakeKeystoreCipher
import com.example.shareflow.testutil.FakeSettingsRepository
import com.example.shareflow.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `updates device name and validates invalid port input`() = runTest(mainDispatcherRule.dispatcher) {
        val fakeRepo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(fakeRepo, FakeKeystoreCipher(available = true))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isKeystoreAvailable)

        viewModel.onDeviceNameDraftChange("ShareFlow Pixel")
        viewModel.saveDeviceName()
        advanceUntilIdle()

        assertEquals("ShareFlow Pixel", viewModel.uiState.value.settings.deviceName)
        assertEquals(SettingsFeedbackType.DEVICE_NAME_SAVED, viewModel.uiState.value.feedbackEvent)
        assertFalse(viewModel.uiState.value.deviceNameError)

        viewModel.onPortDraftChange("80")
        viewModel.saveTransferPort()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.portError)

        viewModel.onThemeModeSelected(ThemeMode.DARK)
        advanceUntilIdle()
        assertEquals(ThemeMode.DARK, viewModel.uiState.value.settings.themeMode)
    }

    @Test
    fun `validates and persists cloud update portal HTTPS URL`() = runTest(mainDispatcherRule.dispatcher) {
        val fakeRepo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(fakeRepo, FakeKeystoreCipher(available = true))
        advanceUntilIdle()

        viewModel.onCloudUpdateUrlDraftChange("http://insecure.example.sd/version.json")
        viewModel.saveCloudUpdateUrl()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cloudUpdateUrlError)
        assertEquals("", viewModel.uiState.value.settings.cloudUpdateManifestUrl)

        viewModel.onCloudUpdateUrlDraftChange("https://shareflow.sd/releases/version.json")
        viewModel.saveCloudUpdateUrl()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.cloudUpdateUrlError)
        assertEquals(
            "https://shareflow.sd/releases/version.json",
            viewModel.uiState.value.settings.cloudUpdateManifestUrl
        )
        assertEquals(SettingsFeedbackType.UPDATE_URL_SAVED, viewModel.uiState.value.feedbackEvent)
    }

    @Test
    fun `build config exposes official ShareFlow update manifest and website HTTPS URLs`() {
        assertEquals(
            "https://ow7ab.github.io/Share-Flow-Releases/version.json",
            BuildConfig.CLOUD_UPDATE_MANIFEST_URL
        )
        assertEquals(
            "https://ow7ab.github.io/Share-Flow-Releases/",
            BuildConfig.CLOUD_UPDATE_PORTAL_URL
        )
    }
}
