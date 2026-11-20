package com.example.shareflow.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.model.VaultItem

@Entity(
    tableName = "transfer_history",
    indices = [
        Index(value = ["started_at_epoch_millis"]),
        Index(value = ["session_id"]),
        Index(value = ["direction"])
    ]
)
data class TransferHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "file_name")
    val fileName: String,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "file_type")
    val fileType: String,
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,
    @ColumnInfo(name = "bytes_transferred")
    val bytesTransferred: Long,
    @ColumnInfo(name = "direction")
    val direction: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "peer_device_id")
    val peerDeviceId: String,
    @ColumnInfo(name = "peer_device_name")
    val peerDeviceName: String,
    @ColumnInfo(name = "local_uri")
    val localUri: String?,
    @ColumnInfo(name = "sha256_checksum")
    val sha256Checksum: String?,
    @ColumnInfo(name = "is_checksum_verified")
    val isChecksumVerified: Boolean,
    @ColumnInfo(name = "started_at_epoch_millis")
    val startedAtEpochMillis: Long,
    @ColumnInfo(name = "completed_at_epoch_millis")
    val completedAtEpochMillis: Long?
)

@Entity(
    tableName = "trusted_devices",
    indices = [Index(value = ["last_seen_epoch_millis"])]
)
data class TrustedDeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_id")
    val deviceId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "device_type")
    val deviceType: String,
    @ColumnInfo(name = "connection_type")
    val connectionType: String,
    @ColumnInfo(name = "public_key_fingerprint")
    val publicKeyFingerprint: String?,
    @ColumnInfo(name = "last_seen_epoch_millis")
    val lastSeenEpochMillis: Long
)

@Entity(
    tableName = "vault_items",
    indices = [Index(value = ["created_at_epoch_millis"])]
)
data class VaultItemEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "encrypted_file_name")
    val encryptedFileName: String,
    @ColumnInfo(name = "original_mime_type")
    val originalMimeType: String,
    @ColumnInfo(name = "original_size_bytes")
    val originalSizeBytes: Long,
    @ColumnInfo(name = "sha256_checksum")
    val sha256Checksum: String,
    @ColumnInfo(name = "key_alias")
    val keyAlias: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "category", defaultValue = "'DOCUMENTS'")
    val category: String = "DOCUMENTS",
    @ColumnInfo(name = "encrypted_metadata_b64", defaultValue = "''")
    val encryptedMetadataB64: String = "",
    @ColumnInfo(name = "metadata_iv_b64", defaultValue = "''")
    val metadataIvB64: String = "",
    @ColumnInfo(name = "wrapped_dek_b64", defaultValue = "''")
    val wrappedDekB64: String = "",
    @ColumnInfo(name = "dek_iv_b64", defaultValue = "''")
    val dekIvB64: String = "",
    @ColumnInfo(name = "file_nonce_prefix_b64", defaultValue = "''")
    val fileNoncePrefixB64: String = "",
    @ColumnInfo(name = "encrypted_size_bytes", defaultValue = "0")
    val encryptedSizeBytes: Long = 0L,
    @ColumnInfo(name = "chunk_count", defaultValue = "0")
    val chunkCount: Int = 0
)

fun TransferHistoryEntity.toDomain(): TransferRecord = TransferRecord(
    id = id,
    sessionId = sessionId,
    fileName = fileName,
    mimeType = mimeType,
    fileType = runCatching { TransferFileType.valueOf(fileType) }.getOrDefault(TransferFileType.OTHER),
    fileSizeBytes = fileSizeBytes,
    bytesTransferred = bytesTransferred,
    direction = runCatching { TransferDirection.valueOf(direction) }.getOrDefault(TransferDirection.RECEIVED),
    status = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED),
    peerDeviceId = peerDeviceId,
    peerDeviceName = peerDeviceName,
    localUri = localUri,
    sha256Checksum = sha256Checksum,
    isChecksumVerified = isChecksumVerified,
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis
)

fun TransferRecord.toEntity(): TransferHistoryEntity = TransferHistoryEntity(
    id = id,
    sessionId = sessionId,
    fileName = fileName,
    mimeType = mimeType,
    fileType = fileType.name,
    fileSizeBytes = fileSizeBytes,
    bytesTransferred = bytesTransferred,
    direction = direction.name,
    status = status.name,
    peerDeviceId = peerDeviceId,
    peerDeviceName = peerDeviceName,
    localUri = localUri,
    sha256Checksum = sha256Checksum,
    isChecksumVerified = isChecksumVerified,
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis
)

fun TrustedDeviceEntity.toDomain(): PeerDevice = PeerDevice(
    deviceId = deviceId,
    displayName = displayName,
    deviceType = runCatching { DeviceType.valueOf(deviceType) }.getOrDefault(DeviceType.UNKNOWN),
    connectionType = runCatching { ConnectionType.valueOf(connectionType) }.getOrDefault(ConnectionType.WIFI_DIRECT),
    publicKeyFingerprint = publicKeyFingerprint,
    isTrusted = true,
    lastSeenEpochMillis = lastSeenEpochMillis
)

fun PeerDevice.toTrustedEntity(): TrustedDeviceEntity = TrustedDeviceEntity(
    deviceId = deviceId,
    displayName = displayName,
    deviceType = deviceType.name,
    connectionType = connectionType.name,
    publicKeyFingerprint = publicKeyFingerprint,
    lastSeenEpochMillis = lastSeenEpochMillis
)

fun VaultItemEntity.toDomain(): VaultItem = VaultItem(
    id = id,
    displayName = displayName,
    encryptedFileName = encryptedFileName,
    originalMimeType = originalMimeType,
    originalSizeBytes = originalSizeBytes,
    sha256Checksum = sha256Checksum,
    keyAlias = keyAlias,
    createdAtEpochMillis = createdAtEpochMillis
)

fun VaultItem.toEntity(): VaultItemEntity = VaultItemEntity(
    id = id,
    displayName = displayName,
    encryptedFileName = encryptedFileName,
    originalMimeType = originalMimeType,
    originalSizeBytes = originalSizeBytes,
    sha256Checksum = sha256Checksum,
    keyAlias = keyAlias,
    createdAtEpochMillis = createdAtEpochMillis
)
