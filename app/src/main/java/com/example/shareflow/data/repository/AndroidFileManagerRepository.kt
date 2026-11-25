package com.example.shareflow.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.data.files.MediaStoreScanner
import com.example.shareflow.data.files.SafStorageEngine
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.network.transfer.ChunkProcessor
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext

/**
 * Production SAF + MediaStore + ShareFlowReceived implementation of [FileManagerRepository].
 * Runs all ContentResolver/DocumentFile/file I/O on [DispatcherProvider.io] and reacts to storage changes.
 */
class AndroidFileManagerRepository(
    context: Context,
    private val checksumVerifier: ChecksumVerifier,
    private val dispatchers: DispatcherProvider,
    private val receiveRootProvider: () -> File = { ChunkProcessor.defaultReceiveRoot(context.applicationContext) }
) : FileManagerRepository {

    private val appContext = context.applicationContext
    private val mediaStoreScanner = MediaStoreScanner(appContext.contentResolver)
    private val safEngine = SafStorageEngine(appContext, checksumVerifier)

    private val mutationEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    // Populated asynchronously on IO dispatcher — never runs DocumentFile I/O on Main Thread at DI init.
    private val grantedDirectoriesState = MutableStateFlow<List<GrantedDirectory>>(emptyList())

    @Volatile
    private var cachedMediaIndex: List<LocalFileEntry>? = null

    @Volatile
    private var cachedMediaIndexTimestampMs: Long = 0L

    private fun invalidateMediaCache() {
        cachedMediaIndex = null
        cachedMediaIndexTimestampMs = 0L
    }

    override fun observeStorageChanges(): Flow<Unit> = merge(
        mediaStoreScanner.observeChanges(),
        mutationEvents
    )

    override fun notifyStorageChanged() {
        invalidateMediaCache()
        mutationEvents.tryEmit(Unit)
    }

    override fun observeGrantedDirectories(): Flow<List<GrantedDirectory>> =
        grantedDirectoriesState.asStateFlow()

    override suspend fun registerGrantedDirectory(treeUriString: String): AppResult<GrantedDirectory> =
        withContext(dispatchers.io) {
            val result = safEngine.registerGrantedDirectory(treeUriString)
            refreshGrantedDirectories()
            if (result is AppResult.Success) {
                invalidateMediaCache()
                mutationEvents.tryEmit(Unit)
            }
            result
        }

    override suspend fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            val result = safEngine.releaseGrantedDirectory(treeUriString)
            refreshGrantedDirectories()
            invalidateMediaCache()
            mutationEvents.tryEmit(Unit)
            result
        }

    override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> =
        withContext(dispatchers.io) {
            refreshGrantedDirectories()
            val now = System.currentTimeMillis()
            val cached = cachedMediaIndex
            if (cached != null && (now - cachedMediaIndexTimestampMs) < MEDIA_CACHE_TTL_MS) {
                return@withContext AppResult.Success(cached)
            }

            val mediaResult = mediaStoreScanner.scanAccessibleMedia()
            val receivedEntries = scanReceivedDirectoryEntries()
            val safEntries = scanGrantedSafDirectoriesRecursive()
            val mediaEntries = (mediaResult as? AppResult.Success)?.data.orEmpty()
            val merged = mergeDeduplicatedEntries(
                primary = mediaEntries,
                secondary = receivedEntries + safEntries
            )
            if (merged.isEmpty() && mediaResult is AppResult.Failure && grantedDirectoriesState.value.isEmpty()) {
                mediaResult
            } else {
                cachedMediaIndex = merged
                cachedMediaIndexTimestampMs = now
                AppResult.Success(merged)
            }
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
            resolveManagedLocalFile(documentUriString)?.let { localFile ->
                return@withContext AppResult.Success(localFileToEntry(localFile))
            }
            safEngine.inspectFile(documentUriString)
        }

    override suspend fun queryByCategory(category: FileCategory): AppResult<List<LocalFileEntry>> =
        withContext(dispatchers.io) {
            when (val mediaResult = scanAccessibleMedia()) {
                is AppResult.Success -> {
                    val filtered = mediaResult.data.filter { !it.isDirectory && it.matchesCategory(category) }
                    AppResult.Success(filtered)
                }
                is AppResult.Failure -> mediaResult
            }
        }

    override suspend fun createFolder(
        parentDirectoryUriString: String,
        folderName: String
    ): AppResult<LocalFileEntry> = withContext(dispatchers.io) {
        val result = safEngine.createFolder(parentDirectoryUriString, folderName)
        if (result is AppResult.Success) {
            invalidateMediaCache()
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun renameFile(
        documentUriString: String,
        newName: String
    ): AppResult<LocalFileEntry> = withContext(dispatchers.io) {
        resolveManagedLocalFile(documentUriString)?.let { localFile ->
            if (!FileNameValidator.isValid(newName)) {
                return@withContext AppResult.Failure(AppError.Validation("Invalid file name."))
            }
            val parent = localFile.parentFile?.canonicalFile
                ?: return@withContext AppResult.Failure(AppError.Storage("Parent directory unavailable."))
            val target = File(parent, newName.trim()).canonicalFile
            if (target.parentFile != parent) {
                return@withContext AppResult.Failure(AppError.Security("Unsafe destination file name blocked."))
            }
            if (target.exists()) {
                return@withContext AppResult.Failure(AppError.Validation("A file with that name already exists."))
            }
            return@withContext if (localFile.renameTo(target)) {
                invalidateMediaCache()
                mutationEvents.tryEmit(Unit)
                AppResult.Success(localFileToEntry(target))
            } else {
                AppResult.Failure(AppError.Storage("Unable to rename file."))
            }
        }
        val result = safEngine.renameFile(documentUriString, newName)
        if (result is AppResult.Success) {
            invalidateMediaCache()
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
            invalidateMediaCache()
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
            invalidateMediaCache()
            mutationEvents.tryEmit(Unit)
        }
        result
    }

    override suspend fun deleteFiles(
        documentUris: List<String>
    ): AppResult<FileOperationResult> = withContext(dispatchers.io) {
        val localTargets = mutableListOf<Pair<String, File>>()
        val safTargets = mutableListOf<String>()
        for (uriStr in documentUris) {
            val local = resolveManagedLocalFile(uriStr)
            if (local != null) {
                localTargets.add(uriStr to local)
            } else {
                safTargets.add(uriStr)
            }
        }

        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<com.example.shareflow.domain.model.FileOperationFailure>()

        for ((uriStr, file) in localTargets) {
            if (!file.exists() || file.delete()) {
                succeeded.add(uriStr)
            } else {
                failures.add(
                    com.example.shareflow.domain.model.FileOperationFailure(
                        uriString = uriStr,
                        displayName = file.name,
                        reason = "Unable to delete ${file.name}"
                    )
                )
            }
        }

        if (safTargets.isNotEmpty()) {
            when (val safRes = safEngine.deleteFiles(safTargets)) {
                is AppResult.Success -> {
                    succeeded.addAll(safRes.data.succeededUris)
                    failures.addAll(safRes.data.failures)
                }
                is AppResult.Failure -> {
                    safTargets.forEach { target ->
                        failures.add(
                            com.example.shareflow.domain.model.FileOperationFailure(
                                uriString = target,
                                displayName = null,
                                reason = safRes.error.message
                            )
                        )
                    }
                }
            }
        }

        if (succeeded.isNotEmpty()) {
            invalidateMediaCache()
            mutationEvents.tryEmit(Unit)
        }
        AppResult.Success(
            FileOperationResult(
                succeededUris = succeeded,
                failures = failures
            )
        )
    }

    override suspend fun readTextPreview(
        documentUriString: String,
        maxBytes: Int
    ): AppResult<String> = withContext(dispatchers.io) {
        resolveManagedLocalFile(documentUriString)?.let { localFile ->
            return@withContext runCatching {
                FileInputStream(localFile).use { input ->
                    val buffer = ByteArray(maxBytes.coerceAtLeast(1024))
                    val read = input.read(buffer)
                    if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
                }
            }.fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Failure(AppError.Storage("Unable to read text preview", it)) }
            )
        }
        safEngine.readTextPreview(documentUriString, maxBytes)
    }

    override suspend fun computeSha256(documentUriString: String): AppResult<String> =
        withContext(dispatchers.io) {
            resolveManagedLocalFile(documentUriString)?.let { localFile ->
                return@withContext runCatching {
                    FileInputStream(localFile).use { input ->
                        checksumVerifier.computeSha256(input)
                    }
                }.getOrElse { AppResult.Failure(AppError.Storage("Unable to compute SHA-256", it)) }
            }
            safEngine.computeSha256(documentUriString)
        }

    private fun scanReceivedDirectoryEntries(): List<LocalFileEntry> {
        val receiveRoot = receiveRootProvider()
        if (!receiveRoot.exists() || !receiveRoot.isDirectory) return emptyList()

        return runCatching {
            receiveRoot.walkTopDown()
                .maxDepth(3)
                .onEnter { dir -> !dir.name.equals(".shareflow_staging", ignoreCase = true) }
                .filter { file ->
                    if (!file.isFile || file.length() <= 0L) return@filter false
                    val path = file.absolutePath
                    if (path.contains(".shareflow_staging")) return@filter false
                    if (file.name.equals(AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME, ignoreCase = true)) {
                        return@filter false
                    }
                    // Inside a split APK bundle folder, show only base.apk rather than confusing config splits
                    val parentHasManifest = file.parentFile?.let {
                        File(it, AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME).exists()
                    } == true
                    if (parentHasManifest && file.extension.equals("apk", ignoreCase = true) &&
                        !file.name.equals("base.apk", ignoreCase = true)
                    ) {
                        return@filter false
                    }
                    true
                }
                .map { localFileToEntry(it) }
                .sortedByDescending { it.lastModifiedEpochMillis }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun localFileToEntry(file: File): LocalFileEntry {
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "apk" -> AndroidAppSharingRepository.APK_MIME_TYPE
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "txt", "log", "md", "json" -> "text/plain"
                else -> "application/octet-stream"
            }
        val category = SafDocumentInspector.classifyCategory(mime, file.name)
        val uriString = runCatching {
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            ).toString()
        }.getOrElse {
            Uri.fromFile(file).toString()
        }

        return LocalFileEntry(
            uriString = uriString,
            displayName = file.name,
            mimeType = mime,
            sizeBytes = file.length(),
            lastModifiedEpochMillis = file.lastModified(),
            category = category,
            isDirectory = false,
            source = FileSource.MEDIA_STORE,
            capabilities = FileCapabilities(
                canRead = file.canRead(),
                canWrite = file.canWrite(),
                canDelete = file.canWrite(),
                canRename = file.canWrite(),
                canCreateChildren = false
            ),
            isAccessible = file.exists() && file.canRead(),
            isDownload = true
        )
    }

    private fun scanGrantedSafDirectoriesRecursive(): List<LocalFileEntry> {
        val granted = grantedDirectoriesState.value.filter { it.isAccessible }
        if (granted.isEmpty()) return emptyList()
        val collected = ArrayList<LocalFileEntry>()
        val remainingCap = SafStorageEngine.DEFAULT_RECURSIVE_MAX_ENTRIES
        for (dir in granted) {
            val budget = (remainingCap - collected.size).coerceAtLeast(0)
            if (budget <= 0) break
            val listed = safEngine.listDirectoryRecursive(
                treeUriString = dir.treeUriString,
                maxDepth = SafStorageEngine.DEFAULT_RECURSIVE_MAX_DEPTH,
                maxEntries = budget
            )
            if (listed is AppResult.Success) {
                collected.addAll(listed.data)
            }
        }
        return collected
    }

    private fun mergeDeduplicatedEntries(
        primary: List<LocalFileEntry>,
        secondary: List<LocalFileEntry>
    ): List<LocalFileEntry> {
        val byUri = LinkedHashMap<String, LocalFileEntry>(primary.size + secondary.size)
        val seenSignatures = HashSet<String>(primary.size + secondary.size)

        for (entry in primary) {
            if (byUri.putIfAbsent(entry.uriString, entry) == null) {
                seenSignatures.add(dedupeSignature(entry))
            }
        }
        for (entry in secondary) {
            val sig = dedupeSignature(entry)
            if (sig !in seenSignatures && !byUri.containsKey(entry.uriString)) {
                byUri[entry.uriString] = entry
                seenSignatures.add(sig)
            }
        }
        return byUri.values.sortedByDescending { it.lastModifiedEpochMillis }
    }

    private fun dedupeSignature(entry: LocalFileEntry): String =
        "${entry.displayName.trim().lowercase()}|${entry.sizeBytes}"

    private fun resolveManagedLocalFile(uriString: String): File? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val receiveRoot = runCatching { receiveRootProvider().canonicalFile }.getOrNull() ?: return null
        val rootPrefix = receiveRoot.path.trimEnd(File.separatorChar) + File.separator

        val candidate = when {
            uri.scheme == "file" -> uri.path?.let { File(it) }
            uri.scheme == "content" && uri.authority == "${appContext.packageName}.fileprovider" -> {
                val segments = uri.pathSegments.orEmpty()
                if (segments.size >= 2 &&
                    (segments[0] == "shareflow_received_external" || segments[0] == "shareflow_received_internal")
                ) {
                    val rel = segments.drop(1).joinToString(File.separator)
                    File(receiveRoot, rel)
                } else {
                    null
                }
            }
            else -> null
        } ?: return null

        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return if (canonical.path.startsWith(rootPrefix) && canonical.exists() && canonical.isFile) {
            canonical
        } else {
            null
        }
    }

    private fun refreshGrantedDirectories() {
        grantedDirectoriesState.value = safEngine.listGrantedDirectories()
    }

    companion object {
        private const val MEDIA_CACHE_TTL_MS = 3_000L
    }
}
