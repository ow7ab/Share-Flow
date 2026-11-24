package com.example.shareflow.data.local.preferences

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.ShareFlowApplication
import com.example.shareflow.data.repository.SettingsRepositoryImpl
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.testutil.TestDispatcherProvider
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StartupAppearanceCacheRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("shareflow_startup_appearance", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `persists and restores saved theme synchronously across cold restarts`() {
        val writerCache = StartupAppearanceCache(context)
        assertFalse(writerCache.isInitialized())
        assertNull(writerCache.readOrNull())

        writerCache.updateThemeMode(ThemeMode.LIGHT)
        writerCache.updateAccentColor(AccentColor.EMERALD)
        writerCache.updateDynamicColor(true)
        writerCache.updateAppLanguage(AppLanguage.ARABIC)

        // New instance simulates a fresh cold process launch with empty in-memory snapshot
        val coldStartCache = StartupAppearanceCache(context)
        assertTrue(coldStartCache.isInitialized())
        val restored = coldStartCache.readOrNull()!!
        assertEquals(ThemeMode.LIGHT, restored.themeMode)
        assertEquals(AccentColor.EMERALD, restored.accentColor)
        assertTrue(restored.useDynamicColor)
        assertEquals(AppLanguage.ARABIC, restored.appLanguage)
    }

    @Test
    fun `settings repository peekInitialSettings returns saved light mode immediately and seeds legacy datastore`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dataStoreFile = File(tempFolder.newFolder(), "startup_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(dispatcher),
            produceFile = { dataStoreFile }
        )

        // Simulate a pre-upgrade install that saved ThemeMode.LIGHT in DataStore before cache existed
        val legacyRepo = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher),
            appearanceCache = null
        )
        legacyRepo.updateThemeMode(ThemeMode.LIGHT)
        legacyRepo.updateAccentColor(AccentColor.CORAL)

        // First cold launch after upgrade: cache is empty, peekInitialSettings seeds from DataStore
        val upgradedCache = StartupAppearanceCache(context)
        assertFalse(upgradedCache.isInitialized())
        val upgradedRepo = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher),
            appearanceCache = upgradedCache
        )

        val seeded = upgradedRepo.peekInitialSettings()
        assertEquals(ThemeMode.LIGHT, seeded.themeMode)
        assertEquals(AccentColor.CORAL, seeded.accentColor)
        assertTrue(upgradedCache.isInitialized())

        // Subsequent updates mirror directly to cache and resetToDefaults clears them back
        upgradedRepo.updateThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, StartupAppearanceCache(context).readOrNull()?.themeMode)

        upgradedRepo.resetToDefaults()
        assertEquals(ThemeMode.SYSTEM, StartupAppearanceCache(context).readOrNull()?.themeMode)
    }

    @Test
    @Config(sdk = [36], qualifiers = "night")
    fun `mainActivity launches directly with light window background under system night mode when light theme is saved`() {
        val app = ApplicationProvider.getApplicationContext<ShareFlowApplication>()
        runBlocking { app.container.settingsRepository.updateThemeMode(ThemeMode.LIGHT) }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val preFrameBg = (activity.window.decorView.background as? ColorDrawable)?.color
            assertEquals(0xFFFFF9F5.toInt(), preFrameBg)

            controller.start().resume().visible()
            val settledBg = (activity.window.decorView.background as? ColorDrawable)?.color
            assertEquals(0xFFFFF9F5.toInt(), settledBg)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `onboarding completion persists across cold restarts and survives resetToDefaults`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dataStoreFile = File(tempFolder.newFolder(), "onboarding_persist.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(dispatcher),
            produceFile = { dataStoreFile }
        )
        val cache = StartupAppearanceCache(context)
        val repo = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher),
            appearanceCache = cache
        )

        assertFalse(repo.peekInitialSettings().hasCompletedOnboarding)
        repo.markOnboardingCompleted()

        // Cold restart with fresh StartupAppearanceCache instance
        val coldCache = StartupAppearanceCache(context)
        assertTrue(coldCache.readOrNull()?.hasCompletedOnboarding == true)

        // Resetting app settings must preserve completed onboarding
        repo.resetToDefaults()
        assertTrue(StartupAppearanceCache(context).readOrNull()?.hasCompletedOnboarding == true)
    }

    @Test
    fun `settings repository recovers gracefully from corrupted preferences file`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val corruptedFile = File(tempFolder.newFolder(), "corrupted_settings.preferences_pb").apply {
            writeBytes(byteArrayOf(0x00, 0x7F, 0x55, 0x2A, 0x11))
        }
        val dataStore = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
                androidx.datastore.preferences.core.emptyPreferences()
            },
            scope = TestScope(dispatcher),
            produceFile = { corruptedFile }
        )
        val repo = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher),
            appearanceCache = null
        )

        val recovered = repo.settings.firstOrNull()
        assertEquals(ThemeMode.SYSTEM, recovered?.themeMode)
        assertFalse(recovered?.hasCompletedOnboarding ?: true)
    }

    @Test
    fun `peekInitialSettings skips blocking DataStore read on fresh install when no file exists`() {
        val dispatcher = StandardTestDispatcher()
        val dataStoreFile = File(tempFolder.newFolder(), "non_existent.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = TestScope(dispatcher),
            produceFile = { dataStoreFile }
        )
        val cache = StartupAppearanceCache(context)
        val repo = SettingsRepositoryImpl(
            dataStore = dataStore,
            dispatchers = TestDispatcherProvider(dispatcher),
            appearanceCache = cache,
            dataStoreFileExists = { false }
        )

        val initial = repo.peekInitialSettings()
        assertEquals(ThemeMode.SYSTEM, initial.themeMode)
        assertFalse(initial.hasCompletedOnboarding)
        assertFalse(cache.isInitialized())
    }

    @Test
    @Config(sdk = [36], qualifiers = "notnight")
    fun `mainActivity launches directly with exact dark window background matching values-night theme`() {
        val app = ApplicationProvider.getApplicationContext<ShareFlowApplication>()
        runBlocking { app.container.settingsRepository.updateThemeMode(ThemeMode.DARK) }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            val preFrameBg = (activity.window.decorView.background as? ColorDrawable)?.color
            assertEquals(0xFF171316.toInt(), preFrameBg)

            controller.start().resume().visible()
            val settledBg = (activity.window.decorView.background as? ColorDrawable)?.color
            assertEquals(0xFF171316.toInt(), settledBg)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
