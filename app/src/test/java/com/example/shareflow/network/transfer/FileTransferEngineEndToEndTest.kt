package com.example.shareflow.network.transfer

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DefaultDispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.repository.TransferHistoryRepositoryImpl
import com.example.shareflow.data.repository.TransferRepositoryImpl
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.EncryptedPeerChannel
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.PeerHandshakeProtocol
import com.example.shareflow.network.security.ReplayProtectionCache
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileTransferEngineEndToEndTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var senderDb: ShareFlowDatabase
    private lateinit var receiverDb: ShareFlowDatabase
    private lateinit var senderRepo: TransferRepositoryImpl
    private lateinit var receiverRepo: TransferRepositoryImpl
    private lateinit var senderHistoryRepo: TransferHistoryRepositoryImpl
    private lateinit var receiverHistoryRepo: TransferHistoryRepositoryImpl

    private lateinit var senderIdentity: AndroidDeviceIdentityManager
    private lateinit var receiverIdentity: AndroidDeviceIdentityManager
    private lateinit var clock: FakeClock

    private val dispatchers = DefaultDispatcherProvider()

    private class FakeClock(initial: Long = 1_750_000_000_000L) : Clock {
        private val now = AtomicLong(initial)
        override fun nowEpochMillis(): Long = now.addAndGet(5L)
    }

    private class FixedStorageChecker(private val freeBytes: Long) : StorageSpaceChecker {
        override fun getAvailableBytes(directory: File): Long = freeBytes
    }

    private data class LoopbackPair(
        val senderChannel: EncryptedPeerChannel,
        val receiverChannel: EncryptedPeerChannel
    ) {
        fun closeQuietly() {
            runCatching { senderChannel.close() }
            runCatching { receiverChannel.close() }
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = FakeClock()

        senderDb = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        receiverDb = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        senderRepo = TransferRepositoryImpl(senderDb.transferSessionDao(), dispatchers)
        receiverRepo = TransferRepositoryImpl(receiverDb.transferSessionDao(), dispatchers)
        senderHistoryRepo = TransferHistoryRepositoryImpl(senderDb.transferHistoryDao(), dispatchers)
        receiverHistoryRepo = TransferHistoryRepositoryImpl(receiverDb.transferHistoryDao(), dispatchers)

        senderIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        receiverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
    }

    @After
    fun tearDown() {
        senderDb.close()
        receiverDb.close()
    }

    private suspend fun openAuthenticatedLoopbackChannels(): LoopbackPair {
        val receiverTokens = PairingTokenManager(clock = clock)
        val senderTokens = PairingTokenManager(clock = clock)
        val receiverProtocol = PeerHandshakeProtocol(
            identityManager = receiverIdentity,
            pairingTokenManager = receiverTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val senderProtocol = PeerHandshakeProtocol(
            identityManager = senderIdentity,
            pairingTokenManager = senderTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort
        val payload = receiverTokens.createPairingPayload(
            localIdentity = receiverIdentity.getLocalIdentity(),
            displayName = "Receiver Pixel",
            hostAddress = "127.0.0.1",
            port = port,
            preferredConnectionType = ConnectionType.LOCAL_LAN_NSD
        )

        val serverJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            val accepted = serverSocket.accept()
            receiverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Receiver Pixel",
                requirePairingToken = true,
                trustedLookup = { _, _ -> false },
                approvalCallback = { true }
            )
        }
        val clientJob = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            senderProtocol.performClientHandshake(
                socket = socket,
                localDisplayName = "Sender Galaxy",
                targetTransport = ConnectionType.LOCAL_LAN_NSD,
                sessionId = payload.sessionId,
                pairingToken = payload.pairingToken,
                expectedServerFingerprint = payload.publicKeyFingerprint
            )
        }

        val serverRes = serverJob.await()
        val clientRes = clientJob.await()
        serverSocket.close()

        assertTrue("Server handshake must succeed: $serverRes", serverRes is AppResult.Success)
        assertTrue("Client handshake must succeed: $clientRes", clientRes is AppResult.Success)
        return LoopbackPair(
            senderChannel = (clientRes as AppResult.Success).data,
            receiverChannel = (serverRes as AppResult.Success).data
        )
    }

    private fun createEnginePair(
        receiverFreeBytes: Long = 512L * 1024L * 1024L
    ): Triple<TransferSender, TransferReceiver, TransferRecoveryManager> {
        val dataSource = AndroidTransferDataSource(context)
        val senderProcessor = ChunkProcessor(dataSource, FixedStorageChecker(512L * 1024L * 1024L))
        val receiverProcessor = ChunkProcessor(dataSource, FixedStorageChecker(receiverFreeBytes))
        val verifier = TransferVerifier(Sha256ChecksumVerifier(), dataSource)

        val sender = TransferSender(
            chunkProcessor = senderProcessor,
            verifier = verifier,
            transferRepository = senderRepo,
            clock = clock
        )
        val receiverRecovery = TransferRecoveryManager(
            transferRepository = receiverRepo,
            dataSource = dataSource,
            verifier = verifier,
            chunkProcessor = receiverProcessor,
            clock = clock
        )
        val receiver = TransferReceiver(
            chunkProcessor = receiverProcessor,
            verifier = verifier,
            recoveryManager = receiverRecovery,
            transferRepository = receiverRepo,
            clock = clock
        )
        return Triple(sender, receiver, receiverRecovery)
    }

    private fun createDeterministicFile(parent: File, relativePath: String, sizeBytes: Int, seed: Long): File {
        val target = File(parent, relativePath)
        target.parentFile?.mkdirs()
        val rng = Random(seed)
        val buffer = ByteArray(16 * 1024)
        var remaining = sizeBytes
        FileOutputStream(target).use { out ->
            while (remaining > 0) {
                val toWrite = minOf(remaining, buffer.size)
                rng.nextBytes(buffer)
                out.write(buffer, 0, toWrite)
                remaining -= toWrite
            }
        }
        return target
    }

    private fun buildSession(
        sessionId: String,
        peer: com.example.shareflow.domain.model.PeerDevice,
        files: List<Pair<File, String>>,
        chunkSizeBytes: Int
    ): TransferSession {
        val now = clock.nowEpochMillis()
        val items = files.mapIndexed { idx, (file, relPath) ->
            TransferItem(
                itemId = "${sessionId}_$idx",
                sessionId = sessionId,
                fileName = file.name,
                relativePath = relPath,
                mimeType = "application/octet-stream",
                fileType = TransferFileType.DOCUMENT,
                fileSizeBytes = file.length(),
                chunkSizeBytes = chunkSizeBytes,
                totalChunks = ChunkProcessor.calculateTotalChunks(file.length(), chunkSizeBytes),
                sourceUri = Uri.fromFile(file).toString(),
                state = TransferState.PENDING,
                isRecoverable = true,
                startedAtEpochMillis = now
            )
        }
        return TransferSession(
            sessionId = sessionId,
            peer = peer,
            direction = TransferDirection.SENT,
            state = TransferState.PENDING,
            transferItems = items,
            chunkSizeBytes = chunkSizeBytes,
            isResumable = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
    }

    @Test
    fun `small file transfer completes bit-exact with SHA-256 verification and atomic Room commit`() = runBlocking {
        val srcRoot = tempFolder.newFolder("small_src")
        val dstRoot = tempFolder.newFolder("small_dst")
        val sourceFile = createDeterministicFile(srcRoot, "hello.txt", 4096, seed = 101L)

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024)
        val session = buildSession("tx_small_1", pair.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val observedSenderStates = CopyOnWriteArrayList<TransferState>()
        val receiverDeferred = async(Dispatchers.IO) {
            receiver.receiveNextSession(pair.receiverChannel, dstRoot, config)
        }
        val senderDeferred = async(Dispatchers.IO) {
            sender.sendSession(pair.senderChannel, session, config) { updated ->
                observedSenderStates.add(updated.state)
            }
        }

        val recvRes = receiverDeferred.await()
        val sendRes = senderDeferred.await()
        pair.closeQuietly()

        assertTrue("Sender should complete: $sendRes", sendRes is AppResult.Success)
        assertTrue("Receiver should complete: $recvRes", recvRes is AppResult.Success)

        val completedSender = (sendRes as AppResult.Success).data
        val completedReceiver = (recvRes as AppResult.Success).data
        assertEquals(TransferState.COMPLETED, completedSender.state)
        assertEquals(TransferState.COMPLETED, completedReceiver.state)
        assertTrue(observedSenderStates.contains(TransferState.TRANSFERRING))
        assertTrue(observedSenderStates.contains(TransferState.COMPLETED))

        val receivedFile = File(dstRoot, "hello.txt")
        assertTrue(receivedFile.exists())
        assertArrayEquals(sourceFile.readBytes(), receivedFile.readBytes())

        // Verify Room persistence for both session/items AND atomic transfer_history commit
        val persistedReceiverSession = receiverRepo.getSessionById("tx_small_1")
        assertNotNull(persistedReceiverSession)
        assertEquals(TransferState.COMPLETED, persistedReceiverSession!!.state)
        val verifier = TransferVerifier(Sha256ChecksumVerifier(), AndroidTransferDataSource(context))
        val expectedHash = (verifier.computeSourceFileSha256(Uri.fromFile(sourceFile).toString()) as AppResult.Success<String>).data
        assertEquals(expectedHash, persistedReceiverSession.transferItems.single().verifiedSha256)

        val receiverHistory = receiverHistoryRepo.observeAllRecords().first()
        assertEquals(1, receiverHistory.size)
        assertEquals(TransferStatus.COMPLETED_VERIFIED, receiverHistory.single().status)
        assertTrue(receiverHistory.single().isChecksumVerified)
        assertEquals(expectedHash, receiverHistory.single().sha256Checksum)

        val senderHistory = senderHistoryRepo.observeAllRecords().first()
        assertEquals(1, senderHistory.size)
        assertEquals(TransferStatus.COMPLETED_VERIFIED, senderHistory.single().status)
    }

    @Test
    fun `large file transfer streams across many chunks with exact SHA-256 verification`() = runBlocking {
        val srcRoot = tempFolder.newFolder("large_src")
        val dstRoot = tempFolder.newFolder("large_dst")
        // 2.65 MB -> 43 chunks at 64 KB chunk size, unaligned tail chunk
        val sizeBytes = 2 * 1024 * 1024 + 665 * 1024 + 137
        val sourceFile = createDeterministicFile(srcRoot, "video_master.bin", sizeBytes, seed = 202L)

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024, maxInFlightChunks = 4)
        val session = buildSession("tx_large_1", pair.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val receiverDeferred = async(Dispatchers.IO) {
            receiver.receiveNextSession(pair.receiverChannel, dstRoot, config)
        }
        val senderDeferred = async(Dispatchers.IO) {
            sender.sendSession(pair.senderChannel, session, config)
        }

        val recvRes = receiverDeferred.await()
        val sendRes = senderDeferred.await()
        pair.closeQuietly()

        assertTrue("Large file send failed: $sendRes", sendRes is AppResult.Success)
        assertTrue("Large file receive failed: $recvRes", recvRes is AppResult.Success)

        val receivedFile = File(dstRoot, "video_master.bin")
        assertEquals(sourceFile.length(), receivedFile.length())

        val verifier = TransferVerifier(Sha256ChecksumVerifier(), AndroidTransferDataSource(context))
        val srcHash = (verifier.computeSourceFileSha256(Uri.fromFile(sourceFile).toString()) as AppResult.Success<String>).data
        val dstHash = (verifier.computeSourceFileSha256(Uri.fromFile(receivedFile).toString()) as AppResult.Success<String>).data
        assertEquals(srcHash, dstHash)
    }

    @Test
    fun `multiple files and nested directory hierarchy are preserved exactly on receiver`() = runBlocking {
        val srcRoot = tempFolder.newFolder("dir_src")
        val dstRoot = tempFolder.newFolder("dir_dst")
        val projectRoot = File(srcRoot, "Vacation2026")
        val f1 = createDeterministicFile(projectRoot, "readme.txt", 1200, seed = 301L)
        val f2 = createDeterministicFile(projectRoot, "Day1/beach.jpg", 95_000, seed = 302L)
        val f3 = createDeterministicFile(projectRoot, "Day2/Evening/dinner.mp4", 180_000, seed = 303L)

        val expanded = TransferQueue.expandLocalDirectory(projectRoot)
        assertEquals(3, expanded.size)

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024)
        val now = clock.nowEpochMillis()
        val items = expanded.mapIndexed { idx, src ->
            TransferItem(
                itemId = "tx_dir_1_$idx",
                sessionId = "tx_dir_1",
                fileName = src.fileName,
                relativePath = src.relativePath,
                mimeType = src.mimeType,
                fileType = src.fileType,
                fileSizeBytes = src.fileSizeBytes,
                chunkSizeBytes = config.chunkSizeBytes,
                totalChunks = ChunkProcessor.calculateTotalChunks(src.fileSizeBytes, config.chunkSizeBytes),
                sourceUri = src.uriString,
                state = TransferState.PENDING,
                isRecoverable = true,
                startedAtEpochMillis = now
            )
        }
        val session = TransferSession(
            sessionId = "tx_dir_1",
            peer = pair.senderChannel.remotePeer,
            direction = TransferDirection.SENT,
            state = TransferState.PENDING,
            transferItems = items,
            chunkSizeBytes = config.chunkSizeBytes,
            isResumable = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )

        val recvJob = async(Dispatchers.IO) { receiver.receiveNextSession(pair.receiverChannel, dstRoot, config) }
        val sendJob = async(Dispatchers.IO) { sender.sendSession(pair.senderChannel, session, config) }
        val recvRes = recvJob.await()
        val sendRes = sendJob.await()
        pair.closeQuietly()

        assertTrue(sendRes is AppResult.Success)
        assertTrue(recvRes is AppResult.Success)

        val dstF1 = File(dstRoot, "Vacation2026/readme.txt")
        val dstF2 = File(dstRoot, "Vacation2026/Day1/beach.jpg")
        val dstF3 = File(dstRoot, "Vacation2026/Day2/Evening/dinner.mp4")
        assertTrue(dstF1.exists())
        assertTrue(dstF2.exists())
        assertTrue(dstF3.exists())
        assertArrayEquals(f1.readBytes(), dstF1.readBytes())
        assertArrayEquals(f2.readBytes(), dstF2.readBytes())
        assertArrayEquals(f3.readBytes(), dstF3.readBytes())
        assertEquals(3, receiverHistoryRepo.observeTotalCount().first())
    }

    @Test
    fun `pause and resume mid-transfer preserves progress and finishes SHA-256 verified`() = runBlocking {
        val srcRoot = tempFolder.newFolder("pause_src")
        val dstRoot = tempFolder.newFolder("pause_dst")
        val sourceFile = createDeterministicFile(srcRoot, "podcast.mp3", 1024 * 1024, seed = 404L)

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024, maxInFlightChunks = 2)
        val session = buildSession("tx_pause_1", pair.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val handle = TransferControlHandle()
        val pausedOnce = AtomicBoolean(false)
        val observedPausedState = AtomicBoolean(false)

        val recvJob = async(Dispatchers.IO) { receiver.receiveNextSession(pair.receiverChannel, dstRoot, config) }
        val sendJob = async(Dispatchers.IO) {
            sender.sendSession(pair.senderChannel, session, config, handle) { updated ->
                if (updated.state == TransferState.PAUSED) {
                    observedPausedState.set(true)
                }
                if (updated.transferredBytes >= 128 * 1024 && pausedOnce.compareAndSet(false, true)) {
                    handle.pause()
                }
            }
        }

        // Wait until paused state is observed, then resume cleanly
        var waited = 0
        while (!observedPausedState.get() && waited < 40) {
            delay(50)
            waited++
        }
        assertTrue("Transfer should enter PAUSED state", observedPausedState.get())
        delay(80)
        handle.resume()

        val recvRes = recvJob.await()
        val sendRes = sendJob.await()
        pair.closeQuietly()

        assertTrue("Resumed send should succeed: $sendRes", sendRes is AppResult.Success)
        assertTrue("Resumed receive should succeed: $recvRes", recvRes is AppResult.Success)
        val dstFile = File(dstRoot, "podcast.mp3")
        assertArrayEquals(sourceFile.readBytes(), dstFile.readBytes())
    }

    @Test
    fun `network interruption and reconnection recovery verifies partial staging file and resumes cleanly`() = runBlocking {
        val srcRoot = tempFolder.newFolder("drop_src")
        val dstRoot = tempFolder.newFolder("drop_dst")
        // 1.5 MB -> 24 chunks at 64 KB
        val sourceFile = createDeterministicFile(srcRoot, "backup.tar", 1536 * 1024, seed = 505L)

        val pair1 = openAuthenticatedLoopbackChannels()
        val (sender, receiver, receiverRecovery) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024, maxInFlightChunks = 2)
        val session = buildSession("tx_drop_1", pair1.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val droppedOnce = AtomicBoolean(false)

        val recvJob1 = async(Dispatchers.IO) {
            receiver.receiveNextSession(pair1.receiverChannel, dstRoot, config) { updated ->
                if (updated.transferredBytes >= 384 * 1024 && droppedOnce.compareAndSet(false, true)) {
                    // Simulate abrupt Wi-Fi drop mid-transfer
                    runCatching { pair1.receiverChannel.close() }
                    runCatching { pair1.senderChannel.close() }
                }
            }
        }
        val sendJob1 = async(Dispatchers.IO) {
            sender.sendSession(pair1.senderChannel, session, config)
        }

        val recvRes1 = recvJob1.await()
        val sendRes1 = sendJob1.await()
        pair1.closeQuietly()

        assertTrue("First attempt should fail due to dropped socket", sendRes1 is AppResult.Failure || recvRes1 is AppResult.Failure)

        // Audit interrupted receiver session via TransferRecoveryManager — must verify real .part on disk
        val receiverReports = receiverRecovery.inspectAndRecoverInterruptedSessions()
        assertEquals(1, receiverReports.size)
        val receiverReport = receiverReports.single()
        assertTrue("Receiver partial staging file should verify as resumable: $receiverReport", receiverReport.isResumable)
        assertEquals(1, receiverReport.verifiedResumableItems)

        val persistedReceiverPartial = receiverRepo.getSessionById("tx_drop_1")!!
        assertEquals(TransferState.PAUSED, persistedReceiverPartial.state)
        assertTrue(persistedReceiverPartial.transferItems.single().nextChunkIndex > 0)
        assertEquals(0, receiverHistoryRepo.observeTotalCount().first()) // Must NOT be marked completed yet!

        // Reconnect fresh authenticated channels between the same verified cryptographic identities
        val pair2 = openAuthenticatedLoopbackChannels()
        val persistedSenderSession = senderRepo.getSessionById("tx_drop_1")!!
        assertTrue(receiverRecovery.canResumeWithPeer(persistedReceiverPartial, pair2.receiverChannel.remotePeer))

        val recvJob2 = async(Dispatchers.IO) { receiver.receiveNextSession(pair2.receiverChannel, dstRoot, config) }
        val sendJob2 = async(Dispatchers.IO) { sender.sendSession(pair2.senderChannel, persistedSenderSession, config) }

        val recvRes2 = recvJob2.await()
        val sendRes2 = sendJob2.await()
        pair2.closeQuietly()

        assertTrue("Recovered send must complete: $sendRes2", sendRes2 is AppResult.Success)
        assertTrue("Recovered receive must complete: $recvRes2", recvRes2 is AppResult.Success)

        val finalFile = File(dstRoot, "backup.tar")
        assertTrue(finalFile.exists())
        assertArrayEquals(sourceFile.readBytes(), finalFile.readBytes())
        assertEquals(1, receiverHistoryRepo.observeTotalCount().first())
    }

    @Test
    fun `corrupted chunk payload is rejected by receiver and never marked completed`() = runBlocking {
        val dstRoot = tempFolder.newFolder("corrupt_dst")
        val pair = openAuthenticatedLoopbackChannels()
        val (_, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024)

        val originalPayload = ByteArray(1024) { (it % 251).toByte() }
        val expectedDigestBytes = MessageDigest.getInstance("SHA-256").digest(originalPayload)
        val expectedSha256 = expectedDigestBytes.joinToString("") { "%02x".format(it) }

        val recvJob = async(Dispatchers.IO) {
            receiver.receiveNextSession(pair.receiverChannel, dstRoot, config)
        }

        // Send a valid offer manually over the encrypted channel
        val offer = TransferWireProtocol.TransferOffer(
            sessionId = "tx_corrupt_1",
            chunkSizeBytes = 64 * 1024,
            files = listOf(
                TransferWireProtocol.OfferFileDescriptor(
                    itemId = "tx_corrupt_1_0",
                    fileName = "tampered.bin",
                    relativePath = "",
                    mimeType = "application/octet-stream",
                    fileType = TransferFileType.OTHER,
                    fileSizeBytes = originalPayload.size.toLong(),
                    totalChunks = 1,
                    expectedSha256 = expectedSha256
                )
            )
        )
        assertTrue(
            pair.senderChannel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_OFFER,
                TransferWireProtocol.encodeOffer(offer)
            ) is AppResult.Success
        )

        val acceptFrame = (pair.senderChannel.receiveEncryptedFrame() as AppResult.Success).data
        assertEquals(EncryptedPeerChannel.FRAME_TRANSFER_OFFER_RESPONSE, acceptFrame.frameType)

        // Tamper with payload bytes while keeping original chunk SHA-256 header
        val corruptedBytes = originalPayload.copyOf().apply { this[0] = (this[0].toInt() xor 0xFF).toByte() }
        val badChunkFrame = TransferWireProtocol.encodeChunkPacket(
            TransferWireProtocol.ChunkPacket(
                itemId = "tx_corrupt_1_0",
                chunkIndex = 0,
                byteOffset = 0L,
                chunkSha256 = expectedDigestBytes,
                data = corruptedBytes
            )
        )
        pair.senderChannel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_TRANSFER_CHUNK, badChunkFrame)

        // Receiver MUST respond with NACK (accepted = false)
        val ackFrame = (pair.senderChannel.receiveEncryptedFrame() as AppResult.Success).data
        assertEquals(EncryptedPeerChannel.FRAME_TRANSFER_CHUNK_ACK, ackFrame.frameType)
        val ack = (TransferWireProtocol.decodeChunkAck(ackFrame.payload) as AppResult.Success).data
        assertFalse("Corrupted chunk MUST be rejected with NACK", ack.accepted)
        assertEquals(0, ack.chunkIndex)

        // Close sender without sending a valid chunk; receiver must NOT mark file completed
        pair.senderChannel.close()
        val recvRes = recvJob.await()
        pair.closeQuietly()

        assertTrue("Corrupted incomplete transfer must fail", recvRes is AppResult.Failure)
        assertFalse("Final destination file must NOT exist", File(dstRoot, "tampered.bin").exists())
        assertEquals(0, receiverHistoryRepo.observeTotalCount().first())
        val persisted = receiverRepo.getSessionById("tx_corrupt_1")
        assertTrue(persisted == null || persisted.state != TransferState.COMPLETED)
    }

    @Test
    fun `duplicate filename never overwrites existing destination file and creates numbered copy`() = runBlocking {
        val srcRoot = tempFolder.newFolder("dup_src")
        val dstRoot = tempFolder.newFolder("dup_dst")

        val existingFile = File(dstRoot, "invoice.pdf").apply { writeText("ORIGINAL_DO_NOT_OVERWRITE") }
        val existingSecond = File(dstRoot, "invoice (1).pdf").apply { writeText("SECOND_DO_NOT_OVERWRITE") }
        val newSource = File(srcRoot, "invoice.pdf").apply { writeText("NEW_INCOMING_CONTENT_2026") }

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024)
        val session = buildSession("tx_dup_1", pair.senderChannel.remotePeer, listOf(newSource to ""), config.chunkSizeBytes)

        val recvJob = async(Dispatchers.IO) { receiver.receiveNextSession(pair.receiverChannel, dstRoot, config) }
        val sendJob = async(Dispatchers.IO) { sender.sendSession(pair.senderChannel, session, config) }
        val recvRes = recvJob.await()
        val sendRes = sendJob.await()
        pair.closeQuietly()

        assertTrue(sendRes is AppResult.Success)
        assertTrue(recvRes is AppResult.Success)

        assertEquals("ORIGINAL_DO_NOT_OVERWRITE", existingFile.readText())
        assertEquals("SECOND_DO_NOT_OVERWRITE", existingSecond.readText())
        val createdCopy = File(dstRoot, "invoice (2).pdf")
        assertTrue("Receiver should create non-overwriting invoice (2).pdf", createdCopy.exists())
        assertEquals("NEW_INCOMING_CONTENT_2026", createdCopy.readText())
    }

    @Test
    fun `low available storage rejects incoming transfer offer cleanly before writing any data`() = runBlocking {
        val srcRoot = tempFolder.newFolder("low_src")
        val dstRoot = tempFolder.newFolder("low_dst")
        val sourceFile = createDeterministicFile(srcRoot, "big_movie.mkv", 256 * 1024, seed = 707L)

        val pair = openAuthenticatedLoopbackChannels()
        // Receiver only reports 100 KB free storage (below 256 KB + 4 MB safety headroom)
        val (sender, receiver, _) = createEnginePair(receiverFreeBytes = 100 * 1024L)
        val config = TransferConfig(chunkSizeBytes = 64 * 1024)
        val session = buildSession("tx_low_1", pair.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val recvJob = async(Dispatchers.IO) { receiver.receiveNextSession(pair.receiverChannel, dstRoot, config) }
        val sendJob = async(Dispatchers.IO) { sender.sendSession(pair.senderChannel, session, config) }
        val recvRes = recvJob.await()
        val sendRes = sendJob.await()
        pair.closeQuietly()

        assertTrue("Receiver must fail on insufficient storage", recvRes is AppResult.Failure)
        assertTrue("Sender must receive storage rejection failure", sendRes is AppResult.Failure)
        assertFalse(File(dstRoot, "big_movie.mkv").exists())
        assertEquals(0, receiverHistoryRepo.observeTotalCount().first())
    }

    @Test
    fun `cancellation mid-transfer marks session cancelled cleans up staging file and never completes`() = runBlocking {
        val srcRoot = tempFolder.newFolder("cancel_src")
        val dstRoot = tempFolder.newFolder("cancel_dst")
        val sourceFile = createDeterministicFile(srcRoot, "cancel_me.bin", 1024 * 1024, seed = 808L)

        val pair = openAuthenticatedLoopbackChannels()
        val (sender, receiver, _) = createEnginePair()
        val config = TransferConfig(chunkSizeBytes = 64 * 1024, maxInFlightChunks = 2)
        val session = buildSession("tx_cancel_1", pair.senderChannel.remotePeer, listOf(sourceFile to ""), config.chunkSizeBytes)

        val handle = TransferControlHandle()
        val cancelledOnce = AtomicBoolean(false)

        val recvJob = async(Dispatchers.IO) { receiver.receiveNextSession(pair.receiverChannel, dstRoot, config) }
        val sendJob = async(Dispatchers.IO) {
            sender.sendSession(pair.senderChannel, session, config, handle) { updated ->
                if (updated.transferredBytes >= 128 * 1024 && cancelledOnce.compareAndSet(false, true)) {
                    handle.cancel()
                }
            }
        }

        val recvRes = recvJob.await()
        val sendRes = sendJob.await()
        pair.closeQuietly()

        assertTrue("Cancelled send should report failure/cancellation", sendRes is AppResult.Failure)
        assertTrue("Cancelled receive should report failure/cancellation", recvRes is AppResult.Failure)

        val senderPersisted = senderRepo.getSessionById("tx_cancel_1")
        val receiverPersisted = receiverRepo.getSessionById("tx_cancel_1")
        assertEquals(TransferState.CANCELLED, senderPersisted?.state)
        assertEquals(TransferState.CANCELLED, receiverPersisted?.state)
        assertFalse(File(dstRoot, "cancel_me.bin").exists())
        val stagingDir = File(dstRoot, ".shareflow_staging")
        val leftoverPartFiles = stagingDir.listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()
        assertTrue("Staging .part file must be cleaned up on cancel", leftoverPartFiles.isEmpty())
        assertEquals(0, receiverHistoryRepo.observeTotalCount().first())
    }

    @Test
    fun `recovery manager refuses to resume missing or corrupted partial staging files`() = runBlocking {
        val dstRoot = tempFolder.newFolder("recovery_audit")
        val corruptedPart = File(dstRoot, "corrupt.part").apply {
            // Recorded 128 KB (2 chunks of 64 KB), but file on disk is truncated to 5000 bytes!
            writeBytes(ByteArray(5000) { 0x42 })
        }

        val now = clock.nowEpochMillis()
        val session = TransferSession(
            sessionId = "tx_corrupt_partial",
            peer = com.example.shareflow.domain.model.PeerDevice(
                deviceId = "peer-abc",
                displayName = "Other Phone",
                connectionType = ConnectionType.LOCAL_LAN_NSD,
                publicKeyFingerprint = "f".repeat(64)
            ),
            direction = TransferDirection.RECEIVED,
            state = TransferState.TRANSFERRING,
            transferItems = listOf(
                TransferItem(
                    itemId = "tx_corrupt_partial_0",
                    sessionId = "tx_corrupt_partial",
                    fileName = "archive.zip",
                    mimeType = "application/zip",
                    fileType = TransferFileType.ARCHIVE,
                    fileSizeBytes = 256 * 1024L,
                    bytesTransferred = 128 * 1024L,
                    chunkSizeBytes = 64 * 1024,
                    totalChunks = 4,
                    nextChunkIndex = 2,
                    tempFilePath = corruptedPart.absolutePath,
                    state = TransferState.TRANSFERRING,
                    isRecoverable = true,
                    startedAtEpochMillis = now
                )
            ),
            chunkSizeBytes = 64 * 1024,
            isResumable = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )
        receiverRepo.saveSession(session)

        val (_, _, recoveryManager) = createEnginePair()
        val reports = recoveryManager.inspectAndRecoverInterruptedSessions()
        assertEquals(1, reports.size)
        val report = reports.single()
        assertEquals(0, report.verifiedResumableItems)
        assertEquals(1, report.resetToZeroItems)
        assertFalse("Corrupted partial staging file must be deleted", corruptedPart.exists())

        val audited = receiverRepo.getSessionById("tx_corrupt_partial")!!
        assertEquals(0, audited.transferItems.single().nextChunkIndex)
        assertEquals(0L, audited.transferItems.single().bytesTransferred)
    }
}
