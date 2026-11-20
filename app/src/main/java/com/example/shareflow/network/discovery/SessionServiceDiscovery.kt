package com.example.shareflow.network.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.BoundWifiNetworkHandle
import com.example.shareflow.domain.model.DiscoveredReceiverEndpoint
import com.example.shareflow.domain.model.ReceiverDiscoveryMethod
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.strategy.SenderWifiConnector
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 4 Per-Session Service Discovery interface.
 * Supports Android NSD (`_shareflow._tcp.`) + HMAC-SHA256 authenticated UDP discovery
 * explicitly bound to the connected Android [android.net.Network] — zero hardcoded IPs.
 */
interface SessionServiceDiscovery {
    val statusLabel: StateFlow<String>

    suspend fun startSessionAdvertising(
        payload: SmartQrPairingPayload,
        ephemeralKeyHash: String
    )

    suspend fun stopSessionAdvertising()

    suspend fun discoverReceiverForSession(
        payload: SmartQrPairingPayload,
        boundNetwork: BoundWifiNetworkHandle?,
        timeoutMillis: Long = 10_000L
    ): AppResult<DiscoveredReceiverEndpoint>
}

class AndroidSessionServiceDiscovery(
    private val context: Context,
    private val senderWifiConnector: SenderWifiConnector,
    private val dispatchers: DispatcherProvider,
    private val udpPort: Int = TransferProtocol.UDP_DISCOVERY_PORT
) : SessionServiceDiscovery {

    private val appContext = context.applicationContext
    private val nsdManager: NsdManager? =
        appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val secureRandom = SecureRandom()

    private val _statusLabel = MutableStateFlow("IDLE")
    override val statusLabel: StateFlow<String> = _statusLabel.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpResponderSocket: DatagramSocket? = null
    private var udpResponderJob: Job? = null

    override suspend fun startSessionAdvertising(
        payload: SmartQrPairingPayload,
        ephemeralKeyHash: String
    ) = withContext(dispatchers.io) {
        stopSessionAdvertising()
        _statusLabel.value = "ADVERTISING_STARTING"

        // 1. Register NSD service with session metadata TXT records
        val manager = nsdManager
        if (manager != null) {
            runCatching {
                val info = NsdServiceInfo().apply {
                    serviceName = payload.serviceName
                    serviceType = TransferProtocol.SERVICE_TYPE_NSD
                    port = payload.servicePort
                    setAttribute(TransferProtocol.TXT_APP, TransferProtocol.SERVICE_APP_ID)
                    setAttribute(TransferProtocol.TXT_VERSION, payload.protocolVersion.toString())
                    setAttribute(TransferProtocol.TXT_DEVICE_ID, payload.deviceId)
                    setAttribute(TransferProtocol.TXT_SESSION_ID, payload.sessionId)
                    setAttribute(TransferProtocol.TXT_EPHEMERAL_KEY_HASH, ephemeralKeyHash)
                    setAttribute(TransferProtocol.TXT_PORT, payload.servicePort.toString())
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        _statusLabel.value = "NSD_AND_UDP_ACTIVE"
                    }

                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        _statusLabel.value = "UDP_ACTIVE_NSD_ERR_$errorCode"
                    }

                    override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                        if (_statusLabel.value.startsWith("NSD")) {
                            _statusLabel.value = "IDLE"
                        }
                    }

                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                }
                registrationListener = listener
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }

        // 2. Start authenticated UDP discovery responder (for local-only hotspots where mDNS may be filtered)
        startAuthenticatedUdpResponder(payload, ephemeralKeyHash)
    }

    override suspend fun stopSessionAdvertising() = withContext(dispatchers.io) {
        udpResponderJob?.cancel()
        udpResponderJob = null
        runCatching { udpResponderSocket?.close() }
        udpResponderSocket = null

        val listener = registrationListener
        registrationListener = null
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
        }
        releaseMulticastLock()
        _statusLabel.value = "IDLE"
    }

    override suspend fun discoverReceiverForSession(
        payload: SmartQrPairingPayload,
        boundNetwork: BoundWifiNetworkHandle?,
        timeoutMillis: Long
    ): AppResult<DiscoveredReceiverEndpoint> = withContext(dispatchers.io) {
        _statusLabel.value = "DISCOVERING_NSD_AND_UDP"

        val found = withTimeoutOrNull(timeoutMillis) {
            coroutineScope {
                val udpDeferred = async {
                    discoverViaAuthenticatedUdp(payload, boundNetwork, timeoutMillis - 500L)
                }
                val nsdDeferred = async {
                    discoverViaNsd(payload, timeoutMillis - 500L)
                }

                // Prefer authenticated UDP or NSD — whichever resolves first with valid session match
                val udpResult = udpDeferred.await()
                if (udpResult != null) {
                    nsdDeferred.cancel()
                    udpResult
                } else {
                    nsdDeferred.await()
                }
            }
        }

        if (found != null) {
            _statusLabel.value = "RESOLVED_${found.method.name}"
            return@withContext AppResult.Success(found)
        }

        // Final dynamic fallback: verify hostAddressHint from the signed QR via direct TCP connect on bound network
        val hintIp = payload.hostAddressHint?.takeIf { it.isNotBlank() }
        if (hintIp != null) {
            val reachable = verifyTcpReachability(hintIp, payload.servicePort, boundNetwork)
            if (reachable) {
                _statusLabel.value = "RESOLVED_QR_HINT_VERIFIED"
                return@withContext AppResult.Success(
                    DiscoveredReceiverEndpoint(
                        hostAddress = hintIp,
                        port = payload.servicePort,
                        serviceName = payload.serviceName,
                        method = ReceiverDiscoveryMethod.QR_HOST_HINT_VERIFIED
                    )
                )
            }
        }

        _statusLabel.value = "DISCOVERY_FAILED"
        AppResult.Failure(
            AppError.Network(
                "Could not locate ShareFlow receiver '${payload.displayName}' on the connected network"
            )
        )
    }

    private fun startAuthenticatedUdpResponder(
        payload: SmartQrPairingPayload,
        ephemeralKeyHash: String
    ) {
        acquireMulticastLock()
        udpResponderJob = scope.launch {
            val socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(udpPort))
                    soTimeout = 1_500
                }
            }.getOrNull() ?: return@launch

            udpResponderSocket = socket
            if (_statusLabel.value == "ADVERTISING_STARTING") {
                _statusLabel.value = "UDP_ACTIVE"
            }

            val buffer = ByteArray(1024)
            try {
                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: Throwable) {
                        if (socket.isClosed) break else continue
                    }

                    val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    val parts = msg.split('|')
                    // Probe format: SF3_PROBE|<sessionId>|<nonce>|<hmac>
                    if (parts.size == 4 && parts[0] == PROBE_PREFIX && parts[1] == payload.sessionId) {
                        val nonce = parts[2]
                        val suppliedProbeHmac = parts[3]
                        val expectedProbeHmac = computeProbeHmac(payload.pairingToken, payload.sessionId, nonce)
                        if (constantTimeEquals(expectedProbeHmac, suppliedProbeHmac)) {
                            val respHmac = computeResponseHmac(
                                pairingToken = payload.pairingToken,
                                sessionId = payload.sessionId,
                                nonce = nonce,
                                port = payload.servicePort,
                                epkHash = ephemeralKeyHash
                            )
                            val responseText = "$RESPONSE_PREFIX|${payload.sessionId}|$nonce|${payload.servicePort}|$ephemeralKeyHash|$respHmac"
                            val respBytes = responseText.toByteArray(Charsets.UTF_8)
                            val respPacket = DatagramPacket(
                                respBytes,
                                respBytes.size,
                                packet.address,
                                packet.port
                            )
                            runCatching { socket.send(respPacket) }
                        }
                    }
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun discoverViaAuthenticatedUdp(
        payload: SmartQrPairingPayload,
        boundNetwork: BoundWifiNetworkHandle?,
        timeoutMillis: Long
    ): DiscoveredReceiverEndpoint? = withContext(dispatchers.io) {
        val targets = LinkedHashSet<String>()
        boundNetwork?.broadcastIpv4Addresses?.forEach { if (it.isNotBlank()) targets.add(it) }
        boundNetwork?.gatewayIpv4Address?.takeIf { it.isNotBlank() }?.let { targets.add(it) }
        payload.hostAddressHint?.takeIf { it.isNotBlank() }?.let { targets.add(it) }
        targets.add("255.255.255.255")

        val socket = runCatching {
            if (boundNetwork != null) {
                senderWifiConnector.createBoundUdpSocket(boundNetwork)
            } else {
                DatagramSocket()
            }.apply {
                broadcast = true
                soTimeout = 700
            }
        }.getOrNull() ?: return@withContext null

        val deadline = System.currentTimeMillis() + timeoutMillis.coerceAtLeast(1_500L)
        val recvBuffer = ByteArray(1024)

        try {
            while (System.currentTimeMillis() < deadline && isActive) {
                val nonceBytes = ByteArray(12).also { secureRandom.nextBytes(it) }
                val nonce = AndroidDeviceIdentityManager.encodeBase64Url(nonceBytes)
                val probeHmac = computeProbeHmac(payload.pairingToken, payload.sessionId, nonce)
                val probeBytes = "$PROBE_PREFIX|${payload.sessionId}|$nonce|$probeHmac".toByteArray(Charsets.UTF_8)

                for (targetHost in targets) {
                    runCatching {
                        val destAddr = InetAddress.getByName(targetHost)
                        socket.send(DatagramPacket(probeBytes, probeBytes.size, destAddr, udpPort))
                    }
                }

                val receiveUntil = (System.currentTimeMillis() + 900L).coerceAtMost(deadline)
                while (System.currentTimeMillis() < receiveUntil && isActive) {
                    val pkt = DatagramPacket(recvBuffer, recvBuffer.size)
                    try {
                        socket.receive(pkt)
                        val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8).trim()
                        val parts = text.split('|')
                        // Response format: SF3_RESP|<sessionId>|<nonce>|<port>|<epkHash>|<hmac>
                        if (parts.size == 6 &&
                            parts[0] == RESPONSE_PREFIX &&
                            parts[1] == payload.sessionId &&
                            parts[2] == nonce
                        ) {
                            val port = parts[3].toIntOrNull() ?: continue
                            val epkHash = parts[4]
                            val suppliedHmac = parts[5]
                            val expectedHmac = computeResponseHmac(
                                pairingToken = payload.pairingToken,
                                sessionId = payload.sessionId,
                                nonce = nonce,
                                port = port,
                                epkHash = epkHash
                            )
                            val senderIp = (pkt.address as? Inet4Address)?.hostAddress
                                ?: pkt.address?.hostAddress
                            if (!senderIp.isNullOrBlank() &&
                                port in 1024..65535 &&
                                constantTimeEquals(expectedHmac, suppliedHmac)
                            ) {
                                return@withContext DiscoveredReceiverEndpoint(
                                    hostAddress = senderIp,
                                    port = port,
                                    serviceName = payload.serviceName,
                                    method = ReceiverDiscoveryMethod.AUTHENTICATED_UDP,
                                    ephemeralKeyHash = epkHash
                                )
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                delay(200L)
            }
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun discoverViaNsd(
        payload: SmartQrPairingPayload,
        timeoutMillis: Long
    ): DiscoveredReceiverEndpoint? {
        val manager = nsdManager ?: return null
        return withTimeoutOrNull(timeoutMillis.coerceAtLeast(1_500L)) {
            suspendCancellableCoroutine<DiscoveredReceiverEndpoint?> { cont ->
                var discoveryListener: NsdManager.DiscoveryListener? = null
                val listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val nameMatches = serviceInfo.serviceName.equals(payload.serviceName, ignoreCase = true) ||
                            serviceInfo.serviceName.contains(payload.deviceId.take(8), ignoreCase = true)
                        if (!nameMatches) return

                        manager.resolveService(
                            serviceInfo,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                                override fun onServiceResolved(resolved: NsdServiceInfo) {
                                    val sidAttr = resolved.attributes[TransferProtocol.TXT_SESSION_ID]
                                        ?.toString(Charsets.UTF_8)
                                    val didAttr = resolved.attributes[TransferProtocol.TXT_DEVICE_ID]
                                        ?.toString(Charsets.UTF_8)
                                    val sessionMatches = sidAttr == null || sidAttr == payload.sessionId
                                    val deviceMatches = didAttr == null || didAttr.equals(payload.deviceId, ignoreCase = true)
                                    val hostIp = resolved.host?.hostAddress
                                    if (sessionMatches && deviceMatches && !hostIp.isNullOrBlank() && resolved.port in 1024..65535) {
                                        if (cont.isActive) {
                                            cont.resume(
                                                DiscoveredReceiverEndpoint(
                                                    hostAddress = hostIp,
                                                    port = resolved.port,
                                                    serviceName = resolved.serviceName,
                                                    method = ReceiverDiscoveryMethod.NSD
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }
                discoveryListener = listener
                try {
                    manager.discoverServices(
                        TransferProtocol.SERVICE_TYPE_NSD,
                        NsdManager.PROTOCOL_DNS_SD,
                        listener
                    )
                } catch (_: Throwable) {
                    if (cont.isActive) cont.resume(null)
                }

                cont.invokeOnCancellation {
                    discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
                }
            }
        }
    }

    private fun verifyTcpReachability(
        host: String,
        port: Int,
        boundNetwork: BoundWifiNetworkHandle?
    ): Boolean = runCatching {
        val socket = if (boundNetwork != null) {
            senderWifiConnector.createBoundTcpSocket(boundNetwork)
        } else {
            java.net.Socket()
        }
        socket.use {
            it.connect(InetSocketAddress(host, port), 2_500)
            true
        }
    }.getOrDefault(false)

    private fun acquireMulticastLock() {
        runCatching {
            val lock = wifiManager?.createMulticastLock("ShareFlowSmartDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    companion object {
        private const val PROBE_PREFIX = "SF3_PROBE"
        private const val RESPONSE_PREFIX = "SF3_RESP"

        fun computeProbeHmac(pairingToken: String, sessionId: String, nonce: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pairingToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val raw = "PROBE|$sessionId|$nonce".toByteArray(Charsets.UTF_8)
            return AndroidDeviceIdentityManager.encodeBase64Url(mac.doFinal(raw))
        }

        fun computeResponseHmac(
            pairingToken: String,
            sessionId: String,
            nonce: String,
            port: Int,
            epkHash: String
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pairingToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val raw = "RESP|$sessionId|$nonce|$port|$epkHash".toByteArray(Charsets.UTF_8)
            return AndroidDeviceIdentityManager.encodeBase64Url(mac.doFinal(raw))
        }

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
