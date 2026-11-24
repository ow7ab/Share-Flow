package com.example.shareflow.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.network.strategy.NetworkPermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Feature-scoped permission domains in ShareFlow.
 * Permissions are strictly requested on demand when the user invokes the corresponding workflow.
 */
enum class ShareFlowPermissionFeature {
    /** Receiver starting LocalOnlyHotspot / Wi-Fi Direct discovery. */
    RECEIVE_WIFI_HOTSPOT,

    /** Sender scanning/joining nearby Wi-Fi Direct or LocalOnlyHotspot peers. */
    SEND_WIFI_NEARBY,

    /** Live camera QR code scanning inside Send / QR Scanner. */
    QR_CAMERA,

    /** Accessing local photos (Android Photo Picker / scoped MediaStore — zero broad storage permission). */
    PHOTOS_MEDIA,

    /** Accessing local videos (Android Photo Picker / scoped MediaStore — zero broad storage permission). */
    VIDEOS_MEDIA,

    /** Listing shareable installed apps via manifest <queries> (zero runtime permission). */
    APP_DISCOVERY,

    /** Launching Android PackageInstaller for verified received APKs (special unknown-sources setting). */
    APP_INSTALL,

    /** Optional transfer progress / foreground notification channel on Android 13+. */
    NOTIFICATIONS
}

/**
 * Snapshot of permission & system readiness for a specific [ShareFlowPermissionFeature].
 */
data class FeaturePermissionStatus(
    val feature: ShareFlowPermissionFeature,
    val missingRuntimePermissions: List<String> = emptyList(),
    val requiresUnknownSourcesSetting: Boolean = false,
    val requiresLocationServicesToggle: Boolean = false,
    val requiresWifiToggle: Boolean = false,
    val denialCount: Int = 0
) {
    val isReady: Boolean
        get() = missingRuntimePermissions.isEmpty() &&
            !requiresUnknownSourcesSetting &&
            !requiresLocationServicesToggle &&
            !requiresWifiToggle

    val canAskRuntimePermissionAgain: Boolean
        get() = missingRuntimePermissions.isNotEmpty() && denialCount < 2
}

/**
 * Centralized, Google Play-policy-compliant permission manager for ShareFlow.
 *
 * Key invariants:
 * - Never requests broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`,
 *   `READ_MEDIA_VIDEO`) — media selection uses zero-permission Android Photo Picker & SAF.
 * - Never requests `QUERY_ALL_PACKAGES` — app discovery uses manifest `<queries>`.
 * - Evaluates exact API-level requirements (e.g., `NEARBY_WIFI_DEVICES` on API 33+,
 *   `ACCESS_FINE_LOCATION` on API <= 32, `POST_NOTIFICATIONS` on API 33+).
 * - Tracks per-feature denial counts during the session to avoid permission nag loops.
 */
class PermissionManager(
    context: Context,
    private val networkPermissionHelper: NetworkPermissionHelper = NetworkPermissionHelper(context),
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val permissionChecker: ((String) -> Boolean)? = null,
    private val canInstallPackagesChecker: (() -> Boolean)? = null
) {
    private val appContext = context.applicationContext

    private val _denialCounts = MutableStateFlow<Map<ShareFlowPermissionFeature, Int>>(emptyMap())
    val denialCounts: StateFlow<Map<ShareFlowPermissionFeature, Int>> = _denialCounts.asStateFlow()

    fun requiredRuntimePermissions(feature: ShareFlowPermissionFeature): List<String> = when (feature) {
        ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT,
        ShareFlowPermissionFeature.SEND_WIFI_NEARBY -> {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        ShareFlowPermissionFeature.QR_CAMERA -> listOf(Manifest.permission.CAMERA)

        ShareFlowPermissionFeature.NOTIFICATIONS -> {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                emptyList()
            }
        }

        // Play-policy compliant: Android Photo Picker, SAF, and <queries> require zero runtime permissions.
        ShareFlowPermissionFeature.PHOTOS_MEDIA,
        ShareFlowPermissionFeature.VIDEOS_MEDIA,
        ShareFlowPermissionFeature.APP_DISCOVERY,
        ShareFlowPermissionFeature.APP_INSTALL -> emptyList()
    }

    fun missingRuntimePermissions(feature: ShareFlowPermissionFeature): List<String> =
        requiredRuntimePermissions(feature).filterNot { isRuntimePermissionGranted(it) }

    fun isRuntimePermissionGranted(permission: String): Boolean {
        permissionChecker?.let { return it(permission) }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun canRequestPackageInstalls(): Boolean {
        canInstallPackagesChecker?.let { return it() }
        return if (sdkInt >= Build.VERSION_CODES.O) {
            runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun evaluateFeature(feature: ShareFlowPermissionFeature): FeaturePermissionStatus {
        val missingRuntime = missingRuntimePermissions(feature)
        val needsUnknownSources = feature == ShareFlowPermissionFeature.APP_INSTALL &&
            !canRequestPackageInstalls()
        val needsLocationToggle = (feature == ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT ||
            feature == ShareFlowPermissionFeature.SEND_WIFI_NEARBY) &&
            networkPermissionHelper.isLocationServicesRequiredForWifiP2p() &&
            !networkPermissionHelper.isLocationServicesEnabled()
        val needsWifiToggle = (feature == ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT ||
            feature == ShareFlowPermissionFeature.SEND_WIFI_NEARBY) &&
            !networkPermissionHelper.isWifiEnabled() &&
            networkPermissionHelper.evaluateAvailability(ConnectionType.HOTSPOT) !is
                com.example.shareflow.domain.model.StrategyAvailability.Available

        return FeaturePermissionStatus(
            feature = feature,
            missingRuntimePermissions = missingRuntime,
            requiresUnknownSourcesSetting = needsUnknownSources,
            requiresLocationServicesToggle = needsLocationToggle,
            requiresWifiToggle = needsWifiToggle,
            denialCount = _denialCounts.value[feature] ?: 0
        )
    }

    fun recordPermissionResult(feature: ShareFlowPermissionFeature, granted: Boolean) {
        _denialCounts.update { current ->
            val updated = current.toMutableMap()
            if (granted) {
                updated.remove(feature)
            } else {
                updated[feature] = (current[feature] ?: 0) + 1
            }
            updated
        }
    }

    fun resetDenialTracking(feature: ShareFlowPermissionFeature) {
        _denialCounts.update { it - feature }
    }

    fun createAppSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createManageUnknownSourcesIntent(): Intent =
        if (sdkInt >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            createAppSettingsIntent()
        }

    /**
     * Returns the official Android Settings Panel for Wi-Fi (`Settings.Panel.ACTION_WIFI`) on
     * Android 10+ (API 29+) so users can toggle Wi-Fi in an in-app bottom sheet without leaving
     * ShareFlow, falling back to `Settings.ACTION_WIFI_SETTINGS` on Android 7–9.
     */
    fun createWifiPanelIntent(): Intent =
        if (sdkInt >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            createWifiSettingsIntent()
        }

    fun createWifiSettingsIntent(): Intent =
        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun createLocationSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
