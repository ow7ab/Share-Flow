package com.example.shareflow.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DiscoveryUiState(
    val settings: AppSettings = AppSettings(),
    val localIdentity: LocalDeviceIdentity? = null,
    val discoverySnapshot: DiscoverySnapshot = DiscoverySnapshot(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val pendingIncomingRequest: IncomingConnectionRequest? = null,
    val enableHotspotWhenAdvertising: Boolean = false,
    val feedbackMessage: String? = null
)

class DiscoveryViewModel(
    private val settingsRepository: SettingsRepository,
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    private val localIdentity: LocalDeviceIdentity = discoveryRepository.getLocalIdentity()
    private val hotspotPreference = MutableStateFlow(false)
    private val feedbackState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DiscoveryUiState> = combine(
        settingsRepository.settings,
        discoveryRepository.discoverySnapshot,
        connectionRepository.connectionState,
        connectionRepository.pendingIncomingRequest,
        hotspotPreference,
        feedbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        DiscoveryUiState(
            settings = values[0] as AppSettings,
            localIdentity = localIdentity,
            discoverySnapshot = values[1] as DiscoverySnapshot,
            connectionState = values[2] as ConnectionState,
            pendingIncomingRequest = values[3] as IncomingConnectionRequest?,
            enableHotspotWhenAdvertising = values[4] as Boolean,
            feedbackMessage = values[5] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = DiscoveryUiState(localIdentity = localIdentity)
    )

    fun refreshCapabilities() {
        discoveryRepository.refreshCapabilities()
    }

    fun setEnableHotspotWhenAdvertising(enabled: Boolean) {
        hotspotPreference.value = enabled
    }

    fun startDiscovery(
        preferredStrategies: Set<ConnectionType> = setOf(
            ConnectionType.WIFI_DIRECT,
            ConnectionType.LOCAL_LAN_NSD
        ),
        timeoutMillis: Long = DeviceDiscoveryRepository.DEFAULT_DISCOVERY_TIMEOUT_MS
    ) {
        viewModelScope.launch {
            val result = discoveryRepository.startDiscovery(preferredStrategies, timeoutMillis)
            if (result is AppResult.Failure) {
                feedbackState.value = result.error.message
            }
        }
    }

    fun stopDiscovery() {
        viewModelScope.launch {
            discoveryRepository.stopDiscovery()
        }
    }

    fun toggleAdvertising() {
        val current = uiState.value
        viewModelScope.launch {
            if (current.discoverySnapshot.isAdvertising) {
                discoveryRepository.stopAdvertising()
            } else {
                val res = discoveryRepository.startAdvertising(
                    deviceName = current.settings.deviceName,
                    port = current.settings.transferPort,
                    enableLocalHotspot = current.enableHotspotWhenAdvertising
                )
                if (res is AppResult.Failure) {
                    feedbackState.value = res.error.message
                }
            }
        }
    }

    fun connectToPeer(peer: PeerDevice) {
        val deviceName = uiState.value.settings.deviceName
        viewModelScope.launch {
            val res = connectionRepository.connectToPeer(peer, deviceName)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun connectViaQrUri(rawUri: String) {
        val deviceName = uiState.value.settings.deviceName
        viewModelScope.launch {
            val res = connectionRepository.connectViaQrPayload(rawUri, deviceName)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun approveIncoming(requestId: String, rememberAsTrusted: Boolean) {
        viewModelScope.launch {
            val res = connectionRepository.approveIncomingConnection(requestId, rememberAsTrusted)
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun rejectIncoming(requestId: String) {
        viewModelScope.launch {
            connectionRepository.rejectIncomingConnection(requestId)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
        }
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }
}
