package com.example.shareflow.network.coordinator

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.IncomingTransferOffer
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.SmartConnectionDiagnostics
import com.example.shareflow.domain.model.SmartConnectionError
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferSourceItem
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.transfer.TransferManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 2 — Unified SHAREit-style Connection & Transfer Manager.
 *
 * Centralizes all wireless connection strategies, QR pairing, discovery, authentication,
 * and transfer execution under one cohesive facade consumed directly by the main Send
 * and Receive workflows — eliminating duplicate or disconnected connection engines.
 *
 * Strategy Priority (delegated cleanly to [SmartConnectionCoordinator] + [ConnectionCoordinator]):
 *   1. LocalOnlyHotspotStrategy (Receiver temporary AP)
 *   2. WiFiNetworkSpecifierStrategy (Sender auto-join + network-bound sockets)
 *   3. WiFiDirectStrategy (P2P fallback)
 *   4. ExistingLocalWiFiStrategy (Shared Wi-Fi LAN fallback)
 */
class UnifiedConnectionManager(
    private val smartCoordinator: SmartConnectionCoordinator,
    private val connectionRepository: ConnectionRepository,
    private val discoveryRepository: DeviceDiscoveryRepository,
    private val transferManager: TransferManager,
    val permissionManager: PermissionManager
) {
    private val sendInFlight = AtomicBoolean(false)

    // Reactive unified state streams (zero duplicated state stores)
    val smartPhase: StateFlow<SmartConnectionPhase> = smartCoordinator.phase
    val activeStrategy: StateFlow<SmartStrategyType?> = smartCoordinator.activeStrategy
    val activeQrPayload: StateFlow<SmartQrPairingPayload?> = smartCoordinator.activeQrPayload
    val activeQrUri: StateFlow<String> = smartCoordinator.activeQrUri
    val activeManualCode: StateFlow<String> = smartCoordinator.activeManualCode
    val smartError: StateFlow<SmartConnectionError?> = smartCoordinator.lastError
    val diagnostics: StateFlow<SmartConnectionDiagnostics> = smartCoordinator.diagnostics

    val connectionState: StateFlow<ConnectionState> = connectionRepository.connectionState
    val discoverySnapshot: StateFlow<DiscoverySnapshot> = discoveryRepository.discoverySnapshot
    val discoveredPeers: StateFlow<List<PeerDevice>> = discoveryRepository.discoveredPeers
    val activeTransferSessions: StateFlow<List<TransferSession>> = transferManager.activeSessions
    val pendingIncomingOffer: StateFlow<IncomingTransferOffer?> = transferManager.pendingIncomingOffer

    /**
     * Receiver entry point: starts LocalOnlyHotspot (or automatic fallback), binds and verifies
     * the TCP server, starts NSD + authenticated UDP advertising, and generates a signed v3 QR
     * strictly after the server is confirmed ready.
     */
    suspend fun startReceiveSession(
        deviceName: String,
        preferredPort: Int,
        preferHotspot: Boolean = true
    ): AppResult<SmartQrPairingPayload> = smartCoordinator.startSmartReceiving(
        deviceName = deviceName,
        preferredPort = preferredPort,
        preferHotspot = preferHotspot
    )

    suspend fun regenerateReceiveQr(): AppResult<SmartQrPairingPayload> =
        smartCoordinator.regenerateActiveReceiverQr()

    /**
     * Sender QR / manual code entry point: transparently handles both Smart Hotspot v3 signed
     * QR/manual payloads (`SF3-...` / `shareflow://pair?v=3...`) and legacy LAN v2 QR payloads.
     */
    suspend fun connectByQrOrCode(
        rawInput: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return AppResult.Failure(AppError.Validation("QR code or manual pairing code is empty."))
        }
        return if (SmartQrPairingProtocol.isSmartV3Payload(trimmed)) {
            smartCoordinator.connectViaSmartQr(
                rawQrOrManualCode = trimmed,
                localDeviceName = localDeviceName
            )
        } else {
            connectionRepository.connectViaQrPayload(
                rawQrUri = trimmed,
                localDeviceName = localDeviceName
            )
        }
    }

    /**
     * Starts nearby LAN / Wi-Fi Direct discovery for the sender workflow.
     */
    suspend fun startNearbyDiscovery(): AppResult<Unit> = discoveryRepository.startDiscovery()

    suspend fun stopNearbyDiscovery() {
        discoveryRepository.stopDiscovery()
    }

    /**
     * Connects to a discovered or trusted nearby peer with full mutual authentication.
     */
    suspend fun connectToPeer(
        peer: PeerDevice,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> = connectionRepository.connectToPeer(peer, localDeviceName)

    /**
     * Sends selected content over the currently authenticated encrypted peer channel.
     * Protected by an atomic duplicate-tap guard so rapid button taps never launch duplicate sessions.
     */
    suspend fun sendSelectedItems(
        sources: List<TransferSourceItem>,
        config: TransferConfig = TransferConfig()
    ): AppResult<TransferSession> {
        if (sources.isEmpty()) {
            return AppResult.Failure(AppError.Validation("Select at least one item to send."))
        }
        if (!sendInFlight.compareAndSet(false, true)) {
            return AppResult.Failure(AppError.Validation("A transfer is already starting."))
        }
        return try {
            transferManager.startSendingOnActiveConnection(
                sources = sources,
                config = config
            )
        } finally {
            sendInFlight.set(false)
        }
    }

    fun approveIncomingTransferOffer(sessionId: String): AppResult<Unit> =
        transferManager.acceptIncomingOffer(sessionId)

    fun rejectIncomingTransferOffer(sessionId: String): AppResult<Unit> =
        transferManager.rejectIncomingOffer(sessionId)

    suspend fun cancelOrDisconnect(markCancelled: Boolean = false) {
        smartCoordinator.cancelOrDisconnect(markCancelled = markCancelled)
        discoveryRepository.stopAdvertising()
        connectionRepository.disconnect()
    }

    fun clearSmartError() {
        smartCoordinator.clearError()
    }

    fun refreshCapabilitiesAndDiagnostics() {
        discoveryRepository.refreshCapabilities()
        smartCoordinator.refreshDiagnosticsSnapshot()
    }
}
