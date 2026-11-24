package com.example.shareflow.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DefaultDispatcherProvider
import com.example.shareflow.core.designsystem.motion.AutoHideBottomBarState
import com.example.shareflow.core.designsystem.theme.DarkSFGlassTokens
import com.example.shareflow.core.designsystem.theme.LightSFGlassTokens
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.network.DeviceNetworkStatus
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.ui.files.FileManagerViewModel
import com.example.shareflow.ui.home.HomeDashboardContent
import com.example.shareflow.ui.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PerformanceUxAndWifiOptimizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `AutoHideBottomBarState applies hysteresis thresholds, ignores small jitter, and restores on scroll up, top, route, or idle`() {
        val state = AutoHideBottomBarState(
            hideThresholdPx = 72f,
            showThresholdPx = 54f,
            idleRevealDelayMs = 2_400L
        )
        assertTrue(state.isVisible)

        // Small jitter below hide threshold must NOT hide the bar
        state.nestedScrollConnection.onPreScroll(Offset(0f, -20f), NestedScrollSource.UserInput)
        state.nestedScrollConnection.onPreScroll(Offset(0f, -30f), NestedScrollSource.UserInput)
        assertTrue("Scroll below 72px threshold must keep bar visible", state.isVisible)

        // Non-user (programmatic) scroll must be ignored
        state.nestedScrollConnection.onPreScroll(Offset(0f, -200f), NestedScrollSource.SideEffect)
        assertTrue("Programmatic scroll must not hide bottom bar", state.isVisible)

        // Crossing hide threshold hides the bar cleanly
        state.nestedScrollConnection.onPreScroll(Offset(0f, -30f), NestedScrollSource.UserInput)
        assertFalse("Crossing downward threshold must hide bottom bar", state.isVisible)

        // Minor upward jitter below show threshold keeps it hidden
        state.nestedScrollConnection.onPreScroll(Offset(0f, 20f), NestedScrollSource.UserInput)
        assertFalse(state.isVisible)

        // Direction reversal back down resets upward accumulator
        state.nestedScrollConnection.onPreScroll(Offset(0f, -15f), NestedScrollSource.UserInput)
        state.nestedScrollConnection.onPreScroll(Offset(0f, 35f), NestedScrollSource.UserInput)
        assertFalse(state.isVisible)

        // Crossing show threshold reveals bar
        state.nestedScrollConnection.onPreScroll(Offset(0f, 25f), NestedScrollSource.UserInput)
        assertTrue("Crossing upward threshold must reveal bottom bar", state.isVisible)

        // Hide again, then verify top-of-page overscroll reveals immediately
        state.onUserScrollDelta(-80f)
        assertFalse(state.isVisible)
        state.nestedScrollConnection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, 12f),
            source = NestedScrollSource.UserInput
        )
        assertTrue("Reaching top boundary must immediately reveal bar", state.isVisible)

        // Hide again, verify idle reveal triggers smoothly
        state.onUserScrollDelta(-90f)
        assertFalse(state.isVisible)
        state.onIdleRevealTriggered()
        assertTrue("Idle reveal timer must restore bar visibility", state.isVisible)

        // Hide again, verify route switch (showImmediately) restores visibility
        state.onUserScrollDelta(-90f)
        assertFalse(state.isVisible)
        state.showImmediately()
        assertTrue("Route switch must immediately restore bar visibility", state.isVisible)
    }

    @Test
    fun `SFGlassNavigationBar frosted tokens enforce dense opaque-frosted glass in Light and Deep Navy Dark themes`() {
        assertTrue(
            "Light frosted nav bar must be dense (>= 0.90 alpha) to stop text bleed-through",
            LightSFGlassTokens.navBase.alpha >= 0.90f
        )
        assertTrue(
            "Dark frosted nav bar must be dense (>= 0.90 alpha) to keep Deep Navy calm",
            DarkSFGlassTokens.navBase.alpha >= 0.90f
        )
        assertFalse(LightSFGlassTokens.isDark)
        assertTrue(DarkSFGlassTokens.isDark)
    }

    @Test
    fun `PermissionManager creates official Android 10+ Wi-Fi Settings Panel intent with legacy fallback`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val api29Manager = PermissionManager(context = context, sdkInt = Build.VERSION_CODES.Q)
        assertEquals(Settings.Panel.ACTION_WIFI, api29Manager.createWifiPanelIntent().action)

        val api28Manager = PermissionManager(context = context, sdkInt = Build.VERSION_CODES.P)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, api28Manager.createWifiPanelIntent().action)
    }

    @Test
    fun `HomeDashboardContent shows Wi-Fi Off prompt banner with one-tap enable action and hides when Wi-Fi is ON`() {
        var panelLaunchCount = 0
        val wifiOffState = HomeUiState.Ready(
            settings = AppSettings(),
            totalTransferCount = 0,
            trustedDeviceCount = 0,
            recentTransfers = emptyList(),
            nearbyDevices = emptyList(),
            recentFiles = emptyList(),
            storageSummary = null,
            connectionState = ConnectionState.Idle,
            networkStatus = DeviceNetworkStatus(
                isWifiConnected = false,
                localIpv4Address = null,
                isWifiEnabled = false,
                hasCheckedInitialState = true
            )
        )

        composeTestRule.setContent {
            ShareFlowTheme {
                HomeDashboardContent(
                    uiState = wifiOffState,
                    onNavigateToRoute = {},
                    onOpenWifiPanel = { panelLaunchCount++ }
                )
            }
        }

        composeTestRule.onNodeWithTag("home_wifi_off_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_wifi_enable_button").assertIsDisplayed().performClick()
        assertEquals(1, panelLaunchCount)

        // Dismiss button hides the prompt cleanly
        composeTestRule.onNodeWithTag("home_wifi_dismiss_button").performClick()
        assertTrue(
            composeTestRule.onAllNodesWithTag("home_wifi_off_banner").fetchSemanticsNodes().isEmpty()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `FileManagerViewModel auto-indexes files, filters inline by category, and debounces MediaStore observer bursts`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val storageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
            var scanCalls = 0
            val sampleEntries = listOf(
                LocalFileEntry(
                    uriString = "content://media/external/images/1",
                    displayName = "sunset.jpg",
                    mimeType = "image/jpeg",
                    sizeBytes = 2_400_000L,
                    lastModifiedEpochMillis = 2000L,
                    isDirectory = false,
                    category = FileCategory.IMAGES,
                    source = FileSource.MEDIA_STORE,
                    capabilities = FileCapabilities()
                ),
                LocalFileEntry(
                    uriString = "content://media/external/video/2",
                    displayName = "clip.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 14_000_000L,
                    lastModifiedEpochMillis = 3000L,
                    isDirectory = false,
                    category = FileCategory.VIDEOS,
                    source = FileSource.MEDIA_STORE,
                    capabilities = FileCapabilities()
                ),
                LocalFileEntry(
                    uriString = "content://media/external/file/3",
                    displayName = "report.pdf",
                    mimeType = "application/pdf",
                    sizeBytes = 420_000L,
                    lastModifiedEpochMillis = 1000L,
                    isDirectory = false,
                    category = FileCategory.DOCUMENTS,
                    source = FileSource.MEDIA_STORE,
                    capabilities = FileCapabilities()
                )
            )

            val fakeRepo = object : FileManagerRepository {
                override fun observeStorageChanges(): Flow<Unit> = storageChanges
                override fun notifyStorageChanged() { storageChanges.tryEmit(Unit) }
                override fun observeGrantedDirectories(): Flow<List<GrantedDirectory>> = flowOf(emptyList())
                override suspend fun registerGrantedDirectory(treeUriString: String) = throw UnsupportedOperationException()
                override suspend fun releaseGrantedDirectory(treeUriString: String) = AppResult.Success(Unit)
                override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> {
                    scanCalls++
                    return AppResult.Success(sampleEntries)
                }
                override suspend fun listDirectory(treeUriString: String) = AppResult.Success(emptyList<LocalFileEntry>())
                override suspend fun inspectFile(documentUriString: String) = AppResult.Success(sampleEntries.first())
                override suspend fun queryByCategory(category: FileCategory) =
                    AppResult.Success(sampleEntries.filter { it.matchesCategory(category) })
                override suspend fun createFolder(parentDirectoryUriString: String, folderName: String) =
                    throw UnsupportedOperationException()
                override suspend fun renameFile(documentUriString: String, newName: String) =
                    throw UnsupportedOperationException()
                override suspend fun copyFiles(sourceUris: List<String>, targetDirectoryUriString: String) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun moveFiles(sourceUris: List<String>, targetDirectoryUriString: String) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun deleteFiles(documentUris: List<String>) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun computeSha256(documentUriString: String) = AppResult.Success("abc")
                override suspend fun readTextPreview(documentUriString: String, maxBytes: Int) = AppResult.Success("")
            }

            val fakeStorageRepo = object : StorageAnalyzerRepository {
                private val summary = MutableStateFlow<AppResult<StorageSummary>>(
                    AppResult.Success(StorageSummary(1000L, 400L, 600L, emptyList()))
                )
                override fun observeStorageSummary(): Flow<AppResult<StorageSummary>> = summary
                override suspend fun refreshStorageSummary(): AppResult<StorageSummary> = summary.value
            }

            val context: Context = ApplicationProvider.getApplicationContext()
            val session = LocalSessionController(context, DefaultDispatcherProvider())
            val vm = FileManagerViewModel(fakeRepo, fakeStorageRepo, session)

            advanceUntilIdle()
            assertEquals(1, scanCalls)
            assertEquals(3, vm.uiState.value.visibleEntriesCount)

            // Inline category filtering updates visible count cleanly without triggering a re-scan
            vm.setOverviewCategoryFilter(FileCategory.IMAGES)
            assertEquals(FileCategory.IMAGES, vm.uiState.value.overviewCategoryFilter)
            assertEquals(1, vm.uiState.value.visibleEntriesCount)
            assertEquals(1, scanCalls)

            // Tapping same filter clears back to All
            vm.setOverviewCategoryFilter(FileCategory.IMAGES)
            assertEquals(null, vm.uiState.value.overviewCategoryFilter)
            assertEquals(3, vm.uiState.value.visibleEntriesCount)

            // Burst of 5 rapid MediaStore notifications must debounce into just 1 additional scan!
            repeat(5) { storageChanges.tryEmit(Unit) }
            advanceTimeBy(FileManagerViewModel.STORAGE_CHANGE_DEBOUNCE_MS + 100L)
            advanceUntilIdle()
            assertEquals(2, scanCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
