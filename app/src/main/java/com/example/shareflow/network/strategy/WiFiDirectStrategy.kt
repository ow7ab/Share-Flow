package com.example.shareflow.network.strategy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import com.example.shareflow.network.TransferProtocol
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Real Android Wi-Fi Direct (P2P) strategy using [WifiP2pManager] and DNS-SD service discovery
 * (`_shareflow._tcp`) to discover genuine ShareFlow devices while filtering unsupported peers.
 * Gracefully handles devices without Wi-Fi Direct hardware and version-specific runtime permissions.
 */
class WiFiDirectStrategy(
    context: Context,
    private val permissionHelper: NetworkPermissionHelper,
    private val clock: Clock = SystemClock()
) : ConnectionStrategy {

    private val appContext = context.applicationContext
    private val p2pManager: WifiP2pManager? by lazy {
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var localServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var activeServiceRequest: WifiP2pDnsSdServiceRequest? = null
    private var pendingConnectionInfo: CompletableDeferred<WifiP2pInfo?>? = null
    private var localDeviceId: String = ""

    private val txtRecordsByMac = ConcurrentHashMap<String, Map<String, String>>()
    private val peersByDeviceId = ConcurrentHashMap<String, PeerDevice>()

    override val connectionType: ConnectionType = ConnectionType.WIFI_DIRECT

    private val _status = MutableStateFlow(
        StrategyStatus(
            connectionType = ConnectionType.WIFI_DIRECT,
            availability = permissionHelper.evaluateAvailability(ConnectionType.WIFI_DIRECT)
        )
    )
    override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _status.value = _status.value.copy(
                        availability = if (enabled) {
                            permissionHelper.evaluateAvailability(ConnectionType.WIFI_DIRECT)
                        } else {
                            StrategyAvailability.WifiDisabled
                        }
                    )
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val mgr = p2pManager ?: return
                    val ch = channel ?: return
                    runCatching {
                        mgr.requestConnectionInfo(ch) { info ->
                            if (info != null && info.groupFormed) {
                                pendingConnectionInfo?.complete(info)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun checkAvailability(): StrategyAvailability {
        val avail = permissionHelper.evaluateAvailability(ConnectionType.WIFI_DIRECT)
        _status.value = _status.value.copy(availability = avail)
        return avail
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery(localIdentity: LocalDeviceIdentity): AppResult<Unit> {
        localDeviceId = localIdentity.deviceId
        val availability = checkAvailability()
        if (availability != StrategyAvailability.Available) {
            return availabilityToFailure(availability)
        }
        val mgr = p2pManager
            ?: return AppResult.Failure(AppError.NotSupported("Wi-Fi Direct manager unavailable"))
        val ch = ensureInitialized(mgr)
            ?: return AppResult.Failure(AppError.Network("Failed to initialize Wi-Fi Direct channel"))

        txtRecordsByMac.clear()
        peersByDeviceId.clear()
        _discoveredPeers.value = emptyList()

        return try {
            mgr.setDnsSdResponseListeners(
                ch,
                { instanceName, registrationType, srcDevice ->
                    if (!registrationType.contains(TransferProtocol.SERVICE_TYPE_P2P, ignoreCase = true)) {
                        return@setDnsSdResponseListeners
                    }
                    val txt = txtRecordsByMac[srcDevice.deviceAddress] ?: return@setDnsSdResponseListeners
                    parseShareFlowPeer(
                        instanceName = instanceName,
                        p2pMac = srcDevice.deviceAddress,
                        fallbackName = srcDevice.deviceName,
                        txt = txt
                    )?.let { peer ->
                        peersByDeviceId[peer.deviceId] = peer
                        _discoveredPeers.value = peersByDeviceId.values.toList()
                    }
                },
                { fullDomainName, txtRecordMap, srcDevice ->
                    if (fullDomainName.contains(TransferProtocol.SERVICE_TYPE_P2P, ignoreCase = true)) {
                        txtRecordsByMac[srcDevice.deviceAddress] = txtRecordMap
                        parseShareFlowPeer(
                            instanceName = srcDevice.deviceName ?: "ShareFlow",
                            p2pMac = srcDevice.deviceAddress,
                            fallbackName = srcDevice.deviceName,
                            txt = txtRecordMap
                        )?.let { peer ->
                            peersByDeviceId[peer.deviceId] = peer
                            _discoveredPeers.value = peersByDeviceId.values.toList()
                        }
                    }
                }
            )

            awaitAction { listener -> mgr.clearServiceRequests(ch, listener) }
            val request = WifiP2pDnsSdServiceRequest.newInstance(TransferProtocol.SERVICE_TYPE_P2P)
            activeServiceRequest = request

            val addResult = awaitAction { listener -> mgr.addServiceRequest(ch, request, listener) }
            if (addResult != 0) {
                return AppResult.Failure(AppError.Network("Wi-Fi Direct service request failed (code $addResult)"))
            }

            val discoverResult = awaitAction { listener -> mgr.discoverServices(ch, listener) }
            if (discoverResult != 0) {
                val errMsg = mapP2pFailureReason(discoverResult)
                _status.value = _status.value.copy(
                    isDiscovering = false,
                    availability = StrategyAvailability.Error(errMsg)
                )
                return AppResult.Failure(AppError.Network(errMsg))
            }

            _status.value = _status.value.copy(
                availability = StrategyAvailability.Available,
                isDiscovering = true,
                statusDetail = "Wi-Fi Direct DNS-SD active"
            )
            AppResult.Success(Unit)
        } catch (se: SecurityException) {
            val missing = permissionHelper.missingPermissionsFor(ConnectionType.WIFI_DIRECT)
            _status.value = _status.value.copy(
                isDiscovering = false,
                availability = StrategyAvailability.PermissionRequired(missing)
            )
            AppResult.Failure(AppError.PermissionDenied("Wi-Fi Direct permission denied", se))
        } catch (t: Throwable) {
            _status.value = _status.value.copy(
                isDiscovering = false,
                availability = StrategyAvailability.Error(t.message ?: "Wi-Fi Direct discovery failed")
            )
            AppResult.Failure(AppError.Network("Wi-Fi Direct discovery failed", t))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopDiscovery(): AppResult<Unit> {
        val mgr = p2pManager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching {
                activeServiceRequest?.let { req ->
                    awaitAction { listener -> mgr.removeServiceRequest(ch, req, listener) }
                }
                awaitAction { listener -> mgr.stopPeerDiscovery(ch, listener) }
            }
        }
        activeServiceRequest = null
        _status.value = _status.value.copy(isDiscovering = false)
        return AppResult.Success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun startAdvertising(
        localIdentity: LocalDeviceIdentity,
        deviceName: String,
        port: Int
    ): AppResult<Unit> {
        localDeviceId = localIdentity.deviceId
        val availability = checkAvailability()
        if (availability != StrategyAvailability.Available) {
            return availabilityToFailure(availability)
        }
        val mgr = p2pManager
            ?: return AppResult.Failure(AppError.NotSupported("Wi-Fi Direct manager unavailable"))
        val ch = ensureInitialized(mgr)
            ?: return AppResult.Failure(AppError.Network("Failed to initialize Wi-Fi Direct channel"))

        return try {
            val txtRecord = mapOf(
                TransferProtocol.TXT_APP to TransferProtocol.SERVICE_APP_ID,
                TransferProtocol.TXT_VERSION to TransferProtocol.PROTOCOL_VERSION.toString(),
                TransferProtocol.TXT_DEVICE_ID to localIdentity.deviceId,
                TransferProtocol.TXT_FINGERPRINT to localIdentity.publicKeyFingerprint,
                TransferProtocol.TXT_DISPLAY_NAME to deviceName.trim().ifBlank { "ShareFlow" }.take(32),
                TransferProtocol.TXT_DEVICE_TYPE to DeviceType.PHONE.name,
                TransferProtocol.TXT_PORT to port.toString()
            )
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                "ShareFlow_${localIdentity.shortDeviceId}",
                TransferProtocol.SERVICE_TYPE_P2P,
                txtRecord
            )
            awaitAction { listener -> mgr.clearLocalServices(ch, listener) }
            val result = awaitAction { listener -> mgr.addLocalService(ch, serviceInfo, listener) }
            if (result != 0) {
                return AppResult.Failure(AppError.Network("Wi-Fi Direct advertising failed: ${mapP2pFailureReason(result)}"))
            }
            localServiceInfo = serviceInfo
            // Kick off passive peer discovery so Android keeps P2P DNS-SD responder awake
            runCatching { mgr.discoverPeers(ch, null) }

            _status.value = _status.value.copy(
                availability = StrategyAvailability.Available,
                isAdvertising = true,
                statusDetail = "Advertising on Wi-Fi Direct :$port"
            )
            AppResult.Success(Unit)
        } catch (se: SecurityException) {
            AppResult.Failure(AppError.PermissionDenied("Wi-Fi Direct advertising permission denied", se))
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Network("Wi-Fi Direct advertising failed", t))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun stopAdvertising(): AppResult<Unit> {
        val mgr = p2pManager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching {
                localServiceInfo?.let { info ->
                    awaitAction { listener -> mgr.removeLocalService(ch, info, listener) }
                }
            }
        }
        localServiceInfo = null
        _status.value = _status.value.copy(isAdvertising = false)
        return AppResult.Success(Unit)
    }

    @SuppressLint("MissingPermission")
    override suspend fun prepareEndpointForPeer(
        peer: PeerDevice,
        passphrase: String?
    ): AppResult<ResolvedEndpoint> {
        val availability = checkAvailability()
        if (availability != StrategyAvailability.Available) {
            return availabilityToFailure(availability)
        }
        val port = peer.port
            ?: return AppResult.Failure(AppError.Validation("Peer did not advertise a ShareFlow port"))

        // If peer already has a reachable IPv4 host (e.g., already in group), use it directly
        if (!peer.hostAddress.isNullOrBlank()) {
            return AppResult.Success(
                ResolvedEndpoint(
                    hostAddress = peer.hostAddress,
                    port = port,
                    connectionType = ConnectionType.WIFI_DIRECT
                )
            )
        }

        val mac = peer.p2pDeviceAddress
            ?: return AppResult.Failure(AppError.Validation("Missing Wi-Fi Direct peer address"))
        val mgr = p2pManager
            ?: return AppResult.Failure(AppError.NotSupported("Wi-Fi Direct unavailable"))
        val ch = ensureInitialized(mgr)
            ?: return AppResult.Failure(AppError.Network("Wi-Fi Direct channel initialization failed"))

        return try {
            val deferred = CompletableDeferred<WifiP2pInfo?>()
            pendingConnectionInfo = deferred

            val config = WifiP2pConfig().apply {
                deviceAddress = mac
                wps.setup = WpsInfo.PBC
            }
            val connectCode = awaitAction { listener -> mgr.connect(ch, config, listener) }
            if (connectCode != 0) {
                pendingConnectionInfo = null
                return AppResult.Failure(
                    AppError.Network("Wi-Fi Direct connect failed: ${mapP2pFailureReason(connectCode)}")
                )
            }

            val p2pInfo = withTimeoutOrNull(P2P_GROUP_CONNECT_TIMEOUT_MS) {
                deferred.await()
            }
            pendingConnectionInfo = null

            val ownerAddress = p2pInfo?.groupOwnerAddress?.hostAddress
            if (p2pInfo == null || !p2pInfo.groupFormed || ownerAddress.isNullOrBlank()) {
                return AppResult.Failure(
                    AppError.Network("Timed out waiting for Wi-Fi Direct group negotiation")
                )
            }

            AppResult.Success(
                ResolvedEndpoint(
                    hostAddress = ownerAddress,
                    port = port,
                    connectionType = ConnectionType.WIFI_DIRECT
                )
            )
        } catch (se: SecurityException) {
            pendingConnectionInfo = null
            AppResult.Failure(AppError.PermissionDenied("Wi-Fi Direct connect permission denied", se))
        } catch (t: Throwable) {
            pendingConnectionInfo = null
            AppResult.Failure(AppError.Network("Wi-Fi Direct connection error", t))
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun releaseTransport(): AppResult<Unit> {
        stopDiscovery()
        stopAdvertising()
        val mgr = p2pManager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching { awaitAction { listener -> mgr.removeGroup(ch, listener) } }
        }
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching { channel?.close() }
        }
        channel = null
        peersByDeviceId.clear()
        txtRecordsByMac.clear()
        _discoveredPeers.value = emptyList()
        return AppResult.Success(Unit)
    }

    private fun ensureInitialized(mgr: WifiP2pManager): WifiP2pManager.Channel? {
        if (channel == null) {
            channel = mgr.initialize(appContext, Looper.getMainLooper(), null)
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                appContext,
                p2pReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        return channel
    }

    private fun parseShareFlowPeer(
        instanceName: String,
        p2pMac: String?,
        fallbackName: String?,
        txt: Map<String, String>
    ): PeerDevice? {
        val appId = txt[TransferProtocol.TXT_APP]
        if (!appId.equals(TransferProtocol.SERVICE_APP_ID, ignoreCase = true)) return null
        val version = txt[TransferProtocol.TXT_VERSION]?.toIntOrNull() ?: return null
        if (version < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) return null
        val deviceId = txt[TransferProtocol.TXT_DEVICE_ID]?.takeIf { it.isNotBlank() } ?: return null
        if (deviceId.equals(localDeviceId, ignoreCase = true)) return null

        val port = txt[TransferProtocol.TXT_PORT]?.toIntOrNull()
        val displayName = txt[TransferProtocol.TXT_DISPLAY_NAME]?.takeIf { it.isNotBlank() }
            ?: fallbackName?.takeIf { it.isNotBlank() }
            ?: instanceName
        val fingerprint = txt[TransferProtocol.TXT_FINGERPRINT]?.takeIf { it.length >= 16 }
        val deviceType = txt[TransferProtocol.TXT_DEVICE_TYPE]?.let { raw ->
            runCatching { DeviceType.valueOf(raw) }.getOrNull()
        } ?: DeviceType.PHONE

        return PeerDevice(
            deviceId = deviceId,
            displayName = displayName,
            deviceType = deviceType,
            connectionType = ConnectionType.WIFI_DIRECT,
            hostAddress = null,
            port = port,
            publicKeyFingerprint = fingerprint,
            lastSeenEpochMillis = clock.nowEpochMillis(),
            protocolVersion = version,
            p2pDeviceAddress = p2pMac
        )
    }

    private suspend fun awaitAction(
        invoke: (WifiP2pManager.ActionListener) -> Unit
    ): Int = suspendCancellableCoroutine { cont ->
        invoke(object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(0)
            }

            override fun onFailure(reason: Int) {
                if (cont.isActive) cont.resume(reason)
            }
        })
    }

    private fun availabilityToFailure(availability: StrategyAvailability): AppResult.Failure =
        when (availability) {
            is StrategyAvailability.PermissionRequired ->
                AppResult.Failure(AppError.PermissionDenied("Missing permissions: ${availability.missingPermissions.joinToString()}"))
            StrategyAvailability.WifiDisabled ->
                AppResult.Failure(AppError.Network("Wi-Fi is turned off. Enable Wi-Fi in system settings to use Wi-Fi Direct."))
            StrategyAvailability.LocationServicesDisabled ->
                AppResult.Failure(AppError.PermissionDenied("Enable Location Services in Android settings for Wi-Fi Direct on Android 12 and lower."))
            is StrategyAvailability.Unsupported ->
                AppResult.Failure(AppError.NotSupported(availability.reason))
            is StrategyAvailability.Error ->
                AppResult.Failure(AppError.Network(availability.reason))
            StrategyAvailability.Available ->
                AppResult.Failure(AppError.Network("Unexpected availability state"))
        }

    private fun mapP2pFailureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
        WifiP2pManager.BUSY -> "Wi-Fi Direct framework is busy; retry shortly"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No Wi-Fi Direct service requests registered"
        else -> "Wi-Fi Direct internal error (code $reason)"
    }

    companion object {
        private const val P2P_GROUP_CONNECT_TIMEOUT_MS = 25_000L
    }
}
