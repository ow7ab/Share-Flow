package com.example.shareflow.network.strategy

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ActiveLocalHotspotSession
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.StrategyAvailability
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Testable abstraction for the receiver-side temporary local-only hotspot lifecycle (Phase 2).
 */
interface ReceiverHotspotController {
    val activeSession: StateFlow<ActiveLocalHotspotSession?>
    val hotspotStateLabel: StateFlow<String>

    fun checkAvailability(): StrategyAvailability
    suspend fun startHotspot(onStoppedUnexpectedly: () -> Unit): AppResult<ActiveLocalHotspotSession>
    suspend fun stopHotspot()
    fun inspectActiveApInterface(): Pair<String?, String?>
}

/**
 * Real Android LocalOnlyHotspot strategy for the receiving device.
 * - Strictly waits for [WifiManager.LocalOnlyHotspotCallback.onStarted] before reporting readiness.
 * - Reads actual system-generated SSID, passphrase, and SoftAp security type (never hardcoded).
 * - Dynamically polls the active AP network interface & IPv4 address (never hardcodes 192.168.43.1).
 * - Properly releases [WifiManager.LocalOnlyHotspotReservation] on cancel, stop, or unexpected failure.
 */
class LocalOnlyHotspotStrategy(
    private val context: Context,
    private val permissionHelper: NetworkPermissionHelper,
    private val clock: Clock = SystemClock()
) : ReceiverHotspotController {

    private val appContext = context.applicationContext
    private val wifiManager: WifiManager? =
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _activeSession = MutableStateFlow<ActiveLocalHotspotSession?>(null)
    override val activeSession: StateFlow<ActiveLocalHotspotSession?> = _activeSession.asStateFlow()

    private val _hotspotStateLabel = MutableStateFlow("INACTIVE")
    override val hotspotStateLabel: StateFlow<String> = _hotspotStateLabel.asStateFlow()

    private var activeReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var stoppingIntentionally = false

    override fun checkAvailability(): StrategyAvailability {
        val base = permissionHelper.evaluateAvailability(ConnectionType.HOTSPOT)
        if (base !is StrategyAvailability.Available) return base
        if (wifiManager == null) {
            return StrategyAvailability.Unsupported("WifiManager service unavailable on this device")
        }
        return StrategyAvailability.Available
    }

    @SuppressLint("MissingPermission")
    override suspend fun startHotspot(
        onStoppedUnexpectedly: () -> Unit
    ): AppResult<ActiveLocalHotspotSession> {
        val availability = checkAvailability()
        if (availability !is StrategyAvailability.Available) {
            val err = when (availability) {
                is StrategyAvailability.PermissionRequired ->
                    AppError.PermissionDenied("Missing permissions: ${availability.missingPermissions.joinToString()}")
                StrategyAvailability.WifiDisabled ->
                    AppError.Network("Wi-Fi is disabled on this device")
                StrategyAvailability.LocationServicesDisabled ->
                    AppError.PermissionDenied("Enable Location Services to start Local-Only Hotspot on Android 12 or lower")
                is StrategyAvailability.Unsupported ->
                    AppError.NotSupported(availability.reason)
                is StrategyAvailability.Error ->
                    AppError.Network(availability.reason)
                StrategyAvailability.Available -> AppError.Network("Unknown hotspot state")
            }
            return AppResult.Failure(err)
        }

        val manager = wifiManager
            ?: return AppResult.Failure(AppError.NotSupported("Wi-Fi service unavailable"))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return AppResult.Failure(AppError.NotSupported("LocalOnlyHotspot requires Android 8.0 (API 26) or higher"))
        }

        // Release any previous reservation cleanly before starting a new one
        stopHotspot()
        stoppingIntentionally = false
        _hotspotStateLabel.value = "STARTING"

        val startOutcome = withTimeoutOrNull(HOTSPOT_START_TIMEOUT_MS) {
            suspendCancellableCoroutine<AppResult<WifiManager.LocalOnlyHotspotReservation>> { cont ->
                val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        if (reservation == null) {
                            _hotspotStateLabel.value = "FAILED_NULL_RESERVATION"
                            if (cont.isActive) {
                                cont.resume(AppResult.Failure(AppError.Network("Android returned an empty hotspot reservation")))
                            }
                            return
                        }
                        activeReservation = reservation
                        _hotspotStateLabel.value = "ACTIVE"
                        if (cont.isActive) {
                            cont.resume(AppResult.Success(reservation))
                        } else {
                            // Coroutine cancelled before callback arrived — release reservation immediately
                            runCatching { reservation.close() }
                            activeReservation = null
                            _hotspotStateLabel.value = "INACTIVE"
                        }
                    }

                    override fun onStopped() {
                        val unexpected = !stoppingIntentionally && _activeSession.value != null
                        activeReservation = null
                        _activeSession.value = null
                        _hotspotStateLabel.value = if (unexpected) "STOPPED_UNEXPECTEDLY" else "INACTIVE"
                        if (unexpected) {
                            onStoppedUnexpectedly()
                        }
                    }

                    override fun onFailed(reason: Int) {
                        activeReservation = null
                        _activeSession.value = null
                        _hotspotStateLabel.value = "FAILED_$reason"
                        val message = when (reason) {
                            ERROR_NO_CHANNEL -> "No Wi-Fi channel available for Local-Only Hotspot"
                            ERROR_GENERIC -> "Android failed to create Local-Only Hotspot"
                            ERROR_INCOMPATIBLE_MODE -> "Device Wi-Fi is in an incompatible mode (e.g. tethering already active)"
                            ERROR_TETHERING_DISALLOWED -> "Hotspot creation is disallowed by device policy"
                            else -> "Local-Only Hotspot failed with code $reason"
                        }
                        if (cont.isActive) {
                            cont.resume(AppResult.Failure(AppError.Network(message)))
                        }
                    }
                }

                try {
                    manager.startLocalOnlyHotspot(callback, mainHandler)
                } catch (se: SecurityException) {
                    _hotspotStateLabel.value = "PERMISSION_DENIED"
                    if (cont.isActive) {
                        cont.resume(
                            AppResult.Failure(
                                AppError.PermissionDenied("Nearby Wi-Fi permission denied by Android", se)
                            )
                        )
                    }
                } catch (ise: IllegalStateException) {
                    _hotspotStateLabel.value = "ALREADY_ACTIVE_OR_CONFLICT"
                    if (cont.isActive) {
                        cont.resume(
                            AppResult.Failure(
                                AppError.Network("Another Local-Only Hotspot request is already active", ise)
                            )
                        )
                    }
                } catch (t: Throwable) {
                    _hotspotStateLabel.value = "ERROR"
                    if (cont.isActive) {
                        cont.resume(AppResult.Failure(AppError.Network("Failed to request Local-Only Hotspot", t)))
                    }
                }

                cont.invokeOnCancellation {
                    runCatching { activeReservation?.close() }
                    activeReservation = null
                }
            }
        } ?: run {
            _hotspotStateLabel.value = "TIMEOUT"
            return AppResult.Failure(AppError.Network("Timed out waiting for Android Local-Only Hotspot to start"))
        }

        if (startOutcome is AppResult.Failure) {
            return startOutcome
        }

        val reservation = (startOutcome as AppResult.Success).data
        val credentials = extractCredentials(reservation)
        if (credentials == null || credentials.first.isBlank()) {
            stopHotspot()
            return AppResult.Failure(AppError.Network("Android started hotspot without providing a valid SSID"))
        }

        // Poll briefly for the OS AP network interface (ap0 / wlan1 / swlan0) to receive its dynamic IPv4
        var ifaceInfo = inspectActiveApInterface()
        if (ifaceInfo.second == null) {
            repeat(6) {
                delay(250L)
                ifaceInfo = inspectActiveApInterface()
                if (ifaceInfo.second != null) return@repeat
            }
        }

        val session = ActiveLocalHotspotSession(
            ssid = credentials.first,
            passphrase = credentials.second,
            securityType = credentials.third,
            interfaceName = ifaceInfo.first,
            localIpv4Address = ifaceInfo.second,
            startedAtEpochMillis = clock.nowEpochMillis()
        )
        _activeSession.value = session
        _hotspotStateLabel.value = "ACTIVE"
        return AppResult.Success(session)
    }

    override suspend fun stopHotspot() {
        stoppingIntentionally = true
        runCatching { activeReservation?.close() }
        activeReservation = null
        _activeSession.value = null
        _hotspotStateLabel.value = "INACTIVE"
    }

    /**
     * Dynamically inspects active UP non-loopback network interfaces, preferring AP-named interfaces
     * (ap0, swlan0, wlan1, softap, p2p) and falling back to any active private IPv4 interface.
     * Never hardcodes any IP address such as 192.168.43.1.
     */
    override fun inspectActiveApInterface(): Pair<String?, String?> = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }

        val candidates = interfaces.mapNotNull { nif ->
            val ipv4 = nif.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress ?: return@mapNotNull null
            nif.name to ipv4
        }

        val apPreferred = candidates.firstOrNull { (name, _) ->
            val lower = name.lowercase()
            lower.startsWith("ap") ||
                lower.startsWith("swlan") ||
                lower.contains("softap") ||
                lower == "wlan1" ||
                lower == "wlan2"
        }
        apPreferred ?: candidates.firstOrNull { (name, _) ->
            name.lowercase().startsWith("wlan")
        } ?: candidates.firstOrNull() ?: (null to null)
    }.getOrDefault(null to null)

    @Suppress("DEPRECATION")
    private fun extractCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation
    ): Triple<String, String?, HotspotSecurityType>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val softAp = reservation.softApConfiguration
            if (softAp != null) {
                val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    softAp.wifiSsid?.toString()?.trim('"') ?: softAp.ssid?.trim('"')
                } else {
                    softAp.ssid?.trim('"')
                }
                val passphrase = softAp.passphrase
                val secType = when (softAp.securityType) {
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> HotspotSecurityType.WPA2_PSK
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE,
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> HotspotSecurityType.WPA3_SAE
                    SoftApConfiguration.SECURITY_TYPE_OPEN -> HotspotSecurityType.OPEN
                    else -> HotspotSecurityType.UNKNOWN
                }
                if (!ssid.isNullOrBlank()) {
                    return Triple(ssid, passphrase, secType)
                }
            }
        }

        val legacy = reservation.wifiConfiguration ?: return null
        val ssid = legacy.SSID?.trim('"').orEmpty()
        if (ssid.isBlank()) return null
        val passphrase = legacy.preSharedKey?.trim('"')
        val secType = if (passphrase.isNullOrBlank()) HotspotSecurityType.OPEN else HotspotSecurityType.WPA2_PSK
        return Triple(ssid, passphrase, secType)
    }

    companion object {
        private const val HOTSPOT_START_TIMEOUT_MS = 15_000L
    }
}
