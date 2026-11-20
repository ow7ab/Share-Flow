package com.example.shareflow.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.shareflow.data.local.database.entity.TransferHistoryEntity
import com.example.shareflow.data.local.database.entity.TransferItemEntity
import com.example.shareflow.data.local.database.entity.TransferSessionEntity
import com.example.shareflow.data.local.database.entity.TransferSessionWithItems
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferSessionDao {

    @Transaction
    @Query("SELECT * FROM transfer_sessions ORDER BY updated_at_epoch_millis DESC")
    fun observeAllSessions(): Flow<List<TransferSessionWithItems>>

    @Transaction
    @Query("SELECT * FROM transfer_sessions WHERE session_id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<TransferSessionWithItems?>

    @Transaction
    @Query("SELECT * FROM transfer_sessions WHERE session_id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: String): TransferSessionWithItems?

    @Transaction
    @Query("SELECT * FROM transfer_sessions WHERE state NOT IN ('COMPLETED', 'CANCELLED') ORDER BY updated_at_epoch_millis DESC")
    suspend fun getIncompleteSessions(): List<TransferSessionWithItems>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: TransferSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<TransferItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: TransferItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(history: TransferHistoryEntity)

    @Transaction
    suspend fun saveSessionWithItems(
        session: TransferSessionEntity,
        items: List<TransferItemEntity>
    ) {
        upsertSession(session)
        if (items.isNotEmpty()) {
            upsertItems(items)
        }
    }

    @Query(
        """
        UPDATE transfer_items
        SET bytes_transferred = :bytesTransferred,
            next_chunk_index = :nextChunkIndex,
            state = :state,
            temp_file_path = COALESCE(:tempFilePath, temp_file_path),
            error_message = :errorMessage,
            is_recoverable = :isRecoverable
        WHERE item_id = :itemId
        """
    )
    suspend fun updateItemProgress(
        itemId: String,
        bytesTransferred: Long,
        nextChunkIndex: Int,
        state: String,
        tempFilePath: String?,
        errorMessage: String?,
        isRecoverable: Boolean
    )

    @Query(
        """
        UPDATE transfer_sessions
        SET state = :state,
            error_message = :errorMessage,
            is_resumable = :isResumable,
            updated_at_epoch_millis = :updatedAtEpochMillis,
            completed_at_epoch_millis = :completedAtEpochMillis
        WHERE session_id = :sessionId
        """
    )
    suspend fun updateSessionState(
        sessionId: String,
        state: String,
        errorMessage: String?,
        isResumable: Boolean,
        updatedAtEpochMillis: Long,
        completedAtEpochMillis: Long?
    )

    /**
     * Atomic DB commit executed ONLY after all chunks arrived, destination file was finalized,
     * and SHA-256 verification succeeded. Updates the transfer item AND persists verified history.
     */
    @Transaction
    suspend fun commitVerifiedItemCompletion(
        completedItem: TransferItemEntity,
        historyRecord: TransferHistoryEntity,
        sessionUpdatedAtEpochMillis: Long
    ) {
        upsertItem(completedItem)
        upsertHistory(historyRecord)
        touchSession(completedItem.sessionId, sessionUpdatedAtEpochMillis)
    }

    @Query("UPDATE transfer_sessions SET updated_at_epoch_millis = :updatedAtEpochMillis WHERE session_id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAtEpochMillis: Long)

    @Query("DELETE FROM transfer_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)
}
