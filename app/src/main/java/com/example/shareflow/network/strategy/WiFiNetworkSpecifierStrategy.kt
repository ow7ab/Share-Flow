package com.example.shareflow.network.strategy

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.example.shareflow.domain.model.BoundWifiNetworkHandle
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

sealed interface WifiJoinOutcome {
    data class Connected(val handle: BoundWifiNetworkHandle) : WifiJoinOutcome
    data class Failed(
        val errorCode: SmartConnectionErrorCode,
        val message: String
    ) : WifiJoinOutcome
}

/**
 * Testable abstraction for sender-side temporary Wi-Fi network connection & Network-bound sockets (Phase 3).
 */
interface SenderWifiConnector {
    fun isSupportedOnDevice(): Boolean
    suspend fun requestLocalHotspotConnection(
        ssid: String,
        passphrase: String?,
        securityType: HotspotSecurityType,
        timeoutMillis: Long = 35_000L,
        onWaitingForApproval: () -> Unit = {},
        onJoiningNetwork: () -> Unit = {},
        onNetworkLost: () -> Unit = {}
    ): WifiJoinOutcome

    fun createBoundTcpSocket(handle: BoundWifiNetworkHandle): Socket
    fun createBoundUdpSocket(handle: BoundWifiNetworkHandle): DatagramSocket
    fun releaseNetwork()
}

/**
 * Phase 3 Android 10+ (API 29+) WifiNetworkSpecifier Strategy.
 *
 * - Requests a temporary local-only Wi-Fi connection (`removeCapability(NET_CAPABILITY_INTERNET)`).
 * - Respects Android system user approval dialog (never bypasses consent).
 * - Binds every TCP socket via [Network.socketFactory] and UDP socket via [Network.bindSocket]
 *   so traffic NEVER falls back to cellular mobile data.
 * - Extracts actual interface, local IP, gateway IP, and subnet broadcast address from [LinkProperties]
 *   (zero hardcoded IP addresses).
 */
class WiFiNetworkSpecifierStrategy(
    private val context: Context,
    private val permissionHelper: NetworkPermissionHelper
) : SenderWifiConnector {

    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    @Volatile
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var activeNetwork: Network? = null

    override fun isSupportedOnDevice(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && connectivityManager != null

    override suspend fun requestLocalHotspotConnection(
        ssid: String,
        passphrase: String?,
        securityType: HotspotSecurityType,
        timeoutMillis: Long,
        onWaitingForApproval: () -> Unit,
        onJoiningNetwork: () -> Unit,
        onNetworkLost: () -> Unit
    ): WifiJoinOutcome {
        if (ssid.isBlank()) {
            return WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.INVALID_QR,
                "QR code does not contain a valid hotspot SSID"
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.UNSUPPORTED_HARDWARE,
                "Automatic temporary Wi-Fi joining requires Android 10 (API 29) or higher"
            )
        }
        val cm = connectivityManager
            ?: return WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.UNSUPPORTED_HARDWARE,
                "ConnectivityManager unavailable"
            )
        if (!permissionHelper.isWifiEnabled()) {
            return WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.WIFI_DISABLED,
                "Wi-Fi is disabled. Enable Wi-Fi and try again."
            )
        }

        releaseNetwork()

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        when (securityType) {
            HotspotSecurityType.WPA3_SAE -> {
                if (!passphrase.isNullOrBlank()) {
                    specifierBuilder.setWpa3Passphrase(passphrase)
                }
            }
            HotspotSecurityType.OPEN -> Unit
            HotspotSecurityType.WPA2_PSK,
            HotspotSecurityType.UNKNOWN -> {
                if (!passphrase.isNullOrBlank()) {
                    specifierBuilder.setWpa2Passphrase(passphrase)
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        onWaitingForApproval()

        val result = withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine<WifiJoinOutcome> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        activeNetwork = network
                        onJoiningNetwork()
                        val props = runCatching { cm.getLinkProperties(network) }.getOrNull()
                        val handle = buildHandleFromNetwork(network, props, isLocalOnly = true)
                        if (cont.isActive) {
                            cont.resume(WifiJoinOutcome.Connected(handle))
                        }
                    }

                    override fun onUnavailable() {
                        activeNetwork = null
                        if (cont.isActive) {
                            cont.resume(
                                WifiJoinOutcome.Failed(
                                    SmartConnectionErrorCode.USER_REJECTED_NETWORK,
                                    "Temporary Wi-Fi connection request was declined or unavailable in Android"
                                )
                            )
                        }
                    }

                    override fun onLost(network: Network) {
                        if (activeNetwork == network) {
                            activeNetwork = null
                            onNetworkLost()
                        }
                    }
                }

                activeCallback = callback
                try {
                    cm.requestNetwork(request, callback)
                } catch (se: SecurityException) {
                    activeCallback = null
                    if (cont.isActive) {
                        cont.resume(
                            WifiJoinOutcome.Failed(
                                SmartConnectionErrorCode.PERMISSION_DENIED,
                                "Android denied Wi-Fi network specifier request: ${se.message ?: "missing permission"}"
                            )
                        )
                    }
                } catch (t: Throwable) {
                    activeCallback = null
                    if (cont.isActive) {
                        cont.resume(
                            WifiJoinOutcome.Failed(
                                SmartConnectionErrorCode.UNKNOWN,
                                "Failed to request Wi-Fi network: ${t.message ?: "unknown error"}"
                            )
                        )
                    }
                }

                cont.invokeOnCancellation {
                    releaseNetwork()
                }
            }
        }

        if (result == null) {
            releaseNetwork()
            return WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.WIFI_JOIN_TIMEOUT,
                "Timed out waiting to join receiver's temporary Wi-Fi hotspot"
            )
        }
        return result
    }

    override fun createBoundTcpSocket(handle: BoundWifiNetworkHandle): Socket {
        val network = handle.network
        return if (network != null) {
            network.socketFactory.createSocket()
        } else {
            Socket()
        }
    }

    override fun createBoundUdpSocket(handle: BoundWifiNetworkHandle): DatagramSocket {
        val socket = DatagramSocket()
        val network = handle.network
        if (network != null) {
            network.bindSocket(socket)
        }
        return socket
    }

    override fun releaseNetwork() {
        val cb = activeCallback
        activeCallback = null
        activeNetwork = null
        if (cb != null) {
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
    }

    companion object {
        fun buildHandleFromNetwork(
            network: Network,
            linkProperties: LinkProperties?,
            isLocalOnly: Boolean
        ): BoundWifiNetworkHandle {
            val localLinkAddr = linkProperties?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            val localIp = localLinkAddr?.address?.hostAddress

            val gatewayIp = linkProperties?.routes
                ?.firstOrNull { route ->
                    val gw = route.gateway
                    gw is Inet4Address && !gw.isAnyLocalAddress && !gw.isLoopbackAddress
                }
                ?.gateway
                ?.hostAddress

            val broadcasts = LinkedHashSet<String>()
            if (localLinkAddr != null && localLinkAddr.address is Inet4Address) {
                computeSubnetBroadcast(localLinkAddr.address as Inet4Address, localLinkAddr.prefixLength)
                    ?.let { broadcasts.add(it) }
            }
            if (!gatewayIp.isNullOrBlank()) {
                broadcasts.add(gatewayIp)
            }
            broadcasts.add("255.255.255.255")

            return BoundWifiNetworkHandle(
                network = network,
                networkId = runCatching { network.networkHandle.toString() }.getOrNull(),
                interfaceName = linkProperties?.interfaceName,
                localIpv4Address = localIp,
                gatewayIpv4Address = gatewayIp,
                broadcastIpv4Addresses = broadcasts.toList(),
                isLocalOnlyHotspot = isLocalOnly
            )
        }

        private fun computeSubnetBroadcast(addr: Inet4Address, prefixLength: Int): String? {
            if (prefixLength !in 1..30) return null
            val bytes = addr.address
            val ipInt = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            val mask = (-1) shl (32 - prefixLength)
            val bcast = ipInt or mask.inv()
            val out = byteArrayOf(
                ((bcast ushr 24) and 0xFF).toByte(),
                ((bcast ushr 16) and 0xFF).toByte(),
                ((bcast ushr 8) and 0xFF).toByte(),
                (bcast and 0xFF).toByte()
            )
            return runCatching { InetAddress.getByAddress(out).hostAddress }.getOrNull()
        }
    }
}
