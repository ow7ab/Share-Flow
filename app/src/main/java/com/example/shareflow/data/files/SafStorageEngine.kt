package com.example.shareflow.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.FileOperationFailure
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * Storage Access Framework (SAF) operations engine.
 * Performs fast single-query child enumeration for large directories, streaming chunked copies
 * (never loading complete files into memory), safe moves, renames, folder creation, and deletion.
 */
class SafStorageEngine(
    private val context: Context,
    private val checksumVerifier: ChecksumVerifier
) {
    private val resolver = context.contentResolver

    fun listGrantedDirectories(): List<GrantedDirectory> {
        val persisted = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
        return persisted
            .filter { it.isReadPermission }
            .mapNotNull { perm ->
                val uri = perm.uri
                val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
                val accessible = runCatching { doc != null && doc.exists() && doc.canRead() }.getOrDefault(false)
                val writable = perm.isWritePermission &&
                    runCatching { doc?.canWrite() == true }.getOrDefault(false)
                val name = runCatching { doc?.name }.getOrNull()
                    ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
                    ?: uri.toString()
                GrantedDirectory(
                    treeUriString = uri.toString(),
                    displayName = name.ifBlank { "Folder" },
                    canWrite = writable,
                    isAccessible = accessible
                )
            }
            .distinctBy { it.treeUriString }
    }

    fun registerGrantedDirectory(treeUriString: String): AppResult<GrantedDirectory> {
        val uri = parseUri(treeUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid directory URI"))

        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.takePersistableUriPermission(uri, flags) }
                .recoverCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            val doc = DocumentFile.fromTreeUri(context, uri)
                ?: return AppResult.Failure(AppError.PermissionDenied("Unable to access selected folder"))

            if (!doc.exists() || !doc.canRead()) {
                return AppResult.Failure(AppError.PermissionDenied("Read permission denied for selected folder"))
            }

            AppResult.Success(
                GrantedDirectory(
                    treeUriString = uri.toString(),
                    displayName = doc.name?.ifBlank { "Folder" } ?: "Folder",
                    canWrite = doc.canWrite(),
                    isAccessible = true
                )
            )
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Folder permission denied or revoked", se))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to register directory access", t))
        }
    }

    fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit> {
        val uri = parseUri(treeUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid directory URI"))
        return runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            resolver.releasePersistableUriPermission(uri, flags)
            AppResult.Success(Unit)
        }.getOrElse {
            AppResult.Success(Unit)
        }
    }

    /**
     * Lists children of a SAF tree root or nested subfolder URI using a single ContentResolver query
     * on [DocumentsContract.buildChildDocumentsUriUsingTree] to scale cleanly to thousands of files.
     */
    fun listDirectory(directoryUriString: String): AppResult<List<LocalFileEntry>> {
        val uri = parseUri(directoryUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid directory URI"))

        return try {
            val docId = when {
                DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
                DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
                else -> null
            }

            if (docId != null && DocumentsContract.isTreeUri(uri)) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
                val queried = queryChildDocuments(
                    treeUri = uri,
                    parentUriString = directoryUriString,
                    childrenUri = childrenUri
                )
                if (queried != null) {
                    return AppResult.Success(queried)
                }
            }

            // Fallback via DocumentFile when tree URI structure is non-standard
            val docFile = DocumentFile.fromTreeUri(context, uri)
                ?: DocumentFile.fromSingleUri(context, uri)
                ?: return AppResult.Failure(AppError.NotFound("Directory not found"))

            if (!docFile.exists()) {
                return AppResult.Failure(AppError.NotFound("Directory no longer exists"))
            }
            if (!docFile.canRead()) {
                return AppResult.Failure(AppError.PermissionDenied("Read permission revoked for this directory"))
            }

            val entries = docFile.listFiles().map { child ->
                val name = child.name ?: "Unnamed"
                val isDir = child.isDirectory
                val mime = child.type ?: if (isDir) {
                    DocumentsContract.Document.MIME_TYPE_DIR
                } else {
                    "application/octet-stream"
                }
                LocalFileEntry(
                    uriString = child.uri.toString(),
                    displayName = name,
                    mimeType = mime,
                    sizeBytes = if (isDir) 0L else child.length().coerceAtLeast(0L),
                    lastModifiedEpochMillis = child.lastModified().coerceAtLeast(0L),
                    category = SafDocumentInspector.classifyCategory(mime, name),
                    isDirectory = isDir,
                    parentUriString = directoryUriString,
                    source = FileSource.SAF_TREE,
                    capabilities = FileCapabilities(
                        canRead = child.canRead(),
                        canWrite = child.canWrite(),
                        canRename = child.canWrite(),
                        canDelete = child.canWrite(),
                        canCreateChildren = isDir && child.canWrite()
                    ),
                    isAccessible = child.canRead()
                )
            }
            AppResult.Success(entries)
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Directory access was revoked or denied", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("Directory no longer exists: ${fnf.message ?: directoryUriString}"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Unable to read directory contents", t))
        }
    }

    /**
     * Performs a bounded breadth-first recursive scan of a persisted SAF tree URI (including
     * nested subfolders such as DCIM/Camera, Pictures/Screenshots, Movies, Documents) while
     * skipping hidden dot-folders, respecting [maxDepth] and [maxEntries], and deduplicating URIs.
     */
    fun listDirectoryRecursive(
        treeUriString: String,
        maxDepth: Int = DEFAULT_RECURSIVE_MAX_DEPTH,
        maxEntries: Int = DEFAULT_RECURSIVE_MAX_ENTRIES
    ): AppResult<List<LocalFileEntry>> {
        val rootUri = parseUri(treeUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid directory URI"))
        val safeMaxDepth = maxDepth.coerceIn(0, 8)
        val safeMaxEntries = maxEntries.coerceIn(1, 10_000)

        val collected = ArrayList<LocalFileEntry>()
        val seenFileUris = HashSet<String>()
        val visitedDirs = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootUri.toString() to 0)
        visitedDirs.add(rootUri.toString())

        var firstError: AppError? = null

        while (queue.isNotEmpty() && collected.size < safeMaxEntries) {
            val (dirUri, depth) = queue.removeFirst()
            when (val listed = listDirectory(dirUri)) {
                is AppResult.Success -> {
                    for (entry in listed.data) {
                        if (collected.size >= safeMaxEntries) break
                        if (entry.isDirectory) {
                            val name = entry.displayName.trim()
                            val skipHidden = name.startsWith(".") ||
                                name.equals("Android", ignoreCase = true) ||
                                name.equals("LOST.DIR", ignoreCase = true)
                            if (depth < safeMaxDepth && !skipHidden && visitedDirs.add(entry.uriString)) {
                                queue.add(entry.uriString to (depth + 1))
                            }
                        } else if (entry.isAccessible && seenFileUris.add(entry.uriString)) {
                            collected.add(entry)
                        }
                    }
                }
                is AppResult.Failure -> {
                    if (depth == 0 && firstError == null) {
                        firstError = listed.error
                    }
                }
            }
        }

        return if (collected.isEmpty() && firstError != null) {
            AppResult.Failure(firstError)
        } else {
            AppResult.Success(collected)
        }
    }

    private fun queryChildDocuments(
        treeUri: Uri,
        parentUriString: String,
        childrenUri: Uri
    ): List<LocalFileEntry>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        val cursor = resolver.query(childrenUri, projection, null, null, null) ?: return null
        val entries = ArrayList<LocalFileEntry>()
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val flagsIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)

            while (c.moveToNext()) {
                if (idIdx < 0 || c.isNull(idIdx)) continue
                val childId = c.getString(idIdx)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else childId
                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) {
                    c.getString(mimeIdx) ?: "application/octet-stream"
                } else {
                    "application/octet-stream"
                }
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (!isDir && sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                    c.getLong(sizeIdx).coerceAtLeast(0L)
                } else {
                    0L
                }
                val modified = if (modIdx >= 0 && !c.isNull(modIdx)) {
                    c.getLong(modIdx).coerceAtLeast(0L)
                } else {
                    0L
                }
                val flags = if (flagsIdx >= 0 && !c.isNull(flagsIdx)) c.getInt(flagsIdx) else 0

                val canWrite = (flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                val canRename = (flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0
                val canDelete = (flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                val canCreate = isDir && (flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0

                entries.add(
                    LocalFileEntry(
                        uriString = childUri.toString(),
                        displayName = name,
                        mimeType = mime,
                        sizeBytes = size,
                        lastModifiedEpochMillis = modified,
                        category = SafDocumentInspector.classifyCategory(mime, name),
                        isDirectory = isDir,
                        parentUriString = parentUriString,
                        source = FileSource.SAF_TREE,
                        capabilities = FileCapabilities(
                            canRead = true,
                            canWrite = canWrite,
                            canRename = canRename,
                            canDelete = canDelete,
                            canCreateChildren = canCreate
                        ),
                        isAccessible = true
                    )
                )
            }
        }
        return entries
    }

    fun inspectFile(documentUriString: String): AppResult<LocalFileEntry> {
        val uri = parseUri(documentUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid file URI"))
        return try {
            val entry = SafDocumentInspector.inspectUri(context, uri)
                ?: return AppResult.Failure(AppError.NotFound("File could not be found or inspected"))
            AppResult.Success(entry)
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Permission denied for file", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("File not found: ${fnf.message ?: documentUriString}"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to inspect file", t))
        }
    }

    fun createFolder(parentDirectoryUriString: String, folderName: String): AppResult<LocalFileEntry> {
        val sanitized = FileNameValidator.sanitize(folderName)
        if (!FileNameValidator.isValid(sanitized)) {
            return AppResult.Failure(AppError.Validation("Invalid folder name"))
        }
        val parentUri = parseUri(parentDirectoryUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid parent folder URI"))

        return try {
            val parentDoc = resolveDirectoryDocumentFile(parentUri)
                ?: return AppResult.Failure(AppError.NotFound("Target folder not found"))
            if (!parentDoc.canWrite()) {
                return AppResult.Failure(AppError.PermissionDenied("Write permission required to create folders"))
            }
            val created = parentDoc.createDirectory(sanitized)
                ?: return AppResult.Failure(AppError.Storage("Provider could not create folder \"$sanitized\""))

            AppResult.Success(
                LocalFileEntry(
                    uriString = created.uri.toString(),
                    displayName = created.name ?: sanitized,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    sizeBytes = 0L,
                    lastModifiedEpochMillis = created.lastModified().coerceAtLeast(0L),
                    category = SafDocumentInspector.classifyCategory(
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        sanitized
                    ),
                    isDirectory = true,
                    parentUriString = parentDirectoryUriString,
                    source = FileSource.SAF_TREE,
                    capabilities = FileCapabilities(
                        canRead = true,
                        canWrite = created.canWrite(),
                        canRename = created.canWrite(),
                        canDelete = created.canWrite(),
                        canCreateChildren = created.canWrite()
                    ),
                    isAccessible = true
                )
            )
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Write permission denied when creating folder", se))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to create folder", t))
        }
    }

    fun renameFile(documentUriString: String, newName: String): AppResult<LocalFileEntry> {
        val sanitized = FileNameValidator.sanitize(newName)
        if (!FileNameValidator.isValid(sanitized)) {
            return AppResult.Failure(AppError.Validation("Invalid file name"))
        }
        val uri = parseUri(documentUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid document URI"))

        return try {
            val renamedUri = runCatching {
                DocumentsContract.renameDocument(resolver, uri, sanitized)
            }.getOrNull()

            if (renamedUri != null) {
                return inspectFile(renamedUri.toString())
            }

            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: return AppResult.Failure(AppError.NotFound("File not found"))
            if (!doc.canWrite()) {
                return AppResult.Failure(AppError.PermissionDenied("Rename is not permitted for this file"))
            }
            val ok = doc.renameTo(sanitized)
            if (!ok) {
                return AppResult.Failure(AppError.Storage("Provider rejected rename to \"$sanitized\""))
            }
            inspectFile(doc.uri.toString())
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Rename permission denied", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("File no longer exists"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Unable to rename file", t))
        }
    }

    fun copyFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult> {
        if (sourceUris.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No files selected to copy"))
        }
        val targetUri = parseUri(targetDirectoryUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid destination folder URI"))

        val targetDoc = try {
            resolveDirectoryDocumentFile(targetUri)
        } catch (se: SecurityException) {
            return AppResult.Failure(AppError.PermissionDenied("Destination folder permission denied", se))
        } ?: return AppResult.Failure(AppError.NotFound("Destination folder not found"))

        if (!targetDoc.exists()) {
            return AppResult.Failure(AppError.NotFound("Destination folder no longer exists"))
        }
        if (!targetDoc.canWrite()) {
            return AppResult.Failure(AppError.PermissionDenied("Destination folder is read-only"))
        }

        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<FileOperationFailure>()

        for (rawSource in sourceUris) {
            val copyOutcome = copySingleFile(rawSource, targetDoc)
            when (copyOutcome) {
                is AppResult.Success -> succeeded.add(copyOutcome.data)
                is AppResult.Failure -> failures.add(
                    FileOperationFailure(
                        uriString = rawSource,
                        displayName = null,
                        reason = copyOutcome.error.message
                    )
                )
            }
        }

        return AppResult.Success(FileOperationResult(succeededUris = succeeded, failures = failures))
    }

    fun moveFiles(
        sourceUris: List<String>,
        targetDirectoryUriString: String
    ): AppResult<FileOperationResult> {
        if (sourceUris.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No files selected to move"))
        }
        val targetUri = parseUri(targetDirectoryUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid destination folder URI"))

        val targetDoc = try {
            resolveDirectoryDocumentFile(targetUri)
        } catch (se: SecurityException) {
            return AppResult.Failure(AppError.PermissionDenied("Destination folder permission denied", se))
        } ?: return AppResult.Failure(AppError.NotFound("Destination folder not found"))

        if (!targetDoc.exists()) {
            return AppResult.Failure(AppError.NotFound("Destination folder no longer exists"))
        }
        if (!targetDoc.canWrite()) {
            return AppResult.Failure(AppError.PermissionDenied("Destination folder is read-only"))
        }

        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<FileOperationFailure>()

        for (rawSource in sourceUris) {
            val copyOutcome = copySingleFile(rawSource, targetDoc)
            when (copyOutcome) {
                is AppResult.Failure -> {
                    // Never delete source if copy fails!
                    failures.add(
                        FileOperationFailure(
                            uriString = rawSource,
                            displayName = null,
                            reason = copyOutcome.error.message
                        )
                    )
                }

                is AppResult.Success -> {
                    val deleteOutcome = deleteSingleUri(rawSource)
                    if (deleteOutcome is AppResult.Success) {
                        succeeded.add(copyOutcome.data)
                    } else {
                        val reason = (deleteOutcome as AppResult.Failure).error.message
                        failures.add(
                            FileOperationFailure(
                                uriString = rawSource,
                                displayName = null,
                                reason = "Copied to destination, but could not remove source: $reason"
                            )
                        )
                    }
                }
            }
        }

        return AppResult.Success(FileOperationResult(succeededUris = succeeded, failures = failures))
    }

    fun deleteFiles(documentUris: List<String>): AppResult<FileOperationResult> {
        if (documentUris.isEmpty()) {
            return AppResult.Failure(AppError.Validation("No files selected to delete"))
        }
        val succeeded = mutableListOf<String>()
        val failures = mutableListOf<FileOperationFailure>()

        for (rawUri in documentUris) {
            when (val outcome = deleteSingleUri(rawUri)) {
                is AppResult.Success -> succeeded.add(rawUri)
                is AppResult.Failure -> failures.add(
                    FileOperationFailure(
                        uriString = rawUri,
                        displayName = null,
                        reason = outcome.error.message
                    )
                )
            }
        }
        return AppResult.Success(FileOperationResult(succeededUris = succeeded, failures = failures))
    }

    fun readTextPreview(documentUriString: String, maxBytes: Int): AppResult<String> {
        val uri = parseUri(documentUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid file URI"))
        val safeMax = maxBytes.coerceIn(256, MAX_TEXT_PREVIEW_BYTES)

        return try {
            val stream = resolver.openInputStream(uri)
                ?: return AppResult.Failure(AppError.NotFound("Unable to open file for preview"))
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (totalRead < safeMax) {
                    val toRead = (safeMax - totalRead).coerceAtMost(buffer.size)
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    totalRead += read
                }
                val text = out.toString(Charsets.UTF_8.name())
                AppResult.Success(text)
            }
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Read permission denied for file preview", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("File no longer exists"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to read file preview", t))
        }
    }

    suspend fun computeSha256(documentUriString: String): AppResult<String> {
        val uri = parseUri(documentUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid file URI"))
        return try {
            val stream = resolver.openInputStream(uri)
                ?: return AppResult.Failure(AppError.NotFound("Unable to open file for SHA-256"))
            stream.use { input -> checksumVerifier.computeSha256(input) }
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Permission denied reading file for checksum", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("File no longer exists"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to compute file SHA-256", t))
        }
    }

    private fun copySingleFile(sourceUriString: String, targetDir: DocumentFile): AppResult<String> {
        val sourceUri = parseUri(sourceUriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid source URI"))

        val inspected = SafDocumentInspector.inspectUri(context, sourceUri)
            ?: return AppResult.Failure(AppError.NotFound("Source file missing or inaccessible"))

        if (inspected.isDirectory) {
            return AppResult.Failure(AppError.NotSupported("Copying folders recursively is not supported yet"))
        }

        val targetName = resolveUniqueName(targetDir, inspected.displayName)
        val created = targetDir.createFile(inspected.mimeType, targetName)
            ?: return AppResult.Failure(AppError.Storage("Unable to create destination file \"$targetName\""))

        return try {
            val input = resolver.openInputStream(sourceUri)
                ?: run {
                    created.delete()
                    return AppResult.Failure(AppError.NotFound("Cannot open source file stream"))
                }
            val output = resolver.openOutputStream(created.uri)
                ?: run {
                    input.close()
                    created.delete()
                    return AppResult.Failure(AppError.Storage("Cannot open destination output stream"))
                }

            var totalWritten = 0L
            input.use { src ->
                output.use { dst ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val read = src.read(buffer)
                        if (read <= 0) break
                        dst.write(buffer, 0, read)
                        totalWritten += read
                    }
                    dst.flush()
                }
            }

            if (inspected.sizeBytes > 0L && totalWritten == 0L) {
                created.delete()
                return AppResult.Failure(AppError.Storage("Copy produced empty output for \"${inspected.displayName}\""))
            }

            AppResult.Success(created.uri.toString())
        } catch (se: SecurityException) {
            runCatching { created.delete() }
            AppResult.Failure(AppError.PermissionDenied("Permission denied while copying file", se))
        } catch (fnf: FileNotFoundException) {
            runCatching { created.delete() }
            AppResult.Failure(AppError.NotFound("Source file disappeared during copy"))
        } catch (t: Throwable) {
            runCatching { created.delete() }
            AppResult.Failure(AppError.Storage("I/O error while copying file", t))
        }
    }

    private fun deleteSingleUri(uriString: String): AppResult<Unit> {
        val uri = parseUri(uriString)
            ?: return AppResult.Failure(AppError.Validation("Invalid file URI"))
        if (uri.scheme != "content") {
            return AppResult.Failure(AppError.Validation("Unsupported URI scheme for deletion"))
        }
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                if (deleted) return AppResult.Success(Unit)

                val doc = DocumentFile.fromSingleUri(context, uri)
                if (doc != null && doc.exists()) {
                    if (!doc.canWrite()) {
                        return AppResult.Failure(AppError.PermissionDenied("Delete is not permitted for this file"))
                    }
                    if (doc.delete()) return AppResult.Success(Unit)
                }
                return AppResult.Failure(AppError.NotFound("File not found or already deleted"))
            } else {
                val client = resolver.acquireUnstableContentProviderClient(uri)
                    ?: return AppResult.Failure(AppError.NotFound("Storage provider not found for file URI"))
                val rows = try {
                    client.delete(uri, null, null)
                } finally {
                    client.close()
                }
                if (rows > 0) {
                    AppResult.Success(Unit)
                } else {
                    AppResult.Failure(AppError.NotFound("File not found or cannot be deleted"))
                }
            }
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Delete permission denied by Android storage policy", se))
        } catch (fnf: FileNotFoundException) {
            AppResult.Failure(AppError.NotFound("File not found"))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage("Failed to delete file", t))
        }
    }

    private fun resolveDirectoryDocumentFile(uri: Uri): DocumentFile? {
        return if (DocumentsContract.isTreeUri(uri)) {
            DocumentFile.fromTreeUri(context, uri)
        } else {
            DocumentFile.fromSingleUri(context, uri)
        }
    }

    private fun resolveUniqueName(targetDir: DocumentFile, desiredName: String): String {
        val existingNames = runCatching {
            targetDir.listFiles().mapNotNull { it.name?.lowercase() }.toSet()
        }.getOrDefault(emptySet())

        if (desiredName.lowercase() !in existingNames) return desiredName

        val dotIdx = desiredName.lastIndexOf('.')
        val base = if (dotIdx > 0) desiredName.substring(0, dotIdx) else desiredName
        val ext = if (dotIdx > 0) desiredName.substring(dotIdx) else ""

        for (index in 1..999) {
            val candidate = "$base ($index)$ext"
            if (candidate.lowercase() !in existingNames) return candidate
        }
        return "${base}_${System.currentTimeMillis()}$ext"
    }

    private fun parseUri(raw: String): Uri? =
        if (raw.isBlank()) null else runCatching { Uri.parse(raw) }.getOrNull()

    companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_TEXT_PREVIEW_BYTES = 64 * 1024
        const val DEFAULT_RECURSIVE_MAX_DEPTH = 4
        const val DEFAULT_RECURSIVE_MAX_ENTRIES = 3_000
    }
}
