package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import kotlinx.coroutines.flow.Flow

/**
 * Contract for persistent Room-backed transfer history records.
 */
interface TransferHistoryRepository {
    fun observeAllRecords(): Flow<List<TransferRecord>>
    fun observeRecordsByDirection(direction: TransferDirection): Flow<List<TransferRecord>>
    fun observeTotalCount(): Flow<Int>
    suspend fun upsertRecord(record: TransferRecord): AppResult<Unit>
    suspend fun deleteRecordById(recordId: String): AppResult<Unit>
    suspend fun clearAllRecords(): AppResult<Unit>
}
