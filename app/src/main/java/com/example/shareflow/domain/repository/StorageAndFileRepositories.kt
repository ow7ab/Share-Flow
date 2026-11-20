package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.InstalledAppInfo
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.StorageSummary
import kotlinx.coroutines.flow.Flow

/**
 * Contract for SAF + MediaStore compliant local file management.
 * Operates strictly on Android content URIs without assuming unrestricted filesystem access.
 */
interface FileManagerRepository {
    /** Emits a signal whenever MediaStore collections or persisted SAF permissions change. */
    fun observeStorageChanges(): Flow<Unit>

    /** Emits persisted user-granted SAF directories with current accessibility & write status. */
    fun observeGrantedDirectories(): Flow<List<GrantedDirectory>>

    /** Persists read/write URI permission for a user-selected SAF directory tree. */
    suspend fun registerGrantedDirectory(treeUriString: String): AppResult<GrantedDirectory>

    /** Releases a previously persisted SAF directory tree permission. */
    suspend fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit>

    /** Scans accessible MediaStore items (Images, Video, Audio, Downloads) without broad permissions. */
    suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>>

    /** Lists children of a SAF tree or nested subfolder URI. */
    suspend fun listDirectory(treeUriString: String): AppResult<List<LocalFileEntry>>

    /** Inspects metadata and capabilities of a single content URI. */
    suspend fun inspectFile(documentUriString: String): AppResult<LocalFileEntry>

    /** Queries all accessible files (MediaStore + persisted SAF roots) matching [category]. */
    suspend fun queryByCategory(category: FileCategory): AppResult<List<LocalFileEntry>>

    /** Creates a subfolder inside a writable SAF directory. */
    suspend fun createFolder(parentDirectoryUriString: String, folderName: String): AppResult<LocalFileEntry>

    /** Renames a file or folder where permitted by its provider. */
    suspend fun renameFile(documentUriString: String, newName: String): AppResult<LocalFileEntry>

    /** Streams copies of [sourceUris] into [targetDirectoryUriString] in fixed-size chunks. */
    suspend fun copyFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult>

    /** Moves [sourceUris] into [targetDirectoryUriString], never deleting source unless copy/move succeeds. */
    suspend fun moveFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult>

    /** Deletes [documentUris] where permitted by the provider, returning per-item results. */
    suspend fun deleteFiles(documentUris: List<String>): AppResult<FileOperationResult>

    /** Reads at most [maxBytes] of a text document safely without loading large files into RAM. */
    suspend fun readTextPreview(documentUriString: String, maxBytes: Int = 16 * 1024): AppResult<String>

    /** Streams a content URI through SHA-256 verification without buffering whole file in memory. */
    suspend fun computeSha256(documentUriString: String): AppResult<String>
}

/**
 * Contract for inspecting device storage usage without broad invasive permissions.
 */
interface StorageAnalyzerRepository {
    fun observeStorageSummary(): Flow<AppResult<StorageSummary>>
    suspend fun refreshStorageSummary(): AppResult<StorageSummary>
}

/**
 * Contract for listing user-installed applications eligible for offline APK sharing.
 */
interface AppSharingRepository {
    suspend fun listShareableApps(includeSystemApps: Boolean = false): AppResult<List<InstalledAppInfo>>
}
