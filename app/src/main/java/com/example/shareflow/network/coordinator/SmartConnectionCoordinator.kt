package com.example.shareflow.network.coordinator

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ActiveLocalHotspotSession
import com.example.shareflow.domain.model.BoundWifiNetworkHandle
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoveredReceiverEndpoint
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.ReceiverDiscoveryMethod
import com.example.shareflow.domain.model.SmartConnectionDiagnostics
import com.example.shareflow.domain.model.SmartConnectionError
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.discovery.SessionServiceDiscovery
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.qr.SmartQrParseResult
import com.example.shareflow.network.strategy.ExistingLocalWiFiStrategy
import com.example.shareflow.network.strategy.ExistingWifiNetworkProvider
import com.example.shareflow.network.strategy.NetworkPermissionHelper
import com.example.shareflow.network.strategy.ReceiverHotspotController
import com.example.shareflow.network.strategy.SenderWifiConnector
import com.example.shareflow.network.strategy.WiFiDirectStrategy
import com.example.shareflow.network.strategy.WifiJoinOutcome
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SHAREit-grade Smart Connection Coordinator (Phases 1–5, 8–10).
 *
 * Strategy Priority:
 *   1. LocalOnlyHotspotStrategy (Receiver temporary local Wi-Fi AP)
 *   2. WiFiNetworkSpecifierStrategy (Sender auto-join + Network-bound sockets bypassing mobile data)
 *   3. WiFiDirectStrategy (Fallback when LocalOnlyHotspot / WifiNetworkSpecifier unavailable)
 *   4. ExistingLocalWiFiStrategy (Fallback when both devices share a local Wi-Fi router)
 */
class SmartConnectionCoordinator(
    private val baseCoordinator: DefaultConnectionCoordinator,
    private val receiverHotspotController: ReceiverHotspotController,
    private val senderWifiConnector: SenderWifiConnector,
    private val existingWifiProvider: ExistingWifiNetworkProvider,
    private val wifiDirectStrategy: WiFiDirectStrategy?,
    private val sessionDiscovery: SessionServiceDiscovery,
    private val qrProtocol: SmartQrPairingProtocol,
    private val permissionHelper: NetworkPermissionHelper,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock = SystemClock(),
    private val defaultSessionTtlMillis: Long = SmartQrPairingProtocol.DEFAULT_TTL_MILLIS
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val operationMutex = Mutex()
    private val senderScanInFlight = AtomicBoolean(false)

    private val _phase = MutableStateFlow(SmartConnectionPhase.IDLE)
    val phase: StateFlow<SmartConnectionPhase> = _phase.asStateFlow()

    private val _activeStrategy = MutableStateFlow<SmartStrategyType?>(null)
    val activeStrategy: StateFlow<SmartStrategyType?> = _activeStrategy.asStateFlow()

    private val _activeQrPayload = MutableStateFlow<SmartQrPairingPayload?>(null)
    val activeQrPayload: StateFlow<SmartQrPairingPayload?> = _activeQrPayload.asStateFlow()

    private val _activeQrUri = MutableStateFlow("")
    val activeQrUri: StateFlow<String> = _activeQrUri.asStateFlow()

    private val _activeManualCode = MutableStateFlow("")
    val activeManualCode: StateFlow<String> = _activeManualCode.asStateFlow()

    private val _lastError = MutableStateFlow<SmartConnectionError?>(null)
    val lastError: StateFlow<SmartConnectionError?> = _lastError.asStateFlow()

    private val _diagnostics = MutableStateFlow(SmartConnectionDiagnostics())
    val diagnostics: StateFlow<SmartConnectionDiagnostics> = _diagnostics.asStateFlow()

    private var expiryWatcherJob: Job? = null
    private var lastSubmittedQrInput: String? = null
    private var lastSubmittedQrTimestamp: Long = 0L
    private var lastReceiverDeviceName: String = "ShareFlow"
    private var activeBoundNetwork: BoundWifiNetworkHandle? = null

    init {
        observeStateStreams()
    }

    /**
     * Phase 2 Receiver Lifecycle:
     * Permissions -> LocalOnlyHotspot (or fallback) -> Bind & Verify TCP Server
     * -> NSD + Authenticated UDP advertising -> Generate signed v3 QR ONLY when ready -> WAITING_FOR_QR.
     */
    suspend fun startSmartReceiving(
        deviceName: String,
        preferredPort: Int,
        preferHotspot: Boolean = true,
        ttlMillis: Long = defaultSessionTtlMillis
    ): AppResult<SmartQrPairingPayload> = withContext(dispatchers.io) {
        operationMutex.withLock {
            cancelSessionLocked(targetPhase = SmartConnectionPhase.IDLE, clearError = true)
            lastReceiverDeviceName = deviceName.trim().ifBlank { "ShareFlow" }

            var chosenStrategy: SmartStrategyType = SmartStrategyType.EXISTING_LOCAL_WIFI
            var hotspotSession: ActiveLocalHotspotSession? = null

            if (preferHotspot) {
                when (val hotspotAvail = receiverHotspotController.checkAvailability()) {
                    StrategyAvailability.Available -> {
                        updatePhase(SmartConnectionPhase.STARTING_HOTSPOT, SmartStrategyType.LOCAL_ONLY_HOTSPOT)
                        val hotspotRes = receiverHotspotController.startHotspot(
                            onStoppedUnexpectedly = {
                                scope.launch { handleUnexpectedReceiverHotspotDrop() }
                            }
                        )
                        when (hotspotRes) {
                            is AppResult.Success -> {
                                hotspotSession = hotspotRes.data
                                chosenStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT
                                updatePhase(SmartConnectionPhase.HOTSPOT_READY, chosenStrategy)
                            }
                            is AppResult.Failure -> {
                                val fallback = selectReceiverFallbackStrategy()
                                if (fallback != null) {
                                    chosenStrategy = fallback
                                    recordNonFatalNotice(
                                        SmartConnectionErrorCode.HOTSPOT_CREATION_FAILED,
                                        hotspotRes.error.message
                                    )
                                } else {
                                    return@withLock failLocked(
                                        code = mapAppErrorToSmartCode(
                                            hotspotRes.error,
                                            SmartConnectionErrorCode.HOTSPOT_CREATION_FAILED
                                        ),
                                        detail = hotspotRes.error.message
                                    )
                                }
                            }
                        }
                    }

                    is StrategyAvailability.PermissionRequired -> {
                        val fallback = selectReceiverFallbackStrategy()
                        if (fallback == SmartStrategyType.EXISTING_LOCAL_WIFI) {
                            chosenStrategy = fallback
                        } else {
                            return@withLock failLocked(
                                code = SmartConnectionErrorCode.PERMISSION_DENIED,
                                detail = "Missing nearby Wi-Fi permissions: ${hotspotAvail.missingPermissions.joinToString()}"
                            )
                        }
                    }

                    StrategyAvailability.LocationServicesDisabled -> {
                        val fallback = selectReceiverFallbackStrategy()
                        if (fallback == SmartStrategyType.EXISTING_LOCAL_WIFI) {
                            chosenStrategy = fallback
                        } else {
                            return@withLock failLocked(
                                code = SmartConnectionErrorCode.PERMISSION_DENIED,
                                detail = "Enable Location Services on Android 12 or lower to start a Local-Only Hotspot"
                            )
                        }
                    }

                    StrategyAvailability.WifiDisabled -> {
                        return@withLock failLocked(
                            code = SmartConnectionErrorCode.WIFI_DISABLED,
                            detail = "Wi-Fi is turned off. Enable Wi-Fi and retry."
                        )
                    }

                    is StrategyAvailability.Unsupported,
                    is StrategyAvailability.Error -> {
                        val fallback = selectReceiverFallbackStrategy()
                        if (fallback != null) {
                            chosenStrategy = fallback
                        } else {
                            val reason = (hotspotAvail as? StrategyAvailability.Unsupported)?.reason
                                ?: (hotspotAvail as? StrategyAvailability.Error)?.reason
                                ?: "Wi-Fi hotspot hardware is unavailable"
                            return@withLock failLocked(
                                code = SmartConnectionErrorCode.UNSUPPORTED_HARDWARE,
                                detail = reason
                            )
                        }
                    }
                }
            } else {
                chosenStrategy = selectReceiverFallbackStrategy()
                    ?: return@withLock failLocked(
                        code = SmartConnectionErrorCode.WIFI_DISABLED,
                        detail = "Connect to Wi-Fi or enable Local Hotspot to receive files"
                    )
            }

            // Start & verify TCP server BEFORE generating QR code
            updatePhase(SmartConnectionPhase.STARTING_SERVER, chosenStrategy)
            val serverResult = baseCoordinator.openTcpServerForSmartSession(
                deviceName = lastReceiverDeviceName,
                preferredPort = preferredPort,
                onIncomingHandshakeStart = {
                    if (_phase.value == SmartConnectionPhase.WAITING_FOR_QR ||
                        _phase.value == SmartConnectionPhase.SERVER_READY
                    ) {
                        updatePhase(SmartConnectionPhase.AUTHENTICATING, _activeStrategy.value)
                        updateDiagnostics { it.copy(authenticationStatus = "AUTHENTICATING_PEER") }
                    }
                },
                onIncomingHandshakeComplete = { outcome ->
                    scope.launch { handleReceiverHandshakeOutcome(outcome) }
                }
            )

            if (serverResult is AppResult.Failure) {
                receiverHotspotController.stopHotspot()
                return@withLock failLocked(
                    code = SmartConnectionErrorCode.SERVER_UNAVAILABLE,
                    detail = serverResult.error.message
                )
            }

            val (boundPort, usedFallbackPort) = (serverResult as AppResult.Success).data
            if (!baseCoordinator.isTcpServerListening()) {
                receiverHotspotController.stopHotspot()
                return@withLock failLocked(
                    code = SmartConnectionErrorCode.SERVER_UNAVAILABLE,
                    detail = "ShareFlow TCP server is not listening after bind"
                )
            }
            if (usedFallbackPort) {
                recordNonFatalNotice(
                    SmartConnectionErrorCode.PORT_CONFLICT,
                    "Port $preferredPort was busy; automatically bound to port $boundPort"
                )
            }

            updatePhase(SmartConnectionPhase.SERVER_READY, chosenStrategy)

            val (ifaceName, localIp) = when (chosenStrategy) {
                SmartStrategyType.LOCAL_ONLY_HOTSPOT -> {
                    val inspected = receiverHotspotController.inspectActiveApInterface()
                    (hotspotSession?.interfaceName ?: inspected.first) to
                        (hotspotSession?.localIpv4Address ?: inspected.second)
                }
                else -> {
                    val handle = existingWifiProvider.getActiveWifiNetworkHandle()
                    if (handle != null) {
                        activeBoundNetwork = handle
                        handle.interfaceName to handle.localIpv4Address
                    } else if (existingWifiProvider is ExistingLocalWiFiStrategy) {
                        existingWifiProvider.resolveCurrentLocalInterface()
                    } else {
                        null to null
                    }
                }
            }

            if (chosenStrategy == SmartStrategyType.WIFI_DIRECT) {
                wifiDirectStrategy?.startAdvertising(
                    localIdentity = baseCoordinator.getLocalIdentity(),
                    deviceName = lastReceiverDeviceName,
                    port = boundPort
                )
            }

            val shortId = baseCoordinator.getLocalIdentity().shortDeviceId
            val uniqueServiceName = "ShareFlow-$shortId-${(clock.nowEpochMillis() % 10000).toString().padStart(4, '0')}"
            val payload = qrProtocol.createSessionPayload(
                displayName = lastReceiverDeviceName,
                serviceName = uniqueServiceName,
                servicePort = boundPort,
                preferredStrategy = chosenStrategy,
                ssid = hotspotSession?.ssid,
                passphrase = hotspotSession?.passphrase,
                securityType = hotspotSession?.securityType ?: HotspotSecurityType.UNKNOWN,
                hostAddressHint = localIp,
                ttlMillis = ttlMillis
            )

            baseCoordinator.registerSmartPairingSession(payload)
            val epkHash = qrProtocol.computeEphemeralKeyHash(payload.ephemeralPublicKey)
            sessionDiscovery.startSessionAdvertising(payload, epkHash)

            _activeQrPayload.value = payload
            _activeQrUri.value = qrProtocol.encodeToUri(payload)
            _activeManualCode.value = qrProtocol.encodeManualPairingCode(payload)

            updateDiagnostics {
                it.copy(
                    activeStrategy = chosenStrategy,
                    hotspotState = receiverHotspotController.hotspotStateLabel.value,
                    ssidAvailable = !payload.ssid.isNullOrBlank(),
                    ssidLabel = payload.ssid,
                    activeNetworkId = activeBoundNetwork?.networkId,
                    localIp = localIp,
                    remoteIp = null,
                    activeNetworkInterface = ifaceName,
                    serverPort = boundPort,
                    isServerListening = baseCoordinator.isTcpServerListening(),
                    nsdDiscoveryStatus = sessionDiscovery.statusLabel.value,
                    qrSessionExpiresAtEpochMillis = payload.expiresAtEpochMillis,
                    authenticationStatus = "WAITING_FOR_PEER"
                )
            }

            updatePhase(SmartConnectionPhase.WAITING_FOR_QR, chosenStrategy)
            startExpiryWatcher(payload)
            AppResult.Success(payload)
        }
    }

    suspend fun regenerateActiveReceiverQr(
        ttlMillis: Long = defaultSessionTtlMillis
    ): AppResult<SmartQrPairingPayload> = withContext(dispatchers.io) {
        operationMutex.withLock {
            val port = baseCoordinator.currentServerPort()
            if (!baseCoordinator.isTcpServerListening() || port == null) {
                return@withLock AppResult.Failure(
                    AppError.Network("Receiver server is not active; start receiving first")
                )
            }
            val strategy = _activeStrategy.value ?: SmartStrategyType.EXISTING_LOCAL_WIFI
            val hotspot = receiverHotspotController.activeSession.value
            val previous = _activeQrPayload.value
            if (previous != null) {
                qrProtocol.invalidateSession(previous.sessionId)
                baseCoordinator.invalidateSmartPairingSession(previous.sessionId)
            }

            val (ifaceName, localIp) = if (hotspot != null) {
                val inspected = receiverHotspotController.inspectActiveApInterface()
                (hotspot.interfaceName ?: inspected.first) to (hotspot.localIpv4Address ?: inspected.second)
            } else {
                val handle = existingWifiProvider.getActiveWifiNetworkHandle()
                handle?.interfaceName to handle?.localIpv4Address
            }

            val shortId = baseCoordinator.getLocalIdentity().shortDeviceId
            val serviceName = "ShareFlow-$shortId-${(clock.nowEpochMillis() % 10000).toString().padStart(4, '0')}"
            val payload = qrProtocol.createSessionPayload(
                displayName = lastReceiverDeviceName,
                serviceName = serviceName,
                servicePort = port,
                preferredStrategy = strategy,
                ssid = hotspot?.ssid,
                passphrase = hotspot?.passphrase,
                securityType = hotspot?.securityType ?: HotspotSecurityType.UNKNOWN,
                hostAddressHint = localIp,
                ttlMillis = ttlMillis
            )

            baseCoordinator.registerSmartPairingSession(payload)
            sessionDiscovery.startSessionAdvertising(
                payload,
                qrProtocol.computeEphemeralKeyHash(payload.ephemeralPublicKey)
            )
            _activeQrPayload.value = payload
            _activeQrUri.value = qrProtocol.encodeToUri(payload)
            _activeManualCode.value = qrProtocol.encodeManualPairingCode(payload)
            _lastError.value = null
            updateDiagnostics {
                it.copy(
                    localIp = localIp ?: it.localIp,
                    activeNetworkInterface = ifaceName ?: it.activeNetworkInterface,
                    qrSessionExpiresAtEpochMillis = payload.expiresAtEpochMillis,
                    nsdDiscoveryStatus = sessionDiscovery.statusLabel.value,
                    authenticationStatus = "WAITING_FOR_PEER",
                    lastError = null
                )
            }
            updatePhase(SmartConnectionPhase.WAITING_FOR_QR, strategy)
            startExpiryWatcher(payload)
            AppResult.Success(payload)
        }
    }

    /**
     * Phase 3 + 4 Sender Auto-Connection Lifecycle:
     * Duplicate guard -> Signed v3 QR verify -> WifiNetworkSpecifier join (or fallback)
     * -> NSD/UDP discovery on bound Network -> Bound TCP socket -> Mutual ECDSA+ECDH+AES-256-GCM auth -> CONNECTED.
     */
    suspend fun connectViaSmartQr(
        rawQrOrManualCode: String,
        localDeviceName: String
    ): AppResult<ConnectionState.Connected> = withContext(dispatchers.io) {
        val trimmed = rawQrOrManualCode.trim()
        val now = clock.nowEpochMillis()

        if (trimmed == lastSubmittedQrInput && (now - lastSubmittedQrTimestamp) < DUPLICATE_SCAN_COOLDOWN_MS &&
            (_phase.value.isInProgress || _phase.value == SmartConnectionPhase.CONNECTED)
        ) {
            return@withContext AppResult.Failure(
                AppError.Validation("Duplicate QR scan ignored while connection is already active")
            )
        }
        if (!senderScanInFlight.compareAndSet(false, true)) {
            return@withContext AppResult.Failure(
                AppError.Validation("Connection attempt already in progress")
            )
        }

        try {
            operationMutex.withLock {
                lastSubmittedQrInput = trimmed
                lastSubmittedQrTimestamp = now
                _lastError.value = null
                updatePhase(SmartConnectionPhase.QR_SCANNED, null)

                val parsed = qrProtocol.parseAndValidate(
                    rawInput = trimmed,
                    localDeviceId = baseCoordinator.getLocalIdentity().deviceId
                )
                val payload = when (parsed) {
                    is SmartQrParseResult.Valid -> parsed.payload
                    is SmartQrParseResult.Rejected -> {
                        val targetPhase = if (parsed.errorCode == SmartConnectionErrorCode.STALE_OR_EXPIRED_QR) {
                            SmartConnectionPhase.EXPIRED
                        } else {
                            SmartConnectionPhase.FAILED
                        }
                        return@withLock failLocked(
                            code = parsed.errorCode,
                            detail = parsed.message,
                            overridePhase = targetPhase
                        )
                    }
                }

                updateDiagnostics {
                    it.copy(
                        ssidAvailable = !payload.ssid.isNullOrBlank(),
                        ssidLabel = payload.ssid,
                        qrSessionExpiresAtEpochMillis = payload.expiresAtEpochMillis,
                        authenticationStatus = "PENDING"
                    )
                }

                var boundHandle: BoundWifiNetworkHandle?
                var effectiveStrategy: SmartStrategyType

                if (payload.hasHotspotCredentials() && senderWifiConnector.isSupportedOnDevice()) {
                    effectiveStrategy = SmartStrategyType.WIFI_NETWORK_SPECIFIER
                    updatePhase(SmartConnectionPhase.REQUESTING_WIFI, effectiveStrategy)

                    val joinOutcome = senderWifiConnector.requestLocalHotspotConnection(
                        ssid = payload.ssid.orEmpty(),
                        passphrase = payload.passphrase,
                        securityType = payload.securityType,
                        onWaitingForApproval = {
                            updatePhase(SmartConnectionPhase.WAITING_FOR_APPROVAL, effectiveStrategy)
                        },
                        onJoiningNetwork = {
                            updatePhase(SmartConnectionPhase.JOINING_NETWORK, effectiveStrategy)
                        },
                        onNetworkLost = {
                            scope.launch { handleSenderNetworkLost() }
                        }
                    )

                    when (joinOutcome) {
                        is WifiJoinOutcome.Connected -> {
                            boundHandle = joinOutcome.handle
                            activeBoundNetwork = boundHandle
                            updateDiagnostics {
                                it.copy(
                                    activeStrategy = effectiveStrategy,
                                    activeNetworkId = boundHandle.networkId,
                                    activeNetworkInterface = boundHandle.interfaceName,
                                    localIp = boundHandle.localIpv4Address
                                )
                            }
                        }
                        is WifiJoinOutcome.Failed -> {
                            if (joinOutcome.errorCode == SmartConnectionErrorCode.USER_REJECTED_NETWORK) {
                                return@withLock failLocked(
                                    code = SmartConnectionErrorCode.USER_REJECTED_NETWORK,
                                    detail = joinOutcome.message,
                                    overridePhase = SmartConnectionPhase.CANCELLED
                                )
                            }
                            // Fallback to an active shared local Wi-Fi network if the temporary hotspot join timed out
                            val fallbackWifiHandle = existingWifiProvider.getActiveWifiNetworkHandle()
                            if (fallbackWifiHandle != null && !payload.hostAddressHint.isNullOrBlank()) {
                                recordNonFatalNotice(joinOutcome.errorCode, joinOutcome.message)
                                effectiveStrategy = SmartStrategyType.EXISTING_LOCAL_WIFI
                                boundHandle = fallbackWifiHandle
                                activeBoundNetwork = fallbackWifiHandle
                                updatePhase(SmartConnectionPhase.JOINING_NETWORK, effectiveStrategy)
                                updateDiagnostics {
                                    it.copy(
                                        activeStrategy = effectiveStrategy,
                                        activeNetworkId = fallbackWifiHandle.networkId,
                                        activeNetworkInterface = fallbackWifiHandle.interfaceName,
                                        localIp = fallbackWifiHandle.localIpv4Address
                                    )
                                }
                            } else {
                                return@withLock failLocked(
                                    code = joinOutcome.errorCode,
                                    detail = joinOutcome.message
                                )
                            }
                        }
                    }
                } else {
                    val wifiHandle = existingWifiProvider.getActiveWifiNetworkHandle()
                    if (wifiHandle == null && !permissionHelper.isWifiEnabled()) {
                        return@withLock failLocked(
                            code = SmartConnectionErrorCode.WIFI_DISABLED,
                            detail = "Enable Wi-Fi to connect to the receiving device"
                        )
                    }
                    effectiveStrategy = if (payload.preferredStrategy == SmartStrategyType.WIFI_DIRECT) {
                        SmartStrategyType.WIFI_DIRECT
                    } else {
                        SmartStrategyType.EXISTING_LOCAL_WIFI
                    }
                    boundHandle = wifiHandle
                    activeBoundNetwork = wifiHandle
                    updatePhase(SmartConnectionPhase.JOINING_NETWORK, effectiveStrategy)
                    updateDiagnostics {
                        it.copy(
                            activeStrategy = effectiveStrategy,
                            activeNetworkId = wifiHandle?.networkId,
                            activeNetworkInterface = wifiHandle?.interfaceName,
                            localIp = wifiHandle?.localIpv4Address
                        )
                    }
                }

                if (payload.isExpired(clock.nowEpochMillis())) {
                    senderWifiConnector.releaseNetwork()
                    activeBoundNetwork = null
                    return@withLock failLocked(
                        code = SmartConnectionErrorCode.SESSION_EXPIRED,
                        detail = "QR session expired before receiver discovery completed",
                        overridePhase = SmartConnectionPhase.EXPIRED
                    )
                }

                updatePhase(SmartConnectionPhase.DISCOVERING_RECEIVER, effectiveStrategy)
                val discoveryResult = sessionDiscovery.discoverReceiverForSession(
                    payload = payload,
                    boundNetwork = boundHandle
                )
                updateDiagnostics {
                    it.copy(nsdDiscoveryStatus = sessionDiscovery.statusLabel.value)
                }

                val endpoint: DiscoveredReceiverEndpoint = when (discoveryResult) {
                    is AppResult.Success -> discoveryResult.data
                    is AppResult.Failure -> {
                        val directHotspotGateway = boundHandle
                            ?.takeIf { it.isLocalOnlyHotspot }
                            ?.gatewayIpv4Address
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        val directHost = directHotspotGateway ?: payload.hostAddressHint?.trim()?.takeIf { it.isNotBlank() }
                        if (directHost != null && payload.servicePort in 1024..65535) {
                            updateDiagnostics {
                                it.copy(
                                    nsdDiscoveryStatus = if (directHotspotGateway != null) {
                                        "DIRECT_HOTSPOT_GATEWAY_FALLBACK"
                                    } else {
                                        "DIRECT_QR_HOST_FALLBACK"
                                    }
                                )
                            }
                            DiscoveredReceiverEndpoint(
                                hostAddress = directHost,
                                port = payload.servicePort,
                                serviceName = payload.serviceName,
                                method = ReceiverDiscoveryMethod.QR_HOST_HINT_VERIFIED
                            )
                        } else {
                            senderWifiConnector.releaseNetwork()
                            activeBoundNetwork = null
                            return@withLock failLocked(
                                code = SmartConnectionErrorCode.WRONG_NETWORK,
                                detail = discoveryResult.error.message
                            )
                        }
                    }
                }
                updateDiagnostics {
                    it.copy(
                        remoteIp = endpoint.hostAddress,
                        serverPort = endpoint.port
                    )
                }

                updatePhase(SmartConnectionPhase.AUTHENTICATING, effectiveStrategy)
                updateDiagnostics { it.copy(authenticationStatus = "AUTHENTICATING_MUTUAL_ECDSA") }

                val candidateHosts = buildList {
                    add(endpoint.hostAddress)
                    payload.hostAddressHint?.trim()
                        ?.takeIf { it.isNotBlank() && it != endpoint.hostAddress }
                        ?.let { add(it) }
                }
                var connectedHost = endpoint.hostAddress
                var lastConnectError: Throwable? = null
                var connectedSocket: Socket? = null

                outer@ for (attempt in 0 until 2) {
                    for (host in candidateHosts) {
                        val candidateSocket = runCatching {
                            val created = if (boundHandle != null) {
                                senderWifiConnector.createBoundTcpSocket(boundHandle)
                            } else {
                                Socket()
                            }
                            created.connect(
                                InetSocketAddress(host, endpoint.port),
                                TransferProtocol.SOCKET_CONNECT_TIMEOUT_MS
                            )
                            created
                        }
                        if (candidateSocket.isSuccess) {
                            connectedSocket = candidateSocket.getOrNull()
                            connectedHost = host
                            break@outer
                        } else {
                            lastConnectError = candidateSocket.exceptionOrNull()
                        }
                    }
                    if (attempt == 0) {
                        delay(400L)
                    }
                }

                val socket: Socket = connectedSocket ?: run {
                    senderWifiConnector.releaseNetwork()
                    activeBoundNetwork = null
                    return@withLock failLocked(
                        code = SmartConnectionErrorCode.SERVER_UNAVAILABLE,
                        detail = "Could not connect to receiver at ${endpoint.hostAddress}:${endpoint.port}: ${lastConnectError?.message ?: "unreachable"}"
                    )
                }
                if (connectedHost != endpoint.hostAddress) {
                    updateDiagnostics { it.copy(remoteIp = connectedHost) }
                }

                val transportType = when (effectiveStrategy) {
                    SmartStrategyType.LOCAL_ONLY_HOTSPOT,
                    SmartStrategyType.WIFI_NETWORK_SPECIFIER -> ConnectionType.HOTSPOT
                    SmartStrategyType.WIFI_DIRECT -> ConnectionType.WIFI_DIRECT
                    SmartStrategyType.EXISTING_LOCAL_WIFI -> ConnectionType.LOCAL_LAN_NSD
                }

                val peer = PeerDevice(
                    deviceId = payload.deviceId,
                    displayName = payload.displayName,
                    connectionType = transportType,
                    hostAddress = connectedHost,
                    port = endpoint.port,
                    publicKeyFingerprint = payload.publicKeyFingerprint,
                    hotspotSsid = payload.ssid,
                    lastSeenEpochMillis = clock.nowEpochMillis(),
                    protocolVersion = payload.protocolVersion
                )

                val authResult = baseCoordinator.authenticateOnBoundSocket(
                    socket = socket,
                    peer = peer,
                    localDeviceName = localDeviceName.trim().ifBlank { "ShareFlow" },
                    sessionId = payload.sessionId,
                    pairingToken = payload.pairingToken,
                    expectedFingerprint = payload.publicKeyFingerprint,
                    transport = transportType
                )

                when (authResult) {
                    is AppResult.Failure -> {
                        senderWifiConnector.releaseNetwork()
                        activeBoundNetwork = null
                        failLocked(
                            code = SmartConnectionErrorCode.AUTHENTICATION_FAILED,
                            detail = authResult.error.message
                        )
                    }

                    is AppResult.Success -> {
                        qrProtocol.markConsumedOnSender(payload.sessionId)
                        updateDiagnostics {
                            it.copy(
                                authenticationStatus = "AUTHENTICATED_AES_256_GCM",
                                remoteIp = connectedHost,
                                serverPort = endpoint.port,
                                lastError = null
                            )
                        }
                        updatePhase(SmartConnectionPhase.CONNECTED, effectiveStrategy)
                        authResult
                    }
                }
            }
        } finally {
            senderScanInFlight.set(false)
        }
    }

    suspend fun cancelOrDisconnect(markCancelled: Boolean = true): AppResult<Unit> =
        withContext(dispatchers.io) {
            operationMutex.withLock {
                val target = if (markCancelled) {
                    SmartConnectionPhase.CANCELLED
                } else {
                    SmartConnectionPhase.DISCONNECTED
                }
                cancelSessionLocked(targetPhase = target, clearError = false)
                AppResult.Success(Unit)
            }
        }

    suspend fun onAppBackgrounded() {
        val current = _phase.value
        if (current == SmartConnectionPhase.REQUESTING_WIFI ||
            current == SmartConnectionPhase.WAITING_FOR_APPROVAL ||
            current == SmartConnectionPhase.DISCOVERING_RECEIVER
        ) {
            operationMutex.withLock {
                failLocked(
                    code = SmartConnectionErrorCode.APP_BACKGROUNDED,
                    detail = "Connection paused because ShareFlow moved to the background. Retry when ready."
                )
            }
        }
    }

    fun notifyTransferActivity(isTransferring: Boolean) {
        val current = _phase.value
        if (isTransferring && current == SmartConnectionPhase.CONNECTED) {
            updatePhase(SmartConnectionPhase.TRANSFERRING, _activeStrategy.value)
        } else if (!isTransferring && current == SmartConnectionPhase.TRANSFERRING) {
            val stillConnected = baseCoordinator.connectionState.value is ConnectionState.Connected
            updatePhase(
                if (stillConnected) SmartConnectionPhase.CONNECTED else SmartConnectionPhase.DISCONNECTED,
                _activeStrategy.value
            )
        }
    }

    suspend fun runDiagnosticsConnectionTest(): String = withContext(dispatchers.io) {
        updateDiagnostics { it.copy(isTestingConnection = true) }
        val resultSummary = try {
            val channel = baseCoordinator.activeChannel
            val wifiHandle = activeBoundNetwork ?: existingWifiProvider.getActiveWifiNetworkHandle()
            val apIface = receiverHotspotController.inspectActiveApInterface()
            val serverListening = baseCoordinator.isTcpServerListening()
            val serverPort = baseCoordinator.currentServerPort()

            when {
                channel != null && channel.isOpen -> {
                    "PASS: Encrypted AES-256-GCM channel open with ${channel.remotePeer.displayName} (SAS ${channel.sasVerificationCode})"
                }
                serverListening && serverPort != null -> {
                    val ip = receiverHotspotController.activeSession.value?.localIpv4Address
                        ?: apIface.second
                        ?: wifiHandle?.localIpv4Address
                        ?: "local interface"
                    "PASS: TCP server listening on $ip:$serverPort (${sessionDiscovery.statusLabel.value})"
                }
                wifiHandle != null -> {
                    "OK: Bound Wi-Fi network ready (ID ${wifiHandle.networkId ?: "N/A"}, IP ${wifiHandle.localIpv4Address ?: "N/A"}) — no active peer session"
                }
                !permissionHelper.isWifiEnabled() -> {
                    "FAIL: Wi-Fi is disabled on this device"
                }
                else -> {
                    "IDLE: Wi-Fi enabled; start Receive or scan a ShareFlow QR code to establish a session"
                }
            }
        } catch (t: Throwable) {
            "FAIL: Diagnostic check error (${t.message ?: "unknown"})"
        }

        refreshDiagnosticsSnapshot(lastTestResult = resultSummary)
        resultSummary
    }

    fun refreshDiagnosticsSnapshot(lastTestResult: String? = _diagnostics.value.lastTestConnectionResult) {
        val hotspot = receiverHotspotController.activeSession.value
        val apIface = if (hotspot != null) {
            hotspot.interfaceName to hotspot.localIpv4Address
        } else {
            null to null
        }
        val wifiHandle = activeBoundNetwork ?: existingWifiProvider.getActiveWifiNetworkHandle()
        val channel = baseCoordinator.activeChannel
        val payload = _activeQrPayload.value

        updateDiagnostics { current ->
            current.copy(
                activeStrategy = _activeStrategy.value,
                phase = _phase.value,
                hotspotState = receiverHotspotController.hotspotStateLabel.value,
                ssidAvailable = !hotspot?.ssid.isNullOrBlank() || !payload?.ssid.isNullOrBlank(),
                ssidLabel = hotspot?.ssid ?: payload?.ssid,
                activeNetworkId = wifiHandle?.networkId,
                localIp = apIface.second ?: wifiHandle?.localIpv4Address ?: current.localIp,
                remoteIp = channel?.remotePeer?.hostAddress ?: current.remoteIp,
                activeNetworkInterface = apIface.first ?: wifiHandle?.interfaceName ?: current.activeNetworkInterface,
                serverPort = baseCoordinator.currentServerPort() ?: payload?.servicePort,
                isServerListening = baseCoordinator.isTcpServerListening(),
                nsdDiscoveryStatus = sessionDiscovery.statusLabel.value,
                qrSessionExpiresAtEpochMillis = payload?.expiresAtEpochMillis ?: current.qrSessionExpiresAtEpochMillis,
                authenticationStatus = when {
                    channel?.isOpen == true -> "AUTHENTICATED_AES_256_GCM"
                    _phase.value == SmartConnectionPhase.AUTHENTICATING -> "AUTHENTICATING"
                    else -> current.authenticationStatus
                },
                lastError = _lastError.value,
                lastTestConnectionResult = lastTestResult,
                isTestingConnection = false
            )
        }
    }

    fun clearError() {
        _lastError.value = null
        updateDiagnostics { it.copy(lastError = null) }
    }

    fun shutdown() {
        expiryWatcherJob?.cancel()
        runCatching { senderWifiConnector.releaseNetwork() }
        baseCoordinator.clearSmartListeners()
        scope.cancel()
    }

    private fun observeStateStreams() {
        scope.launch {
            sessionDiscovery.statusLabel.collect { label ->
                updateDiagnostics { it.copy(nsdDiscoveryStatus = label) }
            }
        }
        scope.launch {
            receiverHotspotController.hotspotStateLabel.collect { hs ->
                updateDiagnostics { it.copy(hotspotState = hs) }
            }
        }
        scope.launch {
            baseCoordinator.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Connected -> {
                        if (_phase.value != SmartConnectionPhase.TRANSFERRING) {
                            updatePhase(SmartConnectionPhase.CONNECTED, _activeStrategy.value)
                        }
                        updateDiagnostics {
                            it.copy(
                                remoteIp = state.peer.hostAddress ?: it.remoteIp,
                                authenticationStatus = "AUTHENTICATED_AES_256_GCM"
                            )
                        }
                    }
                    is ConnectionState.Idle -> {
                        if (_phase.value == SmartConnectionPhase.CONNECTED ||
                            _phase.value == SmartConnectionPhase.TRANSFERRING
                        ) {
                            updatePhase(SmartConnectionPhase.DISCONNECTED, _activeStrategy.value)
                            updateDiagnostics { it.copy(authenticationStatus = "DISCONNECTED") }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleReceiverHandshakeOutcome(outcome: AppResult<ConnectionState.Connected>) {
        when (outcome) {
            is AppResult.Success -> {
                val activePayload = _activeQrPayload.value
                if (activePayload != null) {
                    qrProtocol.invalidateSession(activePayload.sessionId)
                    baseCoordinator.invalidateSmartPairingSession(activePayload.sessionId)
                }
                expiryWatcherJob?.cancel()
                expiryWatcherJob = null
                sessionDiscovery.stopSessionAdvertising()
                updateDiagnostics {
                    it.copy(
                        remoteIp = outcome.data.peer.hostAddress,
                        authenticationStatus = "AUTHENTICATED_AES_256_GCM",
                        lastError = null
                    )
                }
                updatePhase(SmartConnectionPhase.CONNECTED, _activeStrategy.value)
            }

            is AppResult.Failure -> {
                val err = SmartConnectionError(
                    code = SmartConnectionErrorCode.AUTHENTICATION_FAILED,
                    detail = outcome.error.message,
                    timestampEpochMillis = clock.nowEpochMillis()
                )
                _lastError.value = err
                updateDiagnostics {
                    it.copy(
                        authenticationStatus = "FAILED",
                        lastError = err
                    )
                }
                // Keep receiver waiting if server socket is still open
                if (baseCoordinator.isTcpServerListening() && _activeQrPayload.value != null) {
                    updatePhase(SmartConnectionPhase.WAITING_FOR_QR, _activeStrategy.value)
                } else {
                    updatePhase(SmartConnectionPhase.FAILED, _activeStrategy.value)
                }
            }
        }
    }

    private suspend fun handleUnexpectedReceiverHotspotDrop() {
        operationMutex.withLock {
            if (_phase.value == SmartConnectionPhase.IDLE || _phase.value == SmartConnectionPhase.CANCELLED) {
                return@withLock
            }
            val err = SmartConnectionError(
                code = SmartConnectionErrorCode.HOTSPOT_STOPPED_UNEXPECTEDLY,
                detail = "Android stopped the temporary local hotspot. Start receiving again to create a new session.",
                timestampEpochMillis = clock.nowEpochMillis()
            )
            _lastError.value = err
            cancelSessionLocked(targetPhase = SmartConnectionPhase.DISCONNECTED, clearError = false)
            _lastError.value = err
            updateDiagnostics { it.copy(lastError = err) }
        }
    }

    private suspend fun handleSenderNetworkLost() {
        operationMutex.withLock {
            if (_phase.value == SmartConnectionPhase.IDLE || _phase.value == SmartConnectionPhase.CANCELLED) {
                return@withLock
            }
            activeBoundNetwork = null
            val err = SmartConnectionError(
                code = SmartConnectionErrorCode.RECEIVER_DISCONNECTED,
                detail = "Receiver's temporary Wi-Fi network disconnected",
                timestampEpochMillis = clock.nowEpochMillis()
            )
            _lastError.value = err
            baseCoordinator.disconnect()
            updatePhase(SmartConnectionPhase.DISCONNECTED, _activeStrategy.value)
            updateDiagnostics {
                it.copy(
                    activeNetworkId = null,
                    authenticationStatus = "DISCONNECTED",
                    lastError = err
                )
            }
        }
    }

    private fun startExpiryWatcher(payload: SmartQrPairingPayload) {
        expiryWatcherJob?.cancel()
        expiryWatcherJob = scope.launch {
            while (isActive) {
                val remaining = payload.expiresAtEpochMillis - clock.nowEpochMillis()
                if (remaining <= 0L) {
                    operationMutex.withLock {
                        if (_activeQrPayload.value?.sessionId == payload.sessionId &&
                            (_phase.value == SmartConnectionPhase.WAITING_FOR_QR ||
                                _phase.value == SmartConnectionPhase.SERVER_READY)
                        ) {
                            qrProtocol.invalidateSession(payload.sessionId)
                            baseCoordinator.invalidateSmartPairingSession(payload.sessionId)
                            sessionDiscovery.stopSessionAdvertising()
                            val err = SmartConnectionError(
                                code = SmartConnectionErrorCode.SESSION_EXPIRED,
                                detail = "QR pairing session expired. Regenerate a fresh QR code.",
                                timestampEpochMillis = clock.nowEpochMillis()
                            )
                            _lastError.value = err
                            updateDiagnostics { it.copy(lastError = err, authenticationStatus = "EXPIRED") }
                            updatePhase(SmartConnectionPhase.EXPIRED, _activeStrategy.value)
                        }
                    }
                    break
                }
                delay(remaining.coerceAtMost(1_000L))
            }
        }
    }

    private suspend fun cancelSessionLocked(
        targetPhase: SmartConnectionPhase,
        clearError: Boolean
    ) {
        expiryWatcherJob?.cancel()
        expiryWatcherJob = null
        _activeQrPayload.value?.let { previous ->
            qrProtocol.invalidateSession(previous.sessionId)
            baseCoordinator.invalidateSmartPairingSession(previous.sessionId)
        }
        _activeQrPayload.value = null
        _activeQrUri.value = ""
        _activeManualCode.value = ""
        sessionDiscovery.stopSessionAdvertising()
        receiverHotspotController.stopHotspot()
        senderWifiConnector.releaseNetwork()
        activeBoundNetwork = null
        baseCoordinator.clearSmartListeners()
        baseCoordinator.stopAdvertising()
        baseCoordinator.disconnect()
        if (clearError) {
            _lastError.value = null
        }
        updatePhase(targetPhase, if (targetPhase == SmartConnectionPhase.IDLE) null else _activeStrategy.value)
        updateDiagnostics {
            it.copy(
                hotspotState = receiverHotspotController.hotspotStateLabel.value,
                ssidAvailable = false,
                ssidLabel = null,
                activeNetworkId = null,
                remoteIp = null,
                serverPort = null,
                isServerListening = false,
                nsdDiscoveryStatus = sessionDiscovery.statusLabel.value,
                qrSessionExpiresAtEpochMillis = null,
                authenticationStatus = if (clearError) "UNAUTHENTICATED" else targetPhase.name,
                lastError = if (clearError) null else _lastError.value
            )
        }
    }

    private fun selectReceiverFallbackStrategy(): SmartStrategyType? {
        if (wifiDirectStrategy != null &&
            permissionHelper.evaluateAvailability(ConnectionType.WIFI_DIRECT) is StrategyAvailability.Available
        ) {
            return SmartStrategyType.WIFI_DIRECT
        }
        if (existingWifiProvider.getActiveWifiNetworkHandle() != null || permissionHelper.isWifiEnabled()) {
            return SmartStrategyType.EXISTING_LOCAL_WIFI
        }
        return null
    }

    private fun failLocked(
        code: SmartConnectionErrorCode,
        detail: String,
        overridePhase: SmartConnectionPhase = SmartConnectionPhase.FAILED
    ): AppResult.Failure {
        val err = SmartConnectionError(
            code = code,
            detail = detail,
            timestampEpochMillis = clock.nowEpochMillis()
        )
        _lastError.value = err
        updatePhase(overridePhase, _activeStrategy.value)
        updateDiagnostics { it.copy(lastError = err) }
        return AppResult.Failure(
            when (code) {
                SmartConnectionErrorCode.PERMISSION_DENIED -> AppError.PermissionDenied(detail)
                SmartConnectionErrorCode.TAMPERED_QR,
                SmartConnectionErrorCode.REPLAYED_QR,
                SmartConnectionErrorCode.AUTHENTICATION_FAILED -> AppError.Security(detail)
                SmartConnectionErrorCode.INVALID_QR,
                SmartConnectionErrorCode.SELF_PAIRING -> AppError.Validation(detail)
                SmartConnectionErrorCode.UNSUPPORTED_HARDWARE -> AppError.NotSupported(detail)
                else -> AppError.Network(detail)
            }
        )
    }

    private fun recordNonFatalNotice(code: SmartConnectionErrorCode, detail: String) {
        val err = SmartConnectionError(
            code = code,
            detail = detail,
            recoverable = true,
            timestampEpochMillis = clock.nowEpochMillis()
        )
        updateDiagnostics { it.copy(lastError = err) }
    }

    private fun mapAppErrorToSmartCode(
        error: AppError,
        defaultCode: SmartConnectionErrorCode
    ): SmartConnectionErrorCode = when (error) {
        is AppError.PermissionDenied -> SmartConnectionErrorCode.PERMISSION_DENIED
        is AppError.NotSupported -> SmartConnectionErrorCode.UNSUPPORTED_HARDWARE
        is AppError.Security -> SmartConnectionErrorCode.AUTHENTICATION_FAILED
        else -> defaultCode
    }

    private fun updatePhase(newPhase: SmartConnectionPhase, strategy: SmartStrategyType?) {
        _phase.value = newPhase
        _activeStrategy.value = strategy
        updateDiagnostics { it.copy(phase = newPhase, activeStrategy = strategy) }
    }

    private fun updateDiagnostics(transform: (SmartConnectionDiagnostics) -> SmartConnectionDiagnostics) {
        _diagnostics.update(transform)
    }

    companion object {
        private const val DUPLICATE_SCAN_COOLDOWN_MS = 4_000L
    }
}
