package com.example.shareflow.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class PairingSessionInfo(
    val deviceName: String,
    val hostAddress: String?,
    val port: Int,
    val pairingPin: String,
    val pairingUri: String
)

data class DeviceNetworkStatus(
    val isWifiConnected: Boolean,
    val localIpv4Address: String?
)

/**
 * UI-independent controller managing real local TCP receive socket readiness,
 * real device Wi-Fi connectivity status, ephemeral pairing codes, and selected SAF files queue.
 * Never fabricates devices or fake transfer progress.
 */
class LocalSessionController(
    context: Context,
    private val dispatchers: DispatcherProvider
) {
    private val appContext = context.applicationContext
    private val secureRandom = SecureRandom()

    private var activeServerSocket: ServerSocket? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _selectedSendFiles = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val selectedSendFiles: StateFlow<List<LocalFileEntry>> = _selectedSendFiles.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<LocalFileEntry>>(emptyList())
    val recentFiles: StateFlow<List<LocalFileEntry>> = _recentFiles.asStateFlow()

    private val _networkStatus = MutableStateFlow(readNetworkStatus())
    val networkStatus: StateFlow<DeviceNetworkStatus> = _networkStatus.asStateFlow()

    fun refreshNetworkStatus(): DeviceNetworkStatus {
        val status = readNetworkStatus()
        _networkStatus.value = status
        return status
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

    fun addSelectedFiles(entries: List<LocalFileEntry>) {
        if (entries.isEmpty()) return
        _selectedSendFiles.update { current ->
            val existingUris = current.mapTo(mutableSetOf()) { it.uriString }
            current + entries.filter { it.uriString !in existingUris }
        }
        recordRecentFiles(entries)
    }

    fun removeSelectedFile(uriString: String) {
        _selectedSendFiles.update { current -> current.filterNot { it.uriString == uriString } }
    }

    fun clearSelectedFiles() {
        _selectedSendFiles.value = emptyList()
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
        val isWifi = runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val capabilities = cm?.getNetworkCapabilities(activeNetwork)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)

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
            isWifiConnected = isWifi,
            localIpv4Address = ipv4
        )
    }

    companion object {
        private const val MAX_RECENT_FILES = 20
    }
}
