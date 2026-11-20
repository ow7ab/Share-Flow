package com.example.shareflow.utilities

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.migration.ContactsMigrationExporter
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UtilitiesEnginesTest {

    private lateinit var context: Context
    private lateinit var database: ShareFlowDatabase
    private lateinit var tempRoot: File
    private val checksumVerifier = Sha256ChecksumVerifier()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private var fakeNow = 1_700_000_000_000L
    private val testClock = object : Clock {
        override fun nowEpochMillis(): Long = fakeNow
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempRoot = File(context.filesDir, "utilities_test_${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun teardown() {
        database.close()
        tempRoot.deleteRecursively()
    }

    @Test
    fun `duplicate finder uses size pre-filter then SHA-256 verification and never auto-deletes`() = runBlocking {
        val dup1 = File(tempRoot, "photo_a.jpg").apply { writeBytes("IDENTICAL_PAYLOAD_123456".toByteArray()) }
        val dup2 = File(tempRoot, "photo_b.jpg").apply { writeBytes("IDENTICAL_PAYLOAD_123456".toByteArray()) }
        val sameSizeDiff = File(tempRoot, "photo_c.jpg").apply { writeBytes("DIFFERENT_PAYLOAD_999999".toByteArray()) }
        val uniqueSize = File(tempRoot, "short.txt").apply { writeBytes("UNIQUE".toByteArray()) }
        val missingFile = File(tempRoot, "missing.bin")

        val entries = listOf(
            toEntry(dup1, FileCategory.IMAGES),
            toEntry(dup2, FileCategory.IMAGES),
            toEntry(sameSizeDiff, FileCategory.IMAGES),
            toEntry(uniqueSize, FileCategory.DOCUMENTS),
            LocalFileEntry(
                uriString = Uri.fromFile(missingFile).toString(),
                displayName = "missing.bin",
                mimeType = "application/octet-stream",
                sizeBytes = dup1.length(),
                lastModifiedEpochMillis = fakeNow,
                category = FileCategory.OTHER,
                isDirectory = false,
                isAccessible = false
            )
        )

        val fakeFileRepo = FakeFileManagerRepo(entries)
        val engine = DuplicateFileFinderEngine(context, fakeFileRepo, checksumVerifier, testDispatchers)

        val report = engine.findDuplicates(entries)

        assertEquals(4, report.totalFilesInspected)
        // Only the 3 same-size files should have been SHA-256 hashed (uniqueSize skipped in Stage 1!)
        assertEquals(3, report.candidatesHashed)
        assertEquals(1, report.skippedInaccessibleCount)
        assertEquals(1, report.groups.size)
        assertEquals(2, report.groups.first().files.size)
        assertEquals(dup1.length(), report.totalReclaimableBytes)
        // Safety guarantee: never auto-deletes files
        assertTrue(dup1.exists())
        assertTrue(dup2.exists())
    }

    @Test
    fun `zip archive engine compresses preserving originals and extracts safely round-trip`() = runBlocking {
        val src1 = File(tempRoot, "notes.txt").apply { writeText("Hello ShareFlow ZIP compression!") }
        val src2 = File(tempRoot, "data.bin").apply { writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() }) }

        val engine = ZipArchiveEngine(context, checksumVerifier, testDispatchers)
        val archivesDir = File(tempRoot, "out_archives")
        val extractDir = File(tempRoot, "out_extracted")

        val created = engine.compressToZip(
            sourceUris = listOf(Uri.fromFile(src1), Uri.fromFile(src2)),
            archiveBaseName = "my_backup",
            outputDirectory = archivesDir
        )
        assertTrue(created is AppResult.Success)
        val createData = (created as AppResult.Success).data
        assertEquals(2, createData.includedFileCount)
        assertTrue(createData.originalsPreserved)
        assertTrue(createData.sha256Hex.length == 64)
        // Originals strictly preserved
        assertTrue(src1.exists())
        assertTrue(src2.exists())

        val archiveUri = Uri.fromFile(File(createData.archiveFilePath))
        val inspected = engine.inspectZip(archiveUri)
        assertTrue(inspected is AppResult.Success)
        assertEquals(2, (inspected as AppResult.Success).data.size)

        val extracted = engine.extractZip(archiveUri, destinationDirectory = extractDir)
        assertTrue(extracted is AppResult.Success)
        val extractData = (extracted as AppResult.Success).data
        assertEquals(2, extractData.extractedFiles.size)
        assertEquals("Hello ShareFlow ZIP compression!", File(extractDir, "notes.txt").readText())
        assertTrue(File(extractDir, "data.bin").readBytes().contentEquals(src2.readBytes()))
    }

    @Test
    fun `zip archive engine blocks zip-slip path traversal and decompression bomb limits`() = runBlocking {
        val engine = ZipArchiveEngine(context, checksumVerifier, testDispatchers)

        // 1. Malicious Zip-Slip archive
        val evilZip = File(tempRoot, "evil_slip.zip")
        ZipOutputStream(FileOutputStream(evilZip)).use { zos ->
            zos.putNextEntry(ZipEntry("../../escaped_file.txt"))
            zos.write("PWNED".toByteArray())
            zos.closeEntry()
        }
        val extractDir = File(tempRoot, "safe_target")
        val slipInspect = engine.inspectZip(Uri.fromFile(evilZip))
        assertTrue(slipInspect is AppResult.Failure && slipInspect.error is AppError.Security)

        val slipExtract = engine.extractZip(Uri.fromFile(evilZip), destinationDirectory = extractDir)
        assertTrue(slipExtract is AppResult.Failure && slipExtract.error is AppError.Security)
        assertFalse(File(tempRoot, "escaped_file.txt").exists())

        // 2. Decompression bomb size limit
        val largeZip = File(tempRoot, "bomb.zip")
        ZipOutputStream(FileOutputStream(largeZip)).use { zos ->
            zos.putNextEntry(ZipEntry("huge.bin"))
            zos.write(ByteArray(32 * 1024))
            zos.closeEntry()
        }
        val bombResult = engine.extractZip(
            archiveUri = Uri.fromFile(largeZip),
            destinationDirectory = File(tempRoot, "bomb_target"),
            maxExtractedBytes = 4 * 1024L
        )
        assertTrue(bombResult is AppResult.Failure && bombResult.error is AppError.Security)
    }

    @Test
    fun `utilities repository records storage trends and removing favorite never deletes real file`() = runBlocking {
        val userFile = File(tempRoot, "important_contract.pdf").apply {
            writeBytes(ByteArray(15 * 1024))
        }
        val entry = toEntry(userFile, FileCategory.DOCUMENTS)
        val fakeFileRepo = FakeFileManagerRepo(listOf(entry))
        val fakeStorageRepo = FakeStorageRepo(
            StorageSummary(
                totalBytes = 64_000_000_000L,
                availableBytes = 20_000_000_000L,
                usedBytes = 44_000_000_000L,
                breakdown = emptyList()
            )
        )

        val repo = UtilitiesRepository(
            context = context,
            storageAnalyzerRepository = fakeStorageRepo,
            fileManagerRepository = fakeFileRepo,
            favoriteFileDao = database.favoriteFileDao(),
            storageSnapshotDao = database.storageSnapshotDao(),
            dispatchers = testDispatchers,
            clock = testClock
        )

        // Record two storage trend snapshots
        val report1 = repo.analyzeStorage(largeFileThresholdBytes = 10 * 1024L)
        assertTrue(report1 is AppResult.Success)
        assertEquals(1, (report1 as AppResult.Success).data.largeFiles.size)
        assertEquals(1, report1.data.trends.size)

        fakeNow += 120_000L
        fakeStorageRepo.summary = StorageSummary(
            totalBytes = 64_000_000_000L,
            availableBytes = 19_000_000_000L,
            usedBytes = 45_000_000_000L,
            breakdown = emptyList()
        )
        val report2 = repo.analyzeStorage(largeFileThresholdBytes = 10 * 1024L)
        assertTrue(report2 is AppResult.Success)
        assertEquals(2, (report2 as AppResult.Success).data.trends.size)
        assertEquals(1_000_000_000L, report2.data.trendDeltaBytes)

        // Favorites add & remove never deletes real file
        val toggleAdded = repo.toggleFavorite(entry)
        assertEquals(AppResult.Success(true), toggleAdded)
        assertEquals(1, repo.observeFavorites().first().size)

        val removed = repo.removeFavorite(entry.uriString)
        assertEquals(AppResult.Success(Unit), removed)
        assertEquals(0, repo.observeFavorites().first().size)
        assertTrue("Actual file on disk MUST remain untouched", userFile.exists())

        // Safe temporary cache cleanup only clears cacheDir, not filesDir
        val cacheJunk = File(context.cacheDir, "temp_preview.tmp").apply { writeBytes(ByteArray(2048)) }
        val cleared = repo.clearTemporaryCache()
        assertTrue(cleared is AppResult.Success && cleared.data >= 2048L)
        assertFalse(cacheJunk.exists())
        assertTrue(userFile.exists())
    }

    @Test
    fun `contacts migration exporter denies access cleanly when READ_CONTACTS is not granted`() = runBlocking {
        val exporter = ContactsMigrationExporter(context, checksumVerifier, testDispatchers)
        assertFalse(exporter.hasContactsPermission())
        val countResult = exporter.countContacts()
        assertTrue(countResult is AppResult.Failure && countResult.error is AppError.PermissionDenied)
        val exportResult = exporter.exportContactsToVcf()
        assertTrue(exportResult is AppResult.Failure && exportResult.error is AppError.PermissionDenied)
    }

    private fun toEntry(file: File, category: FileCategory): LocalFileEntry =
        LocalFileEntry(
            uriString = Uri.fromFile(file).toString(),
            displayName = file.name,
            mimeType = "application/octet-stream",
            sizeBytes = file.length(),
            lastModifiedEpochMillis = file.lastModified(),
            category = category,
            isDirectory = false,
            isAccessible = true
        )

    private class FakeStorageRepo(var summary: StorageSummary) : StorageAnalyzerRepository {
        private val flow = MutableStateFlow<AppResult<StorageSummary>>(AppResult.Success(summary))
        override fun observeStorageSummary(): Flow<AppResult<StorageSummary>> = flow
        override suspend fun refreshStorageSummary(): AppResult<StorageSummary> {
            val res = AppResult.Success(summary)
            flow.value = res
            return res
        }
    }

    private class FakeFileManagerRepo(private val files: List<LocalFileEntry>) : FileManagerRepository {
        override fun observeStorageChanges(): Flow<Unit> = MutableStateFlow(Unit)
        override fun observeGrantedDirectories(): Flow<List<com.example.shareflow.domain.model.GrantedDirectory>> =
            MutableStateFlow(emptyList())
        override suspend fun registerGrantedDirectory(treeUriString: String): AppResult<com.example.shareflow.domain.model.GrantedDirectory> =
            AppResult.Failure(AppError.NotFound("unused"))
        override suspend fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> = AppResult.Success(files)
        override suspend fun listDirectory(treeUriString: String): AppResult<List<LocalFileEntry>> =
            AppResult.Success(files)
        override suspend fun queryByCategory(category: FileCategory): AppResult<List<LocalFileEntry>> =
            AppResult.Success(files.filter { it.category == category })
        override suspend fun inspectFile(documentUriString: String): AppResult<LocalFileEntry> =
            files.firstOrNull { it.uriString == documentUriString }?.let { AppResult.Success(it) }
                ?: AppResult.Failure(AppError.NotFound("not found"))
        override suspend fun computeSha256(documentUriString: String): AppResult<String> =
            AppResult.Failure(AppError.NotFound("use file stream"))
        override suspend fun createFolder(parentDirectoryUriString: String, folderName: String): AppResult<LocalFileEntry> =
            AppResult.Failure(AppError.NotFound("unused"))
        override suspend fun renameFile(documentUriString: String, newName: String): AppResult<LocalFileEntry> =
            AppResult.Failure(AppError.NotFound("unused"))
        override suspend fun copyFiles(sourceUris: List<String>, targetDirectoryUriString: String): AppResult<FileOperationResult> =
            AppResult.Success(FileOperationResult(emptyList(), emptyList()))
        override suspend fun moveFiles(sourceUris: List<String>, targetDirectoryUriString: String): AppResult<FileOperationResult> =
            AppResult.Success(FileOperationResult(emptyList(), emptyList()))
        override suspend fun deleteFiles(documentUris: List<String>): AppResult<FileOperationResult> =
            AppResult.Success(FileOperationResult(documentUris, emptyList()))
        override suspend fun readTextPreview(documentUriString: String, maxBytes: Int): AppResult<String> =
            AppResult.Success("")
    }
}
