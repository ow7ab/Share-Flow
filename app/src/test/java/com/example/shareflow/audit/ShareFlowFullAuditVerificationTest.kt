package com.example.shareflow.audit

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.update.GitHubReleasesParser
import com.example.shareflow.data.update.VersionPrecedenceDecision
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.repository.TransferRepository
import com.example.shareflow.network.transfer.AndroidTransferDataSource
import com.example.shareflow.network.transfer.ChunkProcessor
import com.example.shareflow.network.transfer.StorageSpaceChecker
import com.example.shareflow.network.transfer.TransferRecoveryManager
import com.example.shareflow.network.transfer.TransferVerifier
import com.example.shareflow.network.transfer.TransferWireProtocol
import com.example.shareflow.ui.viewer.ApkInstallerIntentHelper
import com.example.shareflow.utilities.ZipArchiveEngine
import com.example.shareflow.vault.AesGcmBox
import com.example.shareflow.vault.KeystoreUnwrapResult
import com.example.shareflow.vault.VaultCryptoEngine
import com.example.shareflow.vault.VaultKeystoreProvider
import com.example.shareflow.vault.VaultManager
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFlowFullAuditVerificationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeClock(var currentMs: Long = 1_760_000_000_000L) : Clock {
        override fun nowEpochMillis(): Long = currentMs
    }

    @Test
    fun `apk intent helper blocks file URI pointing to ShareFlow private app storage`() {
        val secretDbLikeFile = File(context.filesDir, "private_vault_secret.apk").apply {
            writeMinimalValidApk(this)
        }
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val stagingDir = File(receiveRoot, "external_apk_imports").apply {
            listFiles()?.forEach { it.delete() }
        }

        try {
            val result = ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = Uri.fromFile(secretDbLikeFile),
                checksumVerifier = Sha256ChecksumVerifier()
            )
            assertNull("Private app storage file:// URI MUST be rejected", result)
            val stagedFiles = stagingDir.listFiles().orEmpty()
            assertTrue("No file should be staged from private internal storage", stagedFiles.isEmpty())
        } finally {
            secretDbLikeFile.delete()
        }
    }

    @Test
    fun `apk intent helper rejects external non-apk file before staging and cleans stale imports`() {
        val externalTempDir = File(System.getProperty("java.io.tmpdir"), "sf_ext_test_${System.nanoTime()}").apply {
            mkdirs()
        }
        val plainTextFile = File(externalTempDir, "not_an_apk.apk").apply {
            writeText("plain secret text, not a zip")
        }
        val validExternalApk = File(externalTempDir, "legit_app.apk").apply {
            writeMinimalValidApk(this)
        }
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val stagingDir = File(receiveRoot, "external_apk_imports").apply { mkdirs() }
        val staleFile = File(stagingDir, "1000_stale.apk").apply {
            writeText("old")
            setLastModified(System.currentTimeMillis() - (48L * 60L * 60L * 1000L))
        }

        try {
            val rejected = ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = Uri.fromFile(plainTextFile),
                checksumVerifier = Sha256ChecksumVerifier()
            )
            assertNull("Non-APK external file must be rejected prior to staging", rejected)
            assertFalse("Stale staged APK older than 24h must be purged automatically", staleFile.exists())

            val accepted = ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = Uri.fromFile(validExternalApk),
                checksumVerifier = Sha256ChecksumVerifier()
            )
            assertNotNull("Valid external APK archive should be staged safely", accepted)
            assertTrue(accepted!!.stagedFile.exists())
            assertTrue(accepted.sha256?.length == 64)
            accepted.stagedFile.delete()
        } finally {
            externalTempDir.deleteRecursively()
        }
    }

    @Test
    fun `receiver resume point rejects peer with matching deviceId but mismatched cryptographic fingerprint`() =
        runBlocking {
            val tempRoot = File(System.getProperty("java.io.tmpdir"), "sf_resume_fp_${System.nanoTime()}").apply {
                mkdirs()
            }
            try {
                val checksum = Sha256ChecksumVerifier()
                val dataSource = AndroidTransferDataSource(context)
                val chunkProcessor = ChunkProcessor(
                    dataSource = dataSource,
                    storageSpaceChecker = object : StorageSpaceChecker {
                        override fun getAvailableBytes(directory: File): Long = 1024L * 1024L * 1024L
                    }
                )
                val verifier = TransferVerifier(checksum, dataSource)
                val partFile = chunkProcessor.resolveTempPartFile(tempRoot, "sess_fp_1", "item_1")
                partFile.writeBytes(ByteArray(65536) { 0x42 })

                val expectedHash = "a".repeat(64)
                val storedSession = TransferSession(
                    sessionId = "sess_fp_1",
                    peer = PeerDevice(
                        deviceId = "peer-device-1",
                        displayName = "Original Peer",
                        connectionType = ConnectionType.HOTSPOT,
                        publicKeyFingerprint = "11112222333344445555666677778888"
                    ),
                    direction = TransferDirection.RECEIVED,
                    state = TransferState.PAUSED,
                    transferItems = listOf(
                        TransferItem(
                            itemId = "item_1",
                            sessionId = "sess_fp_1",
                            fileName = "photo.jpg",
                            fileSizeBytes = 131072L,
                            bytesTransferred = 65536L,
                            chunkSizeBytes = 65536,
                            totalChunks = 2,
                            nextChunkIndex = 1,
                            expectedSha256 = expectedHash,
                            tempFilePath = partFile.absolutePath,
                            state = TransferState.PAUSED,
                            isRecoverable = true
                        )
                    ),
                    chunkSizeBytes = 65536,
                    isResumable = true
                )

                val repo = InMemoryTransferRepo(storedSession)
                val recovery = TransferRecoveryManager(repo, dataSource, verifier, chunkProcessor)
                val descriptor = TransferWireProtocol.OfferFileDescriptor(
                    itemId = "item_1",
                    fileName = "photo.jpg",
                    relativePath = "",
                    mimeType = "image/jpeg",
                    fileType = TransferFileType.IMAGE,
                    fileSizeBytes = 131072L,
                    totalChunks = 2,
                    expectedSha256 = expectedHash
                )

                val spoofedPeer = storedSession.peer.copy(
                    publicKeyFingerprint = "ffffffffffffffffffffffffffffffff"
                )
                val spoofedResume = recovery.resolveVerifiedReceiverResumePoint(
                    sessionId = "sess_fp_1",
                    peer = spoofedPeer,
                    itemDescriptor = descriptor,
                    chunkSizeBytes = 65536
                )
                assertEquals(
                    "Spoofed fingerprint must NOT be permitted to resume existing staging data",
                    0 to 0L,
                    spoofedResume
                )

                val genuineResume = recovery.resolveVerifiedReceiverResumePoint(
                    sessionId = "sess_fp_1",
                    peer = storedSession.peer,
                    itemDescriptor = descriptor,
                    chunkSizeBytes = 65536
                )
                assertEquals(1 to 65536L, genuineResume)
            } finally {
                tempRoot.deleteRecursively()
            }
        }

    @Test
    fun `vault manager enforces lockout in both changePin and recoverWithRecoveryKey`() = runBlocking {
        val clock = FakeClock()
        val blobDir = File(System.getProperty("java.io.tmpdir"), "sf_vault_lock_${System.nanoTime()}").apply {
            mkdirs()
        }
        val db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val configDao = db.vaultConfigDao()
            val itemDao = db.vaultItemDao()
            val cryptoEngine = VaultCryptoEngine()
            val manager = VaultManager(
                context = context,
                vaultConfigDao = configDao,
                vaultItemDao = itemDao,
                cryptoEngine = cryptoEngine,
                keystoreProvider = NoOpVaultKeystoreProvider(),
                dispatchers = testDispatchers,
                clock = clock,
                vaultBlobDirProvider = { blobDir }
            )

            val created = manager.createVault(
                pin = "123456",
                enableBiometric = false,
                autoLockTimeout = VaultAutoLockTimeout.IMMEDIATE
            )
            assertTrue(created is AppResult.Success)
            val recoveryKey = (created as AppResult.Success).data.recoveryKey

            // 5 wrong current PIN attempts via changePin must trigger lockout
            repeat(5) {
                val res = manager.changePin("000000", "654321")
                assertTrue(res is AppResult.Failure)
            }
            // Even with the CORRECT current PIN, changePin must be blocked during active lockout
            val lockedChange = manager.changePin("123456", "654321")
            assertTrue("changePin must enforce active lockout", lockedChange is AppResult.Failure)
            assertTrue((lockedChange as AppResult.Failure).error is AppError.Security)

            // Even with the CORRECT recovery key, recoverWithRecoveryKey must respect active lockout
            val lockedRecovery = manager.recoverWithRecoveryKey(recoveryKey, "654321")
            assertTrue("recoverWithRecoveryKey must enforce active lockout", lockedRecovery is AppResult.Failure)

            // Advance past lockout window -> valid recovery succeeds and resets failed attempts
            clock.currentMs += 60_000L
            val recovered = manager.recoverWithRecoveryKey(recoveryKey, "654321")
            assertTrue("Valid recovery after lockout expiry must succeed", recovered is AppResult.Success)
            assertEquals(0, configDao.getConfig()?.failedAttempts)
        } finally {
            db.close()
            blobDir.deleteRecursively()
        }
    }

    @Test
    fun `chunk processor ThreadLocal sha256 and orphan staging cleanup preserve protected sessions`() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "sf_orphan_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val dataA = "ShareFlow Arabic ملف تجريبي 1".toByteArray(Charsets.UTF_8)
            val dataB = "ShareFlow Arabic ملف تجريبي 2".toByteArray(Charsets.UTF_8)
            val digestA1 = ChunkProcessor.computeChunkSha256(dataA)
            val digestB = ChunkProcessor.computeChunkSha256(dataB)
            val digestA2 = ChunkProcessor.computeChunkSha256(dataA)
            assertTrue(ChunkProcessor.verifyChunkSha256(dataA, digestA1))
            assertTrue(ChunkProcessor.verifyChunkSha256(dataA, digestA2))
            assertFalse(ChunkProcessor.verifyChunkSha256(dataA, digestB))

            val processor = ChunkProcessor(
                dataSource = AndroidTransferDataSource(context),
                storageSpaceChecker = object : StorageSpaceChecker {
                    override fun getAvailableBytes(directory: File): Long = 1024L * 1024L * 1024L
                }
            )
            val now = 1_760_000_000_000L
            val protectedPart = processor.resolveTempPartFile(tempRoot, "sess_active", "item_1").apply {
                writeText("resumable")
                setLastModified(now - 5L * 60L * 60L * 1000L)
            }
            val staleOrphanPart = processor.resolveTempPartFile(tempRoot, "sess_crashed", "item_9").apply {
                writeText("orphan")
                setLastModified(now - 5L * 60L * 60L * 1000L)
            }

            val deleted = processor.cleanupOrphanedStagingDirs(
                destinationRoot = tempRoot,
                protectedSessionIds = setOf("sess_active"),
                nowEpochMillis = now
            )
            assertEquals(1, deleted)
            assertTrue("Protected resumable session part file must remain intact", protectedPart.exists())
            assertFalse("Stale orphaned part file must be removed", staleOrphanPart.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `zip archive engine blocks zip slip and rolls back previously extracted entries`() = runBlocking {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "sf_zip_rb_${System.nanoTime()}").apply {
            mkdirs()
        }
        try {
            val craftedZip = File(tempDir, "crafted.zip")
            ZipOutputStream(FileOutputStream(craftedZip)).use { zos ->
                zos.putNextEntry(ZipEntry("safe_first.txt"))
                zos.write("benign".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("../escape.txt"))
                zos.write("malicious".toByteArray())
                zos.closeEntry()
            }
            val destDir = File(tempDir, "extract_out")
            val engine = ZipArchiveEngine(context, Sha256ChecksumVerifier(), testDispatchers)
            val result = engine.extractZip(Uri.fromFile(craftedZip), destinationDirectory = destDir)
            assertTrue("Zip-Slip archive must fail with Security error", result is AppResult.Failure)
            assertTrue((result as AppResult.Failure).error is AppError.Security)
            assertFalse("Earlier extracted file must be rolled back on Zip-Slip abort", File(destDir, "safe_first.txt").exists())
            assertFalse("Escaped target file must never be created", File(tempDir, "escape.txt").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `v1_1_0 version upgrade and V1_1 tag normalization work seamlessly with versionCode precedence`() {
        assertEquals(25, BuildConfig.VERSION_CODE)
        assertEquals("1.1.2", BuildConfig.VERSION_NAME)
        assertEquals("1.1.0", GitHubReleasesParser.normalizeVersionName("V1.1"))
        assertEquals("1.1.0", GitHubReleasesParser.normalizeVersionName("v1.1"))
        assertEquals("1.1.0", GitHubReleasesParser.normalizeVersionName("v1.1.0"))
        assertEquals(0, GitHubReleasesParser.compareSemanticVersions("V1.1", "1.1.0"))
        assertTrue(GitHubReleasesParser.compareSemanticVersions("V1.1", "1.0.22") > 0)

        assertEquals(
            VersionPrecedenceDecision.UPDATE_AVAILABLE,
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = 23,
                remoteVersionName = "V1.1",
                installedVersionCode = 22,
                installedVersionName = "1.0.22"
            )
        )
        assertEquals(
            VersionPrecedenceDecision.ALREADY_INSTALLED,
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = 23,
                remoteVersionName = "V1.1",
                installedVersionCode = 23,
                installedVersionName = "1.1.0"
            )
        )
        assertEquals(
            VersionPrecedenceDecision.DOWNGRADE_BLOCKED,
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = 22,
                remoteVersionName = "1.0.22",
                installedVersionCode = 23,
                installedVersionName = "1.1.0"
            )
        )
    }

    private fun writeMinimalValidApk(target: File) {
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zos.closeEntry()
        }
    }

    private class InMemoryTransferRepo(initial: TransferSession) : TransferRepository {
        private val sessions = linkedMapOf(initial.sessionId to initial)
        override fun observeAllSessions(): Flow<List<TransferSession>> = flowOf(sessions.values.toList())
        override fun observeSession(sessionId: String): Flow<TransferSession?> = flowOf(sessions[sessionId])
        override suspend fun getSessionById(sessionId: String): TransferSession? = sessions[sessionId]
        override suspend fun getIncompleteSessions(): List<TransferSession> = sessions.values.toList()
        override suspend fun saveSession(session: TransferSession): AppResult<Unit> {
            sessions[session.sessionId] = session
            return AppResult.Success(Unit)
        }
        override suspend fun upsertItem(item: TransferItem): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun updateSessionState(
            sessionId: String,
            state: TransferState,
            errorMessage: String?,
            isResumable: Boolean,
            updatedAtEpochMillis: Long,
            completedAtEpochMillis: Long?
        ): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun updateItemProgress(
            itemId: String,
            bytesTransferred: Long,
            nextChunkIndex: Int,
            state: TransferState,
            tempFilePath: String?,
            errorMessage: String?,
            isRecoverable: Boolean
        ): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun commitVerifiedItemCompletion(
            completedItem: TransferItem,
            historyRecord: TransferRecord,
            sessionUpdatedAtEpochMillis: Long
        ): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteSession(sessionId: String): AppResult<Unit> {
            sessions.remove(sessionId)
            return AppResult.Success(Unit)
        }
    }

    private class NoOpVaultKeystoreProvider : VaultKeystoreProvider {
        override fun isAvailable(): Boolean = false
        override fun ensureKeyExists(alias: String): AppResult<Unit> =
            AppResult.Failure(AppError.NotSupported("Unavailable"))
        override fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox> =
            AppResult.Failure(AppError.NotSupported("Unavailable"))
        override fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult =
            KeystoreUnwrapResult.Failure(AppError.NotSupported("Unavailable"))
        override fun deleteKey(alias: String): AppResult<Unit> = AppResult.Success(Unit)
    }
}
