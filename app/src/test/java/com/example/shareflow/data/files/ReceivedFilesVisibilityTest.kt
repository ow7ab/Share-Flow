package com.example.shareflow.data.files

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.repository.AndroidFileManagerRepository
import com.example.shareflow.domain.model.FileCategory
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedFilesVisibilityTest {

    private lateinit var receiveRoot: File

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        receiveRoot = File(context.cacheDir, "received_visibility_test_${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        receiveRoot.deleteRecursively()
    }

    private fun testDispatchers(dispatcher: CoroutineDispatcher) = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }

    @Test
    fun `received files in ShareFlowReceived appear across file manager categories while staging and manifests stay hidden`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val context = RuntimeEnvironment.getApplication()

            // Create received media, document, and APK files
            File(receiveRoot, "vacation_photo.jpg").writeBytes(ByteArray(2048) { 1 })
            File(receiveRoot, "family_clip.mp4").writeBytes(ByteArray(4096) { 2 })
            File(receiveRoot, "meeting_notes.txt").writeText("Hello from ShareFlow receiver!", Charsets.UTF_8)
            File(receiveRoot, "cool_app.apk").writeBytes("PK\u0003\u0004apk".toByteArray())

            // Create staging folder & manifest file that MUST NOT leak into user-visible file manager
            val stagingDir = File(receiveRoot, ".shareflow_staging").apply { mkdirs() }
            File(stagingDir, "incomplete_transfer.part").writeBytes(ByteArray(512))
            val splitBundleDir = File(receiveRoot, "SplitApp_bundle").apply { mkdirs() }
            File(splitBundleDir, "shareflow_app_manifest.json").writeText("{\"packageName\":\"com.test\"}")
            File(splitBundleDir, "base.apk").writeBytes("PK\u0003\u0004base".toByteArray())
            File(splitBundleDir, "split_config.arm64_v8a.apk").writeBytes("PK\u0003\u0004split".toByteArray())

            val repository = AndroidFileManagerRepository(
                context = context,
                dispatchers = testDispatchers(dispatcher),
                checksumVerifier = Sha256ChecksumVerifier(),
                receiveRootProvider = { receiveRoot }
            )

            val scanResult = repository.scanAccessibleMedia()
            assertTrue(scanResult is AppResult.Success)
            val allEntries = (scanResult as AppResult.Success).data
            val allNames = allEntries.map { it.displayName }.toSet()

            assertTrue("Received image must be visible", "vacation_photo.jpg" in allNames)
            assertTrue("Received video must be visible", "family_clip.mp4" in allNames)
            assertTrue("Received text document must be visible", "meeting_notes.txt" in allNames)
            assertTrue("Received single APK must be visible", "cool_app.apk" in allNames)
            assertTrue("Split bundle base.apk must be visible", "base.apk" in allNames)

            assertFalse("Staging part file must stay hidden", "incomplete_transfer.part" in allNames)
            assertFalse("Internal bundle manifest must stay hidden", "shareflow_app_manifest.json" in allNames)
            assertFalse("Config split APK must not clutter file list", "split_config.arm64_v8a.apk" in allNames)

            // Verify category filtering matches received files
            val images = (repository.queryByCategory(FileCategory.IMAGES) as AppResult.Success).data
            assertEquals(listOf("vacation_photo.jpg"), images.map { it.displayName })

            val videos = (repository.queryByCategory(FileCategory.VIDEOS) as AppResult.Success).data
            assertEquals(listOf("family_clip.mp4"), videos.map { it.displayName })

            val docs = (repository.queryByCategory(FileCategory.DOCUMENTS) as AppResult.Success).data
            assertEquals(listOf("meeting_notes.txt"), docs.map { it.displayName })

            // Verify text preview works directly on received file
            val notesEntry = docs.first()
            val previewRes = repository.readTextPreview(notesEntry.uriString)
            assertTrue(previewRes is AppResult.Success)
            assertEquals("Hello from ShareFlow receiver!", (previewRes as AppResult.Success).data)
        }
}
