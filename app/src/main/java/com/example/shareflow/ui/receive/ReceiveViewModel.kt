package com.example.shareflow.ui.receive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PairingPayload
import com.example.shareflow.domain.model.QrScannerUiState
import com.example.shareflow.domain.model.SmartConnectionError
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.network.DeviceNetworkStatus
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.network.coordinator.SmartConnectionCoordinator
import com.example.shareflow.network.qr.SmartQrPairingProtocol
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
    val enableLocalHotspot: Boolean = true,
    val smartPhase: SmartConnectionPhase = SmartConnectionPhase.IDLE,
    val smartStrategy: SmartStrategyType? = null,
    val smartQrPayload: SmartQrPairingPayload? = null,
    val smartQrUri: String = "",
    val smartManualCode: String = "",
    val smartError: SmartConnectionError? = null,
    val scannerState: QrScannerUiState = QrScannerUiState.SCANNING,
    val feedbackMessage: String? = null
) {
    val isReceiving: Boolean
        get() = smartPhase.isReceiverActive ||
            discoverySnapshot.isAdvertising ||
            connectionState is ConnectionState.Advertising

    val isPreparingReceiver: Boolean
        get() = smartPhase == SmartConnectionPhase.STARTING_HOTSPOT ||
            smartPhase == SmartConnectionPhase.HOTSPOT_READY ||
            smartPhase == SmartConnectionPhase.STARTING_SERVER

    /**
     * Strict Phase 2/7 invariant: Smart Hotspot QR is shown ONLY after hotspot + TCP server
     * are verified active and listening.
     */
    val isSmartQrReady: Boolean
        get() = smartQrPayload != null &&
            smartQrUri.isNotBlank() &&
            (smartPhase == SmartConnectionPhase.SERVER_READY ||
                smartPhase == SmartConnectionPhase.WAITING_FOR_QR)
}

class ReceiveViewModel(
    private val settingsRepository: SettingsRepository,
    private val sessionController: LocalSessionController,
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val connectionRepository: ConnectionRepository,
    private val smartCoordinator: SmartConnectionCoordinator
) : ViewModel() {

    private val localIdentity: LocalDeviceIdentity = discoveryRepository.getLocalIdentity()
    private val hotspotEnabledPref = MutableStateFlow(true)
    private val feedbackState = MutableStateFlow<String?>(null)
    private val scannerUiState = MutableStateFlow(QrScannerUiState.SCANNING)
    private var lastSubmittedQrInput: String? = null

    private data class BaseReceiveBundle(
        val settings: AppSettings,
        val connState: ConnectionState,
        val discovery: DiscoverySnapshot,
        val netStatus: DeviceNetworkStatus,
        val pairingPayload: PairingPayload?,
        val hotspotPref: Boolean,
        val feedback: String?
    )

    private data class SmartReceiveBundle(
        val phase: SmartConnectionPhase,
        val strategy: SmartStrategyType?,
        val qrPayload: SmartQrPairingPayload?,
        val qrUri: String,
        val manualCode: String,
        val error: SmartConnectionError?,
        val scannerState: QrScannerUiState
    )

    private val baseFlow = combine(
        settingsRepository.settings,
        connectionRepository.connectionState,
        discoveryRepository.discoverySnapshot,
        sessionController.networkStatus,
        connectionRepository.activePairingPayload,
        hotspotEnabledPref,
        feedbackState
    ) { values ->
        BaseReceiveBundle(
            settings = values[0] as AppSettings,
            connState = values[1] as ConnectionState,
            discovery = values[2] as DiscoverySnapshot,
            netStatus = values[3] as DeviceNetworkStatus,
            pairingPayload = values[4] as PairingPayload?,
            hotspotPref = values[5] as Boolean,
            feedback = values[6] as String?
        )
    }

    private val smartFlow = combine(
        smartCoordinator.phase,
        smartCoordinator.activeStrategy,
        smartCoordinator.activeQrPayload,
        smartCoordinator.activeQrUri,
        smartCoordinator.activeManualCode,
        smartCoordinator.lastError,
        scannerUiState
    ) { values ->
        SmartReceiveBundle(
            phase = values[0] as SmartConnectionPhase,
            strategy = values[1] as SmartStrategyType?,
            qrPayload = values[2] as SmartQrPairingPayload?,
            qrUri = values[3] as String,
            manualCode = values[4] as String,
            error = values[5] as SmartConnectionError?,
            scannerState = values[6] as QrScannerUiState
        )
    }

    val uiState: StateFlow<ReceiveUiState> = combine(baseFlow, smartFlow) { base, smart ->
        val legacyUri = base.pairingPayload?.let { connectionRepository.encodePairingUri(it) } ?: ""
        ReceiveUiState(
            settings = base.settings,
            localIdentity = localIdentity,
            connectionState = base.connState,
            discoverySnapshot = base.discovery,
            networkStatus = base.netStatus,
            pairingPayload = base.pairingPayload,
            pairingUri = legacyUri,
            enableLocalHotspot = base.hotspotPref,
            smartPhase = smart.phase,
            smartStrategy = smart.strategy,
            smartQrPayload = smart.qrPayload,
            smartQrUri = smart.qrUri,
            smartManualCode = smart.manualCode,
            smartError = smart.error,
            scannerState = smart.scannerState,
            feedbackMessage = base.feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ReceiveUiState(localIdentity = localIdentity)
    )

    init {
        viewModelScope.launch {
            if (connectionRepository.activePairingPayload.value == null) {
                regenerateLegacyPairingSession()
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
                smartCoordinator.cancelOrDisconnect(markCancelled = true)
                discoveryRepository.stopAdvertising()
            } else {
                val res = smartCoordinator.startSmartReceiving(
                    deviceName = current.settings.deviceName,
                    preferredPort = current.settings.transferPort,
                    preferHotspot = current.enableLocalHotspot
                )
                if (res is AppResult.Failure) {
                    feedbackState.value = res.error.message
                }
            }
        }
    }

    fun regeneratePairingSession() {
        val current = uiState.value
        viewModelScope.launch {
            sessionController.refreshNetworkStatus()
            if (current.isReceiving || current.smartPhase == SmartConnectionPhase.EXPIRED) {
                val smartRes = smartCoordinator.regenerateActiveReceiverQr()
                if (smartRes is AppResult.Failure) {
                    // Server may have stopped after expiry; restart cleanly
                    val restartRes = smartCoordinator.startSmartReceiving(
                        deviceName = current.settings.deviceName,
                        preferredPort = current.settings.transferPort,
                        preferHotspot = current.enableLocalHotspot
                    )
                    if (restartRes is AppResult.Failure) {
                        feedbackState.value = restartRes.error.message
                    }
                }
            } else {
                regenerateLegacyPairingSession()
            }
        }
    }

    private suspend fun regenerateLegacyPairingSession() {
        val current = uiState.value
        val port = current.discoverySnapshot.advertisedPort ?: current.settings.transferPort
        val res = connectionRepository.generateQrPairingSession(
            deviceName = current.settings.deviceName,
            port = port
        )
        if (res is AppResult.Failure) {
            feedbackState.value = res.error.message
        }
    }

    fun connectViaQrUri(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return
        lastSubmittedQrInput = trimmed
        val deviceName = uiState.value.settings.deviceName
        viewModelScope.launch {
            scannerUiState.value = QrScannerUiState.LOADING
            if (SmartQrPairingProtocol.isSmartV3Payload(trimmed)) {
                val res = smartCoordinator.connectViaSmartQr(
                    rawQrOrManualCode = trimmed,
                    localDeviceName = deviceName
                )
                when (res) {
                    is AppResult.Success -> {
                        scannerUiState.value = QrScannerUiState.SUCCESS
                    }
                    is AppResult.Failure -> {
                        val code = smartCoordinator.lastError.value?.code
                        scannerUiState.value = when (code) {
                            SmartConnectionErrorCode.STALE_OR_EXPIRED_QR,
                            SmartConnectionErrorCode.SESSION_EXPIRED -> QrScannerUiState.EXPIRED_QR
                            SmartConnectionErrorCode.INVALID_QR,
                            SmartConnectionErrorCode.TAMPERED_QR,
                            SmartConnectionErrorCode.REPLAYED_QR,
                            SmartConnectionErrorCode.SELF_PAIRING -> QrScannerUiState.INVALID_QR
                            else -> QrScannerUiState.FAILURE
                        }
                        feedbackState.value = res.error.message
                    }
                }
            } else {
                val res = connectionRepository.connectViaQrPayload(
                    rawQrUri = trimmed,
                    localDeviceName = deviceName
                )
                when (res) {
                    is AppResult.Success -> {
                        scannerUiState.value = QrScannerUiState.SUCCESS
                    }
                    is AppResult.Failure -> {
                        val msg = res.error.message
                        scannerUiState.value = when {
                            msg.contains("expired", ignoreCase = true) -> QrScannerUiState.EXPIRED_QR
                            msg.contains("invalid", ignoreCase = true) ||
                                msg.contains("malformed", ignoreCase = true) -> QrScannerUiState.INVALID_QR
                            else -> QrScannerUiState.FAILURE
                        }
                        feedbackState.value = msg
                    }
                }
            }
        }
    }

    fun retrySmartConnection() {
        val current = uiState.value
        val lastInput = lastSubmittedQrInput
        viewModelScope.launch {
            smartCoordinator.clearError()
            if (current.isReceiving || current.smartPhase == SmartConnectionPhase.EXPIRED || lastInput.isNullOrBlank()) {
                smartCoordinator.startSmartReceiving(
                    deviceName = current.settings.deviceName,
                    preferredPort = current.settings.transferPort,
                    preferHotspot = current.enableLocalHotspot
                )
            } else {
                connectViaQrUri(lastInput)
            }
        }
    }

    fun cancelSmartConnection() {
        viewModelScope.launch {
            smartCoordinator.cancelOrDisconnect(markCancelled = true)
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun clearSmartError() {
        smartCoordinator.clearError()
        if (scannerUiState.value != QrScannerUiState.LOADING &&
            scannerUiState.value != QrScannerUiState.SUCCESS
        ) {
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun resetScannerState() {
        scannerUiState.value = QrScannerUiState.SCANNING
    }

    fun showFeedback(message: String) {
        feedbackState.value = message
    }

    fun disconnect() {
        viewModelScope.launch {
            smartCoordinator.cancelOrDisconnect(markCancelled = false)
            connectionRepository.disconnect()
            scannerUiState.value = QrScannerUiState.SCANNING
        }
    }

    fun refreshNetworkStatus() {
        sessionController.refreshNetworkStatus()
        discoveryRepository.refreshCapabilities()
        smartCoordinator.refreshDiagnosticsSnapshot()
    }

    fun consumeFeedback() {
        feedbackState.value = null
    }
}
