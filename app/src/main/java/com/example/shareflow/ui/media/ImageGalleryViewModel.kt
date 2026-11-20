package com.example.shareflow.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.media.MediaCenterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageGalleryUiState(
    val isLoading: Boolean = true,
    val allImages: List<MediaImageItem> = emptyList(),
    val selectedAlbum: String? = null,
    val viewerIndex: Int? = null,
    val showMetadataSheet: Boolean = false,
    val pendingDeleteImage: MediaImageItem? = null,
    val errorMessage: String? = null,
    val statusMessage: String? = null
) {
    val albums: List<Pair<String, Int>>
        get() = allImages
            .groupingBy { it.albumName }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }

    val visibleImages: List<MediaImageItem>
        get() = if (selectedAlbum == null) {
            allImages
        } else {
            allImages.filter { it.albumName == selectedAlbum }
        }

    val currentViewerImage: MediaImageItem?
        get() = viewerIndex?.let { visibleImages.getOrNull(it) }
}

class ImageGalleryViewModel(
    private val mediaCenterRepository: MediaCenterRepository
) : ViewModel() {

    private val pickedUris = linkedSetOf<String>()
    private val _uiState = MutableStateFlow(ImageGalleryUiState())
    val uiState: StateFlow<ImageGalleryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val res = mediaCenterRepository.loadImages(extraUris = pickedUris.toList())) {
                is AppResult.Success -> {
                    _uiState.update { state ->
                        val nextSelectedAlbum = state.selectedAlbum?.takeIf { album ->
                            res.data.any { it.albumName == album }
                        }
                        state.copy(
                            isLoading = false,
                            allImages = res.data,
                            selectedAlbum = nextSelectedAlbum,
                            errorMessage = null
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(isLoading = false, errorMessage = res.error.message)
                    }
                }
            }
        }
    }

    fun addPickedUris(uris: List<String>) {
        if (uris.isEmpty()) return
        pickedUris.addAll(uris.filter { it.isNotBlank() })
        refresh()
    }

    fun selectAlbum(album: String?) {
        _uiState.update {
            it.copy(
                selectedAlbum = album,
                viewerIndex = null,
                showMetadataSheet = false
            )
        }
    }

    fun openViewer(index: Int) {
        val visible = _uiState.value.visibleImages
        if (index !in visible.indices) return
        _uiState.update { it.copy(viewerIndex = index, showMetadataSheet = false) }
    }

    fun updateViewerPage(index: Int) {
        val visible = _uiState.value.visibleImages
        if (index in visible.indices) {
            _uiState.update { it.copy(viewerIndex = index) }
        }
    }

    fun closeViewer() {
        _uiState.update { it.copy(viewerIndex = null, showMetadataSheet = false) }
    }

    fun toggleMetadataSheet(show: Boolean) {
        _uiState.update { it.copy(showMetadataSheet = show) }
    }

    fun requestDelete(image: MediaImageItem) {
        _uiState.update { it.copy(pendingDeleteImage = image) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(pendingDeleteImage = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.pendingDeleteImage ?: return
        _uiState.update { it.copy(pendingDeleteImage = null) }
        viewModelScope.launch {
            when (val res = mediaCenterRepository.deleteMediaUri(target.uriString)) {
                is AppResult.Success -> {
                    pickedUris.remove(target.uriString)
                    val remaining = _uiState.value.allImages.filterNot { it.uriString == target.uriString }
                    _uiState.update { state ->
                        val nextVisible = if (state.selectedAlbum == null) {
                            remaining
                        } else {
                            remaining.filter { it.albumName == state.selectedAlbum }
                        }
                        val nextViewerIdx = state.viewerIndex?.let { idx ->
                            if (nextVisible.isEmpty()) null else idx.coerceAtMost(nextVisible.lastIndex)
                        }
                        state.copy(
                            allImages = remaining,
                            viewerIndex = nextViewerIdx,
                            showMetadataSheet = false,
                            statusMessage = if (res.data.succeededCount > 0) {
                                "Deleted ${target.displayName}"
                            } else {
                                res.data.failures.firstOrNull()?.reason
                                    ?: "Provider declined deletion for ${target.displayName}"
                            }
                        )
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(statusMessage = res.error.message) }
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
