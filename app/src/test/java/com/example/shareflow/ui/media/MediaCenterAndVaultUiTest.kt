package com.example.shareflow.ui.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.repository.AndroidFileManagerRepository
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.media.MediaCenterRepository
import com.example.shareflow.media.music.MusicPlaybackController
import com.example.shareflow.testutil.TestDispatcherProvider
import com.example.shareflow.ui.vault.VaultScreen
import com.example.shareflow.ui.vault.VaultViewModel
import com.example.shareflow.vault.AesGcmBox
import com.example.shareflow.vault.KeystoreUnwrapResult
import com.example.shareflow.vault.VaultCryptoEngine
import com.example.shareflow.vault.VaultKeystoreProvider
import com.example.shareflow.vault.VaultManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class MediaCenterAndVaultUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: ShareFlowDatabase
    private lateinit var tempDir: File

    private val testClock = object : Clock {
        override fun nowEpochMillis(): Long = 1_750_000_000_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempDir = File(context.cacheDir, "ui_media_vault_${UUID.randomUUID()}").apply { mkdirs() }
    }

    private val createdContainers = mutableListOf<DefaultAppContainer>()

    private fun newTestContainer(): DefaultAppContainer =
        DefaultAppContainer(context).also { createdContainers += it }

    @After
    fun tearDown() {
        createdContainers.forEach { runCatching { it.database.close() } }
        createdContainers.clear()
        db.close()
        tempDir.deleteRecursively()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `image gallery displays real local PNG opens viewer metadata and delete confirmation`() {
        val pngFile = File(tempDir, "sample_photo.png")
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        FileOutputStream(pngFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val repo = MediaCenterRepository(
            context = context,
            fileManagerRepository = AndroidFileManagerRepository(context, Sha256ChecksumVerifier(), dispatchers),
            mediaPlaybackDao = db.mediaPlaybackDao(),
            musicPlaylistDao = db.musicPlaylistDao(),
            dispatchers = dispatchers,
            clock = testClock
        )
        val viewModel = ImageGalleryViewModel(repo)
        viewModel.addPickedUris(listOf(Uri.fromFile(pngFile).toString()))

        val appContainer = newTestContainer()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowTheme {
                    ImageGalleryScreen(onNavigateBack = {}, providedViewModel = viewModel)
                }
            }
        }

        waitForTag("gallery_image_grid")
        composeRule.onNodeWithTag("gallery_album_chip_all").assertIsDisplayed()
        composeRule.onNodeWithTag("gallery_grid_item_0").performClick()

        waitForTag("gallery_fullscreen_viewer")
        composeRule.onNodeWithTag("gallery_viewer_info_button").performClick()
        waitForTag("gallery_metadata_dialog")
        composeRule.onNodeWithTag("gallery_metadata_close_button").performClick()

        composeRule.onNodeWithTag("gallery_viewer_delete_button").performClick()
        waitForTag("gallery_confirm_delete_button")
        composeRule.onNodeWithTag("gallery_cancel_delete_button").performClick()
    }

    @Test
    fun `music player screen renders controls queue and playlist creation flow`() {
        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val repo = MediaCenterRepository(
            context = context,
            fileManagerRepository = AndroidFileManagerRepository(context, Sha256ChecksumVerifier(), dispatchers),
            mediaPlaybackDao = db.mediaPlaybackDao(),
            musicPlaylistDao = db.musicPlaylistDao(),
            dispatchers = dispatchers,
            clock = testClock
        )
        val controller = MusicPlaybackController(context, repo, dispatchers)
        val viewModel = MusicPlayerViewModel(repo, controller)
        val appContainer = newTestContainer()

        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowTheme {
                    MusicPlayerScreen(onNavigateBack = {}, providedViewModel = viewModel)
                }
            }
        }

        waitForTag("music_now_playing_card")
        composeRule.onNodeWithTag("music_shuffle_button").assertIsDisplayed()
        composeRule.onNodeWithTag("music_repeat_button").assertIsDisplayed()
        composeRule.onNodeWithTag("music_tab_playlists").performClick()

        waitForTag("music_create_playlist_button")
        composeRule.onNodeWithTag("music_create_playlist_button").performClick()
        waitForTag("music_create_playlist_dialog")
        composeRule.onNodeWithTag("music_playlist_name_input").performTextInput("Focus Beats")
        composeRule.onNodeWithTag("music_create_playlist_confirm").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText("Focus Beats", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(viewModel.playlists.value.any { it.name == "Focus Beats" })
    }

    @Test
    fun `private vault screen setup recovery receipt lock unlock details and export confirmation in Arabic RTL`() {
        val crypto = VaultCryptoEngine(pinKdfIterations = 2_000)
        val fakeKeystore = object : VaultKeystoreProvider {
            override fun isAvailable(): Boolean = false
            override fun ensureKeyExists(alias: String): AppResult<Unit> =
                AppResult.Failure(com.example.shareflow.core.common.AppError.Security("Unavailable"))
            override fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox> =
                AppResult.Failure(com.example.shareflow.core.common.AppError.Security("Unavailable"))
            override fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult =
                KeystoreUnwrapResult.KeyInvalidated("Unavailable")
            override fun deleteKey(alias: String): AppResult<Unit> = AppResult.Success(Unit)
        }
        val vaultManager = VaultManager(
            context = context,
            vaultConfigDao = db.vaultConfigDao(),
            vaultItemDao = db.vaultItemDao(),
            cryptoEngine = crypto,
            keystoreProvider = fakeKeystore,
            dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
            clock = testClock,
            vaultBlobDirProvider = { File(tempDir, "vault_blobs").apply { mkdirs() } }
        )
        val viewModel = VaultViewModel(context, vaultManager)
        val appContainer = newTestContainer()

        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                    VaultScreen(onNavigateBack = {}, providedViewModel = viewModel)
                }
            }
        }

        // 1. Setup Vault with PIN
        waitForTag("vault_setup_view")
        composeRule.onNodeWithTag("vault_setup_pin_input").performTextInput("2580")
        composeRule.onNodeWithTag("vault_setup_confirm_pin_input").performTextInput("2580")
        composeRule.onNodeWithTag("vault_create_submit_button").performClick()

        // 2. One-time Recovery Key dialog shown
        waitForTag("vault_recovery_receipt_dialog")
        composeRule.onNodeWithTag("vault_recovery_key_value").assertIsDisplayed()
        composeRule.onNodeWithTag("vault_recovery_saved_button").performClick()

        // 3. Import a real encrypted document & verify item details + export confirmation dialog
        val imported = runBlocking {
            (vaultManager.importFile(
                displayName = "contract.txt",
                mimeType = "text/plain",
                openInputStream = { ByteArrayInputStream("Secret contract terms".toByteArray()) }
            ) as AppResult.Success).data
        }
        waitForTag("vault_item_${imported.id}")
        composeRule.onNodeWithTag("vault_item_${imported.id}").performClick()
        waitForTag("vault_item_details_dialog")
        composeRule.onNodeWithTag("vault_detail_export_button").performClick()
        waitForTag("vault_confirm_export_button")
        composeRule.onNodeWithTag("vault_cancel_export_button").performClick()

        // 4. Lock Now -> Locked screen -> Unlock with PIN
        composeRule.onNodeWithTag("vault_lock_now_button").performClick()
        waitForTag("vault_locked_view")
        composeRule.onNodeWithTag("vault_pin_input").performTextInput("2580")
        composeRule.onNodeWithTag("vault_unlock_button").performClick()
        waitForTag("vault_unlocked_dashboard")
    }
}
