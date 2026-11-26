package com.example.shareflow.domain.model

import androidx.compose.runtime.Immutable

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

/**
 * Canonical production state machine for ShareFlow File Transfer Engine sessions and file items.
 */
enum class TransferState {
    PENDING,
    CONNECTING,
    WAITING_APPROVAL,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = this == PENDING ||
            this == CONNECTING ||
            this == WAITING_APPROVAL ||
            this == TRANSFERRING ||
            this == PAUSED ||
            this == VERIFYING

    fun toHistoryStatus(): TransferStatus = when (this) {
        PENDING, CONNECTING, WAITING_APPROVAL -> TransferStatus.QUEUED
        TRANSFERRING, PAUSED -> TransferStatus.IN_PROGRESS
        VERIFYING -> TransferStatus.VERIFYING
        COMPLETED -> TransferStatus.COMPLETED_VERIFIED
        FAILED -> TransferStatus.FAILED
        CANCELLED -> TransferStatus.CANCELLED
    }
}

enum class TransferFileType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    APK,
    ARCHIVE,
    OTHER;

    companion object {
        fun fromCategory(category: FileCategory): TransferFileType = when (category) {
            FileCategory.IMAGES -> IMAGE
            FileCategory.VIDEOS -> VIDEO
            FileCategory.AUDIO -> AUDIO
            FileCategory.DOCUMENTS -> DOCUMENT
            FileCategory.APKS -> APK
            FileCategory.ARCHIVES -> ARCHIVE
            FileCategory.DOWNLOADS, FileCategory.OTHER -> OTHER
        }
    }
}

/**
 * Immutable domain model for a persisted transfer history entry.
 * Records are only marked COMPLETED_VERIFIED after real byte verification.
 */
@Immutable
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
 * Configurable streaming parameters for chunked file transfer and backpressure control.
 */
data class TransferConfig(
    val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES,
    val maxInFlightChunks: Int = DEFAULT_MAX_IN_FLIGHT_CHUNKS,
    val maxConcurrentFileTransfers: Int = 1,
    val maxChunkRetries: Int = 3,
    val storageSafetyMarginBytes: Long = DEFAULT_STORAGE_SAFETY_MARGIN_BYTES
) {
    val validatedChunkSizeBytes: Int
        get() = chunkSizeBytes.coerceIn(MIN_CHUNK_SIZE_BYTES, MAX_CHUNK_SIZE_BYTES)

    val validatedMaxInFlight: Int
        get() = maxInFlightChunks.coerceIn(1, 32)

    companion object {
        const val MIN_CHUNK_SIZE_BYTES: Int = 16 * 1024
        const val DEFAULT_CHUNK_SIZE_BYTES: Int = 64 * 1024
        const val MAX_CHUNK_SIZE_BYTES: Int = 512 * 1024
        const val DEFAULT_MAX_IN_FLIGHT_CHUNKS: Int = 8
        const val DEFAULT_STORAGE_SAFETY_MARGIN_BYTES: Long = 10L * 1024L * 1024L
    }
}

/**
 * Input descriptor for enqueuing a real local file or directory member into the transfer engine.
 */
data class TransferSourceItem(
    val uriString: String,
    val fileName: String,
    val relativePath: String = "",
    val mimeType: String = "application/octet-stream",
    val fileType: TransferFileType = TransferFileType.OTHER,
    val fileSizeBytes: Long
)

/**
 * Individual file transfer unit within a [TransferSession], persisted in Room for recovery.
 */
@Immutable
data class TransferItem(
    val itemId: String,
    val sessionId: String,
    val fileName: String,
    val relativePath: String = "",
    val mimeType: String = "application/octet-stream",
    val fileType: TransferFileType = TransferFileType.OTHER,
    val fileSizeBytes: Long,
    val bytesTransferred: Long = 0L,
    val chunkSizeBytes: Int = TransferConfig.DEFAULT_CHUNK_SIZE_BYTES,
    val totalChunks: Int = 0,
    val nextChunkIndex: Int = 0,
    val expectedSha256: String = "",
    val verifiedSha256: String? = null,
    val sourceUri: String? = null,
    val destinationUri: String? = null,
    val tempFilePath: String? = null,
    val state: TransferState = TransferState.PENDING,
    val errorMessage: String? = null,
    val isRecoverable: Boolean = false,
    val startedAtEpochMillis: Long = 0L,
    val completedAtEpochMillis: Long? = null
) {
    val progressFraction: Float
        get() = when {
            state == TransferState.COMPLETED -> 1f
            fileSizeBytes <= 0L -> 0f
            else -> (bytesTransferred.toFloat() / fileSizeBytes.toFloat()).coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val displayPath: String
        get() = if (relativePath.isBlank()) fileName else "$relativePath/$fileName"
}

/**
 * Pending incoming file transfer offer from a connected, authenticated peer.
 */
@Immutable
data class IncomingTransferOffer(
    val sessionId: String,
    val peer: PeerDevice,
    val chunkSizeBytes: Int,
    val items: List<TransferItem>,
    val totalBytes: Long,
    val receivedAtEpochMillis: Long
)

/**
 * Outcome of strict [TransferRecoveryManager] verification on a persisted incomplete session.
 */
data class RecoveryVerificationReport(
    val sessionId: String,
    val isResumable: Boolean,
    val verifiedResumableItems: Int,
    val resetToZeroItems: Int,
    val unrecoverableItems: Int,
    val reason: String
)

/**
 * Active or persisted transfer session managed by the ShareFlow File Transfer Engine.
 * All progress, speed, and ETA metrics reflect strictly real transferred bytes.
 */
@Immutable
data class TransferSession(
    val sessionId: String,
    val peer: PeerDevice,
    val direction: TransferDirection,
    val items: List<TransferRecord> = emptyList(),
    val currentSpeedBytesPerSec: Long = 0L,
    val state: TransferState = TransferState.PENDING,
    val transferItems: List<TransferItem> = emptyList(),
    val chunkSizeBytes: Int = TransferConfig.DEFAULT_CHUNK_SIZE_BYTES,
    val estimatedRemainingSeconds: Long? = null,
    val destinationRootPath: String? = null,
    val errorMessage: String? = null,
    val isResumable: Boolean = false,
    val createdAtEpochMillis: Long = 0L,
    val updatedAtEpochMillis: Long = 0L,
    val completedAtEpochMillis: Long? = null
) {
    val totalBytes: Long = if (transferItems.isNotEmpty()) {
        transferItems.sumOf { it.fileSizeBytes }
    } else {
        items.sumOf { it.fileSizeBytes }
    }

    val transferredBytes: Long = if (transferItems.isNotEmpty()) {
        transferItems.sumOf { it.bytesTransferred }
    } else {
        items.sumOf { it.bytesTransferred }
    }

    val progressFraction: Float
        get() = when {
            state == TransferState.COMPLETED -> 1f
            totalBytes <= 0L -> 0f
            else -> (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)

    val activeItem: TransferItem?
        get() = transferItems.firstOrNull {
            it.state == TransferState.TRANSFERRING || it.state == TransferState.VERIFYING
        } ?: transferItems.firstOrNull {
            it.state == TransferState.PAUSED || it.state == TransferState.PENDING
        } ?: transferItems.firstOrNull()

    val completedFileCount: Int
        get() = transferItems.count { it.state == TransferState.COMPLETED }

    val totalFileCount: Int
        get() = if (transferItems.isNotEmpty()) transferItems.size else items.size
}
