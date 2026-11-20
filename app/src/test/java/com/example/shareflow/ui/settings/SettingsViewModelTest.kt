package com.example.shareflow.ui.settings

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
}
