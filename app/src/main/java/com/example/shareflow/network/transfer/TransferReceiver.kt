package com.example.shareflow.network.transfer

import android.net.Uri
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.IncomingTransferOffer
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.repository.TransferRepository
import com.example.shareflow.network.security.EncryptedPeerChannel
import java.io.File
import java.util.BitSet

/**
 * Optional approval callback when an authenticated peer sends a file transfer offer.
 */
fun interface IncomingTransferOfferCallback {
    suspend fun awaitAcceptance(offer: IncomingTransferOffer): Boolean
}

/**
 * Streaming, integrity-enforced file transfer receiver operating over [EncryptedPeerChannel].
 * Validates metadata, checks free storage space, verifies every chunk's SHA-256, prevents duplicate
 * overwrites, and ONLY marks a file COMPLETED after:
 * 1. All expected chunks arrive,
 * 2. The destination file is finalized safely without overwriting existing files,
 * 3. Streaming SHA-256 verification succeeds, and
 * 4. The atomic Room database transaction commits.
 */
class TransferReceiver(
    private val chunkProcessor: ChunkProcessor,
    private val verifier: TransferVerifier,
    private val recoveryManager: TransferRecoveryManager,
    private val transferRepository: TransferRepository,
    private val clock: Clock = SystemClock()
) {
    /**
     * Handles a single incoming transfer session starting from an already-received [offerPayload]
     * OR waits for the next `FRAME_TRANSFER_OFFER` on [channel].
     */
    suspend fun receiveNextSession(
        channel: EncryptedPeerChannel,
        destinationRoot: File,
        config: TransferConfig = TransferConfig(),
        controlHandle: TransferControlHandle = TransferControlHandle(),
        offerApprovalCallback: IncomingTransferOfferCallback = IncomingTransferOfferCallback { true },
        progressListener: TransferProgressListener = TransferProgressListener {}
    ): AppResult<TransferSession> {
        channel.setReadTimeout(IDLE_WAIT_TIMEOUT_MS)
        val firstFrameRes = channel.receiveEncryptedFrame()
        if (firstFrameRes is AppResult.Failure) {
            return AppResult.Failure(AppError.Network("Channel closed while waiting for transfer offer"))
        }
        val firstFrame = (firstFrameRes as AppResult.Success).data
        if (firstFrame.frameType == EncryptedPeerChannel.FRAME_DISCONNECT) {
            return AppResult.Failure(AppError.Network("Peer disconnected cleanly"))
        }
        if (firstFrame.frameType != EncryptedPeerChannel.FRAME_TRANSFER_OFFER) {
            return AppResult.Failure(AppError.Validation("Expected FRAME_TRANSFER_OFFER, got ${firstFrame.frameType}"))
        }
        return processOffer(
            channel = channel,
            offerPayload = firstFrame.payload,
            destinationRoot = destinationRoot,
            config = config,
            controlHandle = controlHandle,
            offerApprovalCallback = offerApprovalCallback,
            progressListener = progressListener
        )
    }

    suspend fun processOffer(
        channel: EncryptedPeerChannel,
        offerPayload: ByteArray,
        destinationRoot: File,
        config: TransferConfig = TransferConfig(),
        controlHandle: TransferControlHandle = TransferControlHandle(),
        offerApprovalCallback: IncomingTransferOfferCallback = IncomingTransferOfferCallback { true },
        progressListener: TransferProgressListener = TransferProgressListener {}
    ): AppResult<TransferSession> {
        val decoded = TransferWireProtocol.decodeAndValidateOffer(offerPayload)
        if (decoded is AppResult.Failure) {
            sendRejectOffer(channel, "", "INVALID_METADATA", decoded.error.message)
            return decoded
        }
        val offer = (decoded as AppResult.Success).data
        val now = clock.nowEpochMillis()
        val peer = channel.remotePeer

        // 1. Resolve verified resume checkpoints for each item (never trust unverified partials)
        val resumeDecisions = mutableListOf<TransferWireProtocol.ItemResumeDecision>()
        val items = mutableListOf<TransferItem>()
        var remainingBytesNeeded = 0L

        for (desc in offer.files) {
            val (resumeChunkIdx, resumeByteOffset) = recoveryManager.resolveVerifiedReceiverResumePoint(
                sessionId = offer.sessionId,
                peer = peer,
                itemDescriptor = desc,
                chunkSizeBytes = offer.chunkSizeBytes
            )
            val tempFile = chunkProcessor.resolveTempPartFile(destinationRoot, offer.sessionId, desc.itemId)
            val alreadyDone = resumeChunkIdx == desc.totalChunks &&
                resumeByteOffset == desc.fileSizeBytes &&
                desc.fileSizeBytes > 0L

            if (!alreadyDone) {
                remainingBytesNeeded += (desc.fileSizeBytes - resumeByteOffset).coerceAtLeast(0L)
            }
            resumeDecisions.add(
                TransferWireProtocol.ItemResumeDecision(
                    itemId = desc.itemId,
                    resumeChunkIndex = resumeChunkIdx,
                    resumeByteOffset = resumeByteOffset
                )
            )
            items.add(
                TransferItem(
                    itemId = desc.itemId,
                    sessionId = offer.sessionId,
                    fileName = desc.fileName,
                    relativePath = desc.relativePath,
                    mimeType = desc.mimeType,
                    fileType = desc.fileType,
                    fileSizeBytes = desc.fileSizeBytes,
                    bytesTransferred = resumeByteOffset,
                    chunkSizeBytes = offer.chunkSizeBytes,
                    totalChunks = desc.totalChunks,
                    nextChunkIndex = resumeChunkIdx,
                    expectedSha256 = desc.expectedSha256,
                    verifiedSha256 = if (alreadyDone) desc.expectedSha256 else null,
                    tempFilePath = tempFile.absolutePath,
                    state = if (alreadyDone) TransferState.COMPLETED else TransferState.WAITING_APPROVAL,
                    isRecoverable = !alreadyDone,
                    startedAtEpochMillis = now,
                    completedAtEpochMillis = if (alreadyDone) now else null
                )
            )
        }

        // 2. Enforce real available storage verification BEFORE accepting or writing bytes
        if (!chunkProcessor.hasSufficientStorage(destinationRoot, remainingBytesNeeded, config.storageSafetyMarginBytes)) {
            val available = chunkProcessor.getAvailableStorageBytes(destinationRoot)
            val msg = "Insufficient storage space: need $remainingBytesNeeded bytes (+margin), available $available bytes"
            sendRejectOffer(channel, offer.sessionId, "LOW_STORAGE", msg)
            return AppResult.Failure(AppError.Storage(msg))
        }

        // 3. Await explicit transfer acceptance if configured
        val offerModel = IncomingTransferOffer(
            sessionId = offer.sessionId,
            peer = peer,
            chunkSizeBytes = offer.chunkSizeBytes,
            items = items.toList(),
            totalBytes = items.sumOf { it.fileSizeBytes },
            receivedAtEpochMillis = now
        )
        val approved = offerApprovalCallback.awaitAcceptance(offerModel)
        if (!approved) {
            val msg = "Transfer offer declined by receiver"
            sendRejectOffer(channel, offer.sessionId, "USER_DECLINED", msg)
            return AppResult.Failure(AppError.PermissionDenied(msg))
        }

        val speedTracker = TransferSpeedTracker(clock = clock)
        val mutableItems = items.map { item ->
            if (item.state == TransferState.COMPLETED) item else item.copy(state = TransferState.PENDING)
        }.toMutableList()
        val itemIndexById = mutableItems.mapIndexed { idx, it -> it.itemId to idx }.toMap()

        var currentSession = TransferSession(
            sessionId = offer.sessionId,
            peer = peer,
            direction = TransferDirection.RECEIVED,
            state = TransferState.TRANSFERRING,
            transferItems = mutableItems.toList(),
            chunkSizeBytes = offer.chunkSizeBytes,
            destinationRootPath = destinationRoot.absolutePath,
            isResumable = true,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now
        )

        fun publish(
            state: TransferState = currentSession.state,
            error: String? = currentSession.errorMessage,
            isResumable: Boolean = currentSession.isResumable,
            completedAt: Long? = currentSession.completedAtEpochMillis
        ) {
            val speed = if (state == TransferState.TRANSFERRING) {
                speedTracker.currentSpeedBytesPerSec()
            } else {
                0L
            }
            val remaining = mutableItems.sumOf { (it.fileSizeBytes - it.bytesTransferred).coerceAtLeast(0L) }
            val eta = if (state == TransferState.TRANSFERRING) {
                speedTracker.estimateRemainingSeconds(remaining)
            } else {
                null
            }
            currentSession = currentSession.copy(
                state = state,
                transferItems = mutableItems.toList(),
                currentSpeedBytesPerSec = speed,
                estimatedRemainingSeconds = eta,
                errorMessage = error,
                isResumable = isResumable,
                updatedAtEpochMillis = clock.nowEpochMillis(),
                completedAtEpochMillis = completedAt
            )
            progressListener.onSessionUpdated(currentSession)
        }

        // Persist accepted session in Room before acknowledging sender
        transferRepository.saveSession(currentSession)
        publish(state = TransferState.TRANSFERRING, error = null, isResumable = true)

        val acceptPayload = TransferWireProtocol.encodeOfferResponse(
            TransferWireProtocol.TransferOfferResponse(
                sessionId = offer.sessionId,
                accepted = true,
                rejectCode = "",
                rejectMessage = "",
                resumeDecisions = resumeDecisions
            )
        )
        val sendAccept = channel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_TRANSFER_OFFER_RESPONSE, acceptPayload)
        if (sendAccept is AppResult.Failure) {
            return interruptSession(currentSession, mutableItems, "Lost connection sending transfer acceptance", ::publish)
        }

        val maxWindowByBytes = (MAX_IN_FLIGHT_WINDOW_BYTES / offer.chunkSizeBytes).coerceAtLeast(1)
        val windowCredits = minOf(config.validatedMaxInFlight, maxWindowByBytes)
        val lastCheckpointedChunk = HashMap<String, Int>()
        val verifiedChunksByItem = HashMap<String, BitSet>()
        mutableItems.forEach { item ->
            val bs = BitSet(item.totalChunks.coerceAtLeast(1))
            if (item.nextChunkIndex > 0) {
                bs.set(0, item.nextChunkIndex)
            }
            verifiedChunksByItem[item.itemId] = bs
        }

        channel.setReadTimeout(TRANSFER_IO_TIMEOUT_MS)

        // 4. Main streaming receive loop
        while (true) {
            if (controlHandle.isCancelled) {
                return cancelReceiverSession(channel, currentSession, mutableItems, ::publish)
            }

            val frameRes = channel.receiveEncryptedFrame()
            if (frameRes is AppResult.Failure) {
                // Network dropped mid-transfer: preserve verified .part staging files & Room checkpoints
                return interruptSession(
                    currentSession,
                    mutableItems,
                    "Network interrupted while receiving transfer",
                    ::publish
                )
            }
            val frame = (frameRes as AppResult.Success).data

            when (frame.frameType) {
                EncryptedPeerChannel.FRAME_TRANSFER_CHUNK -> {
                    val pktRes = TransferWireProtocol.decodeChunkPacket(frame.payload)
                    if (pktRes is AppResult.Failure) {
                        return failReceiverSession(currentSession, mutableItems, pktRes.error.message, false, ::publish)
                    }
                    val pkt = (pktRes as AppResult.Success).data
                    val idx = itemIndexById[pkt.itemId]
                        ?: return failReceiverSession(currentSession, mutableItems, "Unknown itemId ${pkt.itemId}", false, ::publish)
                    val item = mutableItems[idx]

                    val expectedOffset = pkt.chunkIndex.toLong() * offer.chunkSizeBytes.toLong()
                    if (pkt.byteOffset != expectedOffset ||
                        pkt.byteOffset + pkt.data.size.toLong() > item.fileSizeBytes
                    ) {
                        sendChunkAck(channel, pkt.itemId, pkt.chunkIndex, item.bytesTransferred, false, "OFFSET_OUT_OF_BOUNDS", windowCredits)
                        continue
                    }

                    val tempFile = File(
                        item.tempFilePath ?: chunkProcessor.resolveTempPartFile(destinationRoot, offer.sessionId, item.itemId).absolutePath
                    )
                    val writeRes = chunkProcessor.writeVerifiedChunkToTempFile(
                        tempFile = tempFile,
                        byteOffset = pkt.byteOffset,
                        data = pkt.data,
                        expectedChunkSha256 = pkt.chunkSha256
                    )

                    when (writeRes) {
                        is AppResult.Failure -> {
                            sendChunkAck(channel, pkt.itemId, pkt.chunkIndex, item.bytesTransferred, false, "CORRUPT_CHUNK", windowCredits)
                        }

                        is AppResult.Success -> {
                            speedTracker.recordBytes(pkt.data.size.toLong())
                            val bitSet = verifiedChunksByItem.getOrPut(item.itemId) { BitSet() }
                            bitSet.set(pkt.chunkIndex)
                            val contiguousChunks = bitSet.nextClearBit(0).coerceAtMost(item.totalChunks)
                            val contiguousBytes = if (contiguousChunks >= item.totalChunks) {
                                item.fileSizeBytes
                            } else {
                                (contiguousChunks.toLong() * offer.chunkSizeBytes.toLong())
                                    .coerceAtMost(item.fileSizeBytes)
                            }
                            val writtenBytes = maxOf(item.bytesTransferred, contiguousBytes)
                                .coerceAtMost(item.fileSizeBytes)

                            mutableItems[idx] = item.copy(
                                bytesTransferred = writtenBytes,
                                nextChunkIndex = contiguousChunks,
                                tempFilePath = tempFile.absolutePath,
                                state = TransferState.TRANSFERRING,
                                errorMessage = null,
                                isRecoverable = true
                            )

                            val prevCkpt = lastCheckpointedChunk[item.itemId] ?: 0
                            if (contiguousChunks - prevCkpt >= CHECKPOINT_INTERVAL_CHUNKS) {
                                lastCheckpointedChunk[item.itemId] = contiguousChunks
                                transferRepository.updateItemProgress(
                                    itemId = item.itemId,
                                    bytesTransferred = writtenBytes,
                                    nextChunkIndex = contiguousChunks,
                                    state = TransferState.TRANSFERRING,
                                    tempFilePath = tempFile.absolutePath,
                                    isRecoverable = true
                                )
                            }
                            publish(TransferState.TRANSFERRING, null, true, null)
                            sendChunkAck(channel, pkt.itemId, pkt.chunkIndex, writtenBytes, true, "", windowCredits)
                        }
                    }
                }

                EncryptedPeerChannel.FRAME_TRANSFER_FILE_DONE -> {
                    val doneRes = TransferWireProtocol.decodeFileDone(frame.payload)
                    if (doneRes is AppResult.Failure) {
                        return failReceiverSession(currentSession, mutableItems, doneRes.error.message, false, ::publish)
                    }
                    val doneMsg = (doneRes as AppResult.Success).data
                    val idx = itemIndexById[doneMsg.itemId]
                        ?: return failReceiverSession(currentSession, mutableItems, "Unknown itemId in FILE_DONE", false, ::publish)

                    val bitSet = verifiedChunksByItem[doneMsg.itemId]
                    val contiguousVerifiedCount = bitSet?.nextClearBit(0) ?: 0
                    val verifyOutcome = finalizeAndVerifyItem(
                        channel = channel,
                        session = currentSession,
                        item = mutableItems[idx],
                        destinationRoot = destinationRoot,
                        receivedChunkCount = contiguousVerifiedCount,
                        onUpdateItem = { updated ->
                            mutableItems[idx] = updated
                            publish(updated.state, updated.errorMessage, updated.isRecoverable, null)
                        }
                    )
                    if (verifyOutcome is AppResult.Failure) {
                        return failReceiverSession(
                            currentSession,
                            mutableItems,
                            verifyOutcome.error.message,
                            isResumable = false,
                            publish = ::publish
                        )
                    }
                }

                EncryptedPeerChannel.FRAME_TRANSFER_CONTROL -> {
                    val ctrlRes = TransferWireProtocol.decodeControl(frame.payload)
                    if (ctrlRes is AppResult.Success) {
                        when (ctrlRes.data.command) {
                            TransferWireProtocol.ControlCommand.PAUSE -> {
                                speedTracker.reset()
                                for (i in mutableItems.indices) {
                                    if (mutableItems[i].state == TransferState.TRANSFERRING) {
                                        mutableItems[i] = mutableItems[i].copy(state = TransferState.PAUSED)
                                        transferRepository.updateItemProgress(
                                            itemId = mutableItems[i].itemId,
                                            bytesTransferred = mutableItems[i].bytesTransferred,
                                            nextChunkIndex = mutableItems[i].nextChunkIndex,
                                            state = TransferState.PAUSED,
                                            tempFilePath = mutableItems[i].tempFilePath,
                                            isRecoverable = true
                                        )
                                    }
                                }
                                publish(TransferState.PAUSED, null, true, null)
                                transferRepository.updateSessionState(
                                    sessionId = currentSession.sessionId,
                                    state = TransferState.PAUSED,
                                    isResumable = true,
                                    updatedAtEpochMillis = clock.nowEpochMillis()
                                )
                                // Allow long pause wait without timing out socket prematurely
                                channel.setReadTimeout(PAUSED_WAIT_TIMEOUT_MS)
                            }

                            TransferWireProtocol.ControlCommand.RESUME -> {
                                channel.setReadTimeout(TRANSFER_IO_TIMEOUT_MS)
                                for (i in mutableItems.indices) {
                                    if (mutableItems[i].state == TransferState.PAUSED) {
                                        mutableItems[i] = mutableItems[i].copy(state = TransferState.TRANSFERRING)
                                    }
                                }
                                publish(TransferState.TRANSFERRING, null, true, null)
                                transferRepository.updateSessionState(
                                    sessionId = currentSession.sessionId,
                                    state = TransferState.TRANSFERRING,
                                    isResumable = true,
                                    updatedAtEpochMillis = clock.nowEpochMillis()
                                )
                            }

                            TransferWireProtocol.ControlCommand.CANCEL -> {
                                return cancelReceiverSession(channel, currentSession, mutableItems, ::publish, notifyPeer = false)
                            }

                            TransferWireProtocol.ControlCommand.RESEND_CHUNK -> Unit
                        }
                    }
                }

                EncryptedPeerChannel.FRAME_TRANSFER_SESSION_DONE -> {
                    val allCompleted = mutableItems.all { it.state == TransferState.COMPLETED }
                    val finalState = if (allCompleted) TransferState.COMPLETED else TransferState.FAILED
                    val completedAt = if (allCompleted) clock.nowEpochMillis() else null
                    publish(
                        state = finalState,
                        error = if (allCompleted) null else "Transfer session finished with incomplete files",
                        isResumable = !allCompleted,
                        completedAt = completedAt
                    )
                    transferRepository.updateSessionState(
                        sessionId = currentSession.sessionId,
                        state = finalState,
                        errorMessage = currentSession.errorMessage,
                        isResumable = !allCompleted,
                        updatedAtEpochMillis = clock.nowEpochMillis(),
                        completedAtEpochMillis = completedAt
                    )
                    return if (allCompleted) {
                        AppResult.Success(currentSession)
                    } else {
                        AppResult.Failure(AppError.IntegrityMismatch("Incomplete transfer session", "", ""))
                    }
                }

                EncryptedPeerChannel.FRAME_DISCONNECT -> {
                    return interruptSession(currentSession, mutableItems, "Peer disconnected before completion", ::publish)
                }

                else -> {
                    return failReceiverSession(
                        currentSession,
                        mutableItems,
                        "Protocol violation: unexpected frame type ${frame.frameType}",
                        isResumable = false,
                        publish = ::publish
                    )
                }
            }
        }
    }

    /**
     * Strict 4-step verification & completion pipeline:
     * 1. Verify all expected chunks & exact byte count arrived.
     * 2. Fsync staging file & finalize to a non-overwriting destination file.
     * 3. Verify finalized file SHA-256 against sender's expected hash.
     * 4. Commit Room transaction (transfer_items COMPLETED + transfer_history COMPLETED_VERIFIED).
     */
    private suspend fun finalizeAndVerifyItem(
        channel: EncryptedPeerChannel,
        session: TransferSession,
        item: TransferItem,
        destinationRoot: File,
        receivedChunkCount: Int,
        onUpdateItem: (TransferItem) -> Unit
    ): AppResult<TransferItem> {
        onUpdateItem(item.copy(state = TransferState.VERIFYING))

        // Step 1: Verify all expected chunks arrived
        if (receivedChunkCount < item.totalChunks) {
            val reason = "Missing chunks for ${item.fileName}: received=$receivedChunkCount expected=${item.totalChunks}"
            sendFileVerified(channel, item.itemId, false, "", reason)
            onUpdateItem(item.copy(state = TransferState.FAILED, errorMessage = reason, isRecoverable = true))
            return AppResult.Failure(AppError.IntegrityMismatch(reason, item.expectedSha256, ""))
        }

        val tempFile = File(
            item.tempFilePath ?: chunkProcessor.resolveTempPartFile(destinationRoot, session.sessionId, item.itemId).absolutePath
        )

        // Step 2a: Ensure staging file exists & exact byte length matches (handles 0-byte files too)
        val syncRes = chunkProcessor.syncTempFile(tempFile, item.fileSizeBytes)
        if (syncRes is AppResult.Failure) {
            sendFileVerified(channel, item.itemId, false, "", syncRes.error.message)
            onUpdateItem(item.copy(state = TransferState.FAILED, errorMessage = syncRes.error.message, isRecoverable = false))
            return syncRes
        }

        // Step 2b: Finalize atomically to a non-overwriting destination file preserving folder structure
        val finalizeRes = chunkProcessor.finalizeToNonOverwritingDestination(
            tempFile = tempFile,
            destinationRoot = destinationRoot,
            relativePath = item.relativePath,
            fileName = item.fileName
        )
        if (finalizeRes is AppResult.Failure) {
            sendFileVerified(channel, item.itemId, false, "", finalizeRes.error.message)
            onUpdateItem(item.copy(state = TransferState.FAILED, errorMessage = finalizeRes.error.message, isRecoverable = false))
            return finalizeRes
        }
        val finalizedFile = (finalizeRes as AppResult.Success).data

        // Step 3: Streaming SHA-256 verification on the finalized destination file
        val verifyRes = verifier.verifyFinalizedFile(finalizedFile, item.expectedSha256)
        if (verifyRes is AppResult.Failure) {
            // Integrity failed -> remove corrupted destination file so corrupted data is never kept
            runCatching { finalizedFile.delete() }
            val actualHash = (verifyRes.error as? AppError.IntegrityMismatch)?.actualHash ?: ""
            sendFileVerified(channel, item.itemId, false, actualHash, verifyRes.error.message)
            val failedItem = item.copy(
                state = TransferState.FAILED,
                errorMessage = verifyRes.error.message,
                isRecoverable = false
            )
            onUpdateItem(failedItem)
            transferRepository.upsertItem(failedItem)
            return verifyRes
        }
        val verifiedHash = (verifyRes as AppResult.Success).data

        // Step 4: Atomic Room DB transaction (item COMPLETED + verified TransferHistory record)
        val now = clock.nowEpochMillis()
        val completedItem = item.copy(
            fileName = finalizedFile.name,
            bytesTransferred = item.fileSizeBytes,
            nextChunkIndex = item.totalChunks,
            verifiedSha256 = verifiedHash,
            destinationUri = Uri.fromFile(finalizedFile).toString(),
            tempFilePath = null,
            state = TransferState.COMPLETED,
            errorMessage = null,
            isRecoverable = false,
            completedAtEpochMillis = now
        )
        val historyRecord = TransferRecord(
            id = completedItem.itemId,
            sessionId = session.sessionId,
            fileName = completedItem.displayPath,
            mimeType = completedItem.mimeType,
            fileType = completedItem.fileType,
            fileSizeBytes = completedItem.fileSizeBytes,
            bytesTransferred = completedItem.fileSizeBytes,
            direction = TransferDirection.RECEIVED,
            status = TransferStatus.COMPLETED_VERIFIED,
            peerDeviceId = session.peer.deviceId,
            peerDeviceName = session.peer.displayName,
            localUri = completedItem.destinationUri,
            sha256Checksum = verifiedHash,
            isChecksumVerified = true,
            startedAtEpochMillis = completedItem.startedAtEpochMillis,
            completedAtEpochMillis = now
        )
        val commitRes = transferRepository.commitVerifiedItemCompletion(
            completedItem = completedItem,
            historyRecord = historyRecord,
            sessionUpdatedAtEpochMillis = now
        )
        if (commitRes is AppResult.Failure) {
            sendFileVerified(channel, item.itemId, false, verifiedHash, commitRes.error.message)
            onUpdateItem(item.copy(state = TransferState.FAILED, errorMessage = commitRes.error.message, isRecoverable = false))
            return commitRes
        }

        // All 4 preconditions succeeded -> notify sender & update UI
        onUpdateItem(completedItem)
        sendFileVerified(channel, item.itemId, true, verifiedHash, "")
        return AppResult.Success(completedItem)
    }

    private fun sendRejectOffer(
        channel: EncryptedPeerChannel,
        sessionId: String,
        code: String,
        message: String
    ) {
        runCatching {
            channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_OFFER_RESPONSE,
                TransferWireProtocol.encodeOfferResponse(
                    TransferWireProtocol.TransferOfferResponse(
                        sessionId = sessionId,
                        accepted = false,
                        rejectCode = code,
                        rejectMessage = message,
                        resumeDecisions = emptyList()
                    )
                )
            )
        }
    }

    private fun sendChunkAck(
        channel: EncryptedPeerChannel,
        itemId: String,
        chunkIndex: Int,
        bytesReceived: Long,
        accepted: Boolean,
        errorCode: String,
        windowCredits: Int
    ) {
        channel.sendEncryptedFrame(
            EncryptedPeerChannel.FRAME_TRANSFER_CHUNK_ACK,
            TransferWireProtocol.encodeChunkAck(
                TransferWireProtocol.ChunkAckPacket(
                    itemId = itemId,
                    chunkIndex = chunkIndex,
                    bytesReceived = bytesReceived,
                    accepted = accepted,
                    errorCode = errorCode,
                    windowCredits = windowCredits
                )
            )
        )
    }

    private fun sendFileVerified(
        channel: EncryptedPeerChannel,
        itemId: String,
        verified: Boolean,
        actualSha256: String,
        errorMessage: String
    ) {
        runCatching {
            channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_FILE_VERIFIED,
                TransferWireProtocol.encodeFileVerified(
                    TransferWireProtocol.FileVerifiedMessage(
                        itemId = itemId,
                        verified = verified,
                        actualSha256 = actualSha256,
                        errorMessage = errorMessage
                    )
                )
            )
        }
    }

    private suspend fun interruptSession(
        session: TransferSession,
        mutableItems: MutableList<TransferItem>,
        reason: String,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<TransferSession> {
        for (i in mutableItems.indices) {
            if (mutableItems[i].state != TransferState.COMPLETED) {
                mutableItems[i] = mutableItems[i].copy(
                    state = TransferState.PAUSED,
                    errorMessage = reason,
                    isRecoverable = true
                )
            }
        }
        publish(TransferState.PAUSED, reason, true, null)
        transferRepository.saveSession(
            session.copy(
                state = TransferState.PAUSED,
                transferItems = mutableItems.toList(),
                errorMessage = reason,
                isResumable = true,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        )
        return AppResult.Failure(AppError.Network(reason))
    }

    private suspend fun cancelReceiverSession(
        channel: EncryptedPeerChannel,
        session: TransferSession,
        mutableItems: MutableList<TransferItem>,
        publish: (TransferState, String?, Boolean, Long?) -> Unit,
        notifyPeer: Boolean = true
    ): AppResult<TransferSession> {
        if (notifyPeer) {
            runCatching {
                channel.sendEncryptedFrame(
                    EncryptedPeerChannel.FRAME_TRANSFER_CONTROL,
                    TransferWireProtocol.encodeControl(
                        TransferWireProtocol.ControlMessage(session.sessionId, TransferWireProtocol.ControlCommand.CANCEL)
                    )
                )
            }
        }
        for (i in mutableItems.indices) {
            if (mutableItems[i].state != TransferState.COMPLETED) {
                chunkProcessor.cleanupTempFile(mutableItems[i].tempFilePath)
                mutableItems[i] = mutableItems[i].copy(
                    state = TransferState.CANCELLED,
                    tempFilePath = null,
                    errorMessage = "Cancelled",
                    isRecoverable = false
                )
            }
        }
        publish(TransferState.CANCELLED, "Transfer cancelled", false, null)
        transferRepository.saveSession(
            session.copy(
                state = TransferState.CANCELLED,
                transferItems = mutableItems.toList(),
                errorMessage = "Transfer cancelled",
                isResumable = false,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        )
        return AppResult.Failure(AppError.PermissionDenied("Transfer cancelled"))
    }

    private suspend fun failReceiverSession(
        session: TransferSession,
        mutableItems: MutableList<TransferItem>,
        reason: String,
        isResumable: Boolean,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<TransferSession> {
        for (i in mutableItems.indices) {
            if (mutableItems[i].state != TransferState.COMPLETED && mutableItems[i].state != TransferState.CANCELLED) {
                if (!isResumable) {
                    chunkProcessor.cleanupTempFile(mutableItems[i].tempFilePath)
                }
                mutableItems[i] = mutableItems[i].copy(
                    state = TransferState.FAILED,
                    errorMessage = reason,
                    isRecoverable = isResumable
                )
            }
        }
        publish(TransferState.FAILED, reason, isResumable, null)
        transferRepository.saveSession(
            session.copy(
                state = TransferState.FAILED,
                transferItems = mutableItems.toList(),
                errorMessage = reason,
                isResumable = isResumable,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        )
        return AppResult.Failure(AppError.Storage(reason))
    }

    companion object {
        private const val IDLE_WAIT_TIMEOUT_MS = 0 // Block cleanly until offer or socket close
        private const val TRANSFER_IO_TIMEOUT_MS = 30_000
        private const val PAUSED_WAIT_TIMEOUT_MS = 10 * 60 * 1_000
        private const val MAX_IN_FLIGHT_WINDOW_BYTES = 256 * 1024
        private const val CHECKPOINT_INTERVAL_CHUNKS = 8
    }
}
