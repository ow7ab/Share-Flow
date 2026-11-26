package com.example.shareflow.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@Immutable
data class PairingSessionInfo(
    val deviceName: String,
    val hostAddress: String?,
    val port: Int,
    val pairingPin: String,
    val pairingUri: String
)

@Immutable
data class DeviceNetworkStatus(
    val isWifiConnected: Boolean,
    val localIpv4Address: String?,
    val isWifiEnabled: Boolean = isWifiConnected,
    val hasCheckedInitialState: Boolean = false
)

/**
 * UI-independent controller managing real local TCP receive socket readiness,
 * real live device Wi-Fi radio & connectivity state (via NetworkCallback + WIFI_STATE_CHANGED),
 * ephemeral pairing codes, and selected SAF files queue.
 * Never fabricates devices or fake transfer progress.
 */
class LocalSessionController(
    context: Context,
    private val dispatchers: DispatcherProvider
) {
    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()

    private var activeServerSocket: ServerSocket? = null
    private val isMonitoring = AtomicBoolean(false)
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiStateReceiver: BroadcastReceiver? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _selectedSendFiles = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val selectedSendFiles: StateFlow<List<LocalFileEntry>> = _selectedSendFiles.asStateFlow()

    private val _selectedRelativePaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val selectedRelativePaths: StateFlow<Map<String, String>> = _selectedRelativePaths.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val recentFiles: StateFlow<List<LocalFileEntry>> = _recentFiles.asStateFlow()

    private val _networkStatus = MutableStateFlow(
        DeviceNetworkStatus(
            isWifiConnected = false,
            localIpv4Address = null,
            isWifiEnabled = false,
            hasCheckedInitialState = false
        )
    )
    val networkStatus: StateFlow<DeviceNetworkStatus> = _networkStatus.asStateFlow()

    /**
     * Begins observing live Wi-Fi radio state and Wi-Fi transport connectivity changes
     * cleanly without polling or leaking receivers. Safe to call repeatedly.
     */
    fun startNetworkMonitoring() {
        if (!isMonitoring.compareAndSet(false, true)) {
            refreshNetworkStatus()
            return
        }
        refreshNetworkStatus()

        val connectivityManager = runCatching {
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

        if (connectivityManager != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshNetworkStatus()
                }

                override fun onLost(network: Network) {
                    refreshNetworkStatus()
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    refreshNetworkStatus()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    refreshNetworkStatus()
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching {
                connectivityManager.registerNetworkCallback(request, callback)
                wifiNetworkCallback = callback
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val wifiState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN
                    )
                    val enabled = wifiState == WifiManager.WIFI_STATE_ENABLED
                    _networkStatus.update { current ->
                        current.copy(
                            isWifiEnabled = enabled,
                            isWifiConnected = if (enabled) current.isWifiConnected else false,
                            hasCheckedInitialState = true
                        )
                    }
                    if (wifiState == WifiManager.WIFI_STATE_ENABLED ||
                        wifiState == WifiManager.WIFI_STATE_DISABLED
                    ) {
                        refreshNetworkStatus()
                    }
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            wifiStateReceiver = receiver
        }
    }

    /**
     * Unregisters live Wi-Fi observers to prevent memory leaks when lifecycle ends.
     */
    fun stopNetworkMonitoring() {
        if (!isMonitoring.compareAndSet(true, false)) return
        wifiNetworkCallback?.let { callback ->
            runCatching {
                val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(callback)
            }
        }
        wifiNetworkCallback = null

        wifiStateReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        wifiStateReceiver = null
    }

    fun refreshNetworkStatus(): DeviceNetworkStatus {
        val status = readNetworkStatus()
        _networkStatus.value = status
        return status
    }

    suspend fun refreshNetworkStatusAsync(): DeviceNetworkStatus = withContext(dispatchers.io) {
        refreshNetworkStatus()
    }

    suspend fun startReceiving(deviceName: String, port: Int): AppResult<Unit> =
        withContext(dispatchers.io) {
            stopReceivingInternal()
            runCatching {
                val socket = ServerSocket(port).apply {
                    reuseAddress = true
                    soTimeout = TransferProtocol.SOCKET_READ_TIMEOUT_MS
                }
                activeServerSocket = socket
                _networkStatus.value = readNetworkStatus()
                _connectionState.value = ConnectionState.Advertising(
                    localDeviceName = deviceName,
                    port = socket.localPort
                )
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { error ->
                    _connectionState.value = ConnectionState.Error(
                        reason = error.message ?: "Unable to bind local receive port $port",
                        recoverable = true
                    )
                    AppResult.Failure(AppError.Network("Failed to start local receive listener", error))
                }
            )
        }

    suspend fun stopReceiving(): AppResult<Unit> = withContext(dispatchers.io) {
        stopReceivingInternal()
        _connectionState.value = ConnectionState.Idle
        AppResult.Success(Unit)
    }

    fun generatePairingSession(deviceName: String, port: Int): PairingSessionInfo {
        val status = refreshNetworkStatus()
        val pin = (100_000 + secureRandom.nextInt(900_000)).toString()
        val encodedName = deviceName.trim().replace(" ", "_")
        val hostPart = status.localIpv4Address ?: "local"
        val uri = "shareflow://pair?v=${TransferProtocol.PROTOCOL_VERSION}&name=$encodedName&host=$hostPart&port=$port&pin=$pin"
        return PairingSessionInfo(
            deviceName = deviceName,
            hostAddress = status.localIpv4Address,
            port = port,
            pairingPin = pin,
            pairingUri = uri
        )
    }

    fun addSelectedFiles(
        entries: List<LocalFileEntry>,
        relativePathsByUri: Map<String, String> = emptyMap()
    ) {
        if (entries.isEmpty()) return
        _selectedSendFiles.update { current ->
            val existingUris = current.mapTo(mutableSetOf()) { it.uriString }
            current + entries.filter { it.uriString !in existingUris }
        }
        if (relativePathsByUri.isNotEmpty()) {
            _selectedRelativePaths.update { current -> current + relativePathsByUri }
        }
        recordRecentFiles(entries)
    }

    fun removeSelectedFile(uriString: String) {
        _selectedSendFiles.update { current -> current.filterNot { it.uriString == uriString } }
        _selectedRelativePaths.update { current -> current - uriString }
    }

    fun clearSelectedFiles() {
        _selectedSendFiles.value = emptyList()
        _selectedRelativePaths.value = emptyMap()
    }

    fun recordRecentFiles(entries: List<LocalFileEntry>) {
        if (entries.isEmpty()) return
        _recentFiles.update { current ->
            val merged = (entries + current).distinctBy { it.uriString }
            merged.take(MAX_RECENT_FILES)
        }
    }

    private fun stopReceivingInternal() {
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
    }

    private fun readNetworkStatus(): DeviceNetworkStatus {
        val isWifiConnected = runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val capabilities = cm?.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)

        val isWifiEnabled = runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.isWifiEnabled == true
        }.getOrDefault(isWifiConnected) || isWifiConnected

        val ipv4 = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            interfaces.toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull()

        return DeviceNetworkStatus(
            isWifiConnected = isWifiConnected,
            localIpv4Address = ipv4,
            isWifiEnabled = isWifiEnabled,
            hasCheckedInitialState = true
        )
    }

    companion object {
        private const val MAX_RECENT_FILES = 20
    }
}
