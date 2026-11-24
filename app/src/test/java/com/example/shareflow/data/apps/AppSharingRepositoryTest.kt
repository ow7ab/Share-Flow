package com.example.shareflow.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DefaultDispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.data.repository.ApkArchiveInspector
import com.example.shareflow.data.repository.ApkArchiveMetadata
import com.example.shareflow.data.repository.TransferHistoryRepositoryImpl
import com.example.shareflow.data.repository.TransferRepositoryImpl
import com.example.shareflow.domain.model.ApkExtractionStatus
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.ReceivedApkIntegrityStatus
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.EncryptedPeerChannel
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.PeerHandshakeProtocol
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.transfer.AndroidTransferDataSource
import com.example.shareflow.network.transfer.ChunkProcessor
import com.example.shareflow.network.transfer.StorageSpaceChecker
import com.example.shareflow.network.transfer.TransferQueue
import com.example.shareflow.network.transfer.TransferReceiver
import com.example.shareflow.network.transfer.TransferRecoveryManager
import com.example.shareflow.network.transfer.TransferSender
import com.example.shareflow.network.transfer.TransferVerifier
import java.io.File
import java.io.FileInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSharingRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: ShareFlowDatabase
    private lateinit var historyRepo: TransferHistoryRepositoryImpl
    private lateinit var transferRepo: TransferRepositoryImpl
    private val checksumVerifier = Sha256ChecksumVerifier()
    private val dispatchers = DefaultDispatcherProvider()

    private class FakeClock(initial: Long = 1_760_000_000_000L) : Clock {
        private val now = AtomicLong(initial)
        override fun nowEpochMillis(): Long = now.addAndGet(10L)
    }

    /**
     * Test inspector that recognizes ZIP/APK magic header (`PK\u0003\u0004`) files as valid APK
     * archives with deterministic metadata and rejects arbitrary corrupted/non-APK bytes.
     */
    private class MagicHeaderApkInspector : ApkArchiveInspector {
        override fun inspectApkArchive(apkFile: File): ApkArchiveMetadata? {
            if (!apkFile.exists() || !apkFile.isFile || apkFile.length() < 4L) return null
            val header = ByteArray(4)
            FileInputStream(apkFile).use { if (it.read(header) != 4) return null }
            val isZipMagic = header[0] == 0x50.toByte() &&
                header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
            if (!isZipMagic) return null
            return ApkArchiveMetadata(
                packageName = "org.verified.sample",
                appLabel = "Verified Sample App",
                versionName = "2.4.0",
                versionCode = 240L
            )
        }
    }

    @Before
    fun setUp() {
        clearFileProviderStaticCache()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        historyRepo = TransferHistoryRepositoryImpl(db.transferHistoryDao(), dispatchers)
        transferRepo = TransferRepositoryImpl(db.transferSessionDao(), dispatchers)
    }

    private fun clearFileProviderStaticCache() {
        runCatching {
            val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun writeFakeApkBytes(file: File, payload: String): File {
        file.parentFile?.mkdirs()
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\x03\x04 ZIP/APK signature
        file.writeBytes(header + payload.toByteArray(Charsets.UTF_8))
        return file
    }

    @Test
    fun `discoverInstalledApps classifies standalone split and unavailable apps and respects system filter`() = runBlocking {
        val shadowPm = shadowOf(context.packageManager)

        val standaloneApk = writeFakeApkBytes(File(tempFolder.root, "notes_base.apk"), "notes-standalone-apk-bytes")
        val splitBaseApk = writeFakeApkBytes(File(tempFolder.root, "camera_base.apk"), "camera-base-apk")
        val splitArmApk = writeFakeApkBytes(File(tempFolder.root, "camera_arm64.apk"), "camera-split-arm64")
        val missingApkPath = File(tempFolder.root, "restricted/missing_base.apk").absolutePath
        val sysApk = writeFakeApkBytes(File(tempFolder.root, "system_clock.apk"), "system-clock-apk")

        // 1. Standalone user app
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.user.notes"
                versionName = "1.5.0"
                longVersionCode = 15L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.user.notes"
                    nonLocalizedLabel = "Quick Notes"
                    publicSourceDir = standaloneApk.absolutePath
                    sourceDir = standaloneApk.absolutePath
                    flags = 0
                }
            }
        )

        // 2. Split APK user app
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.user.camera"
                versionName = "3.2.1"
                longVersionCode = 321L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.user.camera"
                    nonLocalizedLabel = "Pro Camera"
                    publicSourceDir = splitBaseApk.absolutePath
                    sourceDir = splitBaseApk.absolutePath
                    splitPublicSourceDirs = arrayOf(splitArmApk.absolutePath)
                    flags = 0
                }
            }
        )

        // 3. Unextractable app (missing/restricted APK path)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.user.protectedapp"
                versionName = "1.0.0"
                longVersionCode = 1L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.user.protectedapp"
                    nonLocalizedLabel = "Protected App"
                    publicSourceDir = missingApkPath
                    sourceDir = missingApkPath
                    flags = 0
                }
            }
        )

        // 4. System app
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.android.sysclock"
                versionName = "14.0"
                longVersionCode = 1400L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.android.sysclock"
                    nonLocalizedLabel = "System Clock"
                    publicSourceDir = sysApk.absolutePath
                    sourceDir = sysApk.absolutePath
                    flags = ApplicationInfo.FLAG_SYSTEM
                }
            }
        )

        val repo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            ioDispatcher = Dispatchers.Unconfined
        )

        val userOnlyRes = repo.discoverInstalledApps(includeSystemApps = false)
        assertTrue(userOnlyRes is AppResult.Success)
        val userApps = (userOnlyRes as AppResult.Success).data

        val notes = userApps.firstOrNull { it.packageName == "com.user.notes" }
        assertNotNull(notes)
        assertEquals(ApkExtractionStatus.EXTRACTABLE, notes!!.extractionStatus)
        assertFalse(notes.isSplitApk)
        assertEquals(standaloneApk.length(), notes.totalApkSizeBytes)

        val camera = userApps.firstOrNull { it.packageName == "com.user.camera" }
        assertNotNull(camera)
        assertEquals(ApkExtractionStatus.SPLIT_APK_EXTRACTABLE, camera!!.extractionStatus)
        assertTrue(camera.isSplitApk)
        assertEquals(2, camera.apkPartCount)
        assertEquals(splitBaseApk.length() + splitArmApk.length(), camera.totalApkSizeBytes)

        val protectedApp = userApps.firstOrNull { it.packageName == "com.user.protectedapp" }
        assertNotNull(protectedApp)
        assertEquals(ApkExtractionStatus.UNAVAILABLE, protectedApp!!.extractionStatus)
        assertFalse(protectedApp.isExtractable)
        assertNotNull(protectedApp.unavailableReason)

        // System app excluded when includeSystemApps = false
        assertNull(userApps.firstOrNull { it.packageName == "com.android.sysclock" })

        // Included when includeSystemApps = true
        val withSystem = (repo.discoverInstalledApps(includeSystemApps = true) as AppResult.Success).data
        assertNotNull(withSystem.firstOrNull { it.packageName == "com.android.sysclock" })
    }

    @Test
    fun `extractApksForSharing streams standalone and split APKs with verified SHA-256 and rejects unavailable apps`() = runBlocking {
        val standaloneApk = writeFakeApkBytes(File(tempFolder.root, "calc.apk"), "calculator-apk-binary-content")
        val splitBase = writeFakeApkBytes(File(tempFolder.root, "game_base.apk"), "game-base-binary")
        val splitAssets = writeFakeApkBytes(File(tempFolder.root, "game_assets.apk"), "game-assets-binary")
        val exportRoot = File(tempFolder.root, "exports")

        val shadowPm = shadowOf(context.packageManager)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.test.calc"
                versionName = "1.0"
                longVersionCode = 1L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.test.calc"
                    nonLocalizedLabel = "Calc"
                    publicSourceDir = standaloneApk.absolutePath
                    flags = 0
                }
            }
        )
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.test.splitgame"
                versionName = "2.1"
                longVersionCode = 21L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.test.splitgame"
                    nonLocalizedLabel = "Split Game"
                    publicSourceDir = splitBase.absolutePath
                    splitPublicSourceDirs = arrayOf(splitAssets.absolutePath)
                    flags = 0
                }
            }
        )
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.test.locked"
                versionName = "1.0"
                longVersionCode = 1L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.test.locked"
                    nonLocalizedLabel = "Locked"
                    publicSourceDir = File(tempFolder.root, "nope.apk").absolutePath
                    flags = 0
                }
            }
        )

        val repo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            exportRootProvider = { exportRoot },
            ioDispatcher = Dispatchers.Unconfined
        )

        val apps = (repo.discoverInstalledApps(false) as AppResult.Success).data
        val calc = apps.first { it.packageName == "com.test.calc" }
        val splitGame = apps.first { it.packageName == "com.test.splitgame" }
        val locked = apps.first { it.packageName == "com.test.locked" }

        // Extracting an unavailable app must fail explicitly
        val lockedRes = repo.extractApksForSharing(listOf(locked))
        assertTrue(lockedRes is AppResult.Failure)
        assertTrue((lockedRes as AppResult.Failure).error is AppError.PermissionDenied)

        // Extracting standalone + split succeeds with exact byte sizes, SHA-256 digests, and verified split bundle manifest
        val extractRes = repo.extractApksForSharing(listOf(calc, splitGame))
        assertTrue(extractRes is AppResult.Success)
        val artifacts = (extractRes as AppResult.Success).data
        assertEquals(4, artifacts.size)

        val apkArtifacts = artifacts.filterNot { it.isBundleManifest }
        val manifestArtifacts = artifacts.filter { it.isBundleManifest }
        assertEquals(3, apkArtifacts.size)
        assertEquals(1, manifestArtifacts.size)

        val calcArtifact = apkArtifacts.first { it.packageName == "com.test.calc" }
        assertEquals("", calcArtifact.relativePath)
        assertFalse(calcArtifact.isSplitPart)
        val expectedCalcHash = (checksumVerifier.computeSha256(FileInputStream(standaloneApk)) as AppResult.Success).data
        assertEquals(expectedCalcHash, calcArtifact.sha256Checksum)
        assertTrue(File(calcArtifact.localFilePath).exists())

        val splitApkArtifacts = apkArtifacts.filter { it.packageName == "com.test.splitgame" }
        assertEquals(2, splitApkArtifacts.size)
        assertTrue(splitApkArtifacts.all { it.isSplitPart && it.relativePath.isNotBlank() })
        assertTrue(splitApkArtifacts.any { it.fileName == "base.apk" })

        val bundleManifest = manifestArtifacts.single()
        assertEquals("com.test.splitgame", bundleManifest.packageName)
        assertTrue(bundleManifest.isSplitPart && bundleManifest.relativePath.isNotBlank())
        assertTrue(File(bundleManifest.localFilePath).exists())
        assertEquals(64, bundleManifest.sha256Checksum.length)
    }

    @Test
    fun `verifyReceivedApk enforces existence SHA-256 match and valid APK archive parsing`() = runBlocking {
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val validApk = writeFakeApkBytes(File(receiveRoot, "valid_app.apk"), "legitimate-apk-content")
        val validHash = (checksumVerifier.computeSha256(FileInputStream(validApk)) as AppResult.Success).data

        val garbageApk = File(receiveRoot, "corrupted_not_apk.apk").apply {
            writeText("NOT_A_VALID_APK_BINARY")
        }
        val garbageHash = (checksumVerifier.computeSha256(FileInputStream(garbageApk)) as AppResult.Success).data
        val missingApk = File(receiveRoot, "deleted_app.apk")

        val repo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            receiveRootProvider = { receiveRoot },
            ioDispatcher = Dispatchers.Unconfined
        )

        // 1. Valid APK + matching SHA-256 -> VERIFIED_INSTALLABLE
        val okInfo = repo.verifyReceivedApk(validApk, expectedSha256 = validHash)
        assertEquals(ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE, okInfo.integrityStatus)
        assertTrue(okInfo.canLaunchSystemInstaller)
        assertEquals("org.verified.sample", okInfo.packageName)

        // 2. Tampered / mismatched SHA-256 -> CHECKSUM_MISMATCH
        val mismatchInfo = repo.verifyReceivedApk(validApk, expectedSha256 = "0".repeat(64))
        assertEquals(ReceivedApkIntegrityStatus.CHECKSUM_MISMATCH, mismatchInfo.integrityStatus)
        assertFalse(mismatchInfo.canLaunchSystemInstaller)

        // 3. Matching SHA-256 of non-APK garbage -> INVALID_APK_ARCHIVE
        val invalidArchiveInfo = repo.verifyReceivedApk(garbageApk, expectedSha256 = garbageHash)
        assertEquals(ReceivedApkIntegrityStatus.INVALID_APK_ARCHIVE, invalidArchiveInfo.integrityStatus)
        assertFalse(invalidArchiveInfo.canLaunchSystemInstaller)

        // 4. Missing file -> FILE_MISSING
        val missingInfo = repo.verifyReceivedApk(missingApk, expectedSha256 = validHash)
        assertEquals(ReceivedApkIntegrityStatus.FILE_MISSING, missingInfo.integrityStatus)
        assertFalse(missingInfo.canLaunchSystemInstaller)
    }

    @Test
    fun `createSystemInstallIntent builds scoped FileProvider URI and blocks traversal or tampered files`() = runBlocking {
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val validApk = writeFakeApkBytes(File(receiveRoot, "installable.apk"), "valid-installable-apk")
        val validHash = (checksumVerifier.computeSha256(FileInputStream(validApk)) as AppResult.Success).data

        val outsideApk = writeFakeApkBytes(File(tempFolder.root, "outside_untrusted.apk"), "outside-apk")

        val repo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            receiveRootProvider = { receiveRoot },
            ioDispatcher = Dispatchers.Unconfined
        )

        // Outside receive root must be blocked by Security check
        val outsideRes = repo.createSystemInstallIntent(outsideApk)
        assertTrue(outsideRes is AppResult.Failure)
        assertTrue((outsideRes as AppResult.Failure).error is AppError.Security)

        // Tampered checksum must be blocked by IntegrityMismatch
        val tamperedRes = repo.createSystemInstallIntent(validApk, expectedSha256 = "f".repeat(64))
        assertTrue(tamperedRes is AppResult.Failure)
        assertTrue((tamperedRes as AppResult.Failure).error is AppError.IntegrityMismatch)

        // Valid verified APK builds scoped FileProvider ACTION_VIEW intent with read-only grant
        val intentRes = repo.createSystemInstallIntent(validApk, expectedSha256 = validHash)
        assertTrue("Expected Success but got $intentRes", intentRes is AppResult.Success)
        val intent = (intentRes as AppResult.Success).data
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AndroidAppSharingRepository.APK_MIME_TYPE, intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data?.authority)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun `end to end APK extract send receive over encrypted Transfer Engine verifies received APK`() = runBlocking {
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context)
        val exportRoot = File(tempFolder.root, "e2e_exports")
        val sourceApk = writeFakeApkBytes(
            File(tempFolder.root, "e2e_source.apk"),
            "end-to-end-apk-payload-".repeat(2048)
        )

        val shadowPm = shadowOf(context.packageManager)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.e2e.messenger"
                versionName = "4.0.1"
                longVersionCode = 401L
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.e2e.messenger"
                    nonLocalizedLabel = "Messenger"
                    publicSourceDir = sourceApk.absolutePath
                    flags = 0
                }
            }
        )

        val senderAppRepo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            exportRootProvider = { exportRoot },
            ioDispatcher = Dispatchers.Unconfined
        )
        val discovered = (senderAppRepo.discoverInstalledApps(false) as AppResult.Success).data
        val targetApp = discovered.first { it.packageName == "com.e2e.messenger" }
        val extracted = (senderAppRepo.extractApksForSharing(listOf(targetApp)) as AppResult.Success).data
        assertEquals(1, extracted.size)
        val artifact = extracted.first()

        // Establish real mutually authenticated AES-256-GCM loopback channel
        val clock = FakeClock()
        val senderId = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val receiverId = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val receiverTokens = PairingTokenManager(clock = clock)
        val senderTokens = PairingTokenManager(clock = clock)
        val receiverProtocol = PeerHandshakeProtocol(
            identityManager = receiverId,
            pairingTokenManager = receiverTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val senderProtocol = PeerHandshakeProtocol(
            identityManager = senderId,
            pairingTokenManager = senderTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort
        val payload = receiverTokens.createPairingPayload(
            localIdentity = receiverId.getLocalIdentity(),
            displayName = "Receiver Phone",
            hostAddress = "127.0.0.1",
            port = port,
            preferredConnectionType = ConnectionType.LOCAL_LAN_NSD
        )

        val serverDeferred = async(Dispatchers.IO) {
            val accepted = serverSocket.accept()
            receiverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Receiver Phone",
                requirePairingToken = true,
                trustedLookup = { _, _ -> false },
                approvalCallback = { true }
            )
        }
        val clientDeferred = async(Dispatchers.IO) {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            senderProtocol.performClientHandshake(
                socket = socket,
                localDisplayName = "Sender Phone",
                targetTransport = ConnectionType.LOCAL_LAN_NSD,
                sessionId = payload.sessionId,
                pairingToken = payload.pairingToken,
                expectedServerFingerprint = payload.publicKeyFingerprint
            )
        }
        val serverRes = serverDeferred.await()
        val clientRes = clientDeferred.await()
        serverSocket.close()
        assertTrue("Server handshake failed: $serverRes", serverRes is AppResult.Success)
        assertTrue("Client handshake failed: $clientRes", clientRes is AppResult.Success)
        val receiverChannel = (serverRes as AppResult.Success).data
        val senderChannel = (clientRes as AppResult.Success).data

        val senderDb = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries().build()
        val senderTransferRepo = TransferRepositoryImpl(senderDb.transferSessionDao(), dispatchers)
        val dataSource = AndroidTransferDataSource(context)
        val unlimitedSpace = object : StorageSpaceChecker {
            override fun getAvailableBytes(directory: File): Long = 10L * 1024L * 1024L * 1024L
        }
        val chunkProcessor = ChunkProcessor(dataSource, unlimitedSpace)
        val verifier = TransferVerifier(checksumVerifier, dataSource)
        val recovery = TransferRecoveryManager(transferRepo, dataSource, verifier, chunkProcessor, clock)

        val sender = TransferSender(chunkProcessor, verifier, senderTransferRepo, clock)
        val receiver = TransferReceiver(chunkProcessor, verifier, recovery, transferRepo, clock)

        val config = TransferConfig(chunkSizeBytes = 16 * 1024)
        val sessionNow = clock.nowEpochMillis()
        val session = com.example.shareflow.domain.model.TransferSession(
            sessionId = "apk_e2e_session",
            peer = senderChannel.remotePeer,
            direction = TransferDirection.SENT,
            state = TransferState.PENDING,
            transferItems = listOf(
                com.example.shareflow.domain.model.TransferItem(
                    itemId = "apk_e2e_session_0",
                    sessionId = "apk_e2e_session",
                    fileName = artifact.fileName,
                    relativePath = artifact.relativePath,
                    mimeType = AndroidAppSharingRepository.APK_MIME_TYPE,
                    fileType = TransferFileType.APK,
                    fileSizeBytes = artifact.sizeBytes,
                    chunkSizeBytes = config.validatedChunkSizeBytes,
                    totalChunks = ChunkProcessor.calculateTotalChunks(artifact.sizeBytes, config.validatedChunkSizeBytes),
                    sourceUri = artifact.uriString,
                    state = TransferState.PENDING,
                    isRecoverable = true,
                    startedAtEpochMillis = sessionNow
                )
            ),
            chunkSizeBytes = config.validatedChunkSizeBytes,
            isResumable = true,
            createdAtEpochMillis = sessionNow,
            updatedAtEpochMillis = sessionNow
        )

        val receiveJob = async(Dispatchers.IO) {
            receiver.receiveNextSession(
                channel = receiverChannel,
                destinationRoot = receiveRoot,
                config = config
            )
        }
        val sendJob = async(Dispatchers.IO) {
            sender.sendSession(
                channel = senderChannel,
                initialSession = session,
                config = config
            )
        }

        val sendResult = sendJob.await()
        val receiveResult = receiveJob.await()
        senderChannel.close()
        receiverChannel.close()
        senderDb.close()

        assertTrue("Send failed: $sendResult", sendResult is AppResult.Success)
        assertTrue("Receive failed: $receiveResult", receiveResult is AppResult.Success)
        assertEquals(TransferState.COMPLETED, (receiveResult as AppResult.Success).data.state)

        // Receiver's AppSharingRepository must observe & verify the received APK automatically!
        val receiverAppRepo = AndroidAppSharingRepository(
            context = context,
            transferHistoryRepository = historyRepo,
            checksumVerifier = checksumVerifier,
            apkArchiveInspector = MagicHeaderApkInspector(),
            receiveRootProvider = { receiveRoot },
            ioDispatcher = Dispatchers.Unconfined
        )
        val receivedApks = receiverAppRepo.observeReceivedApks().first()
        val verifiedApk = receivedApks.firstOrNull { it.expectedSha256 == artifact.sha256Checksum }
        assertNotNull("Expected received APK in observeReceivedApks()", verifiedApk)
        assertEquals(ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE, verifiedApk!!.integrityStatus)
        assertTrue(verifiedApk.canLaunchSystemInstaller)
        assertEquals(artifact.sha256Checksum, verifiedApk.actualSha256)

        // Building Android system install intent succeeds with scoped FileProvider URI
        val installIntentRes = receiverAppRepo.createSystemInstallIntent(
            apkFile = File(verifiedApk.filePath),
            expectedSha256 = verifiedApk.expectedSha256
        )
        assertTrue(installIntentRes is AppResult.Success)
    }
}
