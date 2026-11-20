package com.example.shareflow.domain.repository

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
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain repository contract for discovering nearby ShareFlow devices and advertising local availability.
 * Completely independent of Android UI components and emits only real discovered peers.
 */
interface DeviceDiscoveryRepository {
    val discoveredPeers: StateFlow<List<PeerDevice>>
    val discoveryStatus: StateFlow<DiscoveryStatus>
    val strategyStatuses: StateFlow<List<StrategyStatus>>
    val isAdvertising: StateFlow<Boolean>
    val activeHotspot: StateFlow<LocalHotspotInfo?>
    val discoverySnapshot: StateFlow<DiscoverySnapshot>

    fun getLocalIdentity(): LocalDeviceIdentity
    fun refreshCapabilities(): List<StrategyStatus>

    suspend fun startDiscovery(
        preferredStrategies: Set<ConnectionType> = setOf(
            ConnectionType.WIFI_DIRECT,
            ConnectionType.LOCAL_LAN_NSD
        ),
        timeoutMillis: Long = DEFAULT_DISCOVERY_TIMEOUT_MS
    ): AppResult<Unit>

    suspend fun stopDiscovery(): AppResult<Unit>

    suspend fun startAdvertising(
        deviceName: String,
        port: Int,
        enableLocalHotspot: Boolean = false
    ): AppResult<Int>

    suspend fun stopAdvertising(): AppResult<Unit>

    companion object {
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 30_000L
    }
}

/**
 * Domain repository contract for secure peer-to-peer pairing, mutual authentication,
 * explicit user approval, and encrypted connection session management.
 */
interface ConnectionRepository {
    val connectionState: StateFlow<ConnectionState>
    val pendingIncomingRequest: StateFlow<IncomingConnectionRequest?>
    val activePairingPayload: StateFlow<PairingPayload?>

    fun getLocalIdentity(): LocalDeviceIdentity

    suspend fun generateQrPairingSession(
        deviceName: String,
        port: Int,
        ttlMillis: Long = DEFAULT_PAIRING_TTL_MS
    ): AppResult<PairingPayload>

    fun encodePairingUri(payload: PairingPayload): String
    fun parsePairingUri(rawUri: String): AppResult<PairingPayload>

    suspend fun connectToPeer(
        peer: PeerDevice,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected>

    suspend fun connectViaQrPayload(
        rawQrUri: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected>

    suspend fun approveIncomingConnection(
        requestId: String,
        rememberAsTrusted: Boolean = false
    ): AppResult<Unit>

    suspend fun rejectIncomingConnection(requestId: String): AppResult<Unit>

    suspend fun disconnect(): AppResult<Unit>

    companion object {
        const val DEFAULT_PAIRING_TTL_MS = 120_000L
    }
}
