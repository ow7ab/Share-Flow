package com.example.shareflow.network.strategy

import android.net.Network
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PeerConnectionStatus
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import com.example.shareflow.network.TransferProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * Resolved TCP endpoint ready for ShareFlow's mutually authenticated encrypted handshake.
 */
data class ResolvedEndpoint(
    val hostAddress: String,
    val port: Int,
    val connectionType: ConnectionType,
    val boundNetwork: Network? = null
)

/**
 * Independent transport strategy interface implemented by Wi-Fi Direct, Local Wi-Fi (NSD),
 * and Local-Only Hotspot engines. Completely decoupled from UI components.
 */
interface ConnectionStrategy {
    val connectionType: ConnectionType
    val status: StateFlow<StrategyStatus>
    val discoveredPeers: StateFlow<List<PeerDevice>>

    fun checkAvailability(): StrategyAvailability

    suspend fun startDiscovery(localIdentity: LocalDeviceIdentity): AppResult<Unit>
    suspend fun stopDiscovery(): AppResult<Unit>

    suspend fun startAdvertising(
        localIdentity: LocalDeviceIdentity,
        deviceName: String,
        port: Int
    ): AppResult<Unit>

    suspend fun stopAdvertising(): AppResult<Unit>

    /**
     * Establishes or resolves the underlying transport link (e.g., Wi-Fi Direct group,
     * LAN IP endpoint, or Local Hotspot Wi-Fi association) and returns the TCP socket endpoint.
     */
    suspend fun prepareEndpointForPeer(
        peer: PeerDevice,
        passphrase: String? = null
    ): AppResult<ResolvedEndpoint>

    suspend fun releaseTransport(): AppResult<Unit>
}

/**
 * Thread-safe registry that merges discovered peers across all active strategies,
 * filters unsupported/self devices, prevents duplicate device entries, and expires stale peers.
 */
class PeerDiscoveryRegistry(
    private val localDeviceIdProvider: () -> String,
    private val clock: Clock = SystemClock(),
    private val peerStaleMillis: Long = DEFAULT_PEER_STALE_MS
) {
    private val lock = Any()
    private val peersByStrategy = LinkedHashMap<ConnectionType, List<PeerDevice>>()
    private val connectionStatusOverrides = LinkedHashMap<String, PeerConnectionStatus>()

    fun updateStrategyPeers(type: ConnectionType, rawPeers: List<PeerDevice>): List<PeerDevice> {
        synchronized(lock) {
            peersByStrategy[type] = rawPeers
            return buildMergedLocked()
        }
    }

    fun clearStrategy(type: ConnectionType): List<PeerDevice> {
        synchronized(lock) {
            peersByStrategy.remove(type)
            return buildMergedLocked()
        }
    }

    fun setPeerConnectionStatus(deviceId: String, status: PeerConnectionStatus): List<PeerDevice> {
        synchronized(lock) {
            if (status == PeerConnectionStatus.AVAILABLE) {
                connectionStatusOverrides.remove(deviceId)
            } else {
                connectionStatusOverrides[deviceId] = status
            }
            return buildMergedLocked()
        }
    }

    fun clearAll(): List<PeerDevice> {
        synchronized(lock) {
            peersByStrategy.clear()
            connectionStatusOverrides.clear()
            return emptyList()
        }
    }

    fun snapshot(): List<PeerDevice> = synchronized(lock) { buildMergedLocked() }

    private fun buildMergedLocked(): List<PeerDevice> {
        val localId = localDeviceIdProvider()
        val now = clock.nowEpochMillis()
        val merged = LinkedHashMap<String, PeerDevice>()

        val allRaw = peersByStrategy.values.flatten()
        for (candidate in allRaw) {
            if (!isSupportedPeer(candidate, localId, now)) continue
            val key = candidate.deviceId.lowercase()
            val existing = merged[key]
            merged[key] = if (existing == null) {
                candidate
            } else {
                mergeDuplicate(existing, candidate)
            }
        }

        return merged.values
            .map { peer ->
                val statusOverride = connectionStatusOverrides[peer.deviceId]
                if (statusOverride != null) peer.copy(connectionStatus = statusOverride) else peer
            }
            .sortedWith(
                compareByDescending<PeerDevice> { it.connectionStatus == PeerConnectionStatus.CONNECTED }
                    .thenByDescending { it.isTrusted }
                    .thenByDescending { it.hostAddress != null && it.port != null }
                    .thenBy { it.displayName.lowercase() }
            )
    }

    private fun isSupportedPeer(peer: PeerDevice, localDeviceId: String, now: Long): Boolean {
        if (peer.deviceId.isBlank() || peer.displayName.isBlank()) return false
        if (peer.deviceId.equals(localDeviceId, ignoreCase = true)) return false
        if (peer.protocolVersion < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) return false
        if (peer.lastSeenEpochMillis > 0L && now - peer.lastSeenEpochMillis > peerStaleMillis) {
            return false
        }
        return true
    }

    private fun mergeDuplicate(existing: PeerDevice, incoming: PeerDevice): PeerDevice {
        val preferIncoming = when {
            // Prefer entry that already has a directly reachable host+port
            incoming.hostAddress != null && incoming.port != null &&
                (existing.hostAddress == null || existing.port == null) -> true
            existing.hostAddress != null && existing.port != null &&
                (incoming.hostAddress == null || incoming.port == null) -> false
            // Otherwise prefer fresher sighting
            else -> incoming.lastSeenEpochMillis >= existing.lastSeenEpochMillis
        }

        val primary = if (preferIncoming) incoming else existing
        val secondary = if (preferIncoming) existing else incoming

        return primary.copy(
            hostAddress = primary.hostAddress ?: secondary.hostAddress,
            port = primary.port ?: secondary.port,
            publicKeyFingerprint = primary.publicKeyFingerprint ?: secondary.publicKeyFingerprint,
            p2pDeviceAddress = primary.p2pDeviceAddress ?: secondary.p2pDeviceAddress,
            hotspotSsid = primary.hotspotSsid ?: secondary.hotspotSsid,
            isTrusted = primary.isTrusted || secondary.isTrusted,
            lastSeenEpochMillis = maxOf(primary.lastSeenEpochMillis, secondary.lastSeenEpochMillis)
        )
    }

    companion object {
        const val DEFAULT_PEER_STALE_MS = 90_000L
    }
}
