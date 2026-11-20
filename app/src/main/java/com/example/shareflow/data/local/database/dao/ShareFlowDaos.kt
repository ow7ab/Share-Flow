package com.example.shareflow.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shareflow.data.local.database.entity.TransferHistoryEntity
import com.example.shareflow.data.local.database.entity.TrustedDeviceEntity
import com.example.shareflow.data.local.database.entity.VaultItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferHistoryDao {
    @Query("SELECT * FROM transfer_history ORDER BY started_at_epoch_millis DESC")
    fun observeAll(): Flow<List<TransferHistoryEntity>>

    @Query("SELECT * FROM transfer_history WHERE direction = :direction ORDER BY started_at_epoch_millis DESC")
    fun observeByDirection(direction: String): Flow<List<TransferHistoryEntity>>

    @Query("SELECT COUNT(*) FROM transfer_history")
    fun observeCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TransferHistoryEntity)

    @Query("DELETE FROM transfer_history WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transfer_history WHERE started_at_epoch_millis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

    @Query("DELETE FROM transfer_history")
    suspend fun deleteAll()
}

@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices ORDER BY last_seen_epoch_millis DESC")
    fun observeAll(): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT COUNT(*) FROM trusted_devices")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM trusted_devices WHERE device_id = :deviceId LIMIT 1")
    suspend fun findById(deviceId: String): TrustedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TrustedDeviceEntity)

    @Query("DELETE FROM trusted_devices WHERE device_id = :deviceId")
    suspend fun deleteById(deviceId: String)

    @Query("DELETE FROM trusted_devices")
    suspend fun deleteAll()
}

@Dao
interface VaultItemDao {
    @Query("SELECT * FROM vault_items ORDER BY created_at_epoch_millis DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): VaultItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VaultItemEntity)

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun deleteById(id: String)
}
