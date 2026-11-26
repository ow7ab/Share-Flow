package com.example.shareflow.data.repository

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.TransferHistoryDao
import com.example.shareflow.data.local.database.entity.toDomain
import com.example.shareflow.data.local.database.entity.toEntity
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.repository.TransferHistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TransferHistoryRepositoryImpl(
    private val dao: TransferHistoryDao,
    private val dispatchers: DispatcherProvider
) : TransferHistoryRepository {

    override fun observeAllRecords(): Flow<List<TransferRecord>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeRecentRecords(limit: Int): Flow<List<TransferRecord>> =
        dao.observeRecent(limit.coerceAtLeast(0)).map { entities -> entities.map { it.toDomain() } }

    override fun observeRecordsByDirection(direction: TransferDirection): Flow<List<TransferRecord>> =
        dao.observeByDirection(direction.name).map { entities -> entities.map { it.toDomain() } }

    override fun observeTotalCount(): Flow<Int> = dao.observeCount()

    override suspend fun upsertRecord(record: TransferRecord): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.upsert(record.toEntity())
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to save transfer record", it)) }
            )
        }

    override suspend fun deleteRecordById(recordId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteById(recordId)
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to delete transfer record", it)) }
            )
        }

    override suspend fun pruneRecordsOlderThan(cutoffEpochMillis: Long): AppResult<Int> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteOlderThan(cutoffEpochMillis)
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to prune expired transfer history", it)) }
            )
        }

    override suspend fun clearAllRecords(): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteAll()
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to clear transfer history", it)) }
            )
        }
}
