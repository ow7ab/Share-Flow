package com.example.shareflow.domain.model

enum class TransferDirection {
    SENT,
    RECEIVED
}

enum class TransferStatus {
    QUEUED,
    IN_PROGRESS,
    VERIFYING,
    COMPLETED_VERIFIED,
    FAILED,
    CANCELLED
}

enum class TransferFileType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    APK,
    ARCHIVE,
    OTHER
}

/**
 * Immutable domain model for a persisted transfer history entry.
 * Records are only marked COMPLETED_VERIFIED after real byte verification.
 */
data class TransferRecord(
    val id: String,
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val fileType: TransferFileType,
    val fileSizeBytes: Long,
    val bytesTransferred: Long,
    val direction: TransferDirection,
    val status: TransferStatus,
    val peerDeviceId: String,
    val peerDeviceName: String,
    val localUri: String?,
    val sha256Checksum: String?,
    val isChecksumVerified: Boolean,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long?
) {
    val progressFraction: Float
        get() = if (fileSizeBytes <= 0L) 0f else (bytesTransferred.toFloat() / fileSizeBytes.toFloat()).coerceIn(0f, 1f)
}

/**
 * Active transfer session summary exposed by the File Transfer Engine.
 */
data class TransferSession(
    val sessionId: String,
    val peer: PeerDevice,
    val direction: TransferDirection,
    val items: List<TransferRecord>,
    val currentSpeedBytesPerSec: Long = 0L
) {
    val totalBytes: Long = items.sumOf { it.fileSizeBytes }
    val transferredBytes: Long = items.sumOf { it.bytesTransferred }
}
