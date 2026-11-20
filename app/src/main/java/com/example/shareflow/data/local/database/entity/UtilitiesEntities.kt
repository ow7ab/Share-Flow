package com.example.shareflow.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.shareflow.domain.model.FavoriteFileItem
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.StorageTrendPoint

@Entity(
    tableName = "favorite_files",
    indices = [Index(value = ["added_at_epoch_millis"])]
)
data class FavoriteFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri_string")
    val uriString: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "category")
    val category: String,
    @ColumnInfo(name = "added_at_epoch_millis")
    val addedAtEpochMillis: Long
)

fun FavoriteFileEntity.toDomain(isAccessible: Boolean = true): FavoriteFileItem = FavoriteFileItem(
    uriString = uriString,
    displayName = displayName,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    category = runCatching { FileCategory.valueOf(category) }.getOrDefault(FileCategory.OTHER),
    addedAtEpochMillis = addedAtEpochMillis,
    isAccessible = isAccessible
)

@Entity(
    tableName = "storage_snapshots",
    indices = [Index(value = ["captured_at_epoch_millis"])]
)
data class StorageSnapshotEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long,
    @ColumnInfo(name = "used_bytes")
    val usedBytes: Long,
    @ColumnInfo(name = "available_bytes")
    val availableBytes: Long
)

fun StorageSnapshotEntity.toDomain(): StorageTrendPoint = StorageTrendPoint(
    id = id,
    capturedAtEpochMillis = capturedAtEpochMillis,
    totalBytes = totalBytes,
    usedBytes = usedBytes,
    availableBytes = availableBytes
)
