package com.example.shareflow.network.coordinator

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoverySnapshot
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.LocalHotspotInfo
import com.example.shareflow.domain.model.PairingPayload
import com.example.shareflow.domain.model.PeerConnectionStatus
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.security.DeviceIdentityManager
import com.example.shareflow.network.security.EncryptedPeerChannel
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.PeerHandshakeProtocol
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.strategy.ConnectionStrategy
import com.example.shareflow.network.strategy.LocalHotspotStrategy
import com.example.shareflow.network.strategy.PeerDiscoveryRegistry
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Independent orchestrator coordinating multiple [ConnectionStrategy] transports
 * (Wi-Fi Direct, Local Wi-Fi NSD, Local-Only Hotspot), QR pairing handshakes,
 * mutual device authentication, explicit user approval, and encrypted peer sessions.
 */
interface ConnectionCoordinator {
    val discoveredPeers: StateFlow<List<PeerDevice>>
    val discoveryStatus: StateFlow<DiscoveryStatus>
    val strategyStatuses: StateFlow<List<StrategyStatus>>
    val isAdvertising: StateFlow<Boolean>
    val activeHotspot: StateFlow<LocalHotspotInfo?>
    val discoverySnapshot: StateFlow<DiscoverySnapshot>
    val connectionState: StateFlow<ConnectionState>
    val pendingIncomingRequest: StateFlow<IncomingConnectionRequest?>
    val activePairingPayload: StateFlow<PairingPayload?>
    val activeChannel: EncryptedPeerChannel?

    fun getLocalIdentity(): LocalDeviceIdentity
    fun refreshCapabilities(): List<StrategyStatus>

    suspend fun startDiscovery(
        preferredStrategies: Set<ConnectionType>,
        timeoutMillis: Long
    ): AppResult<Unit>

    suspend fun stopDiscovery(): AppResult<Unit>

    suspend fun startAdvertising(
        deviceName: String,
        port: Int,
        enableLocalHotspot: Boolean
    ): AppResult<Int>

    suspend fun stopAdvertising(): AppResult<Unit>

    suspend fun generateQrPairingSession(
        deviceName: String,
        port: Int,
        ttlMillis: Long
    ): AppResult<PairingPayload>

    fun encodePairingUri(payload: PairingPayload): String
    fun parsePairingUri(rawUri: String): AppResult<PairingPayload>

    suspend fun connectToPeer(
        peer: PeerDevice,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected>

    suspend fun connectViaQrPayload(
        rawQrUri: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected>

    suspend fun approveIncomingConnection(
        requestId: String,
        rememberAsTrusted: Boolean
    ): AppResult<Unit>

    suspend fun rejectIncomingConnection(requestId: String): AppResult<Unit>

    suspend fun disconnect(): AppResult<Unit>
}

class DefaultConnectionCoordinator(
    private val strategies: Map<ConnectionType, ConnectionStrategy>,
    private val identityManager: DeviceIdentityManager,
    private val pairingTokenManager: PairingTokenManager,
    private val replayProtectionCache: ReplayProtectionCache,
    private val trustedDeviceRepository: TrustedDeviceRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = SystemClock()
) : ConnectionCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val connectionMutex = Mutex()

    private val handshakeProtocol = PeerHandshakeProtocol(
        identityManager = identityManager,
        pairingTokenManager = pairingTokenManager,
        replayCache = replayProtectionCache,
        clock = clock
    )

    private val peerRegistry = PeerDiscoveryRegistry(
        localDeviceIdProvider = { identityManager.getLocalIdentity().deviceId },
        clock = clock
    )

    private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
    override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

    private val _discoveryStatus = MutableStateFlow<DiscoveryStatus>(DiscoveryStatus.Idle)
    override val discoveryStatus: StateFlow<DiscoveryStatus> = _discoveryStatus.asStateFlow()

    private val _strategyStatuses = MutableStateFlow<List<StrategyStatus>>(emptyList())
    override val strategyStatuses: StateFlow<List<StrategyStatus>> = _strategyStatuses.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    override val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _advertisedPort = MutableStateFlow<Int?>(null)

    private val _activeHotspot = MutableStateFlow<LocalHotspotInfo?>(null)
    override val activeHotspot: StateFlow<LocalHotspotInfo?> = _activeHotspot.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pendingIncomingRequest = MutableStateFlow<IncomingConnectionRequest?>(null)
    override val pendingIncomingRequest: StateFlow<IncomingConnectionRequest?> =
        _pendingIncomingRequest.asStateFlow()

    private val _activePairingPayload = MutableStateFlow<PairingPayload?>(null)
    override val activePairingPayload: StateFlow<PairingPayload?> = _activePairingPayload.asStateFlow()

    @Volatile
    override var activeChannel: EncryptedPeerChannel? = null
        private set

    override val discoverySnapshot: StateFlow<DiscoverySnapshot> = combine(
        _discoveredPeers,
        _discoveryStatus,
        _isAdvertising,
        _advertisedPort,
        _strategyStatuses,
        _activeHotspot
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        DiscoverySnapshot(
            peers = values[0] as List<PeerDevice>,
            status = values[1] as DiscoveryStatus,
            isAdvertising = values[2] as Boolean,
            advertisedPort = values[3] as Int?,
            strategyStatuses = values[4] as List<StrategyStatus>,
            activeHotspot = values[5] as LocalHotspotInfo?
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = DiscoverySnapshot()
    )

    private val trustedFingerprintsByDeviceId = ConcurrentHashMap<String, String>()
    private val pendingApprovalDecisions = ConcurrentHashMap<String, CompletableDeferred<Pair<Boolean, Boolean>>>()

    private var discoveryTimeoutJob: Job? = null
    private var serverAcceptJob: Job? = null
    private var activeServerSocket: ServerSocket? = null
    private var advertisedDeviceName: String = "ShareFlow"

    init {
        observeTrustedDevices()
        observeStrategies()
        refreshCapabilities()
    }

    override fun getLocalIdentity(): LocalDeviceIdentity = identityManager.getLocalIdentity()

    override fun refreshCapabilities(): List<StrategyStatus> {
        val list = ConnectionType.entries.mapNotNull { type ->
            val strategy = strategies[type] ?: return@mapNotNull null
            strategy.checkAvailability()
            strategy.status.value
        }
        _strategyStatuses.value = list
        return list
    }

    override suspend fun startDiscovery(
        preferredStrategies: Set<ConnectionType>,
        timeoutMillis: Long
    ): AppResult<Unit> {
        discoveryTimeoutJob?.cancel()
        val identity = getLocalIdentity()
        val targets = preferredStrategies.mapNotNull { strategies[it] }
        if (targets.isEmpty()) {
            val err = "No discovery transport strategies configured"
            _discoveryStatus.value = DiscoveryStatus.Failed(err)
            return AppResult.Failure(AppError.NotSupported(err))
        }

        val failures = mutableListOf<String>()
        var startedCount = 0

        for (strategy in targets) {
            when (val res = strategy.startDiscovery(identity)) {
                is AppResult.Success -> startedCount++
                is AppResult.Failure -> failures.add("${strategy.connectionType.name}: ${res.error.message}")
            }
        }
        refreshCapabilities()

        if (startedCount == 0) {
            val message = failures.firstOrNull() ?: "No discovery strategy could start"
            _discoveryStatus.value = DiscoveryStatus.Failed(message)
            if (_connectionState.value is ConnectionState.Idle || _connectionState.value is ConnectionState.Discovering) {
                _connectionState.value = ConnectionState.Error(message, recoverable = true)
            }
            return AppResult.Failure(AppError.Network(message))
        }

        val safeTimeout = timeoutMillis.coerceAtLeast(1_000L)
        _discoveryStatus.value = DiscoveryStatus.Discovering(
            startedAtEpochMillis = clock.nowEpochMillis(),
            timeoutMillis = safeTimeout
        )
        if (_connectionState.value is ConnectionState.Idle || _connectionState.value is ConnectionState.Error) {
            _connectionState.value = ConnectionState.Discovering
        }

        discoveryTimeoutJob = scope.launch {
            delay(safeTimeout)
            stopDiscoveryStrategiesInternal()
            _discoveryStatus.value = DiscoveryStatus.TimedOut
            if (_connectionState.value is ConnectionState.Discovering) {
                _connectionState.value = ConnectionState.Idle
            }
        }

        return AppResult.Success(Unit)
    }

    override suspend fun stopDiscovery(): AppResult<Unit> {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        stopDiscoveryStrategiesInternal()
        _discoveryStatus.value = DiscoveryStatus.Idle
        if (_connectionState.value is ConnectionState.Discovering) {
            _connectionState.value = ConnectionState.Idle
        }
        return AppResult.Success(Unit)
    }

    override suspend fun startAdvertising(
        deviceName: String,
        port: Int,
        enableLocalHotspot: Boolean
    ): AppResult<Int> = withContext(dispatchers.io) {
        stopAdvertisingInternal()

        val identity = getLocalIdentity()
        val cleanName = deviceName.trim().ifBlank { "ShareFlow" }
        advertisedDeviceName = cleanName

        val serverSocket = try {
            ServerSocket(port).apply {
                reuseAddress = true
            }
        } catch (t: Throwable) {
            val msg = "Unable to bind ShareFlow receiver port $port: ${t.message ?: "port busy"}"
            _connectionState.value = ConnectionState.Error(msg, recoverable = true)
            return@withContext AppResult.Failure(AppError.Network(msg, t))
        }

        val boundPort = serverSocket.localPort
        activeServerSocket = serverSocket
        _advertisedPort.value = boundPort
        _isAdvertising.value = true
        _connectionState.value = ConnectionState.Advertising(
            localDeviceName = cleanName,
            port = boundPort
        )

        // Start incoming authenticated handshake accept loop
        serverAcceptJob = scope.launch(dispatchers.io) {
            acceptIncomingLoop(serverSocket, cleanName)
        }

        // Advertise on Local Wi-Fi NSD and Wi-Fi Direct when available (best-effort multi-transport)
        strategies[ConnectionType.LOCAL_LAN_NSD]?.startAdvertising(identity, cleanName, boundPort)
        strategies[ConnectionType.WIFI_DIRECT]?.let { p2p ->
            if (p2p.checkAvailability() == StrategyAvailability.Available) {
                p2p.startAdvertising(identity, cleanName, boundPort)
            }
        }

        if (enableLocalHotspot) {
            val hotspotStrategy = strategies[ConnectionType.HOTSPOT]
            if (hotspotStrategy != null) {
                val hotspotResult = hotspotStrategy.startAdvertising(identity, cleanName, boundPort)
                if (hotspotResult is AppResult.Failure) {
                    refreshCapabilities()
                    return@withContext hotspotResult
                }
            } else {
                return@withContext AppResult.Failure(AppError.NotSupported("Local Hotspot strategy unavailable"))
            }
        }

        refreshCapabilities()
        AppResult.Success(boundPort)
    }

    override suspend fun stopAdvertising(): AppResult<Unit> = withContext(dispatchers.io) {
        stopAdvertisingInternal()
        if (_connectionState.value is ConnectionState.Advertising) {
            _connectionState.value = ConnectionState.Idle
        }
        AppResult.Success(Unit)
    }

    override suspend fun generateQrPairingSession(
        deviceName: String,
        port: Int,
        ttlMillis: Long
    ): AppResult<PairingPayload> {
        val activePort = _advertisedPort.value ?: port
        val identity = getLocalIdentity()
        val hotspot = _activeHotspot.value
        val hostIp = hotspot?.hostAddress ?: resolveLocalIpv4Address()
        val preferredMode = if (hotspot != null) ConnectionType.HOTSPOT else ConnectionType.LOCAL_LAN_NSD

        val payload = pairingTokenManager.createPairingPayload(
            localIdentity = identity,
            displayName = deviceName,
            hostAddress = hostIp,
            port = activePort,
            preferredConnectionType = preferredMode,
            hotspotInfo = hotspot,
            ttlMillis = ttlMillis
        )
        _activePairingPayload.value = payload
        return AppResult.Success(payload)
    }

    override fun encodePairingUri(payload: PairingPayload): String =
        pairingTokenManager.encodeToUri(payload)

    override fun parsePairingUri(rawUri: String): AppResult<PairingPayload> =
        pairingTokenManager.parseFromUri(rawUri)

    override suspend fun connectToPeer(
        peer: PeerDevice,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> = connectInternal(
        peer = peer,
        localDeviceName = localDeviceName,
        sessionId = null,
        pairingToken = null,
        expectedFingerprint = peer.publicKeyFingerprint,
        hotspotPassphrase = null
    )

    override suspend fun connectViaQrPayload(
        rawQrUri: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> {
        val parsed = pairingTokenManager.parseFromUri(rawQrUri)
        if (parsed is AppResult.Failure) {
            _connectionState.value = ConnectionState.Error(parsed.error.message, recoverable = true)
            return parsed
        }
        val payload = (parsed as AppResult.Success).data
        if (payload.deviceId.equals(getLocalIdentity().deviceId, ignoreCase = true)) {
            val msg = "Cannot pair a device with itself"
            _connectionState.value = ConnectionState.Error(msg, recoverable = true)
            return AppResult.Failure(AppError.Validation(msg))
        }

        val peer = PeerDevice(
            deviceId = payload.deviceId,
            displayName = payload.displayName,
            connectionType = payload.preferredConnectionType,
            hostAddress = payload.hostAddress,
            port = payload.port,
            publicKeyFingerprint = payload.publicKeyFingerprint,
            hotspotSsid = payload.hotspotSsid,
            lastSeenEpochMillis = clock.nowEpochMillis(),
            protocolVersion = payload.protocolVersion
        )

        return connectInternal(
            peer = peer,
            localDeviceName = localDeviceName,
            sessionId = payload.sessionId,
            pairingToken = payload.pairingToken,
            expectedFingerprint = payload.publicKeyFingerprint,
            hotspotPassphrase = payload.hotspotPassphrase
        )
    }

    override suspend fun approveIncomingConnection(
        requestId: String,
        rememberAsTrusted: Boolean
    ): AppResult<Unit> {
        val deferred = pendingApprovalDecisions.remove(requestId)
            ?: return AppResult.Failure(AppError.NotFound("Connection request expired or already handled"))
        deferred.complete(true to rememberAsTrusted)
        if (_pendingIncomingRequest.value?.requestId == requestId) {
            _pendingIncomingRequest.value = null
        }
        return AppResult.Success(Unit)
    }

    override suspend fun rejectIncomingConnection(requestId: String): AppResult<Unit> {
        val deferred = pendingApprovalDecisions.remove(requestId)
            ?: return AppResult.Failure(AppError.NotFound("Connection request expired or already handled"))
        deferred.complete(false to false)
        if (_pendingIncomingRequest.value?.requestId == requestId) {
            _pendingIncomingRequest.value = null
        }
        return AppResult.Success(Unit)
    }

    override suspend fun disconnect(): AppResult<Unit> = withContext(dispatchers.io) {
        connectionMutex.withLock {
            val channel = activeChannel
            activeChannel = null
            if (channel != null) {
                runCatching {
                    channel.sendEncryptedFrame(
                        EncryptedPeerChannel.FRAME_DISCONNECT,
                        "BYE".toByteArray(Charsets.UTF_8)
                    )
                }
                channel.close()
                updatePeerStatus(channel.remotePeer.deviceId, PeerConnectionStatus.AVAILABLE)
            }
            strategies.values.forEach { strategy ->
                if (strategy.connectionType == ConnectionType.WIFI_DIRECT ||
                    strategy.connectionType == ConnectionType.HOTSPOT
                ) {
                    runCatching { strategy.releaseTransport() }
                }
            }
            _connectionState.value = if (_isAdvertising.value && _advertisedPort.value != null) {
                ConnectionState.Advertising(advertisedDeviceName, _advertisedPort.value!!)
            } else {
                ConnectionState.Idle
            }
        }
        AppResult.Success(Unit)
    }

    private suspend fun connectInternal(
        peer: PeerDevice,
        localDeviceName: String,
        sessionId: String?,
        pairingToken: String?,
        expectedFingerprint: String?,
        hotspotPassphrase: String?
    ): AppResult<ConnectionState.Connected> = withContext(dispatchers.io) {
        connectionMutex.withLock {
            val strategy = strategies[peer.connectionType]
                ?: return@withLock AppResult.Failure(
                    AppError.NotSupported("Transport ${peer.connectionType} is not available")
                )

            updatePeerStatus(peer.deviceId, PeerConnectionStatus.CONNECTING)
            _connectionState.value = ConnectionState.Connecting(peer)

            val endpointResult = strategy.prepareEndpointForPeer(peer, hotspotPassphrase)
            if (endpointResult is AppResult.Failure) {
                updatePeerStatus(peer.deviceId, PeerConnectionStatus.AVAILABLE)
                _connectionState.value = ConnectionState.Error(endpointResult.error.message, recoverable = true)
                return@withLock endpointResult
            }
            val endpoint = (endpointResult as AppResult.Success).data

            val socket = try {
                val created = endpoint.boundNetwork?.socketFactory?.createSocket() ?: Socket()
                created.connect(
                    InetSocketAddress(endpoint.hostAddress, endpoint.port),
                    TransferProtocol.SOCKET_CONNECT_TIMEOUT_MS
                )
                created
            } catch (t: Throwable) {
                val msg = "Unable to reach ${peer.displayName} (${endpoint.hostAddress}:${endpoint.port}): ${t.message ?: "connection refused"}"
                updatePeerStatus(peer.deviceId, PeerConnectionStatus.AVAILABLE)
                _connectionState.value = ConnectionState.Error(msg, recoverable = true)
                return@withLock AppResult.Failure(AppError.Network(msg, t))
            }

            updatePeerStatus(peer.deviceId, PeerConnectionStatus.AWAITING_APPROVAL)
            _connectionState.value = ConnectionState.AwaitingApproval(
                peer = peer,
                sasVerificationCode = ""
            )

            val handshakeResult = if (sessionId != null) {
                handshakeProtocol.performClientHandshake(
                    socket = socket,
                    localDisplayName = localDeviceName,
                    targetTransport = endpoint.connectionType,
                    sessionId = sessionId,
                    pairingToken = pairingToken,
                    expectedServerFingerprint = expectedFingerprint
                )
            } else {
                handshakeProtocol.performClientHandshake(
                    socket = socket,
                    localDisplayName = localDeviceName,
                    targetTransport = endpoint.connectionType,
                    pairingToken = pairingToken,
                    expectedServerFingerprint = expectedFingerprint
                )
            }

            when (handshakeResult) {
                is AppResult.Failure -> {
                    val status = if (handshakeResult.error is AppError.PermissionDenied) {
                        PeerConnectionStatus.REJECTED
                    } else {
                        PeerConnectionStatus.AVAILABLE
                    }
                    updatePeerStatus(peer.deviceId, status)
                    _connectionState.value = ConnectionState.Error(
                        reason = handshakeResult.error.message,
                        recoverable = true
                    )
                    handshakeResult
                }

                is AppResult.Success -> {
                    activeChannel?.close()
                    val channel = handshakeResult.data
                    activeChannel = channel
                    val connectedPeer = channel.remotePeer.copy(
                        isTrusted = isCryptographicallyTrusted(
                            channel.remotePeer.deviceId,
                            channel.remotePeer.publicKeyFingerprint
                        ),
                        connectionStatus = PeerConnectionStatus.CONNECTED
                    )
                    updatePeerStatus(connectedPeer.deviceId, PeerConnectionStatus.CONNECTED)
                    val connectedState = ConnectionState.Connected(
                        peer = connectedPeer,
                        sessionId = channel.sessionId,
                        sasVerificationCode = channel.sasVerificationCode,
                        isEncrypted = true,
                        transport = endpoint.connectionType
                    )
                    _connectionState.value = connectedState
                    AppResult.Success(connectedState)
                }
            }
        }
    }

    private suspend fun acceptIncomingLoop(serverSocket: ServerSocket, localDisplayName: String) {
        while (scope.isActive && !serverSocket.isClosed) {
            val clientSocket = try {
                serverSocket.accept()
            } catch (_: Throwable) {
                if (serverSocket.isClosed) break else continue
            }

            scope.launch(dispatchers.io) {
                handleIncomingSocket(clientSocket, localDisplayName)
            }
        }
    }

    private suspend fun handleIncomingSocket(socket: Socket, localDisplayName: String) {
        val currentSettings = runCatching { settingsRepository.settings.first() }.getOrNull()
        val autoAcceptTrusted = currentSettings?.autoAcceptTrustedDevices == true
        var shouldRememberTrusted = false

        val result = handshakeProtocol.performServerHandshake(
            socket = socket,
            localDisplayName = localDisplayName,
            requirePairingToken = false,
            trustedLookup = { deviceId, fingerprint ->
                isCryptographicallyTrusted(deviceId, fingerprint)
            },
            approvalCallback = { request ->
                if (request.isPreviouslyTrusted && autoAcceptTrusted) {
                    true
                } else {
                    val decisionDeferred = CompletableDeferred<Pair<Boolean, Boolean>>()
                    pendingApprovalDecisions[request.requestId] = decisionDeferred
                    _pendingIncomingRequest.value = request
                    val outcome = withTimeoutOrNull(TransferProtocol.APPROVAL_TIMEOUT_MS) {
                        decisionDeferred.await()
                    }
                    pendingApprovalDecisions.remove(request.requestId)
                    if (_pendingIncomingRequest.value?.requestId == request.requestId) {
                        _pendingIncomingRequest.value = null
                    }
                    if (outcome != null) {
                        shouldRememberTrusted = outcome.second
                        outcome.first
                    } else {
                        false
                    }
                }
            }
        )

        when (result) {
            is AppResult.Success -> {
                val channel = result.data
                activeChannel?.close()
                activeChannel = channel
                if (shouldRememberTrusted) {
                    trustedDeviceRepository.saveTrustedDevice(
                        channel.remotePeer.copy(isTrusted = true)
                    )
                }
                val connectedPeer = channel.remotePeer.copy(
                    isTrusted = shouldRememberTrusted || isCryptographicallyTrusted(
                        channel.remotePeer.deviceId,
                        channel.remotePeer.publicKeyFingerprint
                    ),
                    connectionStatus = PeerConnectionStatus.CONNECTED
                )
                updatePeerStatus(connectedPeer.deviceId, PeerConnectionStatus.CONNECTED)
                _connectionState.value = ConnectionState.Connected(
                    peer = connectedPeer,
                    sessionId = channel.sessionId,
                    sasVerificationCode = channel.sasVerificationCode,
                    isEncrypted = true,
                    transport = connectedPeer.connectionType
                )
            }

            is AppResult.Failure -> {
                // Unauthorized or declined peer was rejected cleanly; keep advertising listener active
            }
        }
    }

    private fun observeTrustedDevices() {
        scope.launch {
            trustedDeviceRepository.observeTrustedDevices().collect { trustedList ->
                trustedFingerprintsByDeviceId.clear()
                trustedList.forEach { device ->
                    val fp = device.publicKeyFingerprint
                    if (!fp.isNullOrBlank()) {
                        trustedFingerprintsByDeviceId[device.deviceId.lowercase()] = fp.lowercase()
                    }
                }
                publishPeersWithTrust()
            }
        }
    }

    private fun observeStrategies() {
        strategies.forEach { (type, strategy) ->
            scope.launch {
                strategy.discoveredPeers.collect { peers ->
                    peerRegistry.updateStrategyPeers(type, peers)
                    publishPeersWithTrust()
                }
            }
            scope.launch {
                strategy.status.collect {
                    _strategyStatuses.value = ConnectionType.entries.mapNotNull { t ->
                        strategies[t]?.status?.value
                    }
                }
            }
            if (strategy is LocalHotspotStrategy) {
                scope.launch {
                    strategy.activeHotspot.collect { hotspotInfo ->
                        _activeHotspot.value = hotspotInfo
                    }
                }
            }
        }
    }

    private fun publishPeersWithTrust() {
        _discoveredPeers.value = peerRegistry.snapshot().map { peer ->
            val trusted = isCryptographicallyTrusted(peer.deviceId, peer.publicKeyFingerprint)
            if (peer.isTrusted == trusted) peer else peer.copy(isTrusted = trusted)
        }
    }

    private fun updatePeerStatus(deviceId: String, status: PeerConnectionStatus) {
        peerRegistry.setPeerConnectionStatus(deviceId, status)
        publishPeersWithTrust()
    }

    /**
     * Strict cryptographic trust check: BOTH deviceId AND full publicKeyFingerprint must match.
     * Never trusts a peer based on display name.
     */
    private fun isCryptographicallyTrusted(deviceId: String, publicKeyFingerprint: String?): Boolean {
        if (publicKeyFingerprint.isNullOrBlank()) return false
        val storedFp = trustedFingerprintsByDeviceId[deviceId.lowercase()] ?: return false
        return storedFp.equals(publicKeyFingerprint, ignoreCase = true)
    }

    private suspend fun stopDiscoveryStrategiesInternal() {
        strategies.values.forEach { strategy ->
            runCatching { strategy.stopDiscovery() }
        }
        refreshCapabilities()
    }

    private suspend fun stopAdvertisingInternal() {
        serverAcceptJob?.cancel()
        serverAcceptJob = null
        runCatching { activeServerSocket?.close() }
        activeServerSocket = null
        _advertisedPort.value = null
        _isAdvertising.value = false
        pairingTokenManager.invalidateAll()
        pendingApprovalDecisions.values.forEach { it.complete(false to false) }
        pendingApprovalDecisions.clear()
        _pendingIncomingRequest.value = null

        strategies.values.forEach { strategy ->
            runCatching { strategy.stopAdvertising() }
        }
        refreshCapabilities()
    }

    private fun resolveLocalIpv4Address(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        interfaces.toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun shutdown() {
        runCatching { activeServerSocket?.close() }
        runCatching { activeChannel?.close() }
        scope.cancel()
    }
}
