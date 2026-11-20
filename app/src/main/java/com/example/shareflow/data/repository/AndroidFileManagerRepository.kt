package com.example.shareflow.data.repository

import android.content.Context
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.data.files.MediaStoreScanner
import com.example.shareflow.data.files.SafStorageEngine
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.repository.FileManagerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Production SAF + MediaStore implementation of [FileManagerRepository].
 * Runs all ContentResolver/DocumentFile I/O on [DispatcherProvider.io] and reacts to storage changes.
 */
class AndroidFileManagerRepository(
    context: Context,
    private val checksumVerifier: ChecksumVerifier,
    private val dispatchers: DispatcherProvider
) : FileManagerRepository {

    private val appContext = context.applicationContext
    private val mediaStoreScanner = MediaStoreScanner(appContext.contentResolver)
    private val safEngine = SafStorageEngine(appContext, checksumVerifier)

    private val mutationEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val grantedDirectoriesState = MutableStateFlow(safEngine.listGrantedDirectories())

    override fun observeStorageChanges(): Flow<Unit> = merge(
        mediaStoreScanner.observeChanges(),
        mutationEvents
    )

    override fun observeGrantedDirectories(): Flow<List<GrantedDirectory>> =
        grantedDirectoriesState.asStateFlow()

    override suspend fun registerGrantedDirectory(treeUriString: String): AppResult<GrantedDirectory> =
        withContext(dispatchers.io) {
            val result = safEngine.registerGrantedDirectory(treeUriString)
            refreshGrantedDirectories()
            if (result is AppResult.Success) {
                mutationEvents.tryEmit(Unit)
            }
            result
        }

    override suspend fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val result = safEngine.releaseGrantedDirectory(treeUriString)
            refreshGrantedDirectories()
            mutationEvents.tryEmit(Unit)
            result
        }

    override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> =
        withContext(dispatchers.io) {
            refreshGrantedDirectories()
            mediaStoreScanner.scanAccessibleMedia()
        }

    override suspend fun listDirectory(treeUriString: String): AppResult<List<LocalFileEntry>> =
        withContext(dispatchers.io) {
            val result = safEngine.listDirectory(treeUriString)
            if (result is AppResult.Failure) {
                refreshGrantedDirectories()
            }
            result
        }

    override suspend fun inspectFile(documentUriString: String): AppResult<LocalFileEntry> =
        withContext(dispatchers.io) {
            safEngine.inspectFile(documentUriString)
        }

    override suspend fun queryByCategory(category: FileCategory): AppResult<List<LocalFileEntry>> =
        withContext(dispatchers.io) {
            val merged = LinkedHashMap<String, LocalFileEntry>()
            val mediaResult = mediaStoreScanner.scanAccessibleMedia()
            if (mediaResult is AppResult.Success) {
                mediaResult.data
                    .filter { it.matchesCategory(category) }
                    .forEach { merged[it.uriString] = it }
            }
            val granted = safEngine.listGrantedDirectories().filter { it.isAccessible }
            for (dir in granted) {
                val listed = safEngine.listDirectory(dir.treeUriString)
                if (listed is AppResult.Success) {
                    listed.data
                        .filter { !it.isDirectory && it.matchesCategory(category) }
                        .forEach { merged[it.uriString] = it }
                }
            }
            if (merged.isEmpty() && mediaResult is AppResult.Failure && granted.isEmpty()) {
                return@withContext mediaResult
            }
            AppResult.Success(merged.values.toList())
        }

    override suspend fun createFolder(
        parentDirectoryUriString: String,
        folderName: String
    ): AppResult<LocalFileEntry> = withContext(dispatchers.io) {
        val result = safEngine.createFolder(parentDirectoryUriString, folderName)
        if (result is AppResult.Success) {
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun renameFile(
        documentUriString: String,
        newName: String
    ): AppResult<LocalFileEntry> = withContext(dispatchers.io) {
        val result = safEngine.renameFile(documentUriString, newName)
        if (result is AppResult.Success) {
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun copyFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult> = withContext(dispatchers.io) {
        val result = safEngine.copyFiles(sourceUris, targetDirectoryUriString)
        if (result is AppResult.Success && result.data.succeededCount > 0) {
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun moveFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult> = withContext(dispatchers.io) {
        val result = safEngine.moveFiles(sourceUris, targetDirectoryUriString)
        if (result is AppResult.Success && result.data.succeededCount > 0) {
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun deleteFiles(
        documentUris: List<String>
    ): AppResult<FileOperationResult> = withContext(dispatchers.io) {
        val result = safEngine.deleteFiles(documentUris)
        if (result is AppResult.Success && result.data.succeededCount > 0) {
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun readTextPreview(
        documentUriString: String,
        maxBytes: Int
    ): AppResult<String> = withContext(dispatchers.io) {
        safEngine.readTextPreview(documentUriString, maxBytes)
    }

    override suspend fun computeSha256(documentUriString: String): AppResult<String> =
        withContext(dispatchers.io) {
            safEngine.computeSha256(documentUriString)
        }

    private fun refreshGrantedDirectories() {
        grantedDirectoriesState.value = safEngine.listGrantedDirectories()
    }
}
