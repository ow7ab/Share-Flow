package com.example.shareflow.data.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.LocalHotspotInfo
import com.example.shareflow.domain.model.PairingPayload
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyStatus
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.network.coordinator.ConnectionCoordinator
import kotlinx.coroutines.flow.StateFlow

class DeviceDiscoveryRepositoryImpl(
    private val coordinator: ConnectionCoordinator
) : DeviceDiscoveryRepository {

    override val discoveredPeers: StateFlow<List<PeerDevice>>
        get() = coordinator.discoveredPeers

    override val discoveryStatus: StateFlow<DiscoveryStatus>
        get() = coordinator.discoveryStatus

    override val strategyStatuses: StateFlow<List<StrategyStatus>>
        get() = coordinator.strategyStatuses

    override val isAdvertising: StateFlow<Boolean>
        get() = coordinator.isAdvertising

    override val activeHotspot: StateFlow<LocalHotspotInfo?>
        get() = coordinator.activeHotspot

    override val discoverySnapshot: StateFlow<DiscoverySnapshot>
        get() = coordinator.discoverySnapshot

    override fun getLocalIdentity(): LocalDeviceIdentity = coordinator.getLocalIdentity()

    override fun refreshCapabilities(): List<StrategyStatus> = coordinator.refreshCapabilities()

    override suspend fun startDiscovery(
        preferredStrategies: Set<ConnectionType>,
        timeoutMillis: Long
    ): AppResult<Unit> = coordinator.startDiscovery(preferredStrategies, timeoutMillis)

    override suspend fun stopDiscovery(): AppResult<Unit> = coordinator.stopDiscovery()

    override suspend fun startAdvertising(
        deviceName: String,
        port: Int,
        enableLocalHotspot: Boolean
    ): AppResult<Int> = coordinator.startAdvertising(deviceName, port, enableLocalHotspot)

    override suspend fun stopAdvertising(): AppResult<Unit> = coordinator.stopAdvertising()
}

class ConnectionRepositoryImpl(
    private val coordinator: ConnectionCoordinator
) : ConnectionRepository {

    override val connectionState: StateFlow<ConnectionState>
        get() = coordinator.connectionState

    override val pendingIncomingRequest: StateFlow<IncomingConnectionRequest?>
        get() = coordinator.pendingIncomingRequest

    override val activePairingPayload: StateFlow<PairingPayload?>
        get() = coordinator.activePairingPayload

    override fun getLocalIdentity(): LocalDeviceIdentity = coordinator.getLocalIdentity()

    override suspend fun generateQrPairingSession(
        deviceName: String,
        port: Int,
        ttlMillis: Long
    ): AppResult<PairingPayload> = coordinator.generateQrPairingSession(deviceName, port, ttlMillis)

    override fun encodePairingUri(payload: PairingPayload): String =
        coordinator.encodePairingUri(payload)

    override fun parsePairingUri(rawUri: String): AppResult<PairingPayload> =
        coordinator.parsePairingUri(rawUri)

    override suspend fun connectToPeer(
        peer: PeerDevice,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> = coordinator.connectToPeer(peer, localDeviceName)

    override suspend fun connectViaQrPayload(
        rawQrUri: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> = coordinator.connectViaQrPayload(rawQrUri, localDeviceName)

    override suspend fun approveIncomingConnection(
        requestId: String,
        rememberAsTrusted: Boolean
    ): AppResult<Unit> = coordinator.approveIncomingConnection(requestId, rememberAsTrusted)

    override suspend fun rejectIncomingConnection(requestId: String): AppResult<Unit> =
        coordinator.rejectIncomingConnection(requestId)

    override suspend fun disconnect(): AppResult<Unit> = coordinator.disconnect()
}
