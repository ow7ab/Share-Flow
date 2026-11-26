package com.example.shareflow.domain.model

import androidx.compose.runtime.Immutable

enum class DeviceType {
    PHONE,
    TABLET,
    DESKTOP,
    UNKNOWN
}

enum class ConnectionType {
    WIFI_DIRECT,
    LOCAL_LAN_NSD,
    HOTSPOT
}

enum class PeerConnectionStatus {
    AVAILABLE,
    CONNECTING,
    AWAITING_APPROVAL,
    CONNECTED,
    REJECTED,
    UNAVAILABLE
}

/**
 * Pure domain representation of a discovered or paired peer device.
 */
@Immutable
data class PeerDevice(
    val deviceId: String,
    val displayName: String,
    val deviceType: DeviceType = DeviceType.PHONE,
    val connectionType: ConnectionType = ConnectionType.WIFI_DIRECT,
    val hostAddress: String? = null,
    val port: Int? = null,
    val publicKeyFingerprint: String? = null,
    val isTrusted: Boolean = false,
    val signalLevel: Int? = null,
    val lastSeenEpochMillis: Long = 0L,
    val protocolVersion: Int = 2,
    val p2pDeviceAddress: String? = null,
    val hotspotSsid: String? = null,
    val connectionStatus: PeerConnectionStatus = PeerConnectionStatus.AVAILABLE
) {
    val shortDeviceId: String
        get() = deviceId.take(8).uppercase()

    val shortFingerprint: String
        get() = publicKeyFingerprint
            ?.replace(":", "")
            ?.take(12)
            ?.chunked(4)
            ?.joinToString(":")
            ?.uppercase()
            ?: ""
}

/**
 * Cryptographic identity summary for the local ShareFlow device (never contains private keys).
 */
@Immutable
data class LocalDeviceIdentity(
    val deviceId: String,
    val publicKeyFingerprint: String,
    val publicKeyBase64: String,
    val isHardwareBackedKeystore: Boolean
) {
    val shortDeviceId: String
        get() = deviceId.take(8).uppercase()

    val formattedFingerprint: String
        get() = publicKeyFingerprint
            .replace(":", "")
            .take(16)
            .chunked(4)
            .joinToString(":")
            .uppercase()
}

/**
 * Structured short-lived pairing session payload encoded into ShareFlow QR codes.
 */
@Immutable
data class PairingPayload(
    val protocolVersion: Int,
    val sessionId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyFingerprint: String,
    val pairingToken: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val hostAddress: String?,
    val port: Int,
    val preferredConnectionType: ConnectionType,
    val hotspotSsid: String? = null,
    val hotspotPassphrase: String? = null
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis
}

/**
 * Pending incoming peer connection request requiring explicit user approval.
 */
@Immutable
data class IncomingConnectionRequest(
    val requestId: String,
    val sessionId: String,
    val peerDeviceId: String,
    val peerDisplayName: String,
    val peerPublicKeyFingerprint: String,
    val sasVerificationCode: String,
    val connectionType: ConnectionType,
    val isPreviouslyTrusted: Boolean,
    val hasValidQrToken: Boolean,
    val requestedAtEpochMillis: Long
) {
    val shortPeerId: String
        get() = peerDeviceId.take(8).uppercase()

    val formattedFingerprint: String
        get() = peerPublicKeyFingerprint
            .replace(":", "")
            .take(16)
            .chunked(4)
            .joinToString(":")
            .uppercase()
}

/**
 * Details of an active Android Local-Only Hotspot reservation created by ShareFlow.
 */
@Immutable
data class LocalHotspotInfo(
    val ssid: String,
    val passphrase: String,
    val hostAddress: String?,
    val port: Int
)

/**
 * Hardware/permission readiness of an individual discovery/connection transport strategy.
 */
@Immutable
sealed interface StrategyAvailability {
    data object Available : StrategyAvailability
    @Immutable
    data class PermissionRequired(val missingPermissions: List<String>) : StrategyAvailability
    data object WifiDisabled : StrategyAvailability
    data object LocationServicesDisabled : StrategyAvailability
    @Immutable
    data class Unsupported(val reason: String) : StrategyAvailability
    @Immutable
    data class Error(val reason: String) : StrategyAvailability
}

@Immutable
data class StrategyStatus(
    val connectionType: ConnectionType,
    val availability: StrategyAvailability,
    val isDiscovering: Boolean = false,
    val isAdvertising: Boolean = false,
    val statusDetail: String? = null
)

@Immutable
sealed interface DiscoveryStatus {
    data object Idle : DiscoveryStatus
    @Immutable
    data class Discovering(val startedAtEpochMillis: Long, val timeoutMillis: Long) : DiscoveryStatus
    data object TimedOut : DiscoveryStatus
    @Immutable
    data class Failed(val reason: String) : DiscoveryStatus
}

@Immutable
data class DiscoverySnapshot(
    val peers: List<PeerDevice> = emptyList(),
    val status: DiscoveryStatus = DiscoveryStatus.Idle,
    val isAdvertising: Boolean = false,
    val advertisedPort: Int? = null,
    val strategyStatuses: List<StrategyStatus> = emptyList(),
    val activeHotspot: LocalHotspotInfo? = null
)

/**
 * Sealed state machine representing the independent networking engine connection lifecycle.
 */
@Immutable
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Discovering : ConnectionState
    @Immutable
    data class Advertising(val localDeviceName: String, val port: Int) : ConnectionState
    @Immutable
    data class Connecting(val peer: PeerDevice) : ConnectionState
    @Immutable
    data class AwaitingApproval(
        val peer: PeerDevice,
        val sasVerificationCode: String
    ) : ConnectionState
    @Immutable
    data class Connected(
        val peer: PeerDevice,
        val sessionId: String,
        val sasVerificationCode: String = "",
        val isEncrypted: Boolean = true,
        val transport: ConnectionType = peer.connectionType,
        val isInitiator: Boolean = true
    ) : ConnectionState
    @Immutable
    data class Error(val reason: String, val recoverable: Boolean = true) : ConnectionState
}
