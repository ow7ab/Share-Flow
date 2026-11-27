package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.RecoveryVerificationReport
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.repository.TransferRepository

/**
 * Inspects persisted incomplete Room sessions after app restart or network interruption.
 * Strictly enforces the rule: NEVER assume an interrupted transfer is resumable unless both
 * the session identity and underlying source/staging file state have been independently verified.
 */
class TransferRecoveryManager(
    private val transferRepository: TransferRepository,
    private val dataSource: TransferDataSource,
    private val verifier: TransferVerifier,
    private val chunkProcessor: ChunkProcessor,
    private val clock: Clock = SystemClock()
) {
    /**
     * Audits every incomplete Room session on startup/reconnection, updating Room state truthfully
     * so corrupted or orphaned partials are never resumed blindly.
     */
    suspend fun inspectAndRecoverInterruptedSessions(): List<RecoveryVerificationReport> {
        val incomplete = transferRepository.getIncompleteSessions()
        return incomplete.map { session -> inspectSession(session) }
    }

    /**
     * Verifies a specific persisted session before resuming or presenting it as recoverable.
     */
    suspend fun inspectSession(session: TransferSession): RecoveryVerificationReport {
        if (session.transferItems.isEmpty()) {
            transferRepository.updateSessionState(
                sessionId = session.sessionId,
                state = TransferState.FAILED,
                errorMessage = "Transfer session has no persisted items",
                isResumable = false,
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
            return RecoveryVerificationReport(
                sessionId = session.sessionId,
                isResumable = false,
                verifiedResumableItems = 0,
                resetToZeroItems = 0,
                unrecoverableItems = 0,
                reason = "Session contains no file items"
            )
        }

        var verifiedResumable = 0
        var resetToZero = 0
        var unrecoverable = 0
        var alreadyCompleted = 0

        for (item in session.transferItems) {
            if (item.state == TransferState.COMPLETED) {
                alreadyCompleted++
                continue
            }
            if (item.state == TransferState.CANCELLED) {
                unrecoverable++
                continue
            }

            when (session.direction) {
                TransferDirection.RECEIVED -> {
                    val partial = verifier.verifyPartialStagingFile(
                        tempFilePath = item.tempFilePath,
                        fileSizeBytes = item.fileSizeBytes,
                        chunkSizeBytes = item.chunkSizeBytes,
                        recordedChunkIndex = item.nextChunkIndex,
                        recordedBytesTransferred = item.bytesTransferred
                    )
                    when (partial) {
                        is PartialVerificationResult.Verified -> {
                            verifiedResumable++
                            transferRepository.updateItemProgress(
                                itemId = item.itemId,
                                bytesTransferred = partial.resumeByteOffset,
                                nextChunkIndex = partial.resumeChunkIndex,
                                state = TransferState.PAUSED,
                                tempFilePath = item.tempFilePath,
                                errorMessage = null,
                                isRecoverable = true
                            )
                        }

                        is PartialVerificationResult.NotResumable -> {
                            // Never resume an unverified partial; delete corrupt staging file and reset safely to 0
                            chunkProcessor.cleanupTempFile(item.tempFilePath)
                            resetToZero++
                            transferRepository.updateItemProgress(
                                itemId = item.itemId,
                                bytesTransferred = 0L,
                                nextChunkIndex = 0,
                                state = TransferState.PAUSED,
                                tempFilePath = null,
                                errorMessage = partial.reason,
                                isRecoverable = true
                            )
                        }
                    }
                }

                TransferDirection.SENT -> {
                    val uri = item.sourceUri
                    val readable = !uri.isNullOrBlank() && dataSource.isReadable(uri)
                    val sizeResult = if (readable) dataSource.getFileSizeBytes(uri!!) else null
                    val sizeMatches = sizeResult is AppResult.Success && sizeResult.data == item.fileSizeBytes
                    if (readable && sizeMatches) {
                        verifiedResumable++
                        transferRepository.updateItemProgress(
                            itemId = item.itemId,
                            bytesTransferred = item.bytesTransferred.coerceIn(0L, item.fileSizeBytes),
                            nextChunkIndex = item.nextChunkIndex.coerceIn(0, item.totalChunks),
                            state = TransferState.PAUSED,
                            tempFilePath = null,
                            errorMessage = null,
                            isRecoverable = true
                        )
                    } else {
                        unrecoverable++
                        transferRepository.updateItemProgress(
                            itemId = item.itemId,
                            bytesTransferred = item.bytesTransferred,
                            nextChunkIndex = item.nextChunkIndex,
                            state = TransferState.FAILED,
                            tempFilePath = null,
                            errorMessage = "Source file is no longer accessible or was modified",
                            isRecoverable = false
                        )
                    }
                }
            }
        }

        val totalRemaining = session.transferItems.size - alreadyCompleted
        val canResume = totalRemaining > 0 && unrecoverable < totalRemaining && (verifiedResumable + resetToZero) > 0
        val newState = when {
            alreadyCompleted == session.transferItems.size -> TransferState.COMPLETED
            canResume -> TransferState.PAUSED
            else -> TransferState.FAILED
        }
        val summaryReason = when {
            canResume && verifiedResumable > 0 ->
                "Verified $verifiedResumable resumable item(s) and $resetToZero reset item(s)"
            canResume ->
                "Staging partials unverified; $resetToZero item(s) reset to chunk 0 for clean retry"
            else ->
                "Session is not resumable: underlying file state verification failed"
        }

        transferRepository.updateSessionState(
            sessionId = session.sessionId,
            state = newState,
            errorMessage = if (newState == TransferState.FAILED) summaryReason else null,
            isResumable = canResume,
            updatedAtEpochMillis = clock.nowEpochMillis(),
            completedAtEpochMillis = if (newState == TransferState.COMPLETED) clock.nowEpochMillis() else null
        )

        return RecoveryVerificationReport(
            sessionId = session.sessionId,
            isResumable = canResume,
            verifiedResumableItems = verifiedResumable,
            resetToZeroItems = resetToZero,
            unrecoverableItems = unrecoverable,
            reason = summaryReason
        )
    }

    /**
     * Checks whether a persisted session may safely resume with the currently connected [connectedPeer].
     * Enforces cryptographic peer identity matching (deviceId + publicKeyFingerprint).
     */
    fun canResumeWithPeer(session: TransferSession, connectedPeer: PeerDevice): Boolean {
        if (!session.isResumable) return false
        if (!session.peer.deviceId.equals(connectedPeer.deviceId, ignoreCase = true)) return false
        val expectedFp = session.peer.publicKeyFingerprint
        val actualFp = connectedPeer.publicKeyFingerprint
        if (!expectedFp.isNullOrBlank() && !actualFp.isNullOrBlank()) {
            return expectedFp.equals(actualFp, ignoreCase = true)
        }
        return true
    }

    /**
     * Determines a verified per-item resume checkpoint on the receiver when a sender re-offers an
     * existing [sessionId] after a network interruption.
     */
    suspend fun resolveVerifiedReceiverResumePoint(
        sessionId: String,
        peer: PeerDevice,
        itemDescriptor: TransferWireProtocol.OfferFileDescriptor,
        chunkSizeBytes: Int
    ): Pair<Int, Long> {
        val existingSession = transferRepository.getSessionById(sessionId) ?: return 0 to 0L
        if (!existingSession.peer.deviceId.equals(peer.deviceId, ignoreCase = true)) return 0 to 0L
        val expectedFp = existingSession.peer.publicKeyFingerprint
        val actualFp = peer.publicKeyFingerprint
        if (!expectedFp.isNullOrBlank() && !actualFp.isNullOrBlank() &&
            !expectedFp.equals(actualFp, ignoreCase = true)
        ) {
            return 0 to 0L
        }
        if (existingSession.chunkSizeBytes != chunkSizeBytes) return 0 to 0L

        val existingItem: TransferItem = existingSession.transferItems
            .firstOrNull { it.itemId == itemDescriptor.itemId } ?: return 0 to 0L

        // Verify metadata immutability: size and expected SHA-256 MUST match exactly
        if (existingItem.fileSizeBytes != itemDescriptor.fileSizeBytes ||
            !existingItem.expectedSha256.equals(itemDescriptor.expectedSha256, ignoreCase = true)
        ) {
            chunkProcessor.cleanupTempFile(existingItem.tempFilePath)
            return 0 to 0L
        }

        if (existingItem.state == TransferState.COMPLETED) {
            return existingItem.totalChunks to existingItem.fileSizeBytes
        }

        return when (
            val check = verifier.verifyPartialStagingFile(
                tempFilePath = existingItem.tempFilePath,
                fileSizeBytes = existingItem.fileSizeBytes,
                chunkSizeBytes = chunkSizeBytes,
                recordedChunkIndex = existingItem.nextChunkIndex,
                recordedBytesTransferred = existingItem.bytesTransferred
            )
        ) {
            is PartialVerificationResult.Verified -> {
                // An unfinalized staging .part file must never report all chunks complete;
                // hold back at least the final chunk so FILE_DONE -> SHA-256 -> atomic finalize runs.
                val maxUnfinalizedChunk = (itemDescriptor.totalChunks - 1).coerceAtLeast(0)
                val safeChunkIndex = check.resumeChunkIndex.coerceAtMost(maxUnfinalizedChunk)
                val safeByteOffset = (safeChunkIndex.toLong() * chunkSizeBytes.toLong())
                    .coerceAtMost(itemDescriptor.fileSizeBytes)
                safeChunkIndex to safeByteOffset
            }
            is PartialVerificationResult.NotResumable -> {
                chunkProcessor.cleanupTempFile(existingItem.tempFilePath)
                0 to 0L
            }
        }
    }
}
