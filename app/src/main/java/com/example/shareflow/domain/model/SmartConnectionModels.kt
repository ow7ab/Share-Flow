package com.example.shareflow.domain.model

import android.net.Network
import android.os.Build

/**
 * Complete 18-state machine for ShareFlow Smart Hotspot Connection & Advanced QR Pairing.
 * Strictly separates network joining, receiver discovery, and cryptographic authentication:
 * CONNECTED is never entered before mutual ECDSA/ECDH/AES-256-GCM authentication succeeds.
 */
enum class SmartConnectionPhase {
    IDLE,
    STARTING_HOTSPOT,
    HOTSPOT_READY,
    STARTING_SERVER,
    SERVER_READY,
    WAITING_FOR_QR,
    QR_SCANNED,
    REQUESTING_WIFI,
    WAITING_FOR_APPROVAL,
    JOINING_NETWORK,
    DISCOVERING_RECEIVER,
    AUTHENTICATING,
    CONNECTED,
    TRANSFERRING,
    DISCONNECTED,
    CANCELLED,
    EXPIRED,
    FAILED;

    val isInProgress: Boolean
        get() = this == STARTING_HOTSPOT ||
            this == HOTSPOT_READY ||
            this == STARTING_SERVER ||
            this == QR_SCANNED ||
            this == REQUESTING_WIFI ||
            this == WAITING_FOR_APPROVAL ||
            this == JOINING_NETWORK ||
            this == DISCOVERING_RECEIVER ||
            this == AUTHENTICATING

    val isReceiverActive: Boolean
        get() = this == STARTING_HOTSPOT ||
            this == HOTSPOT_READY ||
            this == STARTING_SERVER ||
            this == SERVER_READY ||
            this == WAITING_FOR_QR
}

enum class SmartStrategyType {
    LOCAL_ONLY_HOTSPOT,
    WIFI_NETWORK_SPECIFIER,
    WIFI_DIRECT,
    EXISTING_LOCAL_WIFI
}

enum class HotspotSecurityType {
    WPA2_PSK,
    WPA3_SAE,
    OPEN,
    UNKNOWN
}

enum class SmartConnectionErrorCode {
    PERMISSION_DENIED,
    WIFI_DISABLED,
    HOTSPOT_CREATION_FAILED,
    HOTSPOT_STOPPED_UNEXPECTEDLY,
    USER_REJECTED_NETWORK,
    WIFI_JOIN_TIMEOUT,
    WRONG_NETWORK,
    SERVER_UNAVAILABLE,
    PORT_CONFLICT,
    STALE_OR_EXPIRED_QR,
    INVALID_QR,
    TAMPERED_QR,
    REPLAYED_QR,
    SELF_PAIRING,
    AUTHENTICATION_FAILED,
    RECEIVER_DISCONNECTED,
    APP_BACKGROUNDED,
    UNSUPPORTED_HARDWARE,
    SESSION_EXPIRED,
    UNKNOWN
}

data class SmartConnectionError(
    val code: SmartConnectionErrorCode,
    val detail: String,
    val recoverable: Boolean = true,
    val timestampEpochMillis: Long = System.currentTimeMillis()
)

/**
 * Signed v3 Smart QR Pairing payload.
 * Never log raw [passphrase] or [pairingToken]; use [toRedactedSummary] for telemetry.
 */
data class SmartQrPairingPayload(
    val protocolVersion: Int,
    val sessionId: String,
    val deviceId: String,
    val displayName: String,
    val publicKeyFingerprint: String,
    val ssid: String?,
    val passphrase: String?,
    val securityType: HotspotSecurityType,
    val serviceName: String,
    val servicePort: Int,
    val pairingToken: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val ephemeralPublicKey: String,
    val preferredStrategy: SmartStrategyType,
    val hostAddressHint: String? = null,
    val signature: String
) {
    fun isExpired(nowEpochMillis: Long): Boolean = nowEpochMillis >= expiresAtEpochMillis

    fun hasHotspotCredentials(): Boolean = !ssid.isNullOrBlank()

    fun toRedactedSummary(): String =
        "SmartQr(v=$protocolVersion, sid=${sessionId.take(8)}, did=${deviceId.take(8)}, " +
            "strategy=${preferredStrategy.name}, ssidPresent=${!ssid.isNullOrBlank()}, " +
            "port=$servicePort, service=$serviceName, exp=$expiresAtEpochMillis)"
}

/**
 * Active receiver LocalOnlyHotspot session discovered dynamically from Android callbacks.
 */
data class ActiveLocalHotspotSession(
    val ssid: String,
    val passphrase: String?,
    val securityType: HotspotSecurityType,
    val interfaceName: String?,
    val localIpv4Address: String?,
    val startedAtEpochMillis: Long
) {
    fun toRedactedSummary(): String =
        "HotspotSession(ssidPresent=${ssid.isNotBlank()}, sec=${securityType.name}, " +
            "iface=${interfaceName ?: "N/A"}, ip=${localIpv4Address ?: "N/A"})"
}

/**
 * Handle for an explicitly bound Android [Network] returned by WifiNetworkSpecifier or active Wi-Fi.
 * All sender TCP/UDP sockets bind strictly to [network] so traffic never routes over cellular mobile data.
 */
data class BoundWifiNetworkHandle(
    val network: Network?,
    val networkId: String?,
    val interfaceName: String?,
    val localIpv4Address: String?,
    val gatewayIpv4Address: String?,
    val broadcastIpv4Addresses: List<String> = emptyList(),
    val isLocalOnlyHotspot: Boolean = false
)

enum class ReceiverDiscoveryMethod {
    NSD,
    AUTHENTICATED_UDP,
    QR_HOST_HINT_VERIFIED
}

data class DiscoveredReceiverEndpoint(
    val hostAddress: String,
    val port: Int,
    val serviceName: String,
    val method: ReceiverDiscoveryMethod,
    val ephemeralKeyHash: String? = null
)

enum class QrScannerUiState {
    SCANNING,
    LOADING,
    SUCCESS,
    INVALID_QR,
    EXPIRED_QR,
    FAILURE
}

/**
 * Phase 8 Connection Diagnostics snapshot.
 * Strictly excludes hotspot passphrases, pairing tokens, and private cryptographic keys.
 */
data class SmartConnectionDiagnostics(
    val androidVersionLabel: String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    val activeStrategy: SmartStrategyType? = null,
    val phase: SmartConnectionPhase = SmartConnectionPhase.IDLE,
    val hotspotState: String = "INACTIVE",
    val ssidAvailable: Boolean = false,
    val ssidLabel: String? = null,
    val activeNetworkId: String? = null,
    val localIp: String? = null,
    val remoteIp: String? = null,
    val activeNetworkInterface: String? = null,
    val serverPort: Int? = null,
    val isServerListening: Boolean = false,
    val nsdDiscoveryStatus: String = "IDLE",
    val qrSessionExpiresAtEpochMillis: Long? = null,
    val authenticationStatus: String = "UNAUTHENTICATED",
    val lastError: SmartConnectionError? = null,
    val lastTestConnectionResult: String? = null,
    val isTestingConnection: Boolean = false
) {
    fun toSafeReportString(): String = buildString {
        appendLine("ShareFlow Connection Diagnostics (Redacted)")
        appendLine("Android Version: $androidVersionLabel")
        appendLine("Device Model: $deviceModel")
        appendLine("Strategy: ${activeStrategy?.name ?: "NONE"}")
        appendLine("Phase: ${phase.name}")
        appendLine("Hotspot State: $hotspotState")
        appendLine("SSID Available: $ssidAvailable${ssidLabel?.let { " ($it)" } ?: ""}")
        appendLine("Active Network ID: ${activeNetworkId ?: "N/A"}")
        appendLine("Local IP: ${localIp ?: "N/A"}")
        appendLine("Remote IP: ${remoteIp ?: "N/A"}")
        appendLine("Network Interface: ${activeNetworkInterface ?: "N/A"}")
        appendLine("Server Port: ${serverPort ?: "N/A"} (Listening: $isServerListening)")
        appendLine("Discovery Status: $nsdDiscoveryStatus")
        appendLine("QR Expires At: ${qrSessionExpiresAtEpochMillis ?: "N/A"}")
        appendLine("Authentication: $authenticationStatus")
        appendLine("Last Error: ${lastError?.let { "${it.code.name} - ${it.detail}" } ?: "None"}")
        appendLine("Last Connection Test: ${lastTestConnectionResult ?: "Not run"}")
    }
}
