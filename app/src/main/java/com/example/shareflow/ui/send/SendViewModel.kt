package com.example.shareflow.ui.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.LocalSessionController
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
    val feedbackMessage: String? = null
)

class SendViewModel(
    private val sessionController: LocalSessionController,
    trustedDeviceRepository: TrustedDeviceRepository,
    private val settingsRepository: SettingsRepository? = null,
    private val discoveryRepository: DeviceDiscoveryRepository? = null,
    private val connectionRepository: ConnectionRepository? = null
) : ViewModel() {

    private val activeCategoryState = MutableStateFlow<FileCategory?>(null)
    private val feedbackState = MutableStateFlow<String?>(null)

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

    private data class SendNetworkSnapshot(
        val discovered: List<PeerDevice>,
        val discoveryStatus: DiscoveryStatus,
        val connectionState: ConnectionState,
        val feedback: String?
    )

    val uiState: StateFlow<SendUiState> = combine(
        selectionFlow,
        networkFlow
    ) { (category, files, trusted), net ->
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

    fun disconnect() {
        val connRepo = connectionRepository ?: return
        viewModelScope.launch {
            connRepo.disconnect()
        }
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }
}
