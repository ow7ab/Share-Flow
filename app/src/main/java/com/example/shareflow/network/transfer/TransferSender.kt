package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.repository.TransferRepository
import com.example.shareflow.network.security.EncryptedPeerChannel
import com.example.shareflow.network.transfer.TransferWireProtocol.toOfferDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Shared thread-safe control handle allowing [TransferManager] to pause, resume, or cancel
 * an active [TransferSender] or [TransferReceiver] execution loop cleanly.
 */
class TransferControlHandle {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    fun cancel() {
        cancelled.set(true)
        paused.set(false)
    }
}

/**
 * Callback notified whenever real bytes, speed, ETA, or item/session state transitions occur.
 */
fun interface TransferProgressListener {
    fun onSessionUpdated(session: TransferSession)
}

/**
 * Streaming, backpressure-aware file transfer sender operating strictly over an authenticated
 * AES-256-GCM [EncryptedPeerChannel]. Transfers original raw bytes without compression,
 * checkpoints progress in Room, and marks items COMPLETED only after receiver verification + DB commit.
 */
class TransferSender(
    private val chunkProcessor: ChunkProcessor,
    private val verifier: TransferVerifier,
    private val transferRepository: TransferRepository,
    private val clock: Clock = SystemClock()
) {
    /**
     * Executes a complete or resumed multi-file / directory send session over [channel].
     */
    suspend fun sendSession(
        channel: EncryptedPeerChannel,
        initialSession: TransferSession,
        config: TransferConfig = TransferConfig(),
        controlHandle: TransferControlHandle = TransferControlHandle(),
        progressListener: TransferProgressListener = TransferProgressListener {}
    ): AppResult<TransferSession> {
        val chunkSize = config.validatedChunkSizeBytes
        val speedTracker = TransferSpeedTracker(clock = clock)
        val mutableItems = initialSession.transferItems.toMutableList()
        var currentSession = initialSession.copy(
            chunkSizeBytes = chunkSize,
            state = TransferState.PENDING,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )

        var lastProgressEmitEpochMs = 0L

        fun publish(
            state: TransferState = currentSession.state,
            error: String? = currentSession.errorMessage,
            isResumable: Boolean = currentSession.isResumable,
            completedAt: Long? = currentSession.completedAtEpochMillis
        ) {
            val now = clock.nowEpochMillis()
            val isSteadyProgressTick = state == TransferState.TRANSFERRING &&
                currentSession.state == TransferState.TRANSFERRING &&
                error == currentSession.errorMessage &&
                isResumable == currentSession.isResumable &&
                completedAt == currentSession.completedAtEpochMillis
            val remainingBytes = mutableItems.sumOf { item ->
                (item.fileSizeBytes - item.bytesTransferred).coerceAtLeast(0L)
            }
            if (isSteadyProgressTick &&
                remainingBytes > 0L &&
                lastProgressEmitEpochMs > 0L &&
                (now - lastProgressEmitEpochMs) < PROGRESS_EMIT_INTERVAL_MS
            ) {
                return
            }
            lastProgressEmitEpochMs = now

            val speed = if (state == TransferState.TRANSFERRING) {
                speedTracker.currentSpeedBytesPerSec()
            } else {
                0L
            }
            val eta = if (state == TransferState.TRANSFERRING) {
                speedTracker.estimateRemainingSeconds(remainingBytes)
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
                updatedAtEpochMillis = now,
                completedAtEpochMillis = completedAt
            )
            progressListener.onSessionUpdated(currentSession)
        }

        // 1. Pre-compute streaming SHA-256 & chunk counts for every item that lacks one
        for (i in mutableItems.indices) {
            if (controlHandle.isCancelled) {
                return cancelSession(channel, currentSession, mutableItems, publish = ::publish)
            }
            val item = mutableItems[i]
            val uri = item.sourceUri
                ?: return failSession(
                    currentSession,
                    mutableItems,
                    "Missing source URI for ${item.fileName}",
                    isResumable = false,
                    publish = ::publish
                )
            val totalChunks = ChunkProcessor.calculateTotalChunks(item.fileSizeBytes, chunkSize)
            val expectedHash = if (item.expectedSha256.length == 64) {
                item.expectedSha256
            } else {
                when (val hashRes = verifier.computeSourceFileSha256(uri)) {
                    is AppResult.Success -> hashRes.data
                    is AppResult.Failure -> return failSession(
                        currentSession,
                        mutableItems,
                        "Unable to hash ${item.fileName}: ${hashRes.error.message}",
                        isResumable = false,
                        publish = ::publish
                    )
                }
            }
            mutableItems[i] = item.copy(
                chunkSizeBytes = chunkSize,
                totalChunks = totalChunks,
                expectedSha256 = expectedHash
            )
        }

        // 2. Persist initial offer state to Room & send FRAME_TRANSFER_OFFER
        publish(state = TransferState.WAITING_APPROVAL, error = null, isResumable = true)
        transferRepository.saveSession(currentSession)

        val offer = TransferWireProtocol.TransferOffer(
            sessionId = currentSession.sessionId,
            chunkSizeBytes = chunkSize,
            files = mutableItems.map { it.toOfferDescriptor() }
        )
        val sendOfferRes = channel.sendEncryptedFrame(
            EncryptedPeerChannel.FRAME_TRANSFER_OFFER,
            TransferWireProtocol.encodeOffer(offer)
        )
        if (sendOfferRes is AppResult.Failure) {
            return failSession(
                currentSession,
                mutableItems,
                "Failed to transmit transfer offer: ${sendOfferRes.error.message}",
                isResumable = true,
                publish = ::publish
            )
        }

        // 3. Await receiver's offer acceptance + verified per-item resume offsets
        channel.setReadTimeout(OFFER_RESPONSE_TIMEOUT_MS)
        val responseFrameRes = channel.receiveEncryptedFrame()
        if (responseFrameRes is AppResult.Failure) {
            return failSession(
                currentSession,
                mutableItems,
                "Peer did not respond to transfer offer: ${responseFrameRes.error.message}",
                isResumable = true,
                publish = ::publish
            )
        }
        val responseFrame = (responseFrameRes as AppResult.Success).data
        if (responseFrame.frameType != EncryptedPeerChannel.FRAME_TRANSFER_OFFER_RESPONSE) {
            return failSession(
                currentSession,
                mutableItems,
                "Unexpected frame while awaiting transfer approval",
                isResumable = false,
                publish = ::publish
            )
        }
        val decodedResponse = TransferWireProtocol.decodeOfferResponse(responseFrame.payload)
        if (decodedResponse is AppResult.Failure) {
            return failSession(
                currentSession,
                mutableItems,
                decodedResponse.error.message,
                isResumable = false,
                publish = ::publish
            )
        }
        val offerResponse = (decodedResponse as AppResult.Success).data
        if (!offerResponse.accepted) {
            val isDeclined = offerResponse.rejectCode.equals("USER_DECLINED", ignoreCase = true)
            val terminalState = if (isDeclined) TransferState.CANCELLED else TransferState.FAILED
            val reason = offerResponse.rejectMessage.ifBlank { "Transfer rejected (${offerResponse.rejectCode})" }
            for (i in mutableItems.indices) {
                if (mutableItems[i].state != TransferState.COMPLETED) {
                    mutableItems[i] = mutableItems[i].copy(
                        state = terminalState,
                        errorMessage = reason,
                        isRecoverable = false
                    )
                }
            }
            publish(state = terminalState, error = reason, isResumable = false)
            transferRepository.saveSession(currentSession)
            return AppResult.Failure(
                if (offerResponse.rejectCode == "LOW_STORAGE") {
                    AppError.Storage(reason)
                } else if (isDeclined) {
                    AppError.PermissionDenied(reason)
                } else {
                    AppError.Validation(reason)
                }
            )
        }

        val resumeByItem = offerResponse.resumeDecisions.associateBy { it.itemId }
        channel.setReadTimeout(TRANSFER_IO_TIMEOUT_MS)
        publish(state = TransferState.TRANSFERRING, error = null, isResumable = true)
        transferRepository.updateSessionState(
            sessionId = currentSession.sessionId,
            state = TransferState.TRANSFERRING,
            isResumable = true,
            updatedAtEpochMillis = clock.nowEpochMillis()
        )

        // 4. Stream each file with bounded sliding-window backpressure
        // Cap in-flight window bytes <= 256 KB so kernel TCP socket buffers never deadlock
        val maxWindowByBytes = (MAX_IN_FLIGHT_WINDOW_BYTES / chunkSize).coerceAtLeast(1)
        val effectiveWindow = minOf(config.validatedMaxInFlight, maxWindowByBytes)

        for (index in mutableItems.indices) {
            val item = mutableItems[index]
            if (item.state == TransferState.COMPLETED) continue

            val resumeDecision = resumeByItem[item.itemId]
            val startChunk = (resumeDecision?.resumeChunkIndex ?: 0).coerceIn(0, item.totalChunks)
            val startOffset = (resumeDecision?.resumeByteOffset ?: 0L).coerceIn(0L, item.fileSizeBytes)

            if (startChunk == item.totalChunks && startOffset == item.fileSizeBytes && item.fileSizeBytes > 0L) {
                // Already verified complete on receiver from a prior interrupted session
                val completedItem = item.copy(
                    bytesTransferred = item.fileSizeBytes,
                    nextChunkIndex = item.totalChunks,
                    verifiedSha256 = item.expectedSha256,
                    state = TransferState.COMPLETED,
                    completedAtEpochMillis = clock.nowEpochMillis()
                )
                mutableItems[index] = completedItem
                commitSenderItemCompletion(currentSession, completedItem)
                publish()
                continue
            }

            mutableItems[index] = item.copy(
                bytesTransferred = startOffset,
                nextChunkIndex = startChunk,
                state = TransferState.TRANSFERRING,
                errorMessage = null,
                isRecoverable = true
            )
            publish()

            val fileResult = sendSingleFile(
                channel = channel,
                session = currentSession,
                itemIndex = index,
                mutableItems = mutableItems,
                startChunkIndex = startChunk,
                chunkSizeBytes = chunkSize,
                windowSize = effectiveWindow,
                maxChunkRetries = config.maxChunkRetries,
                controlHandle = controlHandle,
                speedTracker = speedTracker,
                publish = ::publish
            )

            if (fileResult is AppResult.Failure) {
                return if (controlHandle.isCancelled || fileResult.error is AppError.PermissionDenied) {
                    cancelSession(channel, currentSession, mutableItems, publish = ::publish)
                } else {
                    val isRecoverable = fileResult.error is AppError.Network
                    failSession(
                        currentSession,
                        mutableItems,
                        fileResult.error.message,
                        isResumable = isRecoverable,
                        publish = ::publish
                    )
                }
            }
        }

        // 5. All items individually verified by receiver & committed to Room
        val allCompleted = mutableItems.all { it.state == TransferState.COMPLETED }
        val finalState = if (allCompleted) TransferState.COMPLETED else TransferState.FAILED
        runCatching {
            channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_SESSION_DONE,
                TransferWireProtocol.encodeSessionDone(
                    TransferWireProtocol.SessionDoneMessage(currentSession.sessionId, finalState.name)
                )
            )
        }
        val completedAt = if (allCompleted) clock.nowEpochMillis() else null
        publish(
            state = finalState,
            error = if (allCompleted) null else "One or more files failed integrity verification",
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
            AppResult.Failure(AppError.IntegrityMismatch("Transfer session incomplete", "", ""))
        }
    }

    private suspend fun sendSingleFile(
        channel: EncryptedPeerChannel,
        session: TransferSession,
        itemIndex: Int,
        mutableItems: MutableList<TransferItem>,
        startChunkIndex: Int,
        chunkSizeBytes: Int,
        windowSize: Int,
        maxChunkRetries: Int,
        controlHandle: TransferControlHandle,
        speedTracker: TransferSpeedTracker,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<Unit> {
        val item = mutableItems[itemIndex]
        val sourceUri = item.sourceUri
            ?: return AppResult.Failure(AppError.NotFound("Missing source URI for ${item.fileName}"))

        val inFlightChunkLengths = LinkedHashMap<Int, Int>()
        var remoteWindowCredits = windowSize
        var lastCheckpointChunk = startChunkIndex

        suspend fun drainAcks(expectedCount: Int): AppResult<Unit> {
            val failedChunksToRetry = mutableListOf<Int>()
            repeat(expectedCount) {
                val frameRes = channel.receiveEncryptedFrame()
                if (frameRes is AppResult.Failure) {
                    return AppResult.Failure(AppError.Network("Connection interrupted awaiting chunk ACK", null))
                }
                val frame = (frameRes as AppResult.Success).data
                when (frame.frameType) {
                    EncryptedPeerChannel.FRAME_TRANSFER_CHUNK_ACK -> {
                        val ackRes = TransferWireProtocol.decodeChunkAck(frame.payload)
                        if (ackRes is AppResult.Failure) return ackRes
                        val ack = (ackRes as AppResult.Success).data
                        if (ack.itemId != item.itemId) {
                            return AppResult.Failure(AppError.Network("ACK itemId mismatch"))
                        }
                        remoteWindowCredits = ack.windowCredits.coerceIn(1, windowSize)
                        val chunkLen = inFlightChunkLengths.remove(ack.chunkIndex) ?: 0
                        if (ack.accepted) {
                            speedTracker.recordBytes(chunkLen.toLong())
                            val current = mutableItems[itemIndex]
                            val updatedBytes = ack.bytesReceived.coerceIn(current.bytesTransferred, current.fileSizeBytes)
                            val nextIdx = (ack.chunkIndex + 1).coerceAtLeast(current.nextChunkIndex)
                            mutableItems[itemIndex] = current.copy(
                                bytesTransferred = updatedBytes,
                                nextChunkIndex = nextIdx,
                                state = TransferState.TRANSFERRING
                            )
                            if (nextIdx - lastCheckpointChunk >= CHECKPOINT_INTERVAL_CHUNKS) {
                                lastCheckpointChunk = nextIdx
                                transferRepository.updateItemProgress(
                                    itemId = item.itemId,
                                    bytesTransferred = updatedBytes,
                                    nextChunkIndex = nextIdx,
                                    state = TransferState.TRANSFERRING,
                                    isRecoverable = true
                                )
                            }
                            publish(TransferState.TRANSFERRING, null, true, null)
                        } else {
                            failedChunksToRetry.add(ack.chunkIndex)
                        }
                    }

                    EncryptedPeerChannel.FRAME_TRANSFER_CONTROL -> {
                        val ctrlRes = TransferWireProtocol.decodeControl(frame.payload)
                        if (ctrlRes is AppResult.Success) {
                            when (ctrlRes.data.command) {
                                TransferWireProtocol.ControlCommand.CANCEL -> {
                                    controlHandle.cancel()
                                    return AppResult.Failure(AppError.PermissionDenied("Transfer cancelled by peer"))
                                }
                                TransferWireProtocol.ControlCommand.PAUSE -> {
                                    controlHandle.pause()
                                    publish(TransferState.PAUSED, null, true, null)
                                }
                                TransferWireProtocol.ControlCommand.RESUME -> {
                                    controlHandle.resume()
                                    publish(TransferState.TRANSFERRING, null, true, null)
                                }
                                TransferWireProtocol.ControlCommand.RESEND_CHUNK -> Unit
                            }
                        }
                    }

                    EncryptedPeerChannel.FRAME_DISCONNECT -> {
                        return AppResult.Failure(AppError.Network("Peer disconnected during transfer"))
                    }

                    else -> return AppResult.Failure(AppError.Network("Unexpected frame type ${frame.frameType}"))
                }
            }

            // All in-flight ACKs drained -> safely retry any NACKed chunks in lockstep
            for (failedIdx in failedChunksToRetry) {
                val retryRes = retryRejectedChunk(
                    channel = channel,
                    item = mutableItems[itemIndex],
                    sourceUri = sourceUri,
                    chunkSizeBytes = chunkSizeBytes,
                    failedChunkIndex = failedIdx,
                    maxRetries = maxChunkRetries,
                    speedTracker = speedTracker,
                    mutableItems = mutableItems,
                    itemIndex = itemIndex,
                    publish = publish
                )
                if (retryRes is AppResult.Failure) return retryRes
            }
            return AppResult.Success(Unit)
        }

        val streamResult = chunkProcessor.streamSourceChunks(
            sourceUri = sourceUri,
            fileSizeBytes = item.fileSizeBytes,
            chunkSizeBytes = chunkSizeBytes,
            startChunkIndex = startChunkIndex
        ) { chunkIndex, byteOffset, chunkBytes, chunkSha256 ->
            // Enforce pause/cancel cleanly between chunks
            if (controlHandle.isPaused && !controlHandle.isCancelled) {
                if (inFlightChunkLengths.isNotEmpty()) {
                    val flushRes = drainAcks(inFlightChunkLengths.size)
                    if (flushRes is AppResult.Failure) return@streamSourceChunks flushRes
                }
                val pauseItem = mutableItems[itemIndex].copy(state = TransferState.PAUSED)
                mutableItems[itemIndex] = pauseItem
                transferRepository.updateItemProgress(
                    itemId = pauseItem.itemId,
                    bytesTransferred = pauseItem.bytesTransferred,
                    nextChunkIndex = pauseItem.nextChunkIndex,
                    state = TransferState.PAUSED,
                    isRecoverable = true
                )
                transferRepository.updateSessionState(
                    sessionId = session.sessionId,
                    state = TransferState.PAUSED,
                    isResumable = true,
                    updatedAtEpochMillis = clock.nowEpochMillis()
                )
                speedTracker.reset()
                publish(TransferState.PAUSED, null, true, null)
                channel.sendEncryptedFrame(
                    EncryptedPeerChannel.FRAME_TRANSFER_CONTROL,
                    TransferWireProtocol.encodeControl(
                        TransferWireProtocol.ControlMessage(session.sessionId, TransferWireProtocol.ControlCommand.PAUSE)
                    )
                )
                while (controlHandle.isPaused && !controlHandle.isCancelled) {
                    delay(100L)
                }
                if (!controlHandle.isCancelled) {
                    val resumeItem = mutableItems[itemIndex].copy(state = TransferState.TRANSFERRING)
                    mutableItems[itemIndex] = resumeItem
                    transferRepository.updateSessionState(
                        sessionId = session.sessionId,
                        state = TransferState.TRANSFERRING,
                        isResumable = true,
                        updatedAtEpochMillis = clock.nowEpochMillis()
                    )
                    publish(TransferState.TRANSFERRING, null, true, null)
                    val sendResume = channel.sendEncryptedFrame(
                        EncryptedPeerChannel.FRAME_TRANSFER_CONTROL,
                        TransferWireProtocol.encodeControl(
                            TransferWireProtocol.ControlMessage(session.sessionId, TransferWireProtocol.ControlCommand.RESUME)
                        )
                    )
                    if (sendResume is AppResult.Failure) {
                        return@streamSourceChunks AppResult.Failure(AppError.Network("Connection lost while resuming"))
                    }
                }
            }

            if (controlHandle.isCancelled) {
                return@streamSourceChunks AppResult.Failure(AppError.PermissionDenied("Transfer cancelled"))
            }

            val packet = TransferWireProtocol.ChunkPacket(
                itemId = item.itemId,
                chunkIndex = chunkIndex,
                byteOffset = byteOffset,
                chunkSha256 = chunkSha256,
                data = chunkBytes
            )
            val sendRes = channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_CHUNK,
                TransferWireProtocol.encodeChunkPacket(packet)
            )
            if (sendRes is AppResult.Failure) {
                return@streamSourceChunks AppResult.Failure(AppError.Network("Connection interrupted while sending chunk $chunkIndex"))
            }
            inFlightChunkLengths[chunkIndex] = chunkBytes.size

            // Backpressure gate: once window credits are filled, drain all in-flight ACKs
            if (inFlightChunkLengths.size >= remoteWindowCredits) {
                val ackRes = drainAcks(inFlightChunkLengths.size)
                if (ackRes is AppResult.Failure) return@streamSourceChunks ackRes
            }
            AppResult.Success(Unit)
        }

        if (streamResult is AppResult.Failure) return streamResult

        // Drain any remaining in-flight ACKs before sending FILE_DONE
        if (inFlightChunkLengths.isNotEmpty()) {
            val flushRes = drainAcks(inFlightChunkLengths.size)
            if (flushRes is AppResult.Failure) return flushRes
        }

        // Transition item to VERIFYING & send FILE_DONE
        mutableItems[itemIndex] = mutableItems[itemIndex].copy(
            state = TransferState.VERIFYING,
            bytesTransferred = item.fileSizeBytes,
            nextChunkIndex = item.totalChunks
        )
        publish(TransferState.VERIFYING, null, true, null)

        val doneRes = channel.sendEncryptedFrame(
            EncryptedPeerChannel.FRAME_TRANSFER_FILE_DONE,
            TransferWireProtocol.encodeFileDone(
                TransferWireProtocol.FileDoneMessage(
                    itemId = item.itemId,
                    totalChunks = item.totalChunks,
                    totalBytes = item.fileSizeBytes
                )
            )
        )
        if (doneRes is AppResult.Failure) {
            return AppResult.Failure(AppError.Network("Connection lost sending file completion marker"))
        }

        // Await receiver's cryptographic verification confirmation
        val verifyFrameRes = channel.receiveEncryptedFrame()
        if (verifyFrameRes is AppResult.Failure) {
            return AppResult.Failure(AppError.Network("Connection lost awaiting file verification"))
        }
        val verifyFrame = (verifyFrameRes as AppResult.Success).data
        if (verifyFrame.frameType != EncryptedPeerChannel.FRAME_TRANSFER_FILE_VERIFIED) {
            return AppResult.Failure(AppError.Network("Expected FILE_VERIFIED frame, got ${verifyFrame.frameType}"))
        }
        val decodedVerify = TransferWireProtocol.decodeFileVerified(verifyFrame.payload)
        if (decodedVerify is AppResult.Failure) return decodedVerify
        val verifiedMsg = (decodedVerify as AppResult.Success).data

        if (!verifiedMsg.verified ||
            !verifiedMsg.actualSha256.equals(item.expectedSha256, ignoreCase = true)
        ) {
            val errMsg = verifiedMsg.errorMessage.ifBlank { "Receiver reported SHA-256 verification failure" }
            mutableItems[itemIndex] = mutableItems[itemIndex].copy(
                state = TransferState.FAILED,
                errorMessage = errMsg,
                isRecoverable = true
            )
            transferRepository.upsertItem(mutableItems[itemIndex])
            return AppResult.Failure(
                AppError.IntegrityMismatch(errMsg, item.expectedSha256, verifiedMsg.actualSha256)
            )
        }

        // All 4 completion preconditions satisfied -> atomic Room transaction
        val completedItem = mutableItems[itemIndex].copy(
            bytesTransferred = item.fileSizeBytes,
            nextChunkIndex = item.totalChunks,
            verifiedSha256 = verifiedMsg.actualSha256,
            state = TransferState.COMPLETED,
            errorMessage = null,
            isRecoverable = false,
            completedAtEpochMillis = clock.nowEpochMillis()
        )
        val commitRes = commitSenderItemCompletion(session, completedItem)
        if (commitRes is AppResult.Failure) {
            mutableItems[itemIndex] = completedItem.copy(
                state = TransferState.FAILED,
                errorMessage = commitRes.error.message
            )
            return commitRes
        }

        mutableItems[itemIndex] = completedItem
        publish(TransferState.TRANSFERRING, null, true, null)
        return AppResult.Success(Unit)
    }

    private suspend fun retryRejectedChunk(
        channel: EncryptedPeerChannel,
        item: TransferItem,
        sourceUri: String,
        chunkSizeBytes: Int,
        failedChunkIndex: Int,
        maxRetries: Int,
        speedTracker: TransferSpeedTracker,
        mutableItems: MutableList<TransferItem>,
        itemIndex: Int,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<Unit> {
        val offset = failedChunkIndex.toLong() * chunkSizeBytes.toLong()
        repeat(maxRetries.coerceAtLeast(1)) {
            val readRes = chunkProcessor.readSingleChunk(sourceUri, item.fileSizeBytes, chunkSizeBytes, failedChunkIndex)
            if (readRes is AppResult.Failure) return readRes
            val (bytes, sha) = (readRes as AppResult.Success).data
            val resend = channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_CHUNK,
                TransferWireProtocol.encodeChunkPacket(
                    TransferWireProtocol.ChunkPacket(
                        itemId = item.itemId,
                        chunkIndex = failedChunkIndex,
                        byteOffset = offset,
                        chunkSha256 = sha,
                        data = bytes
                    )
                )
            )
            if (resend is AppResult.Failure) {
                return AppResult.Failure(AppError.Network("Connection lost retrying chunk $failedChunkIndex"))
            }
            val ackFrameRes = channel.receiveEncryptedFrame()
            if (ackFrameRes is AppResult.Failure) {
                return AppResult.Failure(AppError.Network("Connection lost awaiting retry ACK"))
            }
            val ackRes = TransferWireProtocol.decodeChunkAck((ackFrameRes as AppResult.Success).data.payload)
            if (ackRes is AppResult.Success && ackRes.data.accepted) {
                speedTracker.recordBytes(bytes.size.toLong())
                val current = mutableItems[itemIndex]
                mutableItems[itemIndex] = current.copy(
                    bytesTransferred = ackRes.data.bytesReceived.coerceIn(current.bytesTransferred, current.fileSizeBytes),
                    nextChunkIndex = (failedChunkIndex + 1).coerceAtLeast(current.nextChunkIndex)
                )
                publish(TransferState.TRANSFERRING, null, true, null)
                return AppResult.Success(Unit)
            }
        }
        return AppResult.Failure(
            AppError.IntegrityMismatch("Chunk $failedChunkIndex failed integrity verification after retries", "", "")
        )
    }

    private suspend fun commitSenderItemCompletion(
        session: TransferSession,
        completedItem: TransferItem
    ): AppResult<Unit> {
        val now = clock.nowEpochMillis()
        val historyRecord = TransferRecord(
            id = completedItem.itemId,
            sessionId = session.sessionId,
            fileName = completedItem.displayPath,
            mimeType = completedItem.mimeType,
            fileType = completedItem.fileType,
            fileSizeBytes = completedItem.fileSizeBytes,
            bytesTransferred = completedItem.fileSizeBytes,
            direction = TransferDirection.SENT,
            status = TransferStatus.COMPLETED_VERIFIED,
            peerDeviceId = session.peer.deviceId,
            peerDeviceName = session.peer.displayName,
            localUri = completedItem.sourceUri,
            sha256Checksum = completedItem.verifiedSha256 ?: completedItem.expectedSha256,
            isChecksumVerified = true,
            startedAtEpochMillis = completedItem.startedAtEpochMillis.takeIf { it > 0L } ?: session.createdAtEpochMillis,
            completedAtEpochMillis = completedItem.completedAtEpochMillis ?: now
        )
        return transferRepository.commitVerifiedItemCompletion(
            completedItem = completedItem,
            historyRecord = historyRecord,
            sessionUpdatedAtEpochMillis = now
        )
    }

    private suspend fun cancelSession(
        channel: EncryptedPeerChannel,
        session: TransferSession,
        mutableItems: MutableList<TransferItem>,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<TransferSession> {
        runCatching {
            channel.sendEncryptedFrame(
                EncryptedPeerChannel.FRAME_TRANSFER_CONTROL,
                TransferWireProtocol.encodeControl(
                    TransferWireProtocol.ControlMessage(session.sessionId, TransferWireProtocol.ControlCommand.CANCEL)
                )
            )
        }
        for (i in mutableItems.indices) {
            if (mutableItems[i].state != TransferState.COMPLETED) {
                mutableItems[i] = mutableItems[i].copy(
                    state = TransferState.CANCELLED,
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

    private suspend fun failSession(
        session: TransferSession,
        mutableItems: MutableList<TransferItem>,
        reason: String,
        isResumable: Boolean,
        publish: (TransferState, String?, Boolean, Long?) -> Unit
    ): AppResult<TransferSession> {
        for (i in mutableItems.indices) {
            if (mutableItems[i].state != TransferState.COMPLETED && mutableItems[i].state != TransferState.CANCELLED) {
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
        return AppResult.Failure(
            if (isResumable) AppError.Network(reason) else AppError.Storage(reason)
        )
    }

    companion object {
        private const val OFFER_RESPONSE_TIMEOUT_MS = 60_000
        private const val TRANSFER_IO_TIMEOUT_MS = 30_000
        private const val MAX_IN_FLIGHT_WINDOW_BYTES = 256 * 1024
        private const val CHECKPOINT_INTERVAL_CHUNKS = 8
        private const val PROGRESS_EMIT_INTERVAL_MS = 100L
    }
}
