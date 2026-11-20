package com.example.shareflow.media.music

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.MediaAudioTrack
import com.example.shareflow.domain.model.MusicRepeatMode
import com.example.shareflow.media.MediaCenterRepository
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class MusicPlaybackState(
    val queue: List<MediaAudioTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.OFF,
    val isConnectedToSession: Boolean = false,
    val errorMessage: String? = null
) {
    val currentTrack: MediaAudioTrack?
        get() = queue.getOrNull(currentIndex)
}

/**
 * Coordinates AndroidX Media3 MediaController connection to [ShareFlowMediaSessionService],
 * playback queue state, shuffle/repeat, error reporting, and Room resume persistence.
 * Only polls playback position while actively playing to avoid unnecessary battery drain.
 */
class MusicPlaybackController(
    context: Context,
    private val mediaCenterRepository: MediaCenterRepository,
    private val dispatchers: DispatcherProvider,
    private val injectedPlayerProvider: (() -> Player)? = null
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var activePlayer: Player? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(MusicPlaybackState())
    val state: StateFlow<MusicPlaybackState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) {
                startProgressPolling()
            } else {
                stopProgressPolling()
                persistCurrentPosition()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val player = activePlayer ?: return
            val duration = player.duration.takeIf { it > 0L } ?: (_state.value.currentTrack?.durationMs ?: 0L)
            _state.update {
                it.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = duration,
                    positionMs = player.currentPosition.coerceAtLeast(0L)
                )
            }
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                persistCurrentPosition()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = activePlayer ?: return
            persistCurrentPosition()
            val newIdx = player.currentMediaItemIndex
            val duration = player.duration.takeIf { it > 0L }
                ?: _state.value.queue.getOrNull(newIdx)?.durationMs
                ?: 0L
            _state.update {
                it.copy(
                    currentIndex = newIdx,
                    durationMs = duration,
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    errorMessage = null
                )
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.update { it.copy(shuffleEnabled = shuffleModeEnabled) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.update { it.copy(repeatMode = fromPlayerRepeatMode(repeatMode)) }
        }

        override fun onPlayerError(error: PlaybackException) {
            stopProgressPolling()
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    errorMessage = error.localizedMessage
                        ?: "Unsupported audio format or missing media file (${error.errorCodeName})."
                )
            }
        }
    }

    fun connectIfNeeded(onConnected: ((Player) -> Unit)? = null) {
        activePlayer?.let { existing ->
            onConnected?.invoke(existing)
            return
        }

        injectedPlayerProvider?.let { provider ->
            val player = provider()
            attachPlayer(player)
            onConnected?.invoke(player)
            return
        }

        if (controllerFuture != null) return
        val token = SessionToken(
            appContext,
            ComponentName(appContext, ShareFlowMediaSessionService::class.java)
        )
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    scope.launch {
                        attachPlayer(controller)
                        onConnected?.invoke(controller)
                    }
                } else {
                    scope.launch {
                        _state.update {
                            it.copy(
                                isConnectedToSession = false,
                                errorMessage = "Unable to connect to background audio playback service."
                            )
                        }
                    }
                }
            },
            callbackExecutor
        )
    }

    fun playQueue(
        tracks: List<MediaAudioTrack>,
        startIndex: Int = 0,
        resumeFromSavedPosition: Boolean = true
    ) {
        if (tracks.isEmpty()) return
        val clampedIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val targetTrack = tracks[clampedIndex]

        scope.launch {
            val readable = mediaCenterRepository.verifyUriReadable(targetTrack.uriString)
            if (!readable) {
                _state.update {
                    it.copy(
                        errorMessage = "Audio file is missing or permission was revoked: ${targetTrack.title}"
                    )
                }
                return@launch
            }
            val savedPosition = if (resumeFromSavedPosition) {
                mediaCenterRepository.getPlaybackPosition(targetTrack.uriString)?.positionMs ?: targetTrack.lastPositionMs
            } else {
                0L
            }

            _state.update {
                it.copy(
                    queue = tracks,
                    currentIndex = clampedIndex,
                    durationMs = targetTrack.durationMs,
                    positionMs = savedPosition,
                    errorMessage = null
                )
            }

            connectIfNeeded { player ->
                val mediaItems = tracks.map { it.toMedia3Item() }
                player.setMediaItems(mediaItems, clampedIndex, savedPosition)
                player.prepare()
                player.play()
            }
        }
    }

    fun selectQueueIndex(index: Int) {
        val currentQueue = _state.value.queue
        if (index !in currentQueue.indices) return
        val player = activePlayer
        if (player == null) {
            playQueue(currentQueue, startIndex = index)
            return
        }
        persistCurrentPosition()
        player.seekToDefaultPosition(index)
        player.play()
        _state.update { it.copy(currentIndex = index, errorMessage = null) }
    }

    fun togglePlayPause() {
        val player = activePlayer
        if (player == null) {
            val queue = _state.value.queue
            if (queue.isNotEmpty()) {
                playQueue(queue, startIndex = _state.value.currentIndex.coerceAtLeast(0))
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
            }
            player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val safePos = positionMs.coerceAtLeast(0L)
        activePlayer?.seekTo(safePos)
        _state.update { it.copy(positionMs = safePos) }
    }

    fun skipNext() {
        val player = activePlayer ?: return
        persistCurrentPosition()
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            player.play()
        }
    }

    fun skipPrevious() {
        val player = activePlayer ?: return
        persistCurrentPosition()
        if (player.currentPosition > 3_000L) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            player.play()
        }
    }

    fun toggleShuffle() {
        val next = !_state.value.shuffleEnabled
        activePlayer?.shuffleModeEnabled = next
        _state.update { it.copy(shuffleEnabled = next) }
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            MusicRepeatMode.OFF -> MusicRepeatMode.ALL
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.OFF
        }
        activePlayer?.repeatMode = toPlayerRepeatMode(next)
        _state.update { it.copy(repeatMode = next) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun persistCurrentPosition() {
        val snapshot = _state.value
        val track = snapshot.currentTrack ?: return
        val pos = activePlayer?.currentPosition?.coerceAtLeast(0L) ?: snapshot.positionMs
        val dur = activePlayer?.duration?.takeIf { it > 0L } ?: snapshot.durationMs
        scope.launch(dispatchers.io) {
            mediaCenterRepository.savePlaybackPosition(
                mediaUri = track.uriString,
                mediaType = MediaCenterRepository.MEDIA_TYPE_AUDIO,
                positionMs = pos,
                durationMs = dur
            )
        }
    }

    fun disconnectController() {
        stopProgressPolling()
        persistCurrentPosition()
        activePlayer?.removeListener(playerListener)
        if (injectedPlayerProvider == null) {
            controllerFuture?.let { MediaController.releaseFuture(it) }
            controllerFuture = null
        }
        activePlayer = null
        _state.update { it.copy(isConnectedToSession = false) }
    }

    private fun attachPlayer(player: Player) {
        activePlayer?.removeListener(playerListener)
        activePlayer = player
        player.addListener(playerListener)
        _state.update {
            it.copy(
                isConnectedToSession = true,
                isPlaying = player.isPlaying,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = fromPlayerRepeatMode(player.repeatMode),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { d -> d > 0L } ?: it.durationMs
            )
        }
        if (player.isPlaying) {
            startProgressPolling()
        }
    }

    private fun startProgressPolling() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val player = activePlayer ?: break
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.takeIf { it > 0L } ?: _state.value.durationMs
                _state.update { it.copy(positionMs = pos, durationMs = dur) }
                delay(POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    companion object {
        private const val POSITION_POLL_INTERVAL_MS = 500L

        fun MediaAudioTrack.toMedia3Item(): MediaItem =
            MediaItem.Builder()
                .setMediaId(uriString)
                .setUri(Uri.parse(uriString))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .build()
                )
                .build()

        private fun toPlayerRepeatMode(mode: MusicRepeatMode): Int = when (mode) {
            MusicRepeatMode.OFF -> Player.REPEAT_MODE_OFF
            MusicRepeatMode.ALL -> Player.REPEAT_MODE_ALL
            MusicRepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }

        private fun fromPlayerRepeatMode(mode: Int): MusicRepeatMode = when (mode) {
            Player.REPEAT_MODE_ALL -> MusicRepeatMode.ALL
            Player.REPEAT_MODE_ONE -> MusicRepeatMode.ONE
            else -> MusicRepeatMode.OFF
        }
    }
}
