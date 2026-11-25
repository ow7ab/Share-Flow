package com.example.shareflow.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.data.files.FileListPagingSource
import com.example.shareflow.data.files.FileSortAndFilter
import com.example.shareflow.domain.model.DirectoryBreadcrumb
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSortField
import com.example.shareflow.domain.model.FileSortSpec
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.SortDirection
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.network.LocalSessionController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FileBrowseMode {
    OVERVIEW,
    CATEGORY,
    DIRECTORY
}

enum class FileViewLayout {
    LIST,
    GRID
}

enum class PendingTransferOperation {
    COPY,
    MOVE
}

data class CategoryCountSummary(
    val count: Int = 0,
    val totalBytes: Long = 0L
)

data class FileManagerUiState(
    val browseMode: FileBrowseMode = FileBrowseMode.OVERVIEW,
    val activeCategory: FileCategory? = null,
    val overviewCategoryFilter: FileCategory? = null,
    val breadcrumbs: List<DirectoryBreadcrumb> = emptyList(),
    val currentDirectoryWritable: Boolean = false,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val sortSpec: FileSortSpec = FileSortSpec(),
    val viewLayout: FileViewLayout = FileViewLayout.LIST,
    val selectedUris: Set<String> = emptySet(),
    val visibleEntriesCount: Int = 0,
    val selectedTotalBytes: Long = 0L,
    val isLoading: Boolean = false,
    val isOperationBusy: Boolean = false,
    val isPermissionRevoked: Boolean = false,
    val errorMessage: String? = null,
    val storageSummary: StorageSummary? = null,
    val grantedDirectories: List<GrantedDirectory> = emptyList(),
    val categorySummaries: Map<FileCategory, CategoryCountSummary> = emptyMap(),
    val detailsTarget: LocalFileEntry? = null,
    val detailsSha256: String? = null,
    val isComputingSha256: Boolean = false,
    val previewTarget: LocalFileEntry? = null,
    val previewTextContent: String? = null,
    val isPreviewLoading: Boolean = false,
    val renameTarget: LocalFileEntry? = null,
    val showCreateFolderDialog: Boolean = false,
    val showSortSheet: Boolean = false,
    val showDeleteConfirm: Boolean = false,
    val deleteTargets: List<LocalFileEntry> = emptyList(),
    val pendingTransferOperation: PendingTransferOperation? = null,
    val pendingTransferTargets: List<LocalFileEntry> = emptyList(),
    val feedbackMessage: String? = null
) {
    val isSelectionMode: Boolean get() = selectedUris.isNotEmpty()
    val currentDirectoryUri: String? get() = breadcrumbs.lastOrNull()?.uriString
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FileManagerViewModel(
    private val fileManagerRepository: FileManagerRepository,
    private val storageAnalyzerRepository: StorageAnalyzerRepository,
    private val sessionController: LocalSessionController,
    initialCategory: FileCategory? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FileManagerUiState(
            browseMode = if (initialCategory != null) FileBrowseMode.CATEGORY else FileBrowseMode.OVERVIEW,
            activeCategory = initialCategory,
            viewLayout = if (initialCategory == FileCategory.IMAGES || initialCategory == FileCategory.VIDEOS) {
                FileViewLayout.GRID
            } else {
                FileViewLayout.LIST
            }
        )
    )
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()

    private val allAccessibleEntries = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    private val directoryEntries = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    private val refreshNonce = MutableStateFlow(0L)
    private var activeRefreshJob: Job? = null

    /**
     * Strictly minimal filter parameters that should trigger a new [Pager].
     * Unrelated UI state changes (selection toggles, dialogs, snackbar messages, storageSummary)
     * NEVER recreate the Pager or disrupt scroll position.
     */
    private data class PagingFilterParams(
        val browseMode: FileBrowseMode,
        val effectiveCategory: FileCategory?,
        val searchQuery: String,
        val sortSpec: FileSortSpec
    )

    private data class PagingQueryKey(
        val entries: List<LocalFileEntry>,
        val query: String,
        val category: FileCategory?,
        val sortSpec: FileSortSpec,
        val nonce: Long
    )

    private val pagingFilterFlow: Flow<PagingFilterParams> = _uiState
        .map { state ->
            val effectiveCategory = when (state.browseMode) {
                FileBrowseMode.CATEGORY -> state.activeCategory
                FileBrowseMode.OVERVIEW -> state.overviewCategoryFilter
                FileBrowseMode.DIRECTORY -> null
            }
            PagingFilterParams(
                browseMode = state.browseMode,
                effectiveCategory = effectiveCategory,
                searchQuery = state.searchQuery,
                sortSpec = state.sortSpec
            )
        }
        .distinctUntilChanged()

    val pagedFiles: Flow<PagingData<LocalFileEntry>> = combine(
        pagingFilterFlow,
        allAccessibleEntries,
        directoryEntries,
        refreshNonce
    ) { filter, accessible, dirEntries, nonce ->
        val raw = when (filter.browseMode) {
            FileBrowseMode.DIRECTORY -> dirEntries
            FileBrowseMode.CATEGORY, FileBrowseMode.OVERVIEW -> accessible
        }
        PagingQueryKey(
            entries = raw,
            query = filter.searchQuery,
            category = filter.effectiveCategory,
            sortSpec = filter.sortSpec,
            nonce = nonce
        )
    }.distinctUntilChanged().flatMapLatest { key ->
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE * 2,
                prefetchDistance = PREFETCH_DISTANCE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                FileListPagingSource(
                    loadAllEntries = { key.entries },
                    query = key.query,
                    categoryFilter = key.category,
                    sortSpec = key.sortSpec
                )
            }
        ).flow
    }.cachedIn(viewModelScope)

    init {
        observeStorageSummary()
        observeGrantedDirectories()
        observeSessionRecentFiles()
        observeStorageChanges()
        refreshCurrentView()
    }

    private fun observeStorageSummary() {
        viewModelScope.launch {
            storageAnalyzerRepository.observeStorageSummary().collect { result ->
                val summary = (result as? AppResult.Success)?.data
                _uiState.update { it.copy(storageSummary = summary) }
            }
        }
    }

    private fun observeGrantedDirectories() {
        viewModelScope.launch {
            fileManagerRepository.observeGrantedDirectories().collect { dirs ->
                _uiState.update { state ->
                    val currentRoot = state.breadcrumbs.firstOrNull()?.uriString
                    val matchingDir = dirs.firstOrNull { it.treeUriString == currentRoot }
                    state.copy(
                        grantedDirectories = dirs,
                        currentDirectoryWritable = matchingDir?.canWrite ?: state.currentDirectoryWritable
                    )
                }
            }
        }
    }

    private fun observeSessionRecentFiles() {
        viewModelScope.launch {
            sessionController.recentFiles.collect { picked ->
                if (picked.isNotEmpty()) {
                    mergeIntoAccessibleIndex(picked)
                }
            }
        }
    }

    private fun observeStorageChanges() {
        viewModelScope.launch {
            // Debounce rapid MediaStore ContentObserver bursts into a single calm background sync
            fileManagerRepository.observeStorageChanges()
                .debounce(STORAGE_CHANGE_DEBOUNCE_MS)
                .collect {
                    refreshCurrentView(silent = true)
                }
        }
    }

    fun setOverviewCategoryFilter(category: FileCategory?) {
        _uiState.update { state ->
            val next = if (state.overviewCategoryFilter == category) null else category
            val nextLayout = if (next == FileCategory.IMAGES || next == FileCategory.VIDEOS) {
                FileViewLayout.GRID
            } else {
                state.viewLayout
            }
            state.copy(
                overviewCategoryFilter = next,
                viewLayout = nextLayout
            )
        }
        updateVisibleCountForCurrentMode()
    }

    fun refreshCurrentView(silent: Boolean = false) {
        activeRefreshJob?.cancel()
        activeRefreshJob = viewModelScope.launch {
            val state = _uiState.value
            if (!silent) {
                _uiState.update { it.copy(isLoading = true, errorMessage = null, isPermissionRevoked = false) }
            }

            when (state.browseMode) {
                FileBrowseMode.DIRECTORY -> {
                    val targetUri = state.currentDirectoryUri
                    if (targetUri != null) {
                        loadDirectoryInternal(targetUri)
                    } else {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }

                FileBrowseMode.CATEGORY, FileBrowseMode.OVERVIEW -> {
                    scanAccessibleMediaInternal()
                }
            }
            storageAnalyzerRepository.refreshStorageSummary()
        }
    }

    fun scanAccessibleMedia() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isPermissionRevoked = false) }
            fileManagerRepository.notifyStorageChanged()
            val count = scanAccessibleMediaInternal()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    feedbackMessage = "Scanned $count accessible media file(s)"
                )
            }
        }
    }

    private suspend fun scanAccessibleMediaInternal(): Int {
        val merged = LinkedHashMap<String, LocalFileEntry>()
        sessionController.recentFiles.value.forEach { merged[it.uriString] = it }

        var mediaError: AppError? = null
        when (val mediaResult = fileManagerRepository.scanAccessibleMedia()) {
            is AppResult.Success -> {
                mediaResult.data.forEach { merged[it.uriString] = it }
            }
            is AppResult.Failure -> {
                mediaError = mediaResult.error
            }
        }

        val combinedList = merged.values.toList()
        allAccessibleEntries.value = combinedList
        updateCategoryAndVisibleCounts(combinedList)

        _uiState.update { state ->
            val shouldShowError = combinedList.isEmpty() && mediaError != null
            state.copy(
                isLoading = false,
                errorMessage = if (shouldShowError) mediaError?.message else null,
                isPermissionRevoked = shouldShowError && mediaError is AppError.PermissionDenied
            )
        }
        bumpRefresh()
        return combinedList.size
    }

    fun onDirectoryPicked(treeUriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, isPermissionRevoked = false) }
            when (val reg = fileManagerRepository.registerGrantedDirectory(treeUriString)) {
                is AppResult.Success -> {
                    val dir = reg.data
                    _uiState.update {
                        it.copy(
                            browseMode = FileBrowseMode.DIRECTORY,
                            activeCategory = null,
                            breadcrumbs = listOf(DirectoryBreadcrumb(dir.treeUriString, dir.displayName)),
                            currentDirectoryWritable = dir.canWrite,
                            selectedUris = emptySet(),
                            feedbackMessage = "Folder access granted: ${dir.displayName}"
                        )
                    }
                    loadDirectoryInternal(dir.treeUriString)
                }

                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isPermissionRevoked = reg.error is AppError.PermissionDenied,
                            errorMessage = reg.error.message
                        )
                    }
                }
            }
        }
    }

    fun openGrantedDirectory(directory: GrantedDirectory) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    browseMode = FileBrowseMode.DIRECTORY,
                    activeCategory = null,
                    breadcrumbs = listOf(DirectoryBreadcrumb(directory.treeUriString, directory.displayName)),
                    currentDirectoryWritable = directory.canWrite,
                    selectedUris = emptySet(),
                    isLoading = true,
                    errorMessage = null,
                    isPermissionRevoked = !directory.isAccessible
                )
            }
            loadDirectoryInternal(directory.treeUriString)
        }
    }

    fun removeGrantedDirectory(treeUriString: String) {
        viewModelScope.launch {
            fileManagerRepository.releaseGrantedDirectory(treeUriString)
            if (_uiState.value.breadcrumbs.firstOrNull()?.uriString == treeUriString) {
                exitToOverview()
            }
        }
    }

    fun openSubfolder(folder: LocalFileEntry) {
        if (!folder.isDirectory) return
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    browseMode = FileBrowseMode.DIRECTORY,
                    breadcrumbs = state.breadcrumbs + DirectoryBreadcrumb(folder.uriString, folder.displayName),
                    currentDirectoryWritable = folder.capabilities.canWrite || state.currentDirectoryWritable,
                    selectedUris = emptySet(),
                    isLoading = true,
                    errorMessage = null,
                    isPermissionRevoked = false
                )
            }
            loadDirectoryInternal(folder.uriString)
        }
    }

    fun navigateToBreadcrumb(index: Int) {
        val current = _uiState.value.breadcrumbs
        if (index !in current.indices) return
        val trimmed = current.take(index + 1)
        val target = trimmed.last()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    breadcrumbs = trimmed,
                    selectedUris = emptySet(),
                    isLoading = true,
                    errorMessage = null,
                    isPermissionRevoked = false
                )
            }
            loadDirectoryInternal(target.uriString)
        }
    }

    fun navigateUpDirectory(): Boolean {
        val state = _uiState.value
        if (state.browseMode != FileBrowseMode.DIRECTORY) return false
        return if (state.breadcrumbs.size > 1) {
            navigateToBreadcrumb(state.breadcrumbs.lastIndex - 1)
            true
        } else {
            exitToOverview()
            true
        }
    }

    fun exitToOverview() {
        _uiState.update {
            it.copy(
                browseMode = FileBrowseMode.OVERVIEW,
                activeCategory = null,
                breadcrumbs = emptyList(),
                selectedUris = emptySet(),
                errorMessage = null,
                isPermissionRevoked = false
            )
        }
        updateVisibleCountForCurrentMode()
        bumpRefresh()
    }

    fun openCategory(category: FileCategory) {
        _uiState.update {
            it.copy(
                browseMode = FileBrowseMode.CATEGORY,
                activeCategory = category,
                breadcrumbs = emptyList(),
                selectedUris = emptySet(),
                errorMessage = null,
                isPermissionRevoked = false
            )
        }
        updateVisibleCountForCurrentMode()
        bumpRefresh()
    }

    private suspend fun loadDirectoryInternal(directoryUriString: String) {
        when (val result = fileManagerRepository.listDirectory(directoryUriString)) {
            is AppResult.Success -> {
                directoryEntries.value = result.data
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = null,
                        isPermissionRevoked = false
                    )
                }
                updateVisibleCountForCurrentMode()
                bumpRefresh()
            }

            is AppResult.Failure -> {
                directoryEntries.value = emptyList()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isPermissionRevoked = result.error is AppError.PermissionDenied,
                        errorMessage = result.error.message,
                        visibleEntriesCount = 0
                    )
                }
                bumpRefresh()
            }
        }
    }

    fun onFilesPicked(entries: List<LocalFileEntry>) {
        if (entries.isEmpty()) return
        sessionController.recordRecentFiles(entries)
        mergeIntoAccessibleIndex(entries)
    }

    private fun mergeIntoAccessibleIndex(entries: List<LocalFileEntry>) {
        val merged = LinkedHashMap<String, LocalFileEntry>()
        entries.forEach { merged[it.uriString] = it }
        allAccessibleEntries.value.forEach { merged.putIfAbsent(it.uriString, it) }
        val updated = merged.values.toList()
        allAccessibleEntries.value = updated
        updateCategoryAndVisibleCounts(updated)
        bumpRefresh()
    }

    // --- Search, Sort, View Layout ---

    fun setSearchActive(active: Boolean) {
        _uiState.update {
            it.copy(
                isSearchActive = active,
                searchQuery = if (active) it.searchQuery else ""
            )
        }
        updateVisibleCountForCurrentMode()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        updateVisibleCountForCurrentMode()
    }

    fun toggleViewLayout() {
        _uiState.update {
            val next = if (it.viewLayout == FileViewLayout.LIST) FileViewLayout.GRID else FileViewLayout.LIST
            it.copy(viewLayout = next)
        }
    }

    fun openSortSheet(show: Boolean) {
        _uiState.update { it.copy(showSortSheet = show) }
    }

    fun updateSort(field: FileSortField, direction: SortDirection) {
        _uiState.update {
            it.copy(
                sortSpec = it.sortSpec.copy(field = field, direction = direction),
                showSortSheet = false
            )
        }
    }

    // --- Multi-Selection ---

    fun toggleSelection(uriString: String) {
        _uiState.update { state ->
            val updated = if (uriString in state.selectedUris) {
                state.selectedUris - uriString
            } else {
                state.selectedUris + uriString
            }
            state.copy(
                selectedUris = updated,
                selectedTotalBytes = computeSelectedBytes(updated)
            )
        }
    }

    fun selectAllVisible() {
        val visible = currentFilteredEntries()
        val allUris = visible.mapTo(LinkedHashSet()) { it.uriString }
        _uiState.update {
            it.copy(
                selectedUris = allUris,
                selectedTotalBytes = computeSelectedBytes(allUris)
            )
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedUris = emptySet(), selectedTotalBytes = 0L) }
    }

    fun getSelectedEntries(): List<LocalFileEntry> {
        val selected = _uiState.value.selectedUris
        if (selected.isEmpty()) return emptyList()
        return currentPoolEntries().filter { it.uriString in selected }
    }

    fun queueSelectedForShareFlowSend(): List<LocalFileEntry> {
        val selected = getSelectedEntries().filterNot { it.isDirectory }
        if (selected.isNotEmpty()) {
            sessionController.addSelectedFiles(selected)
            clearSelection()
        }
        return selected
    }

    fun queueSingleForShareFlowSend(entry: LocalFileEntry) {
        if (!entry.isDirectory) {
            sessionController.addSelectedFiles(listOf(entry))
        }
    }

    // --- Details & Preview ---

    fun openDetails(entry: LocalFileEntry) {
        _uiState.update {
            it.copy(
                detailsTarget = entry,
                detailsSha256 = null,
                isComputingSha256 = false
            )
        }
    }

    fun closeDetails() {
        _uiState.update { it.copy(detailsTarget = null, detailsSha256 = null, isComputingSha256 = false) }
    }

    fun computeDetailsSha256() {
        val target = _uiState.value.detailsTarget ?: return
        if (target.isDirectory || _uiState.value.isComputingSha256) return
        viewModelScope.launch {
            _uiState.update { it.copy(isComputingSha256 = true) }
            when (val res = fileManagerRepository.computeSha256(target.uriString)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(isComputingSha256 = false, detailsSha256 = res.data)
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(isComputingSha256 = false, feedbackMessage = res.error.message)
                }
            }
        }
    }

    fun openPreview(entry: LocalFileEntry) {
        if (entry.isDirectory) return
        _uiState.update {
            it.copy(
                previewTarget = entry,
                previewTextContent = null,
                isPreviewLoading = false
            )
        }
        if (com.example.shareflow.core.common.SafDocumentInspector.isPreviewableText(
                entry.mimeType,
                entry.displayName
            )
        ) {
            viewModelScope.launch {
                _uiState.update { it.copy(isPreviewLoading = true) }
                when (val res = fileManagerRepository.readTextPreview(entry.uriString)) {
                    is AppResult.Success -> _uiState.update {
                        it.copy(isPreviewLoading = false, previewTextContent = res.data)
                    }
                    is AppResult.Failure -> _uiState.update {
                        it.copy(isPreviewLoading = false, feedbackMessage = res.error.message)
                    }
                }
            }
        }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewTarget = null, previewTextContent = null, isPreviewLoading = false) }
    }

    // --- File Operations: Create Folder, Rename, Copy, Move, Delete ---

    fun openCreateFolderDialog(show: Boolean) {
        _uiState.update { it.copy(showCreateFolderDialog = show) }
    }

    fun createFolder(folderName: String) {
        val parentUri = _uiState.value.currentDirectoryUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isOperationBusy = true, showCreateFolderDialog = false) }
            when (val res = fileManagerRepository.createFolder(parentUri, folderName)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = "Folder created") }
                    loadDirectoryInternal(parentUri)
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = res.error.message) }
                }
            }
        }
    }

    fun openRenameDialog(entry: LocalFileEntry?) {
        _uiState.update { it.copy(renameTarget = entry) }
    }

    fun renameFile(entry: LocalFileEntry, newName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperationBusy = true, renameTarget = null) }
            when (val res = fileManagerRepository.renameFile(entry.uriString, newName)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = "Renamed successfully") }
                    refreshCurrentView(silent = true)
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = res.error.message) }
                }
            }
        }
    }

    fun requestDelete(entries: List<LocalFileEntry>) {
        if (entries.isEmpty()) return
        _uiState.update { it.copy(showDeleteConfirm = true, deleteTargets = entries) }
    }

    fun dismissDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirm = false, deleteTargets = emptyList()) }
    }

    fun confirmDelete() {
        val targets = _uiState.value.deleteTargets
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirm = false, deleteTargets = emptyList(), isOperationBusy = true) }
            val uris = targets.map { it.uriString }
            when (val res = fileManagerRepository.deleteFiles(uris)) {
                is AppResult.Success -> {
                    val op = res.data
                    val deletedSet = op.succeededUris.toSet()
                    allAccessibleEntries.update { list -> list.filterNot { it.uriString in deletedSet } }
                    directoryEntries.update { list -> list.filterNot { it.uriString in deletedSet } }
                    clearSelection()
                    val msg = if (op.failedCount > 0) {
                        "${op.succeededCount} deleted, ${op.failedCount} failed"
                    } else {
                        "Deleted ${op.succeededCount} item(s)"
                    }
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = msg) }
                    refreshCurrentView(silent = true)
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = res.error.message) }
                }
            }
        }
    }

    fun startTransferOperation(operation: PendingTransferOperation, targets: List<LocalFileEntry>) {
        if (targets.isEmpty()) return
        _uiState.update {
            it.copy(
                pendingTransferOperation = operation,
                pendingTransferTargets = targets
            )
        }
    }

    fun cancelTransferOperation() {
        _uiState.update {
            it.copy(
                pendingTransferOperation = null,
                pendingTransferTargets = emptyList()
            )
        }
    }

    fun executeTransferToDirectory(targetDirectoryUriString: String) {
        val op = _uiState.value.pendingTransferOperation ?: return
        val targets = _uiState.value.pendingTransferTargets
        if (targets.isEmpty()) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    pendingTransferOperation = null,
                    pendingTransferTargets = emptyList(),
                    isOperationBusy = true
                )
            }
            val uris = targets.map { it.uriString }
            val result = when (op) {
                PendingTransferOperation.COPY -> fileManagerRepository.copyFiles(uris, targetDirectoryUriString)
                PendingTransferOperation.MOVE -> fileManagerRepository.moveFiles(uris, targetDirectoryUriString)
            }
            when (result) {
                is AppResult.Success -> {
                    val summary = result.data
                    clearSelection()
                    val verb = if (op == PendingTransferOperation.COPY) "Copied" else "Moved"
                    val msg = if (summary.failedCount > 0) {
                        "$verb ${summary.succeededCount}, ${summary.failedCount} failed"
                    } else {
                        "$verb ${summary.succeededCount} file(s)"
                    }
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = msg) }
                    refreshCurrentView(silent = true)
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isOperationBusy = false, feedbackMessage = result.error.message) }
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

    private fun currentPoolEntries(): List<LocalFileEntry> = when (_uiState.value.browseMode) {
        FileBrowseMode.DIRECTORY -> directoryEntries.value
        FileBrowseMode.CATEGORY, FileBrowseMode.OVERVIEW -> allAccessibleEntries.value
    }

    private fun currentFilteredEntries(): List<LocalFileEntry> {
        val state = _uiState.value
        val category = when (state.browseMode) {
            FileBrowseMode.CATEGORY -> state.activeCategory
            FileBrowseMode.OVERVIEW -> state.overviewCategoryFilter
            FileBrowseMode.DIRECTORY -> null
        }
        return FileSortAndFilter.apply(
            entries = currentPoolEntries(),
            query = state.searchQuery,
            categoryFilter = category,
            sortSpec = state.sortSpec
        )
    }

    private fun updateVisibleCountForCurrentMode() {
        val visible = currentFilteredEntries()
        _uiState.update { it.copy(visibleEntriesCount = visible.size) }
    }

    private fun updateCategoryAndVisibleCounts(entries: List<LocalFileEntry>) {
        val filesOnly = entries.filterNot { it.isDirectory }
        val summaries = FileCategory.entries.associateWith { cat ->
            val matching = filesOnly.filter { it.matchesCategory(cat) }
            CategoryCountSummary(
                count = matching.size,
                totalBytes = matching.sumOf { it.sizeBytes }
            )
        }
        _uiState.update { it.copy(categorySummaries = summaries) }
        updateVisibleCountForCurrentMode()
    }

    private fun computeSelectedBytes(uris: Set<String>): Long =
        currentPoolEntries()
            .asSequence()
            .filter { it.uriString in uris && !it.isDirectory }
            .sumOf { it.sizeBytes }

    private fun bumpRefresh() {
        refreshNonce.update { it + 1L }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val PREFETCH_DISTANCE = 20
        const val STORAGE_CHANGE_DEBOUNCE_MS = 450L
    }
}
