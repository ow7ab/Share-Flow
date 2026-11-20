package com.example.shareflow.core.common

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry

/**
 * Zero-permission Storage Access Framework URI metadata reader.
 * Inspects real user-selected documents via ContentResolver without requiring broad storage permissions.
 */
object SafDocumentInspector {

    fun inspectUris(
        context: Context,
        uris: List<Uri>,
        source: FileSource = FileSource.SAF_DOCUMENT
    ): List<LocalFileEntry> = uris.mapNotNull { uri -> inspectUri(context, uri, source) }

    fun inspectUri(
        context: Context,
        uri: Uri,
        source: FileSource = FileSource.SAF_DOCUMENT
    ): LocalFileEntry? = runCatching {
        val resolver = context.contentResolver
        val resolvedMime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Unnamed File"
        var sizeBytes = 0L
        var lastModified = 0L
        var docFlags = 0
        var isDirectory = resolvedMime == DocumentsContract.Document.MIME_TYPE_DIR

        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val cursor = runCatching {
            resolver.query(uri, projection, null, null, null)
        }.getOrElse {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
        }

        var mimeType = resolvedMime
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) {
                    displayName = c.getString(nameIndex)
                }
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) {
                    sizeBytes = c.getLong(sizeIndex).coerceAtLeast(0L)
                }
                val modifiedIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (modifiedIndex >= 0 && !c.isNull(modifiedIndex)) {
                    lastModified = c.getLong(modifiedIndex).coerceAtLeast(0L)
                }
                val flagsIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS)
                if (flagsIndex >= 0 && !c.isNull(flagsIndex)) {
                    docFlags = c.getInt(flagsIndex)
                }
                val mimeIndex = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (mimeIndex >= 0 && !c.isNull(mimeIndex)) {
                    val queriedMime = c.getString(mimeIndex)
                    if (!queriedMime.isNullOrBlank()) {
                        mimeType = queriedMime
                        isDirectory = queriedMime == DocumentsContract.Document.MIME_TYPE_DIR
                    }
                }
            }
        }

        val capabilities = FileCapabilities(
            canRead = true,
            canWrite = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0,
            canRename = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0,
            canDelete = (docFlags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0,
            canCreateChildren = isDirectory &&
                (docFlags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0
        )

        LocalFileEntry(
            uriString = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            lastModifiedEpochMillis = lastModified,
            category = classifyCategory(mimeType, displayName),
            isDirectory = isDirectory,
            source = source,
            capabilities = capabilities,
            isAccessible = true
        )
    }.getOrNull()

    fun classifyCategory(mimeType: String, fileName: String): FileCategory {
        val lowerMime = mimeType.lowercase()
        val lowerName = fileName.lowercase()
        return when {
            lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif") ||
                lowerName.endsWith(".heic") -> FileCategory.IMAGES
            lowerMime.startsWith("video/") || lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".webm") || lowerName.endsWith(".mov") -> FileCategory.VIDEOS
            lowerMime.startsWith("audio/") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                lowerName.endsWith(".flac") || lowerName.endsWith(".ogg") || lowerName.endsWith(".m4a") -> FileCategory.AUDIO
            lowerMime == "application/vnd.android.package-archive" || lowerName.endsWith(".apk") -> FileCategory.APKS
            lowerMime in ARCHIVE_MIMES || lowerName.endsWith(".zip") || lowerName.endsWith(".rar") ||
                lowerName.endsWith(".7z") || lowerName.endsWith(".tar") || lowerName.endsWith(".gz") -> FileCategory.ARCHIVES
            lowerMime.startsWith("text/") || lowerMime == "application/pdf" ||
                lowerMime.contains("document") || lowerMime.contains("spreadsheet") ||
                lowerMime.contains("presentation") || lowerName.endsWith(".pdf") ||
                lowerName.endsWith(".doc") || lowerName.endsWith(".docx") ||
                lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") ||
                lowerName.endsWith(".ppt") || lowerName.endsWith(".pptx") ||
                lowerName.endsWith(".txt") || lowerName.endsWith(".md") ||
                lowerName.endsWith(".csv") || lowerName.endsWith(".json") -> FileCategory.DOCUMENTS
            else -> FileCategory.OTHER
        }
    }

    fun mimeTypesForCategory(category: FileCategory?): Array<String> = when (category) {
        null -> arrayOf("*/*")
        FileCategory.IMAGES -> arrayOf("image/*")
        FileCategory.VIDEOS -> arrayOf("video/*")
        FileCategory.AUDIO -> arrayOf("audio/*")
        FileCategory.DOCUMENTS -> arrayOf(
            "application/pdf",
            "text/*",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
        FileCategory.APKS -> arrayOf("application/vnd.android.package-archive", "application/octet-stream")
        FileCategory.ARCHIVES -> arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/gzip"
        )
        FileCategory.DOWNLOADS -> arrayOf("*/*")
        FileCategory.OTHER -> arrayOf("*/*")
    }

    fun isPreviewableText(mimeType: String, fileName: String): Boolean {
        val lowerMime = mimeType.lowercase()
        val lowerName = fileName.lowercase()
        return lowerMime.startsWith("text/") ||
            lowerMime == "application/json" ||
            lowerMime == "application/xml" ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".log")
    }

    private val ARCHIVE_MIMES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/x-rar-compressed",
        "application/x-7z-compressed",
        "application/gzip",
        "application/x-tar"
    )
}
