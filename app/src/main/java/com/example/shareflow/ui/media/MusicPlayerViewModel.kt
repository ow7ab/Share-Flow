package com.example.shareflow.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.MediaAudioTrack
import com.example.shareflow.domain.model.MusicPlaylist
import com.example.shareflow.media.MediaCenterRepository
import com.example.shareflow.media.music.MusicPlaybackController
import com.example.shareflow.media.music.MusicPlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MusicTab {
    LIBRARY,
    QUEUE,
    PLAYLISTS
}

data class MusicPlayerUiState(
    val isLoading: Boolean = true,
    val selectedTab: MusicTab = MusicTab.LIBRARY,
    val libraryTracks: List<MediaAudioTrack> = emptyList(),
    val showCreatePlaylistDialog: Boolean = false,
    val trackPendingPlaylistAdd: MediaAudioTrack? = null,
    val playlistPendingDelete: MusicPlaylist? = null,
    val loadError: String? = null,
    val statusMessage: String? = null
)

class MusicPlayerViewModel(
    private val mediaCenterRepository: MediaCenterRepository,
    val playbackController: MusicPlaybackController
) : ViewModel() {

    private val pickedAudioUris = linkedSetOf<String>()

    private val _uiState = MutableStateFlow(MusicPlayerUiState())
    val uiState: StateFlow<MusicPlayerUiState> = _uiState.asStateFlow()

    val playbackState: StateFlow<MusicPlaybackState> = playbackController.state

    val playlists: StateFlow<List<MusicPlaylist>> = mediaCenterRepository.observePlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    init {
        refreshLibrary()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null) }
            when (val res = mediaCenterRepository.loadAudioTracks(extraUris = pickedAudioUris.toList())) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, libraryTracks = res.data, loadError = null) }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, loadError = res.error.message) }
                }
            }
        }
    }

    fun addPickedAudioUris(uris: List<String>) {
        if (uris.isEmpty()) return
        pickedAudioUris.addAll(uris.filter { it.isNotBlank() })
        refreshLibrary()
    }

    fun selectTab(tab: MusicTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun playFromLibrary(index: Int) {
        val tracks = _uiState.value.libraryTracks
        if (index in tracks.indices) {
            playbackController.playQueue(tracks, startIndex = index, resumeFromSavedPosition = true)
        }
    }

    fun playPlaylist(playlist: MusicPlaylist) {
        if (playlist.tracks.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Playlist '${playlist.name}' has no tracks yet.") }
            return
        }
        playbackController.playQueue(playlist.tracks, startIndex = 0, resumeFromSavedPosition = false)
    }

    fun showCreatePlaylist(show: Boolean) {
        _uiState.update { it.copy(showCreatePlaylistDialog = show) }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            when (val res = mediaCenterRepository.createPlaylist(name)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            showCreatePlaylistDialog = false,
                            statusMessage = "Created playlist '${res.data.name}'"
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(statusMessage = res.error.message) }
                }
            }
        }
    }

    fun openAddTrackToPlaylist(track: MediaAudioTrack) {
        _uiState.update { it.copy(trackPendingPlaylistAdd = track) }
    }

    fun dismissAddTrackToPlaylist() {
        _uiState.update { it.copy(trackPendingPlaylistAdd = null) }
    }

    fun addTrackToPlaylist(playlist: MusicPlaylist, track: MediaAudioTrack) {
        viewModelScope.launch {
            mediaCenterRepository.addTrackToPlaylist(playlist.id, track)
            _uiState.update {
                it.copy(
                    trackPendingPlaylistAdd = null,
                    statusMessage = "Added '${track.title}' to '${playlist.name}'"
                )
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackUri: String) {
        viewModelScope.launch {
            mediaCenterRepository.removeTrackFromPlaylist(playlistId, trackUri)
        }
    }

    fun requestDeletePlaylist(playlist: MusicPlaylist) {
        _uiState.update { it.copy(playlistPendingDelete = playlist) }
    }

    fun dismissDeletePlaylist() {
        _uiState.update { it.copy(playlistPendingDelete = null) }
    }

    fun confirmDeletePlaylist() {
        val target = _uiState.value.playlistPendingDelete ?: return
        _uiState.update { it.copy(playlistPendingDelete = null) }
        viewModelScope.launch {
            mediaCenterRepository.deletePlaylist(target.id)
            _uiState.update { it.copy(statusMessage = "Deleted playlist '${target.name}'") }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
