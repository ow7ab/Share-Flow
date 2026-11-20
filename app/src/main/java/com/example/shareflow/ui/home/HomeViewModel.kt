package com.example.shareflow.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.domain.repository.TransferHistoryRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.DeviceNetworkStatus
import com.example.shareflow.network.LocalSessionController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(
        val settings: AppSettings,
        val totalTransferCount: Int,
        val trustedDeviceCount: Int,
        val recentTransfers: List<TransferRecord>,
        val nearbyDevices: List<PeerDevice>,
        val recentFiles: List<LocalFileEntry>,
        val storageSummary: StorageSummary?,
        val connectionState: ConnectionState,
        val networkStatus: DeviceNetworkStatus
    ) : HomeUiState
}

private data class HomePersistentSnapshot(
    val settings: AppSettings,
    val records: List<TransferRecord>,
    val trustedDevices: List<PeerDevice>,
    val storageResult: AppResult<StorageSummary>
)

private data class HomeLiveSessionSnapshot(
    val connectionState: ConnectionState,
    val networkStatus: DeviceNetworkStatus,
    val discoveredPeers: List<PeerDevice>,
    val recentFiles: List<LocalFileEntry>
)

class HomeViewModel(
    settingsRepository: SettingsRepository,
    transferHistoryRepository: TransferHistoryRepository,
    trustedDeviceRepository: TrustedDeviceRepository,
    storageAnalyzerRepository: StorageAnalyzerRepository,
    private val sessionController: LocalSessionController,
    discoveryRepository: com.example.shareflow.domain.repository.DeviceDiscoveryRepository? = null,
    connectionRepository: com.example.shareflow.domain.repository.ConnectionRepository? = null
) : ViewModel() {

    private val persistentFlow = combine(
        settingsRepository.settings,
        transferHistoryRepository.observeAllRecords(),
        trustedDeviceRepository.observeTrustedDevices(),
        storageAnalyzerRepository.observeStorageSummary()
    ) { settings, records, trustedDevices, storageResult ->
        HomePersistentSnapshot(
            settings = settings,
            records = records,
            trustedDevices = trustedDevices,
            storageResult = storageResult
        )
    }

    private val liveSessionFlow = combine(
        connectionRepository?.connectionState ?: sessionController.connectionState,
        sessionController.networkStatus,
        discoveryRepository?.discoveredPeers ?: sessionController.discoveredPeers,
        sessionController.recentFiles
    ) { connectionState, networkStatus, discoveredPeers, recentFiles ->
        HomeLiveSessionSnapshot(
            connectionState = connectionState,
            networkStatus = networkStatus,
            discoveredPeers = discoveredPeers,
            recentFiles = recentFiles
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        persistentFlow,
        liveSessionFlow
    ) { persistent, live ->
        val summary = (persistent.storageResult as? AppResult.Success)?.data
        HomeUiState.Ready(
            settings = persistent.settings,
            totalTransferCount = persistent.records.size,
            trustedDeviceCount = persistent.trustedDevices.size,
            recentTransfers = persistent.records.take(3),
            nearbyDevices = live.discoveredPeers,
            recentFiles = live.recentFiles.take(3),
            storageSummary = summary,
            connectionState = live.connectionState,
            networkStatus = live.networkStatus
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = HomeUiState.Loading
    )

    fun selectFileForSend(file: LocalFileEntry) {
        sessionController.addSelectedFiles(listOf(file))
    }
}
