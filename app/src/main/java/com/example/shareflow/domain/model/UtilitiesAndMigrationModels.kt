package com.example.shareflow.domain.model

/**
 * Persisted storage capacity snapshot used to display real storage trends over time.
 */
data class StorageTrendPoint(
    val id: Long = 0L,
    val capturedAtEpochMillis: Long,
    val totalBytes: Long,
    val usedBytes: Long,
    val availableBytes: Long
)

/**
 * Full Storage Analyzer report combining StatFs totals, accessible media categories,
 * large files, and persisted storage trend history.
 */
data class StorageAnalysisReport(
    val summary: StorageSummary,
    val largeFiles: List<LocalFileEntry>,
    val largeFileThresholdBytes: Long,
    val trends: List<StorageTrendPoint>,
    val scannedAccessibleFilesCount: Int
) {
    val trendDeltaBytes: Long?
        get() {
            if (trends.size < 2) return null
            val latest = trends.first()
            val previous = trends[1]
            return latest.usedBytes - previous.usedBytes
        }
}

/**
 * Confirmed duplicate group verified via metadata size pre-filter + streaming SHA-256 hash.
 * Never deleted automatically — requires explicit user selection and confirmation.
 */
data class DuplicateFileGroup(
    val sha256Hex: String,
    val sizeBytes: Long,
    val files: List<LocalFileEntry>
) {
    val reclaimableBytes: Long
        get() = sizeBytes * (files.size - 1).coerceAtLeast(0)
}

data class DuplicateScanReport(
    val totalFilesInspected: Int,
    val candidatesHashed: Int,
    val skippedInaccessibleCount: Int,
    val groups: List<DuplicateFileGroup>
) {
    val totalDuplicateCopies: Int
        get() = groups.sumOf { (it.files.size - 1).coerceAtLeast(0) }

    val totalReclaimableBytes: Long
        get() = groups.sumOf { it.reclaimableBytes }
}

/**
 * Explicitly supported archive formats backed by tested standard libraries (java.util.zip).
 */
enum class SupportedArchiveFormat(val extension: String, val mimeType: String) {
    ZIP("zip", "application/zip")
}

data class ArchiveEntryInfo(
    val entryPath: String,
    val uncompressedSizeBytes: Long,
    val compressedSizeBytes: Long,
    val isDirectory: Boolean
)

data class ArchiveCreateResult(
    val archiveFilePath: String,
    val archiveFileName: String,
    val archiveSizeBytes: Long,
    val totalInputBytes: Long,
    val includedFileCount: Int,
    val sha256Hex: String,
    val originalsPreserved: Boolean
)

data class ArchiveExtractResult(
    val destinationDirectoryPath: String,
    val extractedFiles: List<String>,
    val totalExtractedBytes: Long,
    val skippedCount: Int
)

/**
 * Persisted favorite file bookmark (removing a favorite never deletes the underlying user file).
 */
data class FavoriteFileItem(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val category: FileCategory,
    val addedAtEpochMillis: Long,
    val isAccessible: Boolean = true
)

/**
 * Guided two-device Phone Migration workflow models.
 * Explicitly scopes migration to user-accessible files, SAF folders, and opt-in Contacts.
 */
enum class MigrationRole {
    SENDER_OLD_PHONE,
    RECEIVER_NEW_PHONE
}

enum class MigrationStep {
    PAIRING,
    CATEGORIES,
    PREVIEW,
    TRANSFERRING,
    REPORT
}

enum class MigrationCategory {
    PHOTOS,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    USER_FOLDERS,
    CONTACTS,
    OTHER_FILES
}

data class MigrationCategorySummary(
    val category: MigrationCategory,
    val itemCount: Int,
    val totalBytes: Long,
    val selected: Boolean,
    val permissionRequired: Boolean = false,
    val permissionGranted: Boolean = true,
    val note: String? = null
)

data class MigrationFailedItem(
    val displayName: String,
    val category: MigrationCategory,
    val reason: String
)

data class MigrationCompletionReport(
    val sessionId: String,
    val peerDeviceName: String,
    val completedCount: Int,
    val failedItems: List<MigrationFailedItem>,
    val totalBytesTransferred: Long,
    val completedAtEpochMillis: Long
) {
    val isFullSuccess: Boolean get() = failedItems.isEmpty() && completedCount > 0
}
