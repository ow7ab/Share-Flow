package com.example.shareflow.domain.model

import androidx.compose.runtime.Immutable

enum class FileCategory {
    IMAGES,
    VIDEOS,
    AUDIO,
    DOCUMENTS,
    ARCHIVES,
    APKS,
    DOWNLOADS,
    OTHER
}

enum class FileSource {
    SAF_TREE,
    SAF_DOCUMENT,
    MEDIA_STORE,
    PHOTO_PICKER
}

@Immutable
data class FileCapabilities(
    val canRead: Boolean = true,
    val canWrite: Boolean = false,
    val canRename: Boolean = false,
    val canDelete: Boolean = false,
    val canCreateChildren: Boolean = false
)

enum class FileSortField {
    NAME,
    DATE_MODIFIED,
    SIZE,
    TYPE
}

enum class SortDirection {
    ASCENDING,
    DESCENDING
}

@Immutable
data class FileSortSpec(
    val field: FileSortField = FileSortField.NAME,
    val direction: SortDirection = SortDirection.ASCENDING,
    val foldersFirst: Boolean = true
)

@Immutable
data class GrantedDirectory(
    val treeUriString: String,
    val displayName: String,
    val canWrite: Boolean,
    val isAccessible: Boolean
)

@Immutable
data class DirectoryBreadcrumb(
    val uriString: String,
    val displayName: String
)

data class FileOperationFailure(
    val uriString: String,
    val displayName: String?,
    val reason: String
)

data class FileOperationResult(
    val succeededUris: List<String>,
    val failures: List<FileOperationFailure>
) {
    val succeededCount: Int get() = succeededUris.size
    val failedCount: Int get() = failures.size
    val isFullySuccessful: Boolean get() = failures.isEmpty() && succeededUris.isNotEmpty()
}

@Immutable
data class LocalFileEntry(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val category: FileCategory,
    val isDirectory: Boolean,
    val parentUriString: String? = null,
    val source: FileSource = FileSource.SAF_DOCUMENT,
    val capabilities: FileCapabilities = FileCapabilities(),
    val isAccessible: Boolean = true,
    val isDownload: Boolean = false
) {
    fun matchesCategory(target: FileCategory): Boolean = when (target) {
        FileCategory.DOWNLOADS -> isDownload || category == FileCategory.DOWNLOADS
        else -> category == target
    }
}

/**
 * Pure validator for safe file and folder names across SAF providers.
 */
object FileNameValidator {
    private const val MAX_NAME_LENGTH = 255
    private val FORBIDDEN_CHARS = setOf('/', '\\', '\u0000', ':', '*', '?', '"', '<', '>', '|')

    fun sanitize(raw: String): String = raw.trim()

    fun isValid(raw: String): Boolean {
        val trimmed = sanitize(raw)
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME_LENGTH) return false
        if (trimmed == "." || trimmed == "..") return false
        if (trimmed.any { it.isISOControl() || it in FORBIDDEN_CHARS }) return false
        return true
    }
}

@Immutable
data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkSizeBytes: Long,
    val isSystemApp: Boolean
)

/**
 * Encrypted Private Vault item metadata. Raw secrets or plaintext paths are never stored unencrypted.
 */
@Immutable
data class VaultItem(
    val id: String,
    val displayName: String,
    val encryptedFileName: String,
    val originalMimeType: String,
    val originalSizeBytes: Long,
    val sha256Checksum: String,
    val keyAlias: String,
    val createdAtEpochMillis: Long
)

@Immutable
data class StorageCategoryUsage(
    val category: FileCategory,
    val bytesUsed: Long,
    val itemCount: Int
)

@Immutable
data class StorageSummary(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val breakdown: List<StorageCategoryUsage>
)
