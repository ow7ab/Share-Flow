package com.example.shareflow.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.QrScannerUiState
import com.example.shareflow.domain.model.ShareableAppInfo
import com.example.shareflow.domain.model.SmartConnectionError
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.repository.AppSharingRepository
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.media.MediaCenterRepository
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.network.coordinator.UnifiedConnectionManager
import com.example.shareflow.network.transfer.TransferManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SendContentTab {
    PHOTOS,
    VIDEOS,
    APPS,
    FILES
}

enum class SendPhotoFilter {
    ALL,
    RECENT,
    ALBUMS
}

enum class SendAppSortOption {
    NAME,
    SIZE,
    RECENT
}

data class SendUiState(
    val activeTab: SendContentTab = SendContentTab.PHOTOS,
    val activeCategory: FileCategory? = null,
    val allSelectedFiles: List<LocalFileEntry> = emptyList(),
    val filteredFiles: List<LocalFileEntry> = emptyList(),
    // Photos gallery state
    val photos: List<MediaImageItem> = emptyList(),
    val photoFilter: SendPhotoFilter = SendPhotoFilter.ALL,
    val selectedAlbum: String? = null,
    val isLoadingPhotos: Boolean = false,
    // Videos gallery state
    val videos: List<MediaVideoItem> = emptyList(),
    val isLoadingVideos: Boolean = false,
    // Installed applications state
    val installedApps: List<ShareableAppInfo> = emptyList(),
    val selectedAppPackages: Set<String> = emptySet(),
    val appSearchQuery: String = "",
    val appSortOption: SendAppSortOption = SendAppSortOption.NAME,
    val includeSystemApps: Boolean = false,
    val isLoadingApps: Boolean = false,
    val isExtractingApps: Boolean = false,
    // Files browser state
    val browsedFiles: List<LocalFileEntry> = emptyList(),
    val isLoadingBrowsedFiles: Boolean = false,
    // Unified totals across selected files + selected installed apps
    val selectedCount: Int = 0,
    val totalSelectedBytes: Long = 0L,
    // Unified connection state
    val trustedDevices: List<PeerDevice> = emptyList(),
    val discoveredDevices: List<PeerDevice> = emptyList(),
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.Idle,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val smartPhase: SmartConnectionPhase = SmartConnectionPhase.IDLE,
    val smartStrategy: SmartStrategyType? = null,
    val smartError: SmartConnectionError? = null,
    val scannerState: QrScannerUiState = QrScannerUiState.SCANNING,
    val activeTransferCount: Int = 0,
    val isStartingTransfer: Boolean = false,
    val feedbackMessage: String? = null
) {
    val selectedUriSet: Set<String>
        get() = allSelectedFiles.mapTo(HashSet()) { it.uriString }

    val availableAlbums: List<String>
        get() = photos.map { it.albumName }.distinct().sorted()

    val visiblePhotos: List<MediaImageItem>
        get() = when (photoFilter) {
            SendPhotoFilter.ALL -> photos
            SendPhotoFilter.RECENT -> photos.sortedByDescending { it.dateModifiedEpochMillis }.take(30)
            SendPhotoFilter.ALBUMS -> if (selectedAlbum.isNullOrBlank()) {
                photos
            } else {
                photos.filter { it.albumName == selectedAlbum }
            }
        }

    val visibleApps: List<ShareableAppInfo>
        get() {
            val q = appSearchQuery.trim().lowercase()
            val matched = if (q.isEmpty()) {
                installedApps
            } else {
                installedApps.filter {
                    it.appName.lowercase().contains(q) ||
                        it.packageName.lowercase().contains(q) ||
                        it.categoryLabel.lowercase().contains(q)
                }
            }
            return when (appSortOption) {
                SendAppSortOption.NAME -> matched.sortedBy { it.appName.lowercase() }
                SendAppSortOption.SIZE -> matched.sortedByDescending { it.totalApkSizeBytes ?: 0L }
                SendAppSortOption.RECENT -> matched.sortedByDescending { it.installedAtEpochMillis }
            }
        }

    val visibleBrowsedFiles: List<LocalFileEntry>
        get() = if (activeCategory == null) {
            browsedFiles
        } else {
            browsedFiles.filter { it.category == activeCategory }
        }
}

class SendViewModel(
    private val sessionController: LocalSessionController,
    trustedDeviceRepository: TrustedDeviceRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val discoveryRepository: DeviceDiscoveryRepository? = null,
    private val connectionRepository: ConnectionRepository? = null,
    private val transferManager: TransferManager? = null,
    private val mediaCenterRepository: MediaCenterRepository? = null,
    private val appSharingRepository: AppSharingRepository? = null,
    private val fileManagerRepository: FileManagerRepository? = null,
    private val unifiedConnectionManager: UnifiedConnectionManager? = null
) : ViewModel() {

    private data class BrowserState(
        val activeTab: SendContentTab = SendContentTab.PHOTOS,
        val activeCategory: FileCategory? = null,
        val photos: List<MediaImageItem> = emptyList(),
        val photoFilter: SendPhotoFilter = SendPhotoFilter.ALL,
        val selectedAlbum: String? = null,
        val isLoadingPhotos: Boolean = false,
        val videos: List<MediaVideoItem> = emptyList(),
        val isLoadingVideos: Boolean = false,
        val installedApps: List<ShareableAppInfo> = emptyList(),
        val selectedAppPackages: Set<String> = emptySet(),
        val appSearchQuery: String = "",
        val appSortOption: SendAppSortOption = SendAppSortOption.NAME,
        val includeSystemApps: Boolean = false,
        val isLoadingApps: Boolean = false,
        val isExtractingApps: Boolean = false,
        val browsedFiles: List<LocalFileEntry> = emptyList(),
        val isLoadingBrowsedFiles: Boolean = false
    )

    private data class SendNetworkSnapshot(
        val discovered: List<PeerDevice>,
        val discoveryStatus: DiscoveryStatus,
        val connectionState: ConnectionState,
        val smartPhase: SmartConnectionPhase,
        val smartStrategy: SmartStrategyType?,
        val smartError: SmartConnectionError?,
        val scannerState: QrScannerUiState,
        val feedback: String?
    )

    private val browserState = MutableStateFlow(BrowserState())
    private val feedbackState = MutableStateFlow<String?>(null)
    private val startingTransferState = MutableStateFlow(false)
    private val scannerUiState = MutableStateFlow(QrScannerUiState.SCANNING)

    private val pickedPhotoUris = mutableListOf<String>()
    private val pickedVideoUris = mutableListOf<String>()
    private var photosLoadedOnce = false
    private var videosLoadedOnce = false
    private var appsLoadedOnce = false
    private var filesLoadedOnce = false
    private var lastSubmittedQrInput: String? = null

    private val selectionFlow = combine(
        browserState,
        sessionController.selectedSendFiles,
        trustedDeviceRepository.observeTrustedDevices()
    ) { browser, files, trusted ->
        Triple(browser, files, trusted)
    }

    private val smartFlow = combine(
        unifiedConnectionManager?.smartPhase ?: MutableStateFlow(SmartConnectionPhase.IDLE),
        unifiedConnectionManager?.activeStrategy ?: MutableStateFlow<SmartStrategyType?>(null),
        unifiedConnectionManager?.smartError ?: MutableStateFlow<SmartConnectionError?>(null),
        scannerUiState
    ) { phase, strategy, err, scanState ->
        Quad(phase, strategy, err, scanState)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private val networkFlow = combine(
        unifiedConnectionManager?.discoveredPeers
            ?: discoveryRepository?.discoveredPeers
            ?: sessionController.discoveredPeers,
        discoveryRepository?.discoveryStatus ?: MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle),
        unifiedConnectionManager?.connectionState
            ?: connectionRepository?.connectionState
            ?: sessionController.connectionState,
        smartFlow,
        feedbackState
    ) { discovered, discStatus, connState, smart, feedback ->
        SendNetworkSnapshot(
            discovered = discovered,
            discoveryStatus = discStatus,
            connectionState = connState,
            smartPhase = smart.a,
            smartStrategy = smart.b,
            smartError = smart.c,
            scannerState = smart.d,
            feedback = feedback
        )
    }

    private val transferFlow = combine(
        unifiedConnectionManager?.activeTransferSessions
            ?: transferManager?.activeSessions
            ?: MutableStateFlow(emptyList()),
        startingTransferState
    ) { active, starting ->
        active.size to starting
    }

    val uiState: StateFlow<SendUiState> = combine(
        selectionFlow,
        networkFlow,
        transferFlow
    ) { (browser, files, trusted), net, (activeTransfers, starting) ->
        val filtered = if (browser.activeCategory == null) {
            files
        } else {
            files.filter { it.category == browser.activeCategory }
        }
        val selectedApps = browser.installedApps.filter { it.packageName in browser.selectedAppPackages }
        val appBytes = selectedApps.sumOf { it.totalApkSizeBytes ?: 0L }
        val fileBytes = files.sumOf { it.sizeBytes }

        SendUiState(
            activeTab = browser.activeTab,
            activeCategory = browser.activeCategory,
            allSelectedFiles = files,
            filteredFiles = filtered,
            photos = browser.photos,
            photoFilter = browser.photoFilter,
            selectedAlbum = browser.selectedAlbum,
            isLoadingPhotos = browser.isLoadingPhotos,
            videos = browser.videos,
            isLoadingVideos = browser.isLoadingVideos,
            installedApps = browser.installedApps,
            selectedAppPackages = browser.selectedAppPackages,
            appSearchQuery = browser.appSearchQuery,
            appSortOption = browser.appSortOption,
            includeSystemApps = browser.includeSystemApps,
            isLoadingApps = browser.isLoadingApps,
            isExtractingApps = browser.isExtractingApps,
            browsedFiles = browser.browsedFiles,
            isLoadingBrowsedFiles = browser.isLoadingBrowsedFiles,
            selectedCount = files.size + browser.selectedAppPackages.size,
            totalSelectedBytes = fileBytes + appBytes,
            trustedDevices = trusted,
            discoveredDevices = net.discovered,
            discoveryStatus = net.discoveryStatus,
            connectionState = net.connectionState,
            smartPhase = net.smartPhase,
            smartStrategy = net.smartStrategy,
            smartError = net.smartError,
            scannerState = net.scannerState,
            activeTransferCount = activeTransfers,
            isStartingTransfer = starting,
            feedbackMessage = net.feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SendUiState()
    )

    init {
        // Load default Photos tab lazily on entry if repository is wired
        if (mediaCenterRepository != null) {
            loadPhotos(forceRefresh = false)
        }
    }

    fun selectTab(tab: SendContentTab) {
        browserState.update { it.copy(activeTab = tab) }
        when (tab) {
            SendContentTab.PHOTOS -> loadPhotos(forceRefresh = false)
            SendContentTab.VIDEOS -> loadVideos(forceRefresh = false)
            SendContentTab.APPS -> loadInstalledApps(forceRefresh = false)
            SendContentTab.FILES -> loadBrowsedFiles(forceRefresh = false)
        }
    }

    fun selectCategory(category: FileCategory?) {
        browserState.update { it.copy(activeCategory = category) }
    }

    fun selectPhotoFilter(filter: SendPhotoFilter, album: String? = null) {
        browserState.update {
            it.copy(
                photoFilter = filter,
                selectedAlbum = if (filter == SendPhotoFilter.ALBUMS) album ?: it.selectedAlbum else null
            )
        }
    }

    fun loadPhotos(forceRefresh: Boolean = true) {
        val repo = mediaCenterRepository ?: return
        if (photosLoadedOnce && !forceRefresh) return
        photosLoadedOnce = true
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingPhotos = true) }
            when (val res = repo.loadImages(extraUris = pickedPhotoUris.toList())) {
                is AppResult.Success -> browserState.update {
                    it.copy(photos = res.data, isLoadingPhotos = false)
                }
                is AppResult.Failure -> browserState.update {
                    it.copy(isLoadingPhotos = false)
                }
            }
        }
    }

    fun onPhotoPickerResult(uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        val repo = mediaCenterRepository ?: return
        uriStrings.forEach { uri ->
            if (uri !in pickedPhotoUris) pickedPhotoUris.add(uri)
        }
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingPhotos = true) }
            val inspected = uriStrings.mapNotNull { repo.inspectImageUri(it) }
            if (inspected.isNotEmpty()) {
                val entries = inspected.map { img ->
                    LocalFileEntry(
                        uriString = img.uriString,
                        displayName = img.displayName,
                        mimeType = img.mimeType,
                        sizeBytes = img.sizeBytes,
                        lastModifiedEpochMillis = img.dateModifiedEpochMillis,
                        category = FileCategory.IMAGES,
                        isDirectory = false
                    )
                }
                sessionController.addSelectedFiles(entries)
            }
            when (val res = repo.loadImages(extraUris = pickedPhotoUris.toList())) {
                is AppResult.Success -> browserState.update {
                    it.copy(photos = res.data, isLoadingPhotos = false)
                }
                is AppResult.Failure -> browserState.update { it.copy(isLoadingPhotos = false) }
            }
        }
    }

    fun togglePhotoSelection(photo: MediaImageItem) {
        val currentSelected = sessionController.selectedSendFiles.value
        if (currentSelected.any { it.uriString == photo.uriString }) {
            sessionController.removeSelectedFile(photo.uriString)
        } else {
            sessionController.addSelectedFiles(
                listOf(
                    LocalFileEntry(
                        uriString = photo.uriString,
                        displayName = photo.displayName,
                        mimeType = photo.mimeType,
                        sizeBytes = photo.sizeBytes,
                        lastModifiedEpochMillis = photo.dateModifiedEpochMillis,
                        category = FileCategory.IMAGES,
                        isDirectory = false
                    )
                )
            )
        }
    }

    fun toggleSelectAllVisiblePhotos() {
        val visible = uiState.value.visiblePhotos
        if (visible.isEmpty()) return
        val selectedUris = uiState.value.selectedUriSet
        val allSelected = visible.all { it.uriString in selectedUris }
        if (allSelected) {
            visible.forEach { sessionController.removeSelectedFile(it.uriString) }
        } else {
            sessionController.addSelectedFiles(
                visible.map { photo ->
                    LocalFileEntry(
                        uriString = photo.uriString,
                        displayName = photo.displayName,
                        mimeType = photo.mimeType,
                        sizeBytes = photo.sizeBytes,
                        lastModifiedEpochMillis = photo.dateModifiedEpochMillis,
                        category = FileCategory.IMAGES,
                        isDirectory = false
                    )
                }
            )
        }
    }

    fun loadVideos(forceRefresh: Boolean = true) {
        val repo = mediaCenterRepository ?: return
        if (videosLoadedOnce && !forceRefresh) return
        videosLoadedOnce = true
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingVideos = true) }
            when (val res = repo.loadVideos(extraUris = pickedVideoUris.toList())) {
                is AppResult.Success -> browserState.update {
                    it.copy(videos = res.data, isLoadingVideos = false)
                }
                is AppResult.Failure -> browserState.update { it.copy(isLoadingVideos = false) }
            }
        }
    }

    fun onVideoPickerResult(uriStrings: List<String>) {
        if (uriStrings.isEmpty()) return
        val repo = mediaCenterRepository ?: return
        uriStrings.forEach { uri ->
            if (uri !in pickedVideoUris) pickedVideoUris.add(uri)
        }
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingVideos = true) }
            val inspected = uriStrings.mapNotNull { repo.inspectVideoUri(it) }
            if (inspected.isNotEmpty()) {
                val entries = inspected.map { vid ->
                    LocalFileEntry(
                        uriString = vid.uriString,
                        displayName = vid.displayName,
                        mimeType = vid.mimeType,
                        sizeBytes = vid.sizeBytes,
                        lastModifiedEpochMillis = vid.dateModifiedEpochMillis,
                        category = FileCategory.VIDEOS,
                        isDirectory = false
                    )
                }
                sessionController.addSelectedFiles(entries)
            }
            when (val res = repo.loadVideos(extraUris = pickedVideoUris.toList())) {
                is AppResult.Success -> browserState.update {
                    it.copy(videos = res.data, isLoadingVideos = false)
                }
                is AppResult.Failure -> browserState.update { it.copy(isLoadingVideos = false) }
            }
        }
    }

    fun toggleVideoSelection(video: MediaVideoItem) {
        val currentSelected = sessionController.selectedSendFiles.value
        if (currentSelected.any { it.uriString == video.uriString }) {
            sessionController.removeSelectedFile(video.uriString)
        } else {
            sessionController.addSelectedFiles(
                listOf(
                    LocalFileEntry(
                        uriString = video.uriString,
                        displayName = video.displayName,
                        mimeType = video.mimeType,
                        sizeBytes = video.sizeBytes,
                        lastModifiedEpochMillis = video.dateModifiedEpochMillis,
                        category = FileCategory.VIDEOS,
                        isDirectory = false
                    )
                )
            )
        }
    }

    fun toggleSelectAllVideos() {
        val videos = uiState.value.videos
        if (videos.isEmpty()) return
        val selectedUris = uiState.value.selectedUriSet
        val allSelected = videos.all { it.uriString in selectedUris }
        if (allSelected) {
            videos.forEach { sessionController.removeSelectedFile(it.uriString) }
        } else {
            sessionController.addSelectedFiles(
                videos.map { vid ->
                    LocalFileEntry(
                        uriString = vid.uriString,
                        displayName = vid.displayName,
                        mimeType = vid.mimeType,
                        sizeBytes = vid.sizeBytes,
                        lastModifiedEpochMillis = vid.dateModifiedEpochMillis,
                        category = FileCategory.VIDEOS,
                        isDirectory = false
                    )
                }
            )
        }
    }

    fun loadInstalledApps(forceRefresh: Boolean = true) {
        val repo = appSharingRepository ?: return
        if (appsLoadedOnce && !forceRefresh) return
        appsLoadedOnce = true
        val includeSystem = browserState.value.includeSystemApps
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingApps = true) }
            when (val res = repo.discoverInstalledApps(includeSystemApps = includeSystem)) {
                is AppResult.Success -> browserState.update {
                    it.copy(installedApps = res.data, isLoadingApps = false)
                }
                is AppResult.Failure -> {
                    browserState.update { it.copy(isLoadingApps = false) }
                    feedbackState.value = res.error.message
                }
            }
        }
    }

    fun updateAppSearchQuery(query: String) {
        browserState.update { it.copy(appSearchQuery = query) }
    }

    fun setAppSortOption(option: SendAppSortOption) {
        browserState.update { it.copy(appSortOption = option) }
    }

    fun setIncludeSystemApps(include: Boolean) {
        if (browserState.value.includeSystemApps == include) return
        browserState.update { it.copy(includeSystemApps = include) }
        loadInstalledApps(forceRefresh = true)
    }

    fun toggleAppSelection(app: ShareableAppInfo) {
        if (!app.isExtractable) {
            feedbackState.value = app.unavailableReason
                ?: "${app.appName} cannot be extracted on this device."
            return
        }
        browserState.update { current ->
            val next = current.selectedAppPackages.toMutableSet()
            if (!next.add(app.packageName)) {
                next.remove(app.packageName)
            }
            current.copy(selectedAppPackages = next)
        }
    }

    fun toggleSelectAllVisibleApps() {
        val extractable = uiState.value.visibleApps.filter { it.isExtractable }
        if (extractable.isEmpty()) return
        val currentSelected = browserState.value.selectedAppPackages
        val allSelected = extractable.all { it.packageName in currentSelected }
        browserState.update { state ->
            val next = state.selectedAppPackages.toMutableSet()
            if (allSelected) {
                extractable.forEach { next.remove(it.packageName) }
            } else {
                extractable.forEach { next.add(it.packageName) }
            }
            state.copy(selectedAppPackages = next)
        }
    }

    fun loadBrowsedFiles(forceRefresh: Boolean = true) {
        val repo = fileManagerRepository ?: return
        if (filesLoadedOnce && !forceRefresh) return
        filesLoadedOnce = true
        viewModelScope.launch {
            browserState.update { it.copy(isLoadingBrowsedFiles = true) }
            val categories = listOf(
                FileCategory.DOCUMENTS,
                FileCategory.AUDIO,
                FileCategory.ARCHIVES,
                FileCategory.APKS,
                FileCategory.DOWNLOADS,
                FileCategory.OTHER
            )
            val collected = linkedMapOf<String, LocalFileEntry>()
            for (cat in categories) {
                when (val res = repo.queryByCategory(cat)) {
                    is AppResult.Success -> {
                        res.data.filter { !it.isDirectory && it.isAccessible }
                            .forEach { collected[it.uriString] = it }
                    }
                    is AppResult.Failure -> Unit
                }
            }
            browserState.update {
                it.copy(
                    browsedFiles = collected.values.sortedByDescending { f -> f.lastModifiedEpochMillis },
                    isLoadingBrowsedFiles = false
                )
            }
        }
    }

    fun toggleFileSelection(entry: LocalFileEntry) {
        val currentSelected = sessionController.selectedSendFiles.value
        if (currentSelected.any { it.uriString == entry.uriString }) {
            sessionController.removeSelectedFile(entry.uriString)
        } else {
            sessionController.addSelectedFiles(listOf(entry))
        }
    }

    fun toggleSelectAllVisibleFiles() {
        val visible = uiState.value.visibleBrowsedFiles
        if (visible.isEmpty()) return
        val selectedUris = uiState.value.selectedUriSet
        val allSelected = visible.all { it.uriString in selectedUris }
        if (allSelected) {
            visible.forEach { sessionController.removeSelectedFile(it.uriString) }
        } else {
            sessionController.addSelectedFiles(visible)
        }
    }

    fun addSelectedFiles(entries: List<LocalFileEntry>) {
        sessionController.addSelectedFiles(entries)
    }

    fun addSelectedDirectorySources(sources: List<TransferSourceItem>) {
        if (sources.isEmpty()) return
        val now = System.currentTimeMillis()
        val entries = sources.map { src ->
            LocalFileEntry(
                uriString = src.uriString,
                displayName = if (src.relativePath.isBlank()) src.fileName else "${src.relativePath}/${src.fileName}",
                mimeType = src.mimeType,
                sizeBytes = src.fileSizeBytes,
                lastModifiedEpochMillis = now,
                category = fileCategoryFromTransferType(src.fileType),
                isDirectory = false
            )
        }
        val relMap = sources.associate { it.uriString to it.relativePath }
        sessionController.addSelectedFiles(entries, relMap)
    }

    fun removeSelectedFile(uriString: String) {
        sessionController.removeSelectedFile(uriString)
    }

    fun clearSelection() {
        sessionController.clearSelectedFiles()
        browserState.update { it.copy(selectedAppPackages = emptySet()) }
    }

    /**
     * Stages any selected installed applications (base APK + all required split APKs + bundle
     * manifest with SHA-256 verification) into [LocalSessionController] before navigating to
     * the unified Send connection step.
     */
    fun prepareSelectionAndContinue(onReady: () -> Unit) {
        val selectedPackages = browserState.value.selectedAppPackages
        if (selectedPackages.isEmpty()) {
            if (sessionController.selectedSendFiles.value.isNotEmpty()) {
                onReady()
            }
            return
        }
        val repo = appSharingRepository
        if (repo == null) {
            onReady()
            return
        }
        val appsToExtract = browserState.value.installedApps.filter { it.packageName in selectedPackages }
        if (appsToExtract.isEmpty()) {
            onReady()
            return
        }

        viewModelScope.launch {
            browserState.update { it.copy(isExtractingApps = true) }
            try {
                when (val extracted = repo.extractApksForSharing(appsToExtract)) {
                    is AppResult.Success -> {
                        val now = System.currentTimeMillis()
                        val entries = extracted.data.map { artifact ->
                            LocalFileEntry(
                                uriString = artifact.uriString,
                                displayName = if (artifact.relativePath.isBlank()) {
                                    artifact.fileName
                                } else {
                                    "${artifact.relativePath}/${artifact.fileName}"
                                },
                                mimeType = if (artifact.isBundleManifest) {
                                    "application/json"
                                } else {
                                    "application/vnd.android.package-archive"
                                },
                                sizeBytes = artifact.sizeBytes,
                                lastModifiedEpochMillis = now,
                                category = if (artifact.isBundleManifest) FileCategory.OTHER else FileCategory.APKS,
                                isDirectory = false
                            )
                        }
                        val relMap = extracted.data.associate { it.uriString to it.relativePath }
                        sessionController.addSelectedFiles(entries, relMap)
                        browserState.update { it.copy(selectedAppPackages = emptySet()) }
                        onReady()
                    }
                    is AppResult.Failure -> {
                        feedbackState.value = extracted.error.message
                    }
                }
            } finally {
                browserState.update { it.copy(isExtractingApps = false) }
            }
        }
    }

    fun connectViaQrOrCode(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        lastSubmittedQrInput = trimmed
        viewModelScope.launch {
            scannerUiState.value = QrScannerUiState.LOADING
            val deviceName = runCatching { settingsRepository?.settings?.first()?.deviceName }
                .getOrNull() ?: "ShareFlow"
            val result = unifiedConnectionManager?.connectByQrOrCode(trimmed, deviceName)
                ?: connectionRepository?.connectViaQrPayload(trimmed, deviceName)
                ?: return@launch

            when (result) {
                is AppResult.Success -> {
                    scannerUiState.value = QrScannerUiState.SUCCESS
                }
                is AppResult.Failure -> {
                    val code = unifiedConnectionManager?.smartError?.value?.code
                    val msg = result.error.message
                    scannerUiState.value = when {
                        code == SmartConnectionErrorCode.STALE_OR_EXPIRED_QR ||
                            code == SmartConnectionErrorCode.SESSION_EXPIRED ||
                            msg.contains("expired", ignoreCase = true) -> QrScannerUiState.EXPIRED_QR
                        code == SmartConnectionErrorCode.INVALID_QR ||
                            code == SmartConnectionErrorCode.TAMPERED_QR ||
                            code == SmartConnectionErrorCode.REPLAYED_QR ||
                            code == SmartConnectionErrorCode.SELF_PAIRING ||
                            msg.contains("invalid", ignoreCase = true) ||
                            msg.contains("malformed", ignoreCase = true) ||
                            msg.contains("unsupported", ignoreCase = true) ||
                            msg.contains("qr", ignoreCase = true) -> QrScannerUiState.INVALID_QR
                        else -> QrScannerUiState.FAILURE
                    }
                    feedbackState.value = msg
                }
            }
        }
    }

    fun retrySmartConnect() {
        val last = lastSubmittedQrInput
        unifiedConnectionManager?.clearSmartError()
        if (!last.isNullOrBlank()) {
            connectViaQrOrCode(last)
        } else {
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun cancelSmartConnect() {
        viewModelScope.launch {
            unifiedConnectionManager?.cancelOrDisconnect(markCancelled = true)
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun clearSmartError() {
        unifiedConnectionManager?.clearSmartError()
        if (scannerUiState.value != QrScannerUiState.LOADING &&
            scannerUiState.value != QrScannerUiState.SUCCESS
        ) {
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun resetScannerState() {
        scannerUiState.value = QrScannerUiState.SCANNING
    }

    fun startPeerDiscovery() {
        viewModelScope.launch {
            val res = unifiedConnectionManager?.startNearbyDiscovery()
                ?: discoveryRepository?.startDiscovery()
                ?: return@launch
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun connectToPeer(peer: PeerDevice) {
        viewModelScope.launch {
            val deviceName = runCatching { settingsRepository?.settings?.first()?.deviceName }
                .getOrNull() ?: "ShareFlow"
            val res = unifiedConnectionManager?.connectToPeer(peer, deviceName)
                ?: connectionRepository?.connectToPeer(peer, deviceName)
                ?: return@launch
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun startEncryptedTransfer(onStarted: () -> Unit) {
        val selectedFiles = sessionController.selectedSendFiles.value
        if (selectedFiles.isEmpty()) {
            feedbackState.value = "Select at least one file to send"
            return
        }
        val relativePaths = sessionController.selectedRelativePaths.value
        val sources = selectedFiles.map { entry ->
            val relPath = relativePaths[entry.uriString].orEmpty()
            val cleanFileName = entry.displayName.substringAfterLast('/')
            TransferSourceItem(
                uriString = entry.uriString,
                fileName = cleanFileName,
                relativePath = relPath,
                mimeType = entry.mimeType,
                fileType = transferTypeFromFileCategory(entry.category),
                fileSizeBytes = entry.sizeBytes
            )
        }

        viewModelScope.launch {
            startingTransferState.value = true
            try {
                val result = unifiedConnectionManager?.sendSelectedItems(sources)
                    ?: transferManager?.startSendingOnActiveConnection(sources)
                    ?: return@launch
                when (result) {
                    is AppResult.Success -> {
                        sessionController.clearSelectedFiles()
                        onStarted()
                    }
                    is AppResult.Failure -> {
                        feedbackState.value = result.error.message
                    }
                }
            } finally {
                startingTransferState.value = false
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            if (unifiedConnectionManager != null) {
                unifiedConnectionManager.cancelOrDisconnect(markCancelled = false)
            } else {
                connectionRepository?.disconnect()
            }
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun showFeedback(message: String) {
        feedbackState.value = message
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }

    private fun transferTypeFromFileCategory(category: FileCategory): TransferFileType = when (category) {
        FileCategory.IMAGES -> TransferFileType.IMAGE
        FileCategory.VIDEOS -> TransferFileType.VIDEO
        FileCategory.AUDIO -> TransferFileType.AUDIO
        FileCategory.DOCUMENTS -> TransferFileType.DOCUMENT
        FileCategory.APKS -> TransferFileType.APK
        FileCategory.ARCHIVES -> TransferFileType.ARCHIVE
        FileCategory.DOWNLOADS, FileCategory.OTHER -> TransferFileType.OTHER
    }

    private fun fileCategoryFromTransferType(type: TransferFileType): FileCategory = when (type) {
        TransferFileType.IMAGE -> FileCategory.IMAGES
        TransferFileType.VIDEO -> FileCategory.VIDEOS
        TransferFileType.AUDIO -> FileCategory.AUDIO
        TransferFileType.DOCUMENT -> FileCategory.DOCUMENTS
        TransferFileType.APK -> FileCategory.APKS
        TransferFileType.ARCHIVE -> FileCategory.ARCHIVES
        TransferFileType.OTHER -> FileCategory.OTHER
    }
}
