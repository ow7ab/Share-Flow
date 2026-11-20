package com.example.shareflow.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shareflow.data.local.database.entity.FavoriteFileEntity
import com.example.shareflow.data.local.database.entity.StorageSnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteFileDao {
    @Query("SELECT * FROM favorite_files ORDER BY added_at_epoch_millis DESC")
    fun observeAll(): Flow<List<FavoriteFileEntity>>

    @Query("SELECT uri_string FROM favorite_files")
    fun observeFavoriteUris(): Flow<List<String>>

    @Query("SELECT * FROM favorite_files WHERE uri_string = :uriString LIMIT 1")
    suspend fun findByUri(uriString: String): FavoriteFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FavoriteFileEntity)

    @Query("DELETE FROM favorite_files WHERE uri_string = :uriString")
    suspend fun deleteByUri(uriString: String)

    @Query("DELETE FROM favorite_files")
    suspend fun deleteAll()
}

@Dao
interface StorageSnapshotDao {
    @Query("SELECT * FROM storage_snapshots ORDER BY captured_at_epoch_millis DESC LIMIT :limit")
    fun observeRecent(limit: Int = 14): Flow<List<StorageSnapshotEntity>>

    @Query("SELECT * FROM storage_snapshots ORDER BY captured_at_epoch_millis DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 14): List<StorageSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: StorageSnapshotEntity)

    @Query("DELETE FROM storage_snapshots WHERE id NOT IN (SELECT id FROM storage_snapshots ORDER BY captured_at_epoch_millis DESC LIMIT :keepCount)")
    suspend fun pruneKeepingLatest(keepCount: Int = 30)
}
