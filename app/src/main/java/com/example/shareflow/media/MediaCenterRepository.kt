package com.example.shareflow.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.MediaPlaybackDao
import com.example.shareflow.data.local.database.dao.MusicPlaylistDao
import com.example.shareflow.data.local.database.entity.MediaPlaybackStateEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistTrackEntity
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MediaAudioTrack
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.domain.model.MediaPlaybackSnapshot
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.domain.model.MusicPlaylist
import com.example.shareflow.domain.repository.FileManagerRepository
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Play-policy-compliant media repository for ShareFlow Media Center.
 * Reads local images, videos, and audio strictly through scoped MediaStore, Photo Picker, and SAF URIs
 * without requiring broad storage permissions, and persists playback resume positions & playlists in Room.
 */
class MediaCenterRepository(
    context: Context,
    private val fileManagerRepository: FileManagerRepository,
    private val mediaPlaybackDao: MediaPlaybackDao,
    private val musicPlaylistDao: MusicPlaylistDao,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock
) {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    suspend fun loadImages(extraUris: List<String> = emptyList()): AppResult<List<MediaImageItem>> =
        withContext(dispatchers.io) {
            val baseEntries = when (val res = fileManagerRepository.queryByCategory(FileCategory.IMAGES)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> emptyList()
            }
            val byUri = linkedMapOf<String, MediaImageItem>()
            for (entry in baseEntries) {
                if (entry.isDirectory || !entry.isAccessible) continue
                inspectImageFromEntry(entry)?.let { byUri[it.uriString] = it }
            }
            for (uriStr in extraUris) {
                if (uriStr.isBlank() || byUri.containsKey(uriStr)) continue
                inspectImageUri(uriStr)?.let { byUri[it.uriString] = it }
            }
            AppResult.Success(
                byUri.values.sortedByDescending { it.dateModifiedEpochMillis }
            )
        }

    suspend fun inspectImageUri(uriString: String): MediaImageItem? = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        if (!isUriReadableInternal(uri)) return@withContext null

        val basic = queryBasicUriInfo(uri, defaultMime = "image/*")
        val album = queryAlbumName(uri) ?: inferFolderNameFromUri(uri) ?: DEFAULT_ALBUM_NAME
        val bounds = readImageBounds(uri)
        val exif = readExifMetadata(uri)

        MediaImageItem(
            uriString = uriString,
            displayName = basic.displayName,
            albumName = album,
            mimeType = basic.mimeType,
            sizeBytes = basic.sizeBytes,
            widthPx = bounds.first,
            heightPx = bounds.second,
            dateModifiedEpochMillis = basic.dateModifiedMillis,
            cameraModel = exif.cameraModel,
            orientationLabel = exif.orientationLabel,
            canDelete = uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == "file"
        )
    }

    suspend fun loadVideos(extraUris: List<String> = emptyList()): AppResult<List<MediaVideoItem>> =
        withContext(dispatchers.io) {
            val baseEntries = when (val res = fileManagerRepository.queryByCategory(FileCategory.VIDEOS)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> emptyList()
            }
            val savedStates = mediaPlaybackDao.getAll().associateBy { it.mediaUri }
            val byUri = linkedMapOf<String, MediaVideoItem>()

            for (entry in baseEntries) {
                if (entry.isDirectory || !entry.isAccessible) continue
                inspectVideoUri(
                    uriString = entry.uriString,
                    fallbackEntry = entry,
                    savedState = savedStates[entry.uriString]
                )?.let { byUri[it.uriString] = it }
            }
            for (uriStr in extraUris) {
                if (uriStr.isBlank() || byUri.containsKey(uriStr)) continue
                inspectVideoUri(
                    uriString = uriStr,
                    fallbackEntry = null,
                    savedState = savedStates[uriStr]
                )?.let { byUri[it.uriString] = it }
            }
            AppResult.Success(
                byUri.values.sortedByDescending { it.dateModifiedEpochMillis }
            )
        }

    suspend fun inspectVideoUri(
        uriString: String,
        fallbackEntry: LocalFileEntry? = null,
        savedState: MediaPlaybackStateEntity? = null
    ): MediaVideoItem? = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        if (!isUriReadableInternal(uri)) return@withContext null

        val basic = if (fallbackEntry != null) {
            BasicUriInfo(
                displayName = fallbackEntry.displayName,
                mimeType = fallbackEntry.mimeType,
                sizeBytes = fallbackEntry.sizeBytes,
                dateModifiedMillis = fallbackEntry.lastModifiedEpochMillis
            )
        } else {
            queryBasicUriInfo(uri, defaultMime = "video/*")
        }

        val state = savedState ?: mediaPlaybackDao.getByUri(uriString)
        val retrieverInfo = extractMediaMetadata(uri)
        val folder = queryAlbumName(uri) ?: inferFolderNameFromUri(uri) ?: DEFAULT_VIDEO_FOLDER

        MediaVideoItem(
            uriString = uriString,
            displayName = basic.displayName,
            folderName = folder,
            mimeType = basic.mimeType,
            sizeBytes = basic.sizeBytes,
            durationMs = retrieverInfo.durationMs.takeIf { it > 0L } ?: (state?.durationMs ?: 0L),
            widthPx = retrieverInfo.widthPx,
            heightPx = retrieverInfo.heightPx,
            dateModifiedEpochMillis = basic.dateModifiedMillis,
            lastPositionMs = state?.positionMs ?: 0L,
            lastPlaybackSpeed = state?.playbackSpeed ?: 1.0f
        )
    }

    suspend fun loadAudioTracks(extraUris: List<String> = emptyList()): AppResult<List<MediaAudioTrack>> =
        withContext(dispatchers.io) {
            val baseEntries = when (val res = fileManagerRepository.queryByCategory(FileCategory.AUDIO)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> emptyList()
            }
            val savedStates = mediaPlaybackDao.getAll().associateBy { it.mediaUri }
            val byUri = linkedMapOf<String, MediaAudioTrack>()

            for (entry in baseEntries) {
                if (entry.isDirectory || !entry.isAccessible) continue
                inspectAudioUri(
                    uriString = entry.uriString,
                    fallbackEntry = entry,
                    savedState = savedStates[entry.uriString]
                )?.let { byUri[it.uriString] = it }
            }
            for (uriStr in extraUris) {
                if (uriStr.isBlank() || byUri.containsKey(uriStr)) continue
                inspectAudioUri(
                    uriString = uriStr,
                    fallbackEntry = null,
                    savedState = savedStates[uriStr]
                )?.let { byUri[it.uriString] = it }
            }
            AppResult.Success(
                byUri.values.sortedBy { it.title.lowercase() }
            )
        }

    suspend fun inspectAudioUri(
        uriString: String,
        fallbackEntry: LocalFileEntry? = null,
        savedState: MediaPlaybackStateEntity? = null
    ): MediaAudioTrack? = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext null
        if (!isUriReadableInternal(uri)) return@withContext null

        val basic = if (fallbackEntry != null) {
            BasicUriInfo(
                displayName = fallbackEntry.displayName,
                mimeType = fallbackEntry.mimeType,
                sizeBytes = fallbackEntry.sizeBytes,
                dateModifiedMillis = fallbackEntry.lastModifiedEpochMillis
            )
        } else {
            queryBasicUriInfo(uri, defaultMime = "audio/*")
        }

        val state = savedState ?: mediaPlaybackDao.getByUri(uriString)
        val meta = extractMediaMetadata(uri)
        val cleanTitle = meta.title?.takeIf { it.isNotBlank() }
            ?: basic.displayName.substringBeforeLast('.')
                .ifBlank { basic.displayName }

        MediaAudioTrack(
            uriString = uriString,
            title = cleanTitle,
            artist = meta.artist?.takeIf { it.isNotBlank() } ?: DEFAULT_ARTIST,
            album = meta.album?.takeIf { it.isNotBlank() } ?: DEFAULT_MUSIC_ALBUM,
            mimeType = basic.mimeType,
            sizeBytes = basic.sizeBytes,
            durationMs = meta.durationMs.takeIf { it > 0L } ?: (state?.durationMs ?: 0L),
            lastPositionMs = state?.positionMs ?: 0L
        )
    }

    suspend fun verifyUriReadable(uriString: String): Boolean = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
        isUriReadableInternal(uri)
    }

    suspend fun savePlaybackPosition(
        mediaUri: String,
        mediaType: String,
        positionMs: Long,
        durationMs: Long,
        playbackSpeed: Float = 1.0f
    ) = withContext(dispatchers.io) {
        if (mediaUri.isBlank()) return@withContext
        val sanitizedPos = if (durationMs > 0L && positionMs >= durationMs - 2_000L) {
            0L
        } else {
            positionMs.coerceAtLeast(0L)
        }
        mediaPlaybackDao.upsert(
            MediaPlaybackStateEntity(
                mediaUri = mediaUri,
                mediaType = mediaType,
                positionMs = sanitizedPos,
                durationMs = durationMs.coerceAtLeast(0L),
                playbackSpeed = playbackSpeed.coerceIn(0.25f, 3.0f),
                updatedAtEpochMillis = clock.nowEpochMillis()
            )
        )
    }

    suspend fun getPlaybackPosition(mediaUri: String): MediaPlaybackSnapshot? = withContext(dispatchers.io) {
        val entity = mediaPlaybackDao.getByUri(mediaUri) ?: return@withContext null
        MediaPlaybackSnapshot(
            mediaUri = entity.mediaUri,
            positionMs = entity.positionMs,
            durationMs = entity.durationMs,
            playbackSpeed = entity.playbackSpeed
        )
    }

    fun observePlaylists(): Flow<List<MusicPlaylist>> = combine(
        musicPlaylistDao.observePlaylists(),
        musicPlaylistDao.observeAllPlaylistTracks()
    ) { playlists, allTracks ->
        val grouped = allTracks.groupBy { it.playlistId }
        playlists.map { pl ->
            MusicPlaylist(
                id = pl.id,
                name = pl.name,
                createdAtEpochMillis = pl.createdAtEpochMillis,
                tracks = grouped[pl.id].orEmpty().map { track ->
                    MediaAudioTrack(
                        uriString = track.trackUri,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        mimeType = "audio/*",
                        durationMs = track.durationMs
                    )
                }
            )
        }
    }

    suspend fun createPlaylist(name: String): AppResult<MusicPlaylist> = withContext(dispatchers.io) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) {
            return@withContext AppResult.Failure(AppError.Validation("Playlist name must be 1–80 characters."))
        }
        val id = UUID.randomUUID().toString()
        val now = clock.nowEpochMillis()
        musicPlaylistDao.upsertPlaylist(
            MusicPlaylistEntity(
                id = id,
                name = trimmed,
                createdAtEpochMillis = now
            )
        )
        AppResult.Success(
            MusicPlaylist(
                id = id,
                name = trimmed,
                tracks = emptyList(),
                createdAtEpochMillis = now
            )
        )
    }

    suspend fun deletePlaylist(playlistId: String): AppResult<Unit> = withContext(dispatchers.io) {
        musicPlaylistDao.deletePlaylist(playlistId)
        AppResult.Success(Unit)
    }

    suspend fun addTrackToPlaylist(playlistId: String, track: MediaAudioTrack): AppResult<Unit> =
        withContext(dispatchers.io) {
            val existing = musicPlaylistDao.getTracksForPlaylist(playlistId)
            if (existing.any { it.trackUri == track.uriString }) {
                return@withContext AppResult.Success(Unit)
            }
            val nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            musicPlaylistDao.upsertTrack(
                MusicPlaylistTrackEntity(
                    playlistId = playlistId,
                    trackUri = track.uriString,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    sortOrder = nextOrder
                )
            )
            AppResult.Success(Unit)
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, trackUri: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            musicPlaylistDao.removeTrackFromPlaylist(playlistId, trackUri)
            AppResult.Success(Unit)
        }

    suspend fun deleteMediaUri(uriString: String): AppResult<FileOperationResult> = withContext(dispatchers.io) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext AppResult.Failure(AppError.Validation("Invalid media URI."))
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists() && file.delete()) {
                mediaPlaybackDao.deleteByUri(uriString)
                return@withContext AppResult.Success(
                    FileOperationResult(succeededUris = listOf(uriString), failures = emptyList())
                )
            }
            return@withContext AppResult.Failure(AppError.PermissionDenied("Unable to delete local file."))
        }
        val res = fileManagerRepository.deleteFiles(listOf(uriString))
        if (res is AppResult.Success && res.data.succeededCount > 0) {
            mediaPlaybackDao.deleteByUri(uriString)
        }
        res
    }

    fun createShareImageIntent(image: MediaImageItem): Intent? {
        val uri = runCatching { Uri.parse(image.uriString) }.getOrNull() ?: return null
        val shareUri = if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) } ?: return null
            runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    f
                )
            }.getOrNull() ?: return null
        } else {
            uri
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = image.mimeType.ifBlank { "image/*" }
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun inspectImageFromEntry(entry: LocalFileEntry): MediaImageItem? {
        val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull() ?: return null
        if (!isUriReadableInternal(uri)) return null
        val album = queryAlbumName(uri) ?: inferFolderNameFromUri(uri) ?: DEFAULT_ALBUM_NAME
        val bounds = readImageBounds(uri)
        val exif = readExifMetadata(uri)
        return MediaImageItem(
            uriString = entry.uriString,
            displayName = entry.displayName,
            albumName = album,
            mimeType = entry.mimeType,
            sizeBytes = entry.sizeBytes,
            widthPx = bounds.first,
            heightPx = bounds.second,
            dateModifiedEpochMillis = entry.lastModifiedEpochMillis,
            cameraModel = exif.cameraModel,
            orientationLabel = exif.orientationLabel,
            canDelete = entry.capabilities.canDelete
        )
    }

    private fun isUriReadableInternal(uri: Uri): Boolean {
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it).let { f -> f.exists() && f.isFile && f.canRead() } } ?: false
            ContentResolver.SCHEME_CONTENT -> runCatching {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun openInputStream(uri: Uri): InputStream? = when (uri.scheme) {
        "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() && it.canRead() }?.inputStream()
        ContentResolver.SCHEME_CONTENT -> runCatching { contentResolver.openInputStream(uri) }.getOrNull()
        else -> null
    }

    private fun readImageBounds(uri: Uri): Pair<Int, Int> {
        return runCatching {
            openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                val w = opts.outWidth.coerceAtLeast(0)
                val h = opts.outHeight.coerceAtLeast(0)
                w to h
            } ?: (0 to 0)
        }.getOrDefault(0 to 0)
    }

    private data class ExifSummary(
        val cameraModel: String? = null,
        val orientationLabel: String? = null
    )

    private fun readExifMetadata(uri: Uri): ExifSummary {
        return runCatching {
            openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
                val camera = when {
                    make.isNotEmpty() && model.isNotEmpty() && !model.startsWith(make, ignoreCase = true) -> "$make $model"
                    model.isNotEmpty() -> model
                    make.isNotEmpty() -> make
                    else -> null
                }
                val orientation = when (
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> "90°"
                    ExifInterface.ORIENTATION_ROTATE_180 -> "180°"
                    ExifInterface.ORIENTATION_ROTATE_270 -> "270°"
                    else -> "Normal"
                }
                ExifSummary(cameraModel = camera, orientationLabel = orientation)
            } ?: ExifSummary()
        }.getOrDefault(ExifSummary())
    }

    private data class RetrievedMediaMeta(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val durationMs: Long = 0L,
        val widthPx: Int = 0,
        val heightPx: Int = 0
    )

    private fun extractMediaMetadata(uri: Uri): RetrievedMediaMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            when (uri.scheme) {
                "file" -> uri.path?.let { retriever.setDataSource(it) }
                ContentResolver.SCHEME_CONTENT -> retriever.setDataSource(appContext, uri)
                else -> return RetrievedMediaMeta()
            }
            RetrievedMediaMeta(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                widthPx = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                heightPx = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0
            )
        } catch (_: Exception) {
            RetrievedMediaMeta()
        } finally {
            runCatching { retriever.release() }
        }
    }

    private data class BasicUriInfo(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val dateModifiedMillis: Long
    )

    private fun queryBasicUriInfo(uri: Uri, defaultMime: String): BasicUriInfo {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            val name = f?.name ?: "media"
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "flac" -> "audio/flac"
                else -> defaultMime
            }
            return BasicUriInfo(
                displayName = name,
                mimeType = mime,
                sizeBytes = f?.length() ?: 0L,
                dateModifiedMillis = f?.lastModified() ?: 0L
            )
        }

        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = 0L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { name = it }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                }
            }
        }
        val mime = runCatching { contentResolver.getType(uri) }.getOrNull() ?: defaultMime
        return BasicUriInfo(
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            dateModifiedMillis = clock.nowEpochMillis()
        )
    }

    private fun queryAlbumName(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun inferFolderNameFromUri(uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).parentFile?.name }?.takeIf { it.isNotBlank() }
        }
        val segments = uri.pathSegments
        return if (segments.size >= 2) segments[segments.size - 2]?.takeIf { it.isNotBlank() } else null
    }

    companion object {
        const val MEDIA_TYPE_VIDEO = "VIDEO"
        const val MEDIA_TYPE_AUDIO = "AUDIO"
        private const val DEFAULT_ALBUM_NAME = "Gallery"
        private const val DEFAULT_VIDEO_FOLDER = "Videos"
        private const val DEFAULT_ARTIST = "Unknown Artist"
        private const val DEFAULT_MUSIC_ALBUM = "Local Library"
    }
}
