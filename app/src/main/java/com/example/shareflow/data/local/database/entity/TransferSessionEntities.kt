package com.example.shareflow.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState

@Entity(
    tableName = "transfer_sessions",
    indices = [
        Index(value = ["state"]),
        Index(value = ["updated_at_epoch_millis"]),
        Index(value = ["peer_device_id"])
    ]
)
data class TransferSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "peer_device_id")
    val peerDeviceId: String,
    @ColumnInfo(name = "peer_display_name")
    val peerDisplayName: String,
    @ColumnInfo(name = "peer_public_key_fingerprint")
    val peerPublicKeyFingerprint: String?,
    @ColumnInfo(name = "peer_connection_type")
    val peerConnectionType: String,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "chunk_size_bytes")
    val chunkSizeBytes: Int,
    @ColumnInfo(name = "destination_root_path")
    val destinationRootPath: String?,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "is_resumable")
    val isResumable: Boolean,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?
)

@Entity(
    tableName = "transfer_items",
    foreignKeys = [
        ForeignKey(
            entity = TransferSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"]),
        Index(value = ["state"])
    ]
)
data class TransferItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_id")
    val itemId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_type")
    val fileType: String,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long,
    @ColumnInfo(name = "chunk_size_bytes")
    val chunkSizeBytes: Int,
    @ColumnInfo(name = "total_chunks")
    val totalChunks: Int,
    @ColumnInfo(name = "next_chunk_index")
    val nextChunkIndex: Int,
    @ColumnInfo(name = "expected_sha256")
    val expectedSha256: String,
    @ColumnInfo(name = "verified_sha256")
    val verifiedSha256: String?,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String?,
    @ColumnInfo(name = "destination_uri")
    val destinationUri: String?,
    @ColumnInfo(name = "temp_file_path")
    val tempFilePath: String?,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    @ColumnInfo(name = "is_recoverable")
    val isRecoverable: Boolean,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?
)

data class TransferSessionWithItems(
    @Embedded val session: TransferSessionEntity,
    @Relation(
        parentColumn = "session_id",
        entityColumn = "session_id"
    )
    val items: List<TransferItemEntity>
)

fun TransferItemEntity.toDomain(): TransferItem = TransferItem(
    itemId = itemId,
    sessionId = sessionId,
    fileName = fileName,
    relativePath = relativePath,
    mimeType = mimeType,
    fileType = runCatching { TransferFileType.valueOf(fileType) }.getOrDefault(TransferFileType.OTHER),
    fileSizeBytes = fileSizeBytes,
    bytesTransferred = bytesTransferred,
    chunkSizeBytes = chunkSizeBytes,
    totalChunks = totalChunks,
    nextChunkIndex = nextChunkIndex,
    expectedSha256 = expectedSha256,
    verifiedSha256 = verifiedSha256,
    sourceUri = sourceUri,
    destinationUri = destinationUri,
    tempFilePath = tempFilePath,
    state = runCatching { TransferState.valueOf(state) }.getOrDefault(TransferState.FAILED),
    errorMessage = errorMessage,
    isRecoverable = isRecoverable,
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis
)

fun TransferItem.toEntity(): TransferItemEntity = TransferItemEntity(
    itemId = itemId,
    sessionId = sessionId,
    fileName = fileName,
    relativePath = relativePath,
    mimeType = mimeType,
    fileType = fileType.name,
    fileSizeBytes = fileSizeBytes,
    bytesTransferred = bytesTransferred,
    chunkSizeBytes = chunkSizeBytes,
    totalChunks = totalChunks,
    nextChunkIndex = nextChunkIndex,
    expectedSha256 = expectedSha256,
    verifiedSha256 = verifiedSha256,
    sourceUri = sourceUri,
    destinationUri = destinationUri,
    tempFilePath = tempFilePath,
    state = state.name,
    errorMessage = errorMessage,
    isRecoverable = isRecoverable,
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis
)

fun TransferSessionWithItems.toDomain(): TransferSession {
    val domainItems = items.map { it.toDomain() }
    val peer = PeerDevice(
        deviceId = session.peerDeviceId,
        displayName = session.peerDisplayName,
        deviceType = DeviceType.PHONE,
        connectionType = runCatching { ConnectionType.valueOf(session.peerConnectionType) }
            .getOrDefault(ConnectionType.LOCAL_LAN_NSD),
        publicKeyFingerprint = session.peerPublicKeyFingerprint
    )
    return TransferSession(
        sessionId = session.sessionId,
        peer = peer,
        direction = runCatching { TransferDirection.valueOf(session.direction) }
            .getOrDefault(TransferDirection.RECEIVED),
        state = runCatching { TransferState.valueOf(session.state) }
            .getOrDefault(TransferState.FAILED),
        transferItems = domainItems,
        chunkSizeBytes = session.chunkSizeBytes,
        destinationRootPath = session.destinationRootPath,
        errorMessage = session.errorMessage,
        isResumable = session.isResumable,
        createdAtEpochMillis = session.createdAtEpochMillis,
        updatedAtEpochMillis = session.updatedAtEpochMillis,
        completedAtEpochMillis = session.completedAtEpochMillis
    )
}

fun TransferSession.toSessionEntity(): TransferSessionEntity = TransferSessionEntity(
    sessionId = sessionId,
    peerDeviceId = peer.deviceId,
    peerDisplayName = peer.displayName,
    peerPublicKeyFingerprint = peer.publicKeyFingerprint,
    peerConnectionType = peer.connectionType.name,
    direction = direction.name,
    state = state.name,
    chunkSizeBytes = chunkSizeBytes,
    destinationRootPath = destinationRootPath,
    errorMessage = errorMessage,
    isResumable = isResumable,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis
)
