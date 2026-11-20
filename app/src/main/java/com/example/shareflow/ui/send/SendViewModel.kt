package com.example.shareflow.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.network.transfer.TransferManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SendUiState(
    val activeCategory: FileCategory? = null,
    val allSelectedFiles: List<LocalFileEntry> = emptyList(),
    val filteredFiles: List<LocalFileEntry> = emptyList(),
    val selectedCount: Int = 0,
    val totalSelectedBytes: Long = 0L,
    val trustedDevices: List<PeerDevice> = emptyList(),
    val discoveredDevices: List<PeerDevice> = emptyList(),
    val discoveryStatus: DiscoveryStatus = DiscoveryStatus.Idle,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val activeTransferCount: Int = 0,
    val isStartingTransfer: Boolean = false,
    val feedbackMessage: String? = null
)

class SendViewModel(
    private val sessionController: LocalSessionController,
    trustedDeviceRepository: TrustedDeviceRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val discoveryRepository: DeviceDiscoveryRepository? = null,
    private val connectionRepository: ConnectionRepository? = null,
    private val transferManager: TransferManager? = null
) : ViewModel() {

    private val activeCategoryState = MutableStateFlow<FileCategory?>(null)
    private val feedbackState = MutableStateFlow<String?>(null)
    private val startingTransferState = MutableStateFlow(false)

    private val selectionFlow = combine(
        activeCategoryState,
        sessionController.selectedSendFiles,
        trustedDeviceRepository.observeTrustedDevices()
    ) { category, files, trusted ->
        Triple(category, files, trusted)
    }

    private val networkFlow = combine(
        discoveryRepository?.discoveredPeers ?: sessionController.discoveredPeers,
        discoveryRepository?.discoveryStatus ?: MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle),
        connectionRepository?.connectionState ?: sessionController.connectionState,
        feedbackState
    ) { discovered, discStatus, connState, feedback ->
        SendNetworkSnapshot(discovered, discStatus, connState, feedback)
    }

    private val transferFlow = combine(
        transferManager?.activeSessions ?: MutableStateFlow(emptyList()),
        startingTransferState
    ) { active, starting ->
        active.size to starting
    }

    private data class SendNetworkSnapshot(
        val discovered: List<PeerDevice>,
        val discoveryStatus: DiscoveryStatus,
        val connectionState: ConnectionState,
        val feedback: String?
    )

    val uiState: StateFlow<SendUiState> = combine(
        selectionFlow,
        networkFlow,
        transferFlow
    ) { (category, files, trusted), net, (activeTransfers, starting) ->
        val filtered = if (category == null) {
            files
        } else {
            files.filter { it.category == category }
        }
        SendUiState(
            activeCategory = category,
            allSelectedFiles = files,
            filteredFiles = filtered,
            selectedCount = files.size,
            totalSelectedBytes = files.sumOf { it.sizeBytes },
            trustedDevices = trusted,
            discoveredDevices = net.discovered,
            discoveryStatus = net.discoveryStatus,
            connectionState = net.connectionState,
            activeTransferCount = activeTransfers,
            isStartingTransfer = starting,
            feedbackMessage = net.feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SendUiState()
    )

    fun selectCategory(category: FileCategory?) {
        activeCategoryState.value = category
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
    }

    fun startPeerDiscovery() {
        val repo = discoveryRepository ?: return
        viewModelScope.launch {
            val res = repo.startDiscovery()
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun connectToPeer(peer: PeerDevice) {
        val connRepo = connectionRepository ?: return
        viewModelScope.launch {
            val deviceName = runCatching { settingsRepository?.settings?.first()?.deviceName }
                .getOrNull() ?: "ShareFlow"
            val res = connRepo.connectToPeer(peer, deviceName)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun startEncryptedTransfer(onStarted: () -> Unit) {
        val manager = transferManager ?: return
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
                when (val result = manager.startSendingOnActiveConnection(sources)) {
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
        val connRepo = connectionRepository ?: return
        viewModelScope.launch {
            connRepo.disconnect()
        }
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
