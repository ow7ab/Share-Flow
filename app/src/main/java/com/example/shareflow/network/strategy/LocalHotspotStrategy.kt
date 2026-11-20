package com.example.shareflow.network.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.LocalHotspotInfo
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Real Android Local-Only Hotspot strategy using [WifiManager.startLocalOnlyHotspot] (API 26+)
 * for hosting and [WifiNetworkSpecifier] (API 29+) for joining a QR-paired peer hotspot.
 * Never assumes hotspot or Wi-Fi can be forced on programmatically; surfaces clear errors if unsupported or denied.
 */
class LocalHotspotStrategy(
    context: Context,
    private val permissionHelper: NetworkPermissionHelper
) : ConnectionStrategy {

    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? by lazy {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private val connectivityManager: ConnectivityManager? by lazy {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private var activeReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override val connectionType: ConnectionType = ConnectionType.HOTSPOT

    private val _status = MutableStateFlow(
        StrategyStatus(
            connectionType = ConnectionType.HOTSPOT,
            availability = permissionHelper.evaluateAvailability(ConnectionType.HOTSPOT)
        )
    )
    override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _activeHotspot = MutableStateFlow<LocalHotspotInfo?>(null)
    val activeHotspot: StateFlow<LocalHotspotInfo?> = _activeHotspot.asStateFlow()

    override fun checkAvailability(): StrategyAvailability {
        val avail = permissionHelper.evaluateAvailability(ConnectionType.HOTSPOT)
        _status.value = _status.value.copy(availability = avail)
        return avail
    }

    override suspend fun startDiscovery(localIdentity: LocalDeviceIdentity): AppResult<Unit> {
        // Local-Only Hotspots are paired via QR code rather than broadcast scanning.
        val avail = checkAvailability()
        return if (avail == StrategyAvailability.Available) {
            AppResult.Success(Unit)
        } else {
            AppResult.Failure(AppError.NotSupported("Hotspot discovery uses QR pairing"))
        }
    }

    override suspend fun stopDiscovery(): AppResult<Unit> = AppResult.Success(Unit)

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertising(
        localIdentity: LocalDeviceIdentity,
        deviceName: String,
        port: Int
    ): AppResult<Unit> {
        val availability = checkAvailability()
        if (availability != StrategyAvailability.Available) {
            return availabilityToFailure(availability)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return AppResult.Failure(AppError.NotSupported("Local-only hotspot requires Android 8.0 or higher"))
        }
        val mgr = wifiManager
            ?: return AppResult.Failure(AppError.NotSupported("Wi-Fi service unavailable"))

        stopAdvertising()

        return suspendCancellableCoroutine { cont ->
            try {
                mgr.startLocalOnlyHotspot(
                    object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                            activeReservation = reservation
                            val (ssid, passphrase) = extractHotspotCredentials(reservation)
                            if (ssid.isNullOrBlank() || passphrase.isNullOrBlank()) {
                                runCatching { reservation.close() }
                                activeReservation = null
                                if (cont.isActive) {
                                    cont.resume(
                                        AppResult.Failure(
                                            AppError.Network("Hotspot started without readable SSID or passphrase")
                                        )
                                    )
                                }
                                return
                            }
                            val hostIp = resolveHotspotHostIpv4()
                            val info = LocalHotspotInfo(
                                ssid = ssid,
                                passphrase = passphrase,
                                hostAddress = hostIp,
                                port = port
                            )
                            _activeHotspot.value = info
                            _status.value = _status.value.copy(
                                availability = StrategyAvailability.Available,
                                isAdvertising = true,
                                statusDetail = "Local Hotspot active ($ssid)"
                            )
                            if (cont.isActive) cont.resume(AppResult.Success(Unit))
                        }

                        override fun onStopped() {
                            activeReservation = null
                            _activeHotspot.value = null
                            _status.value = _status.value.copy(isAdvertising = false)
                        }

                        override fun onFailed(reason: Int) {
                            activeReservation = null
                            _activeHotspot.value = null
                            val message = mapHotspotFailure(reason)
                            _status.value = _status.value.copy(
                                isAdvertising = false,
                                availability = StrategyAvailability.Error(message)
                            )
                            if (cont.isActive) {
                                cont.resume(AppResult.Failure(AppError.Network(message)))
                            }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            } catch (se: SecurityException) {
                val missing = permissionHelper.missingPermissionsFor(ConnectionType.HOTSPOT)
                _status.value = _status.value.copy(
                    isAdvertising = false,
                    availability = StrategyAvailability.PermissionRequired(missing)
                )
                if (cont.isActive) {
                    cont.resume(AppResult.Failure(AppError.PermissionDenied("Permission denied starting local hotspot", se)))
                }
            } catch (t: Throwable) {
                _status.value = _status.value.copy(
                    isAdvertising = false,
                    availability = StrategyAvailability.Error(t.message ?: "Hotspot failed")
                )
                if (cont.isActive) {
                    cont.resume(AppResult.Failure(AppError.Network("Failed to start local-only hotspot", t)))
                }
            }
        }
    }

    override suspend fun stopAdvertising(): AppResult<Unit> {
        runCatching { activeReservation?.close() }
        activeReservation = null
        _activeHotspot.value = null
        _status.value = _status.value.copy(isAdvertising = false)
        return AppResult.Success(Unit)
    }

    override suspend fun prepareEndpointForPeer(
        peer: PeerDevice,
        passphrase: String?
    ): AppResult<ResolvedEndpoint> {
        val port = peer.port?.takeIf { it in 1024..65535 }
            ?: return AppResult.Failure(AppError.Validation("Hotspot peer port is missing"))
        val ssid = peer.hotspotSsid?.takeIf { it.isNotBlank() }

        // If already reachable on current Wi-Fi or hotspot SSID not provided, connect directly to hostAddress
        if (ssid == null || passphrase.isNullOrBlank()) {
            val host = peer.hostAddress?.takeIf { it.isNotBlank() }
                ?: return AppResult.Failure(AppError.Validation("Hotspot host address or credentials missing"))
            return AppResult.Success(
                ResolvedEndpoint(
                    hostAddress = host,
                    port = port,
                    connectionType = ConnectionType.HOTSPOT
                )
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val host = peer.hostAddress?.takeIf { it.isNotBlank() }
                ?: return AppResult.Failure(
                    AppError.NotSupported("Join Wi-Fi network \"$ssid\" manually in Android Settings on Android 9 or lower.")
                )
            return AppResult.Success(
                ResolvedEndpoint(
                    hostAddress = host,
                    port = port,
                    connectionType = ConnectionType.HOTSPOT
                )
            )
        }

        val cm = connectivityManager
            ?: return AppResult.Failure(AppError.NotSupported("ConnectivityManager unavailable"))

        releaseRequestedNetwork()

        val network = withTimeoutOrNull(HOTSPOT_JOIN_TIMEOUT_MS) {
            suspendCancellableCoroutine<Network?> { cont ->
                val specifier = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(passphrase)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(specifier)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                activeNetworkCallback = callback
                try {
                    cm.requestNetwork(request, callback)
                } catch (_: Throwable) {
                    activeNetworkCallback = null
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        if (network == null) {
            releaseRequestedNetwork()
            return AppResult.Failure(
                AppError.Network("Unable to join local hotspot \"$ssid\". Approve the Android Wi-Fi connection prompt and retry.")
            )
        }

        val targetHost = peer.hostAddress?.takeIf { it.isNotBlank() }
            ?: resolveGatewayFromNetwork(cm, network)
            ?: "192.168.43.1"

        return AppResult.Success(
            ResolvedEndpoint(
                hostAddress = targetHost,
                port = port,
                connectionType = ConnectionType.HOTSPOT,
                boundNetwork = network
            )
        )
    }

    override suspend fun releaseTransport(): AppResult<Unit> {
        stopAdvertising()
        releaseRequestedNetwork()
        return AppResult.Success(Unit)
    }

    private fun releaseRequestedNetwork() {
        val cb = activeNetworkCallback
        activeNetworkCallback = null
        if (cb != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
    }

    private fun extractHotspotCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val softAp = reservation.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                softAp.wifiSsid?.bytes?.toString(Charsets.UTF_8)
            } else {
                @Suppress("DEPRECATION")
                softAp.ssid
            }
            return ssid to softAp.passphrase
        }
        @Suppress("DEPRECATION")
        val legacy = reservation.wifiConfiguration
        @Suppress("DEPRECATION")
        return legacy?.SSID to legacy?.preSharedKey
    }

    private fun resolveHotspotHostIpv4(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val apIface = interfaces.firstOrNull { iface ->
            iface.isUp && !iface.isLoopback &&
                (iface.name.startsWith("ap") || iface.name.startsWith("swlan") || iface.name.startsWith("wlan"))
        }
        apIface?.inetAddresses?.toList()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun resolveGatewayFromNetwork(cm: ConnectivityManager, network: Network): String? =
        runCatching {
            val props = cm.getLinkProperties(network) ?: return@runCatching null
            props.routes
                .mapNotNull { it.gateway }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isAnyLocalAddress && !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

    private fun availabilityToFailure(availability: StrategyAvailability): AppResult.Failure =
        when (availability) {
            is StrategyAvailability.PermissionRequired ->
                AppResult.Failure(AppError.PermissionDenied("Missing permissions for Local Hotspot: ${availability.missingPermissions.joinToString()}"))
            StrategyAvailability.LocationServicesDisabled ->
                AppResult.Failure(AppError.PermissionDenied("Enable Location Services on Android 12 or lower to start a Local-Only Hotspot."))
            is StrategyAvailability.Unsupported ->
                AppResult.Failure(AppError.NotSupported(availability.reason))
            is StrategyAvailability.Error ->
                AppResult.Failure(AppError.Network(availability.reason))
            else ->
                AppResult.Failure(AppError.Network("Local-only hotspot is unavailable"))
        }

    private fun mapHotspotFailure(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel available for Local Hotspot"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Cannot start Local Hotspot while tethering or conflicting Wi-Fi mode is active"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Local Hotspot is restricted by device policy"
        else -> "Failed to start Local-Only Hotspot (code $reason)"
    }

    companion object {
        private const val HOTSPOT_JOIN_TIMEOUT_MS = 25_000L
    }
}
