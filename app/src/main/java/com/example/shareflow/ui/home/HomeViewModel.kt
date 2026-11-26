package com.example.shareflow.ui.home

import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
sealed interface HomeUiState {
    data object Loading : HomeUiState
    @Immutable
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
    val recentRecords: List<TransferRecord>,
    val totalTransferCount: Int,
    val trustedDeviceCount: Int,
    val storageResult: AppResult<StorageSummary>?
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

    private val initialSettings: AppSettings = settingsRepository.peekInitialSettings()

    init {
        // Query real Wi-Fi/IPv4 status off the Main Thread right after HomeViewModel starts
        viewModelScope.launch {
            sessionController.refreshNetworkStatusAsync()
        }
    }

    private val persistentFlow = combine(
        settingsRepository.settings.onStart { emit(initialSettings) },
        transferHistoryRepository.observeRecentRecords(RECENT_TRANSFER_LIMIT).onStart { emit(emptyList()) },
        transferHistoryRepository.observeTotalCount().onStart { emit(0) },
        trustedDeviceRepository.observeTrustedCount().onStart { emit(0) },
        storageAnalyzerRepository.observeStorageSummary()
            .onStart<AppResult<StorageSummary>?> { emit(null) }
    ) { settings, recentRecords, totalTransferCount, trustedDeviceCount, storageResult ->
        HomePersistentSnapshot(
            settings = settings,
            recentRecords = recentRecords,
            totalTransferCount = totalTransferCount,
            trustedDeviceCount = trustedDeviceCount,
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

    private val initialReadyState = HomeUiState.Ready(
        settings = initialSettings,
        totalTransferCount = 0,
        trustedDeviceCount = 0,
        recentTransfers = emptyList(),
        nearbyDevices = emptyList(),
        recentFiles = sessionController.recentFiles.value.take(3),
        storageSummary = null,
        connectionState = connectionRepository?.connectionState?.value ?: sessionController.connectionState.value,
        networkStatus = sessionController.networkStatus.value
    )

    val uiState: StateFlow<HomeUiState> = combine(
        persistentFlow,
        liveSessionFlow
    ) { persistent, live ->
        val summary = (persistent.storageResult as? AppResult.Success)?.data
        HomeUiState.Ready(
            settings = persistent.settings,
            totalTransferCount = persistent.totalTransferCount,
            trustedDeviceCount = persistent.trustedDeviceCount,
            recentTransfers = persistent.recentRecords,
            nearbyDevices = live.discoveredPeers,
            recentFiles = live.recentFiles.take(3),
            storageSummary = summary,
            connectionState = live.connectionState,
            networkStatus = live.networkStatus
        )
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = initialReadyState
        )

    fun selectFileForSend(file: LocalFileEntry) {
        sessionController.addSelectedFiles(listOf(file))
    }

    companion object {
        internal const val RECENT_TRANSFER_LIMIT = 3
    }
}
