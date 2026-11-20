package com.example.shareflow.network.strategy

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Real Android Local Wi-Fi (mDNS/DNS-SD) discovery & advertising strategy using [NsdManager]
 * and a reference-counted [WifiManager.MulticastLock].
 * Filters out self advertisements and non-ShareFlow / incompatible services.
 */
class LocalNetworkStrategy(
    context: Context,
    private val permissionHelper: NetworkPermissionHelper,
    private val clock: Clock = SystemClock()
) : ConnectionStrategy {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? by lazy {
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }
    private val wifiManager: WifiManager? by lazy {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var localDeviceId: String = ""
    private var registeredServiceName: String? = null

    private val resolveMutex = Mutex()
    private val resolveExecutor = Executors.newSingleThreadExecutor()
    private val activeInfoCallbacks = ConcurrentHashMap<String, Any>()
    private val peersByDeviceId = ConcurrentHashMap<String, PeerDevice>()
    private val serviceNameToDeviceId = ConcurrentHashMap<String, String>()

    override val connectionType: ConnectionType = ConnectionType.LOCAL_LAN_NSD

    private val _status = MutableStateFlow(
        StrategyStatus(
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            availability = permissionHelper.evaluateAvailability(ConnectionType.LOCAL_LAN_NSD)
        )
    )
    override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    override fun checkAvailability(): StrategyAvailability {
        val avail = permissionHelper.evaluateAvailability(ConnectionType.LOCAL_LAN_NSD)
        _status.value = _status.value.copy(availability = avail)
        return avail
    }

    override suspend fun startDiscovery(localIdentity: LocalDeviceIdentity): AppResult<Unit> {
        localDeviceId = localIdentity.deviceId
        val availability = checkAvailability()
        if (availability != StrategyAvailability.Available) {
            return AppResult.Failure(AppError.NotSupported("Local Wi-Fi network discovery unavailable"))
        }
        val mgr = nsdManager
            ?: return AppResult.Failure(AppError.NotSupported("Android NsdManager is unavailable"))

        stopDiscovery()
        acquireMulticastLock()
        peersByDeviceId.clear()
        serviceNameToDeviceId.clear()
        _discoveredPeers.value = emptyList()

        return suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    releaseMulticastLock()
                    discoveryListener = null
                    val msg = "Local Wi-Fi discovery failed to start (code $errorCode)"
                    _status.value = _status.value.copy(
                        isDiscovering = false,
                        availability = StrategyAvailability.Error(msg)
                    )
                    if (cont.isActive) cont.resume(AppResult.Failure(AppError.Network(msg)))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    _status.value = _status.value.copy(isDiscovering = false)
                }

                override fun onDiscoveryStarted(regType: String) {
                    _status.value = _status.value.copy(
                        availability = StrategyAvailability.Available,
                        isDiscovering = true,
                        statusDetail = "Listening for _shareflow._tcp on local Wi-Fi"
                    )
                    if (cont.isActive) cont.resume(AppResult.Success(Unit))
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    _status.value = _status.value.copy(isDiscovering = false)
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_shareflow", ignoreCase = true)) return
                    if (registeredServiceName != null && serviceInfo.serviceName == registeredServiceName) return
                    resolveDiscoveredService(mgr, serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val deviceId = serviceNameToDeviceId.remove(serviceInfo.serviceName)
                    if (deviceId != null) {
                        peersByDeviceId.remove(deviceId)
                        _discoveredPeers.value = peersByDeviceId.values.toList()
                    }
                    unregisterInfoCallback(mgr, serviceInfo.serviceName)
                }
            }

            discoveryListener = listener
            try {
                mgr.discoverServices(
                    TransferProtocol.SERVICE_TYPE_NSD,
                    NsdManager.PROTOCOL_DNS_SD,
                    listener
                )
            } catch (t: Throwable) {
                releaseMulticastLock()
                discoveryListener = null
                _status.value = _status.value.copy(
                    isDiscovering = false,
                    availability = StrategyAvailability.Error(t.message ?: "NSD start error")
                )
                if (cont.isActive) {
                    cont.resume(AppResult.Failure(AppError.Network("Failed to start NSD discovery", t)))
                }
            }
        }
    }

    override suspend fun stopDiscovery(): AppResult<Unit> {
        val mgr = nsdManager
        val listener = discoveryListener
        discoveryListener = null
        if (mgr != null && listener != null) {
            runCatching { mgr.stopServiceDiscovery(listener) }
        }
        if (mgr != null) {
            activeInfoCallbacks.keys.toList().forEach { name ->
                unregisterInfoCallback(mgr, name)
            }
        }
        releaseMulticastLock()
        _status.value = _status.value.copy(isDiscovering = false)
        return AppResult.Success(Unit)
    }

    override suspend fun startAdvertising(
        localIdentity: LocalDeviceIdentity,
        deviceName: String,
        port: Int
    ): AppResult<Unit> {
        localDeviceId = localIdentity.deviceId
        val mgr = nsdManager
            ?: return AppResult.Failure(AppError.NotSupported("Android NsdManager is unavailable"))
        if (port !in 1024..65535) {
            return AppResult.Failure(AppError.Validation("Invalid listening port $port"))
        }

        stopAdvertising()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "ShareFlow-${localIdentity.shortDeviceId}"
            serviceType = TransferProtocol.SERVICE_TYPE_NSD
            setPort(port)
            setAttribute(TransferProtocol.TXT_APP, TransferProtocol.SERVICE_APP_ID)
            setAttribute(TransferProtocol.TXT_VERSION, TransferProtocol.PROTOCOL_VERSION.toString())
            setAttribute(TransferProtocol.TXT_DEVICE_ID, localIdentity.deviceId)
            setAttribute(TransferProtocol.TXT_FINGERPRINT, localIdentity.publicKeyFingerprint)
            setAttribute(TransferProtocol.TXT_DISPLAY_NAME, deviceName.trim().ifBlank { "ShareFlow" }.take(32))
            setAttribute(TransferProtocol.TXT_DEVICE_TYPE, DeviceType.PHONE.name)
            setAttribute(TransferProtocol.TXT_PORT, port.toString())
        }

        return suspendCancellableCoroutine { cont ->
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                    registeredServiceName = nsdServiceInfo.serviceName
                    _status.value = _status.value.copy(
                        availability = StrategyAvailability.Available,
                        isAdvertising = true,
                        statusDetail = "Advertising ${nsdServiceInfo.serviceName} on LAN :$port"
                    )
                    if (cont.isActive) cont.resume(AppResult.Success(Unit))
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    registrationListener = null
                    val msg = "NSD service registration failed (code $errorCode)"
                    _status.value = _status.value.copy(isAdvertising = false)
                    if (cont.isActive) cont.resume(AppResult.Failure(AppError.Network(msg)))
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    registeredServiceName = null
                    _status.value = _status.value.copy(isAdvertising = false)
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    _status.value = _status.value.copy(isAdvertising = false)
                }
            }

            registrationListener = listener
            try {
                mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (t: Throwable) {
                registrationListener = null
                if (cont.isActive) {
                    cont.resume(AppResult.Failure(AppError.Network("NSD registerService failed", t)))
                }
            }
        }
    }

    override suspend fun stopAdvertising(): AppResult<Unit> {
        val mgr = nsdManager
        val listener = registrationListener
        registrationListener = null
        registeredServiceName = null
        if (mgr != null && listener != null) {
            runCatching { mgr.unregisterService(listener) }
        }
        _status.value = _status.value.copy(isAdvertising = false)
        return AppResult.Success(Unit)
    }

    override suspend fun prepareEndpointForPeer(
        peer: PeerDevice,
        passphrase: String?
    ): AppResult<ResolvedEndpoint> {
        val host = peer.hostAddress?.takeIf { it.isNotBlank() }
            ?: return AppResult.Failure(AppError.Validation("Peer IP address is not available yet"))
        val port = peer.port?.takeIf { it in 1024..65535 }
            ?: return AppResult.Failure(AppError.Validation("Peer listening port is invalid"))
        return AppResult.Success(
            ResolvedEndpoint(
                hostAddress = host,
                port = port,
                connectionType = ConnectionType.LOCAL_LAN_NSD
            )
        )
    }

    override suspend fun releaseTransport(): AppResult<Unit> {
        stopDiscovery()
        stopAdvertising()
        peersByDeviceId.clear()
        serviceNameToDeviceId.clear()
        _discoveredPeers.value = emptyList()
        return AppResult.Success(Unit)
    }

    private fun resolveDiscoveredService(mgr: NsdManager, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceUpdated(updatedInfo: NsdServiceInfo) {
                    handleResolvedService(updatedInfo)
                }

                override fun onServiceLost() {
                    val deviceId = serviceNameToDeviceId.remove(serviceInfo.serviceName)
                    if (deviceId != null) {
                        peersByDeviceId.remove(deviceId)
                        _discoveredPeers.value = peersByDeviceId.values.toList()
                    }
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            activeInfoCallbacks[serviceInfo.serviceName] = callback
            runCatching {
                mgr.registerServiceInfoCallback(serviceInfo, resolveExecutor, callback)
            }
        } else {
            resolveExecutor.execute {
                @Suppress("DEPRECATION")
                runCatching {
                    mgr.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                handleResolvedService(resolved)
                            }
                        }
                    )
                }
            }
        }
    }

    private fun handleResolvedService(info: NsdServiceInfo) {
        val attrs = info.attributes.mapValues { (_, bytes) ->
            bytes?.toString(Charsets.UTF_8) ?: ""
        }
        val appId = attrs[TransferProtocol.TXT_APP]
        if (!appId.equals(TransferProtocol.SERVICE_APP_ID, ignoreCase = true)) return
        val version = attrs[TransferProtocol.TXT_VERSION]?.toIntOrNull() ?: return
        if (version < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) return
        val deviceId = attrs[TransferProtocol.TXT_DEVICE_ID]?.takeIf { it.isNotBlank() } ?: return
        if (deviceId.equals(localDeviceId, ignoreCase = true)) return

        val hostAddress = resolveIpv4Address(info) ?: return
        val port = info.port.takeIf { it in 1024..65535 }
            ?: attrs[TransferProtocol.TXT_PORT]?.toIntOrNull()
            ?: return

        val displayName = attrs[TransferProtocol.TXT_DISPLAY_NAME]?.takeIf { it.isNotBlank() }
            ?: info.serviceName
        val fingerprint = attrs[TransferProtocol.TXT_FINGERPRINT]?.takeIf { it.length >= 16 }
        val deviceType = attrs[TransferProtocol.TXT_DEVICE_TYPE]?.let { raw ->
            runCatching { DeviceType.valueOf(raw) }.getOrNull()
        } ?: DeviceType.PHONE

        val peer = PeerDevice(
            deviceId = deviceId,
            displayName = displayName,
            deviceType = deviceType,
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            hostAddress = hostAddress,
            port = port,
            publicKeyFingerprint = fingerprint,
            lastSeenEpochMillis = clock.nowEpochMillis(),
            protocolVersion = version
        )
        serviceNameToDeviceId[info.serviceName] = deviceId
        peersByDeviceId[deviceId] = peer
        _discoveredPeers.value = peersByDeviceId.values.toList()
    }

    private fun resolveIpv4Address(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addresses = info.hostAddresses
            val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
            if (ipv4 != null) return ipv4.hostAddress
            addresses.firstOrNull { !it.isLoopbackAddress }?.hostAddress?.let { return it }
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    private fun unregisterInfoCallback(mgr: NsdManager, serviceName: String) {
        val cb = activeInfoCallbacks.remove(serviceName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && cb is NsdManager.ServiceInfoCallback) {
            runCatching { mgr.unregisterServiceInfoCallback(cb) }
        }
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("ShareFlowNsdMulticastLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }
}
