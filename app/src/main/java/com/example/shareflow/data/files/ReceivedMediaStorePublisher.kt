package com.example.shareflow.data.files

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.domain.model.TransferFileType
import java.io.File
import java.io.FileInputStream

/**
 * Result of publishing a verified received file so it appears in Android's Gallery / Files app.
 */
data class PublishedReceivedFile(
    val publicContentUri: String?,
    val localFilePath: String
)

/**
 * Abstraction for exporting verified received files to public Android MediaStore collections.
 */
fun interface ReceivedFilePublisher {
    fun publishVerifiedFile(
        localFile: File,
        displayName: String,
        mimeType: String,
        fileType: TransferFileType
    ): PublishedReceivedFile
}

/**
 * Zero-permission, Google-Play-compliant publisher that exports verified received photos, videos,
 * audio, and downloads into Android's public [MediaStore] collections (`Pictures/ShareFlow`,
 * `Movies/ShareFlow`, `Music/ShareFlow`, `Download/ShareFlow`) on Android 10+ (API 29+) using
 * atomic `IS_PENDING = 1 -> 0` writes, and triggers [MediaScannerConnection] on API 24–28.
 *
 * Security & Integrity Guarantees:
 * - Only invoked AFTER full SHA-256 verification passes in [com.example.shareflow.network.transfer.TransferReceiver].
 * - Never requests `WRITE_EXTERNAL_STORAGE` or any broad storage permission.
 * - Keeps internal split-APK manifests (`shareflow_app_manifest.json`) private inside ShareFlow's
 *   received folder so internal metadata never pollutes the user's Downloads or Gallery.
 * - Never fails a transfer if MediaStore rejects an insert (e.g., restricted user profile or JVM tests).
 */
class ReceivedMediaStorePublisher(
    context: Context
) : ReceivedFilePublisher {

    private val appContext = context.applicationContext

    override fun publishVerifiedFile(
        localFile: File,
        displayName: String,
        mimeType: String,
        fileType: TransferFileType
    ): PublishedReceivedFile {
        if (!localFile.exists() || !localFile.isFile) {
            return PublishedReceivedFile(publicContentUri = null, localFilePath = localFile.absolutePath)
        }

        val cleanName = localFile.name.ifBlank { displayName.substringAfterLast('/') }
        // Internal split-APK manifest JSON stays strictly inside ShareFlow's verified directory
        if (cleanName.equals(AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME, ignoreCase = true)) {
            return PublishedReceivedFile(publicContentUri = null, localFilePath = localFile.absolutePath)
        }

        // Config split APKs inside a bundle folder stay inside ShareFlowReceived for PackageInstaller
        val parentHasManifest = localFile.parentFile?.let {
            File(it, AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME).exists()
        } == true
        if (parentHasManifest && cleanName.endsWith(".apk", ignoreCase = true)) {
            return PublishedReceivedFile(publicContentUri = null, localFilePath = localFile.absolutePath)
        }

        val resolvedMime = resolveMimeType(cleanName, mimeType, fileType)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publishedUri = publishViaMediaStoreApi29Plus(
                localFile = localFile,
                fileName = cleanName,
                mimeType = resolvedMime,
                fileType = fileType
            )
            return PublishedReceivedFile(
                publicContentUri = publishedUri?.toString(),
                localFilePath = localFile.absolutePath
            )
        }

        // API 24–28: notify MediaScanner on the verified file path
        runCatching {
            MediaScannerConnection.scanFile(
                appContext,
                arrayOf(localFile.absolutePath),
                arrayOf(resolvedMime),
                null
            )
        }
        return PublishedReceivedFile(
            publicContentUri = null,
            localFilePath = localFile.absolutePath
        )
    }

    private fun publishViaMediaStoreApi29Plus(
        localFile: File,
        fileName: String,
        mimeType: String,
        fileType: TransferFileType
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val target = resolveCollectionAndRelativePath(fileName, mimeType, fileType)
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        var insertedUri: Uri? = null
        return runCatching {
            insertedUri = resolver.insert(target.collectionUri, values)
            val uri = insertedUri ?: return@runCatching null

            resolver.openOutputStream(uri, "w")?.use { outStream ->
                FileInputStream(localFile).use { inStream ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var read = inStream.read(buffer)
                    while (read != -1) {
                        if (read > 0) {
                            outStream.write(buffer, 0, read)
                        }
                        read = inStream.read(buffer)
                    }
                    outStream.flush()
                }
            } ?: run {
                runCatching { resolver.delete(uri, null, null) }
                return@runCatching null
            }

            val finalizeValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, finalizeValues, null, null)
            uri
        }.getOrElse {
            insertedUri?.let { uri -> runCatching { resolver.delete(uri, null, null) } }
            null
        }
    }

    private data class MediaStoreTarget(
        val collectionUri: Uri,
        val relativePath: String
    )

    private fun resolveCollectionAndRelativePath(
        fileName: String,
        mimeType: String,
        fileType: TransferFileType
    ): MediaStoreTarget {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isImage = fileType == TransferFileType.IMAGE ||
            mimeType.startsWith("image/", ignoreCase = true) ||
            ext in IMAGE_EXTENSIONS
        val isVideo = fileType == TransferFileType.VIDEO ||
            mimeType.startsWith("video/", ignoreCase = true) ||
            ext in VIDEO_EXTENSIONS
        val isAudio = fileType == TransferFileType.AUDIO ||
            mimeType.startsWith("audio/", ignoreCase = true) ||
            ext in AUDIO_EXTENSIONS

        return when {
            isImage -> MediaStoreTarget(
                collectionUri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "${Environment.DIRECTORY_PICTURES}/$SHAREFLOW_FOLDER_NAME"
            )
            isVideo -> MediaStoreTarget(
                collectionUri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "${Environment.DIRECTORY_MOVIES}/$SHAREFLOW_FOLDER_NAME"
            )
            isAudio -> MediaStoreTarget(
                collectionUri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "${Environment.DIRECTORY_MUSIC}/$SHAREFLOW_FOLDER_NAME"
            )
            else -> MediaStoreTarget(
                collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$SHAREFLOW_FOLDER_NAME"
            )
        }
    }

    private fun resolveMimeType(
        fileName: String,
        providedMime: String,
        fileType: TransferFileType
    ): String {
        val trimmed = providedMime.trim()
        if (trimmed.isNotEmpty() && trimmed != "*/*" && trimmed != "application/octet-stream") {
            return trimmed
        }
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "zip" -> "application/zip"
            "apk" -> AndroidAppSharingRepository.APK_MIME_TYPE
            else -> when (fileType) {
                TransferFileType.IMAGE -> "image/jpeg"
                TransferFileType.VIDEO -> "video/mp4"
                TransferFileType.AUDIO -> "audio/mpeg"
                TransferFileType.APK -> AndroidAppSharingRepository.APK_MIME_TYPE
                else -> "application/octet-stream"
            }
        }
    }

    companion object {
        const val SHAREFLOW_FOLDER_NAME = "ShareFlow"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus")
    }
}
