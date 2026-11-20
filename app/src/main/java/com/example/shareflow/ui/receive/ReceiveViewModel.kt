package com.example.shareflow.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PairingPayload
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.network.DeviceNetworkStatus
import com.example.shareflow.network.LocalSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReceiveUiState(
    val settings: AppSettings = AppSettings(),
    val localIdentity: LocalDeviceIdentity? = null,
    val connectionState: ConnectionState = ConnectionState.Idle,
    val discoverySnapshot: DiscoverySnapshot = DiscoverySnapshot(),
    val networkStatus: DeviceNetworkStatus = DeviceNetworkStatus(false, null),
    val pairingPayload: PairingPayload? = null,
    val pairingUri: String = "",
    val enableLocalHotspot: Boolean = false,
    val feedbackMessage: String? = null
) {
    val isReceiving: Boolean
        get() = discoverySnapshot.isAdvertising || connectionState is ConnectionState.Advertising
}

class ReceiveViewModel(
    private val settingsRepository: SettingsRepository,
    private val sessionController: LocalSessionController,
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val connectionRepository: ConnectionRepository
) : ViewModel() {

    private val localIdentity: LocalDeviceIdentity = discoveryRepository.getLocalIdentity()
    private val hotspotEnabledPref = MutableStateFlow(false)
    private val feedbackState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ReceiveUiState> = combine(
        settingsRepository.settings,
        connectionRepository.connectionState,
        discoveryRepository.discoverySnapshot,
        sessionController.networkStatus,
        connectionRepository.activePairingPayload,
        hotspotEnabledPref,
        feedbackState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val settings = values[0] as AppSettings
        val connState = values[1] as ConnectionState
        val discovery = values[2] as DiscoverySnapshot
        val netStatus = values[3] as DeviceNetworkStatus
        val payload = values[4] as PairingPayload?
        val hotspotPref = values[5] as Boolean
        val feedback = values[6] as String?

        val uri = payload?.let { connectionRepository.encodePairingUri(it) } ?: ""

        ReceiveUiState(
            settings = settings,
            localIdentity = localIdentity,
            connectionState = connState,
            discoverySnapshot = discovery,
            networkStatus = netStatus,
            pairingPayload = payload,
            pairingUri = uri,
            enableLocalHotspot = hotspotPref,
            feedbackMessage = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReceiveUiState(localIdentity = localIdentity)
    )

    init {
        viewModelScope.launch {
            if (connectionRepository.activePairingPayload.value == null) {
                regeneratePairingSession()
            }
        }
    }

    fun setEnableLocalHotspot(enabled: Boolean) {
        hotspotEnabledPref.value = enabled
    }

    fun toggleReceiving() {
        val current = uiState.value
        viewModelScope.launch {
            sessionController.refreshNetworkStatus()
            if (current.isReceiving) {
                discoveryRepository.stopAdvertising()
            } else {
                val res = discoveryRepository.startAdvertising(
                    deviceName = current.settings.deviceName,
                    port = current.settings.transferPort,
                    enableLocalHotspot = current.enableLocalHotspot
                )
                when (res) {
                    is AppResult.Success -> {
                        connectionRepository.generateQrPairingSession(
                            deviceName = current.settings.deviceName,
                            port = res.data
                        )
                    }

                    is AppResult.Failure -> {
                        feedbackState.value = res.error.message
                    }
                }
            }
        }
    }

    fun regeneratePairingSession() {
        val current = uiState.value
        viewModelScope.launch {
            sessionController.refreshNetworkStatus()
            val port = current.discoverySnapshot.advertisedPort ?: current.settings.transferPort
            val res = connectionRepository.generateQrPairingSession(
                deviceName = current.settings.deviceName,
                port = port
            )
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun connectViaQrUri(rawUri: String) {
        val deviceName = uiState.value.settings.deviceName
        viewModelScope.launch {
            val res = connectionRepository.connectViaQrPayload(
                rawQrUri = rawUri,
                localDeviceName = deviceName
            )
            if (res is AppResult.Failure) {
                feedbackState.value = res.error.message
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            connectionRepository.disconnect()
        }
    }

    fun refreshNetworkStatus() {
        sessionController.refreshNetworkStatus()
        discoveryRepository.refreshCapabilities()
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }
}
