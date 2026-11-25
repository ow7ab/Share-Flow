package com.example.shareflow.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DefaultDispatcherProvider
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.core.di.LocalAppContainer
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
import com.example.shareflow.ui.files.FileManagerGridTile
import com.example.shareflow.ui.files.FileManagerViewModel
import com.example.shareflow.ui.files.FileViewLayout
import com.example.shareflow.ui.home.HomeDashboardContent
import com.example.shareflow.ui.home.HomeUiState
import com.example.shareflow.ui.media.MediaStudioContent
import com.example.shareflow.ui.media.MediaStudioTab
import com.example.shareflow.ui.media.MediaStudioUiState
import com.example.shareflow.ui.media.MediaStudioViewModel
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiFixesAndMediaStudioRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `APK preview dialog exposes visible inset-safe bottom action dock and install action`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val appContainer = DefaultAppContainer(context)
        var installClicked = 0
        var sendClicked = 0

        val apkEntry = LocalFileEntry(
            uriString = "content://com.example.shareflow.fileprovider/received/shareflow-update.apk",
            displayName = "shareflow-update.apk",
            mimeType = "application/vnd.android.package-archive",
            sizeBytes = 18_500_000L,
            lastModifiedEpochMillis = 5000L,
            isDirectory = false,
            category = FileCategory.APKS,
            source = FileSource.SAF_DOCUMENT,
            capabilities = FileCapabilities(canRead = true, canWrite = false, canDelete = true, canRename = false)
        )

        composeTestRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowTheme {
                    CustomFileViewerDialog(
                        file = apkEntry,
                        onSendWithShareFlow = { sendClicked++ },
                        onInstallApk = { installClicked++ },
                        onDismiss = {}
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("custom_file_viewer_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_bottom_action_dock").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_action_install_apk").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("viewer_action_move_to_vault").assertIsDisplayed()
        composeTestRule.onNodeWithTag("viewer_action_send").assertIsDisplayed().performClick()

        assertEquals(1, installClicked)
        assertEquals(1, sendClicked)
    }

    @Test
    fun `FileManagerGridTile renders full-bleed photo and video cards without file names while preserving menu and non-media tiles`() {
        val photoEntry = LocalFileEntry(
            uriString = "content://media/external/images/101",
            displayName = "vacation_2026.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 3_250_000L,
            lastModifiedEpochMillis = 4000L,
            isDirectory = false,
            category = FileCategory.IMAGES,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities(canRead = true, canWrite = true, canDelete = true, canRename = true)
        )
        val videoEntry = LocalFileEntry(
            uriString = "content://media/external/video/202",
            displayName = "concert_clip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 24_800_000L,
            lastModifiedEpochMillis = 4500L,
            isDirectory = false,
            category = FileCategory.VIDEOS,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities(canRead = true, canWrite = true, canDelete = true, canRename = true)
        )
        val pdfEntry = LocalFileEntry(
            uriString = "content://media/external/file/303",
            displayName = "contract.pdf",
            mimeType = "application/pdf",
            sizeBytes = 512_000L,
            lastModifiedEpochMillis = 3000L,
            isDirectory = false,
            category = FileCategory.DOCUMENTS,
            source = FileSource.SAF_DOCUMENT,
            capabilities = FileCapabilities(canRead = true, canWrite = true, canDelete = true, canRename = true)
        )

        var detailsTriggered = 0

        composeTestRule.setContent {
            ShareFlowTheme {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FileManagerGridTile(
                        file = photoEntry,
                        isSelectionMode = false,
                        isSelected = false,
                        onClick = {},
                        onLongClick = {},
                        onToggleSelect = {},
                        onPreview = {},
                        onDetails = { detailsTriggered++ },
                        onRename = {},
                        onCopy = {},
                        onMove = {},
                        onShare = {},
                        onSend = {},
                        onDelete = {},
                        modifier = Modifier.weight(1f)
                    )
                    FileManagerGridTile(
                        file = videoEntry,
                        isSelectionMode = false,
                        isSelected = false,
                        onClick = {},
                        onLongClick = {},
                        onToggleSelect = {},
                        onPreview = {},
                        onDetails = {},
                        onRename = {},
                        onCopy = {},
                        onMove = {},
                        onShare = {},
                        onSend = {},
                        onDelete = {},
                        modifier = Modifier.weight(1f)
                    )
                    FileManagerGridTile(
                        file = pdfEntry,
                        isSelectionMode = false,
                        isSelected = false,
                        onClick = {},
                        onLongClick = {},
                        onToggleSelect = {},
                        onPreview = {},
                        onDetails = {},
                        onRename = {},
                        onCopy = {},
                        onMove = {},
                        onShare = {},
                        onSend = {},
                        onDelete = {},
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        // Photo card: NO file name text, size shown, 3-dot menu shown, NO video play badge
        assertTrue(
            "Photo card must hide file name completely",
            composeTestRule.onAllNodesWithText("vacation_2026.jpg", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("media_card_size_vacation_2026.jpg", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_item_menu_vacation_2026.jpg", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Photo card must not render video play badge",
            composeTestRule.onAllNodesWithTag("media_card_video_badge_vacation_2026.jpg", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )

        // Video card: NO file name text, video play badge shown, size shown, 3-dot menu shown
        assertTrue(
            "Video card must hide file name completely",
            composeTestRule.onAllNodesWithText("concert_clip.mp4", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithTag("media_card_video_badge_concert_clip.mp4", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_size_concert_clip.mp4", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("file_item_menu_concert_clip.mp4", useUnmergedTree = true).assertIsDisplayed()

        // Non-media document card: keeps file name unchanged
        composeTestRule.onNodeWithText("contract.pdf", useUnmergedTree = true).assertIsDisplayed()

        // Tapping 3-dot menu on photo card opens the preserved action menu
        composeTestRule.onNodeWithTag("file_item_menu_vacation_2026.jpg", useUnmergedTree = true).performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Details").assertIsDisplayed().performClick()
        assertEquals(1, detailsTriggered)
    }

    @Test
    fun `MediaStudioViewModel organizes images, videos, and albums strictly without duplicates or non-media files`() {
        val camImg1 = LocalFileEntry(
            uriString = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera%2Fimg1.jpg",
            displayName = "img1.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1_500_000L,
            lastModifiedEpochMillis = 9000L,
            isDirectory = false,
            category = FileCategory.IMAGES,
            source = FileSource.SAF_DOCUMENT,
            capabilities = FileCapabilities(),
            parentUriString = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera"
        )
        val camImg1Duplicate = camImg1.copy(
            uriString = "content://media/external/images/999"
        )
        val tripVideo = LocalFileEntry(
            uriString = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FTrips%2Ftrip.mp4",
            displayName = "trip.mp4",
            mimeType = "video/mp4",
            sizeBytes = 42_000_000L,
            lastModifiedEpochMillis = 8000L,
            isDirectory = false,
            category = FileCategory.VIDEOS,
            source = FileSource.SAF_DOCUMENT,
            capabilities = FileCapabilities(),
            parentUriString = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FTrips"
        )
        val receivedImg = LocalFileEntry(
            uriString = "content://com.example.shareflow.fileprovider/received/ShareFlowReceived/banner.png",
            displayName = "banner.png",
            mimeType = "image/png",
            sizeBytes = 850_000L,
            lastModifiedEpochMillis = 7000L,
            isDirectory = false,
            category = FileCategory.IMAGES,
            source = FileSource.SAF_DOCUMENT,
            capabilities = FileCapabilities()
        )
        val ignoredPdf = LocalFileEntry(
            uriString = "content://media/external/file/555",
            displayName = "notes.pdf",
            mimeType = "application/pdf",
            sizeBytes = 20_000L,
            lastModifiedEpochMillis = 9500L,
            isDirectory = false,
            category = FileCategory.DOCUMENTS,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities()
        )

        val organized = MediaStudioViewModel.organizeMediaEntries(
            listOf(camImg1, camImg1Duplicate, tripVideo, receivedImg, ignoredPdf)
        )

        assertEquals(3, organized.allMedia.size)
        assertEquals(2, organized.imagesOnly.size)
        assertTrue(organized.imagesOnly.all { it.mimeType.startsWith("image/") })
        assertEquals(1, organized.videosOnly.size)
        assertTrue(organized.videosOnly.all { it.mimeType.startsWith("video/") })
        assertEquals(3, organized.albums.size)
        assertEquals(setOf("Camera", "Trips", "ShareFlow"), organized.albums.map { it.name }.toSet())
    }

    @Test
    fun `MediaStudioContent switches All, Images, Videos, Albums tabs cleanly and previews on tap without auto-sending`() {
        val img = LocalFileEntry(
            uriString = "content://media/external/images/1",
            displayName = "studio_photo.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 2_000_000L,
            lastModifiedEpochMillis = 6000L,
            isDirectory = false,
            category = FileCategory.IMAGES,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities(),
            parentUriString = "content://docs/tree/primary%3ADCIM/document/primary%3ADCIM%2FCamera"
        )
        val vid = LocalFileEntry(
            uriString = "content://media/external/video/2",
            displayName = "studio_video.mp4",
            mimeType = "video/mp4",
            sizeBytes = 12_000_000L,
            lastModifiedEpochMillis = 5000L,
            isDirectory = false,
            category = FileCategory.VIDEOS,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities(),
            parentUriString = "content://docs/tree/primary%3AMovies/document/primary%3AMovies%2FClips"
        )
        val organized = MediaStudioViewModel.organizeMediaEntries(listOf(img, vid))
        val linkedFolder = GrantedDirectory(
            treeUriString = "content://docs/tree/primary%3ADCIM",
            displayName = "DCIM",
            canWrite = true,
            isAccessible = true
        )

        var previewed: LocalFileEntry? = null
        var sentCount = 0

        composeTestRule.setContent {
            var state by remember {
                mutableStateOf(
                    MediaStudioUiState(
                        activeTab = MediaStudioTab.ALL_MEDIA,
                        allMedia = organized.allMedia,
                        imagesOnly = organized.imagesOnly,
                        videosOnly = organized.videosOnly,
                        albums = organized.albums,
                        grantedDirectories = listOf(linkedFolder),
                        isLoading = false
                    )
                )
            }
            ShareFlowTheme {
                MediaStudioContent(
                    uiState = state,
                    onSelectTab = { tab ->
                        state = state.copy(
                            activeTab = tab,
                            selectedAlbumName = if (tab == MediaStudioTab.ALBUMS) state.selectedAlbumName else null
                        )
                    },
                    onOpenAlbum = { album ->
                        state = state.copy(activeTab = MediaStudioTab.ALBUMS, selectedAlbumName = album)
                    },
                    onCloseAlbum = {
                        state = state.copy(selectedAlbumName = null)
                    },
                    onPreview = { previewed = it },
                    onSend = { sentCount++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        val grid = composeTestRule.onNodeWithTag("media_screen_list")

        // Quick studio player shortcuts remain preserved
        composeTestRule.onNodeWithTag("media_card_photos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_videos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_music").assertIsDisplayed()

        // All Media shows both photo and video
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_photo.jpg"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_photo.jpg").assertIsDisplayed()
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_video.mp4"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_video.mp4").assertIsDisplayed()

        // Tapping item opens preview and does NOT auto-send
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_photo.jpg"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_photo.jpg").performClick()
        assertEquals(img.uriString, previewed?.uriString)
        assertEquals(0, sentCount)

        // Images tab shows ONLY images
        grid.performScrollToNode(hasTestTag("media_studio_tab_bar"))
        composeTestRule.onNodeWithTag("media_studio_tab_images").performClick()
        composeTestRule.waitForIdle()
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_photo.jpg"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_photo.jpg").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithTag("media_studio_item_studio_video.mp4").fetchSemanticsNodes().isEmpty()
        )

        // Videos tab shows ONLY videos
        grid.performScrollToNode(hasTestTag("media_studio_tab_bar"))
        composeTestRule.onNodeWithTag("media_studio_tab_videos").performClick()
        composeTestRule.waitForIdle()
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_video.mp4"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_video.mp4").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithTag("media_studio_item_studio_photo.jpg").fetchSemanticsNodes().isEmpty()
        )

        // Albums tab groups by folder and supports drill-in & back
        grid.performScrollToNode(hasTestTag("media_studio_tab_bar"))
        composeTestRule.onNodeWithTag("media_studio_tab_albums").performClick()
        composeTestRule.waitForIdle()
        grid.performScrollToNode(hasTestTag("media_album_tile_Camera"))
        composeTestRule.onNodeWithTag("media_album_tile_Camera").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        grid.performScrollToNode(hasTestTag("media_album_back_chip"))
        composeTestRule.onNodeWithTag("media_album_back_chip").assertIsDisplayed()
        grid.performScrollToNode(hasTestTag("media_studio_item_studio_photo.jpg"))
        composeTestRule.onNodeWithTag("media_studio_item_studio_photo.jpg").assertIsDisplayed()
        grid.performScrollToNode(hasTestTag("media_album_back_chip"))
        composeTestRule.onNodeWithTag("media_album_back_chip").performClick()
        composeTestRule.waitForIdle()
        grid.performScrollToNode(hasTestTag("media_album_tile_Clips"))
        composeTestRule.onNodeWithTag("media_album_tile_Clips").assertIsDisplayed()
    }

    @Test
    fun `HomeDashboardContent never renders Recent Files section even when recentFiles is populated`() {
        val sampleRecent = listOf(
            LocalFileEntry(
                uriString = "content://media/external/images/77",
                displayName = "recent_photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 1_200_000L,
                lastModifiedEpochMillis = 9999L,
                isDirectory = false,
                category = FileCategory.IMAGES,
                source = FileSource.MEDIA_STORE,
                capabilities = FileCapabilities()
            )
        )
        val state = HomeUiState.Ready(
            settings = AppSettings(),
            totalTransferCount = 0,
            trustedDeviceCount = 0,
            recentTransfers = emptyList(),
            nearbyDevices = emptyList(),
            recentFiles = sampleRecent,
            storageSummary = StorageSummary(totalBytes = 64_000_000_000L, usedBytes = 20_000_000_000L, availableBytes = 44_000_000_000L, breakdown = emptyList()),
            connectionState = ConnectionState.Idle,
            networkStatus = DeviceNetworkStatus(
                isWifiConnected = true,
                localIpv4Address = "192.168.1.25",
                isWifiEnabled = true,
                hasCheckedInitialState = true
            )
        )

        composeTestRule.setContent {
            ShareFlowTheme {
                HomeDashboardContent(
                    uiState = state,
                    onNavigateToRoute = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("home_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_receive_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_section_recent_transfers").assertIsDisplayed()

        // Scroll through entire dashboard to ensure storage & quick tools exist while recent files never appear
        composeTestRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_section_storage"))
        composeTestRule.onNodeWithTag("home_section_storage").assertIsDisplayed()

        composeTestRule.onNodeWithTag("home_dashboard_list")
            .performScrollToNode(hasTestTag("home_section_quick_tools"))
        composeTestRule.onNodeWithTag("home_section_quick_tools").assertIsDisplayed()

        assertTrue(
            "Recent files header must be removed from Home screen",
            composeTestRule.onAllNodesWithTag("home_section_recent_files").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Recent files empty state must be removed from Home screen",
            composeTestRule.onAllNodesWithTag("home_recent_files_empty").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            "Recent file items must not be displayed on Home screen",
            composeTestRule.onAllNodesWithText("recent_photo.jpg").fetchSemanticsNodes().isEmpty()
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `FileManagerViewModel defaults Photos and Videos categories to Grid view for full-bleed cards`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            val storageChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
            val fakeRepo = object : FileManagerRepository {
                override fun observeStorageChanges(): Flow<Unit> = storageChanges
                override fun notifyStorageChanged() {}
                override fun observeGrantedDirectories(): Flow<List<GrantedDirectory>> = flowOf(emptyList())
                override suspend fun registerGrantedDirectory(treeUriString: String) = throw UnsupportedOperationException()
                override suspend fun releaseGrantedDirectory(treeUriString: String) = AppResult.Success(Unit)
                override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> = AppResult.Success(emptyList())
                override suspend fun listDirectory(treeUriString: String) = AppResult.Success(emptyList<LocalFileEntry>())
                override suspend fun inspectFile(documentUriString: String) = throw UnsupportedOperationException()
                override suspend fun queryByCategory(category: FileCategory) = AppResult.Success(emptyList<LocalFileEntry>())
                override suspend fun createFolder(parentDirectoryUriString: String, folderName: String) = throw UnsupportedOperationException()
                override suspend fun renameFile(documentUriString: String, newName: String) = throw UnsupportedOperationException()
                override suspend fun copyFiles(sourceUris: List<String>, targetDirectoryUriString: String) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun moveFiles(sourceUris: List<String>, targetDirectoryUriString: String) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun deleteFiles(documentUris: List<String>) =
                    AppResult.Success(FileOperationResult(emptyList(), emptyList()))
                override suspend fun computeSha256(documentUriString: String) = AppResult.Success("")
                override suspend fun readTextPreview(documentUriString: String, maxBytes: Int) = AppResult.Success("")
            }
            val fakeStorage = object : StorageAnalyzerRepository {
                private val flow = MutableStateFlow<AppResult<StorageSummary>>(
                    AppResult.Success(StorageSummary(100L, 40L, 60L, emptyList()))
                )
                override fun observeStorageSummary(): Flow<AppResult<StorageSummary>> = flow
                override suspend fun refreshStorageSummary(): AppResult<StorageSummary> = flow.value
            }

            val context: Context = ApplicationProvider.getApplicationContext()
            val session = LocalSessionController(context, DefaultDispatcherProvider())

            val imagesVm = FileManagerViewModel(fakeRepo, fakeStorage, session, initialCategory = FileCategory.IMAGES)
            val videosVm = FileManagerViewModel(fakeRepo, fakeStorage, session, initialCategory = FileCategory.VIDEOS)
            val docsVm = FileManagerViewModel(fakeRepo, fakeStorage, session, initialCategory = FileCategory.DOCUMENTS)
            advanceUntilIdle()

            assertEquals(FileViewLayout.GRID, imagesVm.uiState.value.viewLayout)
            assertEquals(FileViewLayout.GRID, videosVm.uiState.value.viewLayout)
            assertEquals(FileViewLayout.LIST, docsVm.uiState.value.viewLayout)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
