package com.example.shareflow.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.media.MediaCenterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoPlayerUiState(
    val isLoading: Boolean = true,
    val videos: List<MediaVideoItem> = emptyList(),
    val currentIndex: Int = -1,
    val playbackSpeed: Float = 1.0f,
    val isFullScreen: Boolean = false,
    val isLandscapeLocked: Boolean = false,
    val subtitleUriString: String? = null,
    val subtitleMimeType: String? = null,
    val subtitlesEnabled: Boolean = true,
    val resumePositionMs: Long = 0L,
    val playbackError: String? = null,
    val loadError: String? = null
) {
    val currentVideo: MediaVideoItem?
        get() = videos.getOrNull(currentIndex)

    val hasNext: Boolean
        get() = currentIndex in 0 until videos.lastIndex

    val hasPrevious: Boolean
        get() = currentIndex > 0
}

class VideoPlayerViewModel(
    private val mediaCenterRepository: MediaCenterRepository
) : ViewModel() {

    private val pickedUris = linkedSetOf<String>()
    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            when (val res = mediaCenterRepository.loadVideos(extraUris = pickedUris.toList())) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        val nextIdx = when {
                            res.data.isEmpty() -> -1
                            state.currentIndex in res.data.indices -> state.currentIndex
                            else -> 0
                        }
                        val active = res.data.getOrNull(nextIdx)
                        state.copy(
                            isLoading = false,
                            videos = res.data,
                            currentIndex = nextIdx,
                            resumePositionMs = active?.lastPositionMs ?: 0L,
                            playbackSpeed = active?.lastPlaybackSpeed ?: 1.0f,
                            loadError = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, loadError = res.error.message) }
                }
            }
        }
    }

    fun addPickedVideoUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val firstNew = uris.firstOrNull { it.isNotBlank() }
        pickedUris.addAll(uris.filter { it.isNotBlank() })
        viewModelScope.launch {
            val res = mediaCenterRepository.loadVideos(extraUris = pickedUris.toList())
            if (res is AppResult.Success) {
                val targetIdx = firstNew?.let { uri -> res.data.indexOfFirst { it.uriString == uri } }
                    ?.takeIf { it >= 0 } ?: 0
                val active = res.data.getOrNull(targetIdx)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        videos = res.data,
                        currentIndex = if (res.data.isEmpty()) -1 else targetIdx,
                        resumePositionMs = active?.lastPositionMs ?: 0L,
                        playbackSpeed = active?.lastPlaybackSpeed ?: 1.0f,
                        subtitleUriString = null,
                        subtitleMimeType = null,
                        playbackError = null
                    )
                }
            }
        }
    }

    fun selectVideo(index: Int) {
        val list = _uiState.value.videos
        if (index !in list.indices) return
        val target = list[index]
        viewModelScope.launch {
            val readable = mediaCenterRepository.verifyUriReadable(target.uriString)
            if (!readable) {
                _uiState.update {
                    it.copy(
                        currentIndex = index,
                        playbackError = "Video file is missing or unreadable: ${target.displayName}"
                    )
                }
                return@launch
            }
            val saved = mediaCenterRepository.getPlaybackPosition(target.uriString)
            _uiState.update {
                it.copy(
                    currentIndex = index,
                    resumePositionMs = saved?.positionMs ?: target.lastPositionMs,
                    playbackSpeed = saved?.playbackSpeed ?: target.lastPlaybackSpeed,
                    subtitleUriString = null,
                    subtitleMimeType = null,
                    playbackError = null
                )
            }
        }
    }

    fun playNext() {
        val state = _uiState.value
        if (state.hasNext) selectVideo(state.currentIndex + 1)
    }

    fun playPrevious() {
        val state = _uiState.value
        if (state.hasPrevious) selectVideo(state.currentIndex - 1)
    }

    fun cyclePlaybackSpeed() {
        val speeds = SUPPORTED_SPEEDS
        val currentIdx = speeds.indexOfFirst { kotlin.math.abs(it - _uiState.value.playbackSpeed) < 0.05f }
        val nextSpeed = speeds[(currentIdx + 1) % speeds.size]
        _uiState.update { it.copy(playbackSpeed = nextSpeed) }
    }

    fun setFullScreen(fullScreen: Boolean) {
        _uiState.update { it.copy(isFullScreen = fullScreen) }
    }

    fun toggleLandscape() {
        _uiState.update { it.copy(isLandscapeLocked = !it.isLandscapeLocked) }
    }

    fun attachSubtitle(uriString: String, fileName: String?) {
        val mime = when {
            fileName?.endsWith(".vtt", ignoreCase = true) == true -> "text/vtt"
            else -> "application/x-subrip"
        }
        _uiState.update {
            it.copy(
                subtitleUriString = uriString,
                subtitleMimeType = mime,
                subtitlesEnabled = true
            )
        }
    }

    fun toggleSubtitles() {
        _uiState.update { it.copy(subtitlesEnabled = !it.subtitlesEnabled) }
    }

    fun reportPlaybackError(message: String) {
        _uiState.update { it.copy(playbackError = message) }
    }

    fun clearPlaybackError() {
        _uiState.update { it.copy(playbackError = null) }
    }

    fun persistProgress(mediaUri: String, positionMs: Long, durationMs: Long, speed: Float) {
        viewModelScope.launch {
            mediaCenterRepository.savePlaybackPosition(
                mediaUri = mediaUri,
                mediaType = MediaCenterRepository.MEDIA_TYPE_VIDEO,
                positionMs = positionMs,
                durationMs = durationMs,
                playbackSpeed = speed
            )
            _uiState.update { state ->
                val updatedVideos = state.videos.map { video ->
                    if (video.uriString == mediaUri) {
                        video.copy(lastPositionMs = positionMs, lastPlaybackSpeed = speed)
                    } else {
                        video
                    }
                }
                state.copy(videos = updatedVideos)
            }
        }
    }

    companion object {
        val SUPPORTED_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}
