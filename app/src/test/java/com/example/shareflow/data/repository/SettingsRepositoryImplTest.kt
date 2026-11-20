package com.example.shareflow.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.testutil.TestDispatcherProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists valid settings rejects invalid input and resets cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(dispatcher),
            produceFile = { File(tempFolder.newFolder(), "test_settings.preferences_pb") }
        )
        val repository = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher)
        )

        // Default values
        val initial = repository.settings.first()
        assertEquals(AppSettings.DEFAULT_DEVICE_NAME, initial.deviceName)
        assertEquals(AppSettings.DEFAULT_TRANSFER_PORT, initial.transferPort)

        // Valid updates
        assertTrue(repository.updateDeviceName("  Galaxy Studio  ") is AppResult.Success)
        assertTrue(repository.updateThemeMode(ThemeMode.DARK) is AppResult.Success)
        assertTrue(repository.updateAccentColor(AccentColor.EMERALD) is AppResult.Success)
        assertTrue(repository.updateAppLanguage(AppLanguage.ARABIC) is AppResult.Success)
        assertTrue(repository.updateTransferPort(50001) is AppResult.Success)

        val updated = repository.settings.first()
        assertEquals("Galaxy Studio", updated.deviceName)
        assertEquals(ThemeMode.DARK, updated.themeMode)
        assertEquals(AccentColor.EMERALD, updated.accentColor)
        assertEquals(AppLanguage.ARABIC, updated.appLanguage)
        assertEquals(50001, updated.transferPort)

        // Invalid updates rejected
        val invalidPort = repository.updateTransferPort(80)
        assertTrue(invalidPort is AppResult.Failure && invalidPort.error is AppError.Validation)
        val invalidName = repository.updateDeviceName("X")
        assertTrue(invalidName is AppResult.Failure && invalidName.error is AppError.Validation)

        // Reset restores defaults
        assertTrue(repository.resetToDefaults() is AppResult.Success)
        val reset = repository.settings.first()
        assertEquals(AppSettings.DEFAULT_DEVICE_NAME, reset.deviceName)
        assertEquals(ThemeMode.SYSTEM, reset.themeMode)
        assertEquals(AccentColor.COBALT, reset.accentColor)
        assertEquals(AppLanguage.SYSTEM, reset.appLanguage)
        assertEquals(AppSettings.DEFAULT_TRANSFER_PORT, reset.transferPort)
    }
}
