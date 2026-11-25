package com.example.shareflow.ui.media

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.ui.files.isVideoMedia
import com.example.shareflow.ui.files.isVisualMedia
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MediaStudioTab {
    ALL_MEDIA,
    IMAGES,
    VIDEOS,
    ALBUMS
}

data class MediaStudioAlbum(
    val name: String,
    val coverItem: LocalFileEntry,
    val items: List<LocalFileEntry>
) {
    val itemCount: Int get() = items.size
}

data class MediaStudioUiState(
    val activeTab: MediaStudioTab = MediaStudioTab.ALL_MEDIA,
    val selectedAlbumName: String? = null,
    val allMedia: List<LocalFileEntry> = emptyList(),
    val imagesOnly: List<LocalFileEntry> = emptyList(),
    val videosOnly: List<LocalFileEntry> = emptyList(),
    val albums: List<MediaStudioAlbum> = emptyList(),
    val grantedDirectories: List<GrantedDirectory> = emptyList(),
    val isLoading: Boolean = true,
    val previewTarget: LocalFileEntry? = null,
    val detailsTarget: LocalFileEntry? = null,
    val feedbackMessage: String? = null
) {
    val activeAlbum: MediaStudioAlbum?
        get() = selectedAlbumName?.let { name -> albums.firstOrNull { it.name == name } }

    val displayedItems: List<LocalFileEntry>
        get() = when (activeTab) {
            MediaStudioTab.ALL_MEDIA -> allMedia
            MediaStudioTab.IMAGES -> imagesOnly
            MediaStudioTab.VIDEOS -> videosOnly
            MediaStudioTab.ALBUMS -> activeAlbum?.items.orEmpty()
        }
}

@OptIn(FlowPreview::class)
class MediaStudioViewModel(
    private val fileManagerRepository: FileManagerRepository,
    private val sessionController: LocalSessionController
) : ViewModel() {

    private val _uiState = MutableStateFlow(MediaStudioUiState())
    val uiState: StateFlow<MediaStudioUiState> = _uiState.asStateFlow()

    private var activeLoadJob: Job? = null

    init {
        observeGrantedDirectories()
        observeSessionRecentMedia()
        observeStorageChanges()
        refreshMedia(silent = false)
    }

    private fun observeGrantedDirectories() {
        viewModelScope.launch {
            fileManagerRepository.observeGrantedDirectories().collect { dirs ->
                _uiState.update { it.copy(grantedDirectories = dirs) }
            }
        }
    }

    private fun observeSessionRecentMedia() {
        viewModelScope.launch {
            sessionController.recentFiles.collect { recent ->
                val visual = recent.filter { it.isVisualMedia() }
                if (visual.isNotEmpty()) {
                    mergeAdditionalMedia(visual)
                }
            }
        }
    }

    private fun observeStorageChanges() {
        viewModelScope.launch {
            fileManagerRepository.observeStorageChanges()
                .debounce(STORAGE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refreshMedia(silent = true)
                }
        }
    }

    fun selectTab(tab: MediaStudioTab) {
        _uiState.update { state ->
            state.copy(
                activeTab = tab,
                selectedAlbumName = if (tab == MediaStudioTab.ALBUMS) state.selectedAlbumName else null
            )
        }
    }

    fun openAlbum(albumName: String) {
        _uiState.update {
            it.copy(
                activeTab = MediaStudioTab.ALBUMS,
                selectedAlbumName = albumName
            )
        }
    }

    fun closeSelectedAlbum() {
        _uiState.update { it.copy(selectedAlbumName = null) }
    }

    fun refreshMedia(silent: Boolean = false) {
        activeLoadJob?.cancel()
        activeLoadJob = viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(isLoading = true) }
            }
            val scanned = when (val res = fileManagerRepository.scanAccessibleMedia()) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> emptyList()
            }
            val sessionVisual = sessionController.recentFiles.value.filter { it.isVisualMedia() }
            val organized = withContext(Dispatchers.Default) {
                organizeMediaEntries(scanned + sessionVisual)
            }
            _uiState.update { state ->
                val stillValidAlbum = state.selectedAlbumName?.takeIf { sel ->
                    organized.albums.any { it.name == sel }
                }
                state.copy(
                    allMedia = organized.allMedia,
                    imagesOnly = organized.imagesOnly,
                    videosOnly = organized.videosOnly,
                    albums = organized.albums,
                    selectedAlbumName = stillValidAlbum,
                    isLoading = false
                )
            }
        }
    }

    fun onDirectoryPicked(treeUriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val res = fileManagerRepository.registerGrantedDirectory(treeUriString)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(feedbackMessage = "Folder linked: ${res.data.displayName}")
                    }
                    refreshMedia(silent = false)
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(isLoading = false, feedbackMessage = res.error.message)
                    }
                }
            }
        }
    }

    fun onMediaPicked(entries: List<LocalFileEntry>) {
        val visual = entries.filter { it.isVisualMedia() }
        if (visual.isEmpty()) return
        sessionController.recordRecentFiles(visual)
        mergeAdditionalMedia(visual)
    }

    private fun mergeAdditionalMedia(newItems: List<LocalFileEntry>) {
        viewModelScope.launch {
            val existing = _uiState.value.allMedia
            val organized = withContext(Dispatchers.Default) {
                organizeMediaEntries(newItems + existing)
            }
            _uiState.update { state ->
                state.copy(
                    allMedia = organized.allMedia,
                    imagesOnly = organized.imagesOnly,
                    videosOnly = organized.videosOnly,
                    albums = organized.albums,
                    isLoading = false
                )
            }
        }
    }

    fun openPreview(entry: LocalFileEntry) {
        _uiState.update { it.copy(previewTarget = entry) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewTarget = null) }
    }

    fun openDetails(entry: LocalFileEntry) {
        _uiState.update { it.copy(detailsTarget = entry) }
    }

    fun closeDetails() {
        _uiState.update { it.copy(detailsTarget = null) }
    }

    fun queueSingleForSend(entry: LocalFileEntry) {
        if (!entry.isDirectory) {
            sessionController.addSelectedFiles(listOf(entry))
        }
    }

    fun deleteEntry(entry: LocalFileEntry) {
        viewModelScope.launch {
            when (val res = fileManagerRepository.deleteFiles(listOf(entry.uriString))) {
                is AppResult.Success -> {
                    if (res.data.succeededCount > 0) {
                        _uiState.update { it.copy(feedbackMessage = "Deleted ${entry.displayName}") }
                        refreshMedia(silent = true)
                    } else {
                        val reason = res.data.failures.firstOrNull()?.reason ?: "Unable to delete item"
                        _uiState.update { it.copy(feedbackMessage = reason) }
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(feedbackMessage = res.error.message) }
                }
            }
        }
    }

    fun showFeedback(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    fun consumeFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    internal data class OrganizedMedia(
        val allMedia: List<LocalFileEntry>,
        val imagesOnly: List<LocalFileEntry>,
        val videosOnly: List<LocalFileEntry>,
        val albums: List<MediaStudioAlbum>
    )

    companion object {
        private const val STORAGE_CHANGE_DEBOUNCE_MS = 450L
        private const val DEFAULT_ALBUM_NAME = "Gallery"

        internal fun organizeMediaEntries(rawEntries: List<LocalFileEntry>): OrganizedMedia {
            val byUri = LinkedHashMap<String, LocalFileEntry>()
            val seenSignatures = HashSet<String>()

            for (entry in rawEntries) {
                if (!entry.isVisualMedia() || !entry.isAccessible) continue
                val signature = "${entry.displayName.trim().lowercase()}|${entry.sizeBytes}|${entry.isVideoMedia()}"
                if (!byUri.containsKey(entry.uriString) && seenSignatures.add(signature)) {
                    byUri[entry.uriString] = entry
                }
            }

            val allMedia = byUri.values.sortedByDescending { it.lastModifiedEpochMillis }
            val imagesOnly = allMedia.filter { !it.isVideoMedia() && (it.category == FileCategory.IMAGES || it.mimeType.startsWith("image/", ignoreCase = true)) }
            val videosOnly = allMedia.filter { it.isVideoMedia() }

            val albums = allMedia
                .groupBy { inferAlbumName(it) }
                .entries
                .map { (albumName, items) ->
                    MediaStudioAlbum(
                        name = albumName,
                        coverItem = items.first(),
                        items = items
                    )
                }
                .sortedByDescending { it.coverItem.lastModifiedEpochMillis }

            return OrganizedMedia(
                allMedia = allMedia,
                imagesOnly = imagesOnly,
                videosOnly = videosOnly,
                albums = albums
            )
        }

        internal fun inferAlbumName(entry: LocalFileEntry): String {
            // 1. Prefer SAF parent folder name if present
            val parentRaw = entry.parentUriString
            if (!parentRaw.isNullOrBlank()) {
                val decodedParent = runCatching { Uri.decode(parentRaw) }.getOrDefault(parentRaw)
                val parentSegment = decodedParent
                    .substringAfterLast(':')
                    .trimEnd('/')
                    .substringAfterLast('/')
                    .trim()
                if (parentSegment.isNotBlank() && !parentSegment.equals("document", ignoreCase = true)) {
                    return parentSegment
                }
            }

            // 2. Inspect URI path segments (file://, SAF document://, or FileProvider ShareFlowReceived)
            val decodedUri = runCatching { Uri.decode(entry.uriString) }.getOrDefault(entry.uriString)
            if (decodedUri.contains("ShareFlowReceived", ignoreCase = true) ||
                decodedUri.contains("shareflow_received", ignoreCase = true)
            ) {
                return "ShareFlow"
            }

            val parsed = runCatching { Uri.parse(entry.uriString) }.getOrNull()
            if (parsed != null) {
                if (parsed.scheme.equals("file", ignoreCase = true)) {
                    val parentName = parsed.path?.let { File(it).parentFile?.name }?.trim()
                    if (!parentName.isNullOrBlank()) return parentName
                }
                val docPart = decodedUri.substringAfterLast(':', "")
                if (docPart.contains('/')) {
                    val folder = docPart.substringBeforeLast('/').substringAfterLast('/').trim()
                    if (folder.isNotBlank()) return folder
                }
            }

            return if (entry.isDownload) "Downloads" else DEFAULT_ALBUM_NAME
        }
    }
}
