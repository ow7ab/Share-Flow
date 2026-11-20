package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed contract for persistent transfer sessions, per-file chunk checkpoints,
 * atomic verified completion, and interrupted session recovery.
 */
interface TransferRepository {
    fun observeAllSessions(): Flow<List<TransferSession>>
    fun observeSession(sessionId: String): Flow<TransferSession?>
    suspend fun getSessionById(sessionId: String): TransferSession?
    suspend fun getIncompleteSessions(): List<TransferSession>
    suspend fun saveSession(session: TransferSession): AppResult<Unit>
    suspend fun upsertItem(item: TransferItem): AppResult<Unit>
    suspend fun updateItemProgress(
        itemId: String,
        bytesTransferred: Long,
        nextChunkIndex: Int,
        state: TransferState,
        tempFilePath: String? = null,
        errorMessage: String? = null,
        isRecoverable: Boolean = false
    ): AppResult<Unit>

    suspend fun updateSessionState(
        sessionId: String,
        state: TransferState,
        errorMessage: String? = null,
        isResumable: Boolean = false,
        updatedAtEpochMillis: Long,
        completedAtEpochMillis: Long? = null
    ): AppResult<Unit>

    /**
     * Atomically marks a verified item COMPLETED and records it in transfer_history inside a single
     * Room transaction. Must ONLY be invoked after chunks, file finalization, and SHA-256 verification succeed.
     */
    suspend fun commitVerifiedItemCompletion(
        completedItem: TransferItem,
        historyRecord: TransferRecord,
        sessionUpdatedAtEpochMillis: Long
    ): AppResult<Unit>

    suspend fun deleteSession(sessionId: String): AppResult<Unit>
}
