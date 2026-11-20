package com.example.shareflow.network.strategy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.StrategyAvailability

/**
 * Android version-aware permission and hardware capability inspector for P2P networking.
 * Never attempts to forcibly toggle Wi-Fi or Location Services programmatically.
 */
class NetworkPermissionHelper(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT
) {
    private val appContext = context.applicationContext

    fun requiredPermissionsFor(type: ConnectionType): List<String> = when (type) {
        ConnectionType.LOCAL_LAN_NSD -> emptyList()
        ConnectionType.WIFI_DIRECT,
        ConnectionType.HOTSPOT -> {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    fun missingPermissionsFor(type: ConnectionType): List<String> =
        requiredPermissionsFor(type).filter { perm ->
            ContextCompat.checkSelfPermission(appContext, perm) != PackageManager.PERMISSION_GRANTED
        }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun isCameraHardwareSupported(): Boolean =
        appContext.packageManager?.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) == true

    fun isWifiDirectSupported(): Boolean =
        appContext.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) == true &&
            appContext.getSystemService(Context.WIFI_P2P_SERVICE) != null

    fun isLocalHotspotSupported(): Boolean =
        sdkInt >= Build.VERSION_CODES.O &&
            appContext.packageManager?.hasSystemFeature(PackageManager.FEATURE_WIFI) == true &&
            appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) != null

    fun isWifiEnabled(): Boolean = runCatching {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.isWifiEnabled == true
    }.getOrDefault(false)

    /**
     * On Android 12L and below (API <= 32), Wi-Fi Direct and Local-Only Hotspot require
     * system Location Services to be enabled by the user.
     */
    fun isLocationServicesRequiredForWifiP2p(): Boolean =
        sdkInt < Build.VERSION_CODES.TIRAMISU

    fun isLocationServicesEnabled(): Boolean = runCatching {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@runCatching false
        if (sdkInt >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(false)

    fun evaluateAvailability(type: ConnectionType): StrategyAvailability {
        when (type) {
            ConnectionType.LOCAL_LAN_NSD -> {
                val hasWifiFeature = appContext.packageManager
                    ?.hasSystemFeature(PackageManager.FEATURE_WIFI) != false
                if (!hasWifiFeature) {
                    return StrategyAvailability.Unsupported("Device does not have Wi-Fi hardware")
                }
                return StrategyAvailability.Available
            }

            ConnectionType.WIFI_DIRECT -> {
                if (!isWifiDirectSupported()) {
                    return StrategyAvailability.Unsupported("Wi-Fi Direct is not supported on this device")
                }
                val missing = missingPermissionsFor(ConnectionType.WIFI_DIRECT)
                if (missing.isNotEmpty()) {
                    return StrategyAvailability.PermissionRequired(missing)
                }
                if (!isWifiEnabled()) {
                    return StrategyAvailability.WifiDisabled
                }
                if (isLocationServicesRequiredForWifiP2p() && !isLocationServicesEnabled()) {
                    return StrategyAvailability.LocationServicesDisabled
                }
                return StrategyAvailability.Available
            }

            ConnectionType.HOTSPOT -> {
                if (!isLocalHotspotSupported()) {
                    return StrategyAvailability.Unsupported("Local-only hotspot requires Android 8.0 or higher with Wi-Fi")
                }
                val missing = missingPermissionsFor(ConnectionType.HOTSPOT)
                if (missing.isNotEmpty()) {
                    return StrategyAvailability.PermissionRequired(missing)
                }
                if (isLocationServicesRequiredForWifiP2p() && !isLocationServicesEnabled()) {
                    return StrategyAvailability.LocationServicesDisabled
                }
                return StrategyAvailability.Available
            }
        }
    }
}
