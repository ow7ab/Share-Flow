package com.example.shareflow.data.repository

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.TransferSessionDao
import com.example.shareflow.data.local.database.entity.toDomain
import com.example.shareflow.data.local.database.entity.toEntity
import com.example.shareflow.data.local.database.entity.toSessionEntity
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.domain.repository.TransferRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Production Room-backed implementation of [TransferRepository].
 */
class TransferRepositoryImpl(
    private val dao: TransferSessionDao,
    private val dispatchers: DispatcherProvider
) : TransferRepository {

    override fun observeAllSessions(): Flow<List<TransferSession>> =
        dao.observeAllSessions().map { list -> list.map { it.toDomain() } }

    override fun observeSession(sessionId: String): Flow<TransferSession?> =
        dao.observeSessionById(sessionId).map { it?.toDomain() }

    override suspend fun getSessionById(sessionId: String): TransferSession? =
        withContext(dispatchers.io) {
            runCatching { dao.getSessionById(sessionId)?.toDomain() }.getOrNull()
        }

    override suspend fun getIncompleteSessions(): List<TransferSession> =
        withContext(dispatchers.io) {
            runCatching { dao.getIncompleteSessions().map { it.toDomain() } }
                .getOrDefault(emptyList())
        }

    override suspend fun saveSession(session: TransferSession): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.saveSessionWithItems(
                    session = session.toSessionEntity(),
                    items = session.transferItems.map { it.toEntity() }
                )
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to persist transfer session", it)) }
            )
        }

    override suspend fun upsertItem(item: TransferItem): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.upsertItem(item.toEntity())
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to update transfer item", it)) }
            )
        }

    override suspend fun updateItemProgress(
        itemId: String,
        bytesTransferred: Long,
        nextChunkIndex: Int,
        state: TransferState,
        tempFilePath: String?,
        errorMessage: String?,
        isRecoverable: Boolean
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            dao.updateItemProgress(
                itemId = itemId,
                bytesTransferred = bytesTransferred,
                nextChunkIndex = nextChunkIndex,
                state = state.name,
                tempFilePath = tempFilePath,
                errorMessage = errorMessage,
                isRecoverable = isRecoverable
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to checkpoint transfer progress", it)) }
        )
    }

    override suspend fun updateSessionState(
        sessionId: String,
        state: TransferState,
        errorMessage: String?,
        isResumable: Boolean,
        updatedAtEpochMillis: Long,
        completedAtEpochMillis: Long?
    ): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching {
            dao.updateSessionState(
                sessionId = sessionId,
                state = state.name,
                errorMessage = errorMessage,
                isResumable = isResumable,
                updatedAtEpochMillis = updatedAtEpochMillis,
                completedAtEpochMillis = completedAtEpochMillis
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to update transfer session state", it)) }
        )
    }

    override suspend fun commitVerifiedItemCompletion(
        completedItem: TransferItem,
        historyRecord: TransferRecord,
        sessionUpdatedAtEpochMillis: Long
    ): AppResult<Unit> = withContext(dispatchers.io) {
        if (completedItem.state != TransferState.COMPLETED || !historyRecord.isChecksumVerified) {
            return@withContext AppResult.Failure(
                AppError.Validation("Cannot commit unverified item as COMPLETED")
            )
        }
        runCatching {
            dao.commitVerifiedItemCompletion(
                completedItem = completedItem.toEntity(),
                historyRecord = historyRecord.toEntity(),
                sessionUpdatedAtEpochMillis = sessionUpdatedAtEpochMillis
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Atomic completion DB commit failed", it)) }
        )
    }

    override suspend fun deleteSession(sessionId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching { dao.deleteSession(sessionId) }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to delete transfer session", it)) }
            )
        }
}
