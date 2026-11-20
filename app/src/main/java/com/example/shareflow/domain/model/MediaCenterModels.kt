package com.example.shareflow.domain.model

/**
 * Local image item for ShareFlow Image Gallery, derived from real MediaStore / SAF / Photo Picker URIs.
 */
data class MediaImageItem(
    val uriString: String,
    val displayName: String,
    val albumName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val dateModifiedEpochMillis: Long = 0L,
    val cameraModel: String? = null,
    val orientationLabel: String? = null,
    val canDelete: Boolean = false
)

/**
 * Local video item for ShareFlow Media3 Video Player.
 */
data class MediaVideoItem(
    val uriString: String,
    val displayName: String,
    val folderName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long = 0L,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val dateModifiedEpochMillis: Long = 0L,
    val lastPositionMs: Long = 0L,
    val lastPlaybackSpeed: Float = 1.0f
)

/**
 * Local audio track for ShareFlow Media3 Music Player & MediaSession background playback.
 */
data class MediaAudioTrack(
    val uriString: String,
    val title: String,
    val artist: String,
    val album: String,
    val mimeType: String,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val lastPositionMs: Long = 0L
)

/**
 * User-created persistent Music Playlist stored in Room.
 */
data class MusicPlaylist(
    val id: String,
    val name: String,
    val tracks: List<MediaAudioTrack>,
    val createdAtEpochMillis: Long
)

enum class MusicRepeatMode {
    OFF,
    ALL,
    ONE
}

data class MediaPlaybackSnapshot(
    val mediaUri: String,
    val positionMs: Long,
    val durationMs: Long,
    val playbackSpeed: Float
)
