package com.example.shareflow.network.strategy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.shareflow.domain.model.BoundWifiNetworkHandle
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Testable provider for Strategy #4 (ExistingLocalWiFiStrategy) when both devices share a Wi-Fi router.
 */
interface ExistingWifiNetworkProvider {
    fun getActiveWifiNetworkHandle(): BoundWifiNetworkHandle?
}

/**
 * Phase 1 Strategy #4: Shared Local Wi-Fi fallback.
 * Locates the active Wi-Fi [android.net.Network] via [ConnectivityManager] so sockets bind to Wi-Fi
 * instead of cellular data even when both phones are already connected to an existing Wi-Fi LAN.
 */
class ExistingLocalWiFiStrategy(
    private val context: Context,
    private val permissionHelper: NetworkPermissionHelper
) : ExistingWifiNetworkProvider {

    private val appContext = context.applicationContext
    private val connectivityManager: ConnectivityManager? =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun getActiveWifiNetworkHandle(): BoundWifiNetworkHandle? {
        val cm = connectivityManager ?: return null
        val active = runCatching { cm.activeNetwork }.getOrNull()
        if (active != null) {
            val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val props = runCatching { cm.getLinkProperties(active) }.getOrNull()
                return WiFiNetworkSpecifierStrategy.buildHandleFromNetwork(active, props, isLocalOnly = false)
            }
        }
        return null
    }

    fun isWifiConnected(): Boolean =
        getActiveWifiNetworkHandle() != null || permissionHelper.isWifiEnabled()

    fun resolveCurrentLocalInterface(): Pair<String?, String?> = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
        for (nif in interfaces) {
            val ipv4 = nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
            if (ipv4 != null) return@runCatching nif.name to ipv4
        }
        null to null
    }.getOrDefault(null to null)
}
