package com.example.shareflow.network.coordinator

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.ActiveLocalHotspotSession
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.BoundWifiNetworkHandle
import com.example.shareflow.domain.model.DiscoveredReceiverEndpoint
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.ReceiverDiscoveryMethod
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.discovery.SessionServiceDiscovery
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.strategy.ExistingWifiNetworkProvider
import com.example.shareflow.network.strategy.NetworkPermissionHelper
import com.example.shareflow.network.strategy.ReceiverHotspotController
import com.example.shareflow.network.strategy.SenderWifiConnector
import com.example.shareflow.network.strategy.WifiJoinOutcome
import com.example.shareflow.testutil.FakeSettingsRepository
import java.net.DatagramSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartConnectionCoordinatorTest {

    private class FakeClock(initial: Long = 1_760_000_000_000L) : Clock {
        val now = AtomicLong(initial)
        override fun nowEpochMillis(): Long = now.get()
        fun advanceBy(ms: Long) {
            now.addAndGet(ms)
        }
    }

    private val ioDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class InMemoryTrustedRepo : TrustedDeviceRepository {
        private val devices = MutableStateFlow<List<PeerDevice>>(emptyList())
        override fun observeTrustedDevices(): Flow<List<PeerDevice>> = devices
        override fun observeTrustedCount(): Flow<Int> = devices.map { it.size }
        override suspend fun getTrustedDevice(deviceId: String): PeerDevice? =
            devices.value.firstOrNull { it.deviceId == deviceId }
        override suspend fun saveTrustedDevice(device: PeerDevice): AppResult<Unit> {
            devices.value = devices.value.filterNot { it.deviceId == device.deviceId } + device
            return AppResult.Success(Unit)
        }
        override suspend fun revokeTrustedDevice(deviceId: String): AppResult<Unit> {
            devices.value = devices.value.filterNot { it.deviceId == deviceId }
            return AppResult.Success(Unit)
        }
    }

    private class FakeReceiverHotspotController(
        private var availability: StrategyAvailability = StrategyAvailability.Available,
        private val ssid: String = "AndroidShare_4821",
        private val passphrase: String = "TopSecretHotspotPass!42"
    ) : ReceiverHotspotController {
        private val _activeSession = MutableStateFlow<ActiveLocalHotspotSession?>(null)
        override val activeSession: StateFlow<ActiveLocalHotspotSession?> = _activeSession.asStateFlow()
        private val _label = MutableStateFlow("INACTIVE")
        override val hotspotStateLabel: StateFlow<String> = _label.asStateFlow()
        var stopCallback: (() -> Unit)? = null

        override fun checkAvailability(): StrategyAvailability = availability

        override suspend fun startHotspot(
            onStoppedUnexpectedly: () -> Unit
        ): AppResult<ActiveLocalHotspotSession> {
            if (availability !is StrategyAvailability.Available) {
                return AppResult.Failure(AppError.NotSupported("Hotspot unavailable"))
            }
            stopCallback = onStoppedUnexpectedly
            val session = ActiveLocalHotspotSession(
                ssid = ssid,
                passphrase = passphrase,
                securityType = HotspotSecurityType.WPA2_PSK,
                interfaceName = "ap0",
                localIpv4Address = "127.0.0.1",
                startedAtEpochMillis = System.currentTimeMillis()
            )
            _activeSession.value = session
            _label.value = "ACTIVE"
            return AppResult.Success(session)
        }

        override suspend fun stopHotspot() {
            _activeSession.value = null
            _label.value = "INACTIVE"
            stopCallback = null
        }

        override fun inspectActiveApInterface(): Pair<String?, String?> = "ap0" to "127.0.0.1"

        fun triggerUnexpectedDrop() {
            _activeSession.value = null
            _label.value = "STOPPED_BY_SYSTEM"
            stopCallback?.invoke()
        }
    }

    private class FakeSenderWifiConnector(
        private val supported: Boolean = true,
        private val failOutcome: WifiJoinOutcome.Failed? = null
    ) : SenderWifiConnector {
        var released = false

        override fun isSupportedOnDevice(): Boolean = supported

        override suspend fun requestLocalHotspotConnection(
            ssid: String,
            passphrase: String?,
            securityType: HotspotSecurityType,
            timeoutMillis: Long,
            onWaitingForApproval: () -> Unit,
            onJoiningNetwork: () -> Unit,
            onNetworkLost: () -> Unit
        ): WifiJoinOutcome {
            onWaitingForApproval()
            failOutcome?.let { return it }
            onJoiningNetwork()
            val handle = BoundWifiNetworkHandle(
                network = null,
                networkId = "net-1042",
                interfaceName = "wlan0",
                localIpv4Address = "127.0.0.1",
                gatewayIpv4Address = "127.0.0.1",
                broadcastIpv4Addresses = listOf("127.0.0.1"),
                isLocalOnlyHotspot = true
            )
            return WifiJoinOutcome.Connected(handle)
        }

        override fun createBoundTcpSocket(handle: BoundWifiNetworkHandle): Socket = Socket()

        override fun createBoundUdpSocket(handle: BoundWifiNetworkHandle): DatagramSocket = DatagramSocket()

        override fun releaseNetwork() {
            released = true
        }
    }

    private class FakeExistingWifiProvider(
        private val handle: BoundWifiNetworkHandle? = BoundWifiNetworkHandle(
            network = null,
            networkId = "wifi-900",
            interfaceName = "wlan0",
            localIpv4Address = "127.0.0.1",
            gatewayIpv4Address = "127.0.0.1",
            isLocalOnlyHotspot = false
        )
    ) : ExistingWifiNetworkProvider {
        override fun getActiveWifiNetworkHandle(): BoundWifiNetworkHandle? = handle
    }

    private class FakeSessionDiscovery : SessionServiceDiscovery {
        private val _status = MutableStateFlow("IDLE")
        override val statusLabel: StateFlow<String> = _status.asStateFlow()
        var advertisedPayload: SmartQrPairingPayload? = null

        override suspend fun startSessionAdvertising(
            payload: SmartQrPairingPayload,
            ephemeralKeyHash: String
        ) {
            advertisedPayload = payload
            _status.value = "ADVERTISING_NSD_AND_UDP (${payload.serviceName})"
        }

        override suspend fun stopSessionAdvertising() {
            advertisedPayload = null
            _status.value = "IDLE"
        }

        override suspend fun discoverReceiverForSession(
            payload: SmartQrPairingPayload,
            boundNetwork: BoundWifiNetworkHandle?,
            timeoutMillis: Long
        ): AppResult<DiscoveredReceiverEndpoint> {
            _status.value = "DISCOVERED_NSD"
            return AppResult.Success(
                DiscoveredReceiverEndpoint(
                    hostAddress = "127.0.0.1",
                    port = payload.servicePort,
                    serviceName = payload.serviceName,
                    method = ReceiverDiscoveryMethod.NSD
                )
            )
        }
    }

    private fun buildNode(
        clock: Clock,
        hotspotController: FakeReceiverHotspotController = FakeReceiverHotspotController(),
        senderConnector: FakeSenderWifiConnector = FakeSenderWifiConnector(),
        existingWifiProvider: FakeExistingWifiProvider = FakeExistingWifiProvider(),
        sessionDiscovery: SessionServiceDiscovery = FakeSessionDiscovery()
    ): Pair<DefaultConnectionCoordinator, SmartConnectionCoordinator> {
        val context = RuntimeEnvironment.getApplication()
        val identity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val settingsRepo = FakeSettingsRepository(AppSettings(deviceName = "ShareFlow Test"))
        val trustedRepo = InMemoryTrustedRepo()
        val permHelper = NetworkPermissionHelper(context)
        val base = DefaultConnectionCoordinator(
            strategies = emptyMap(),
            identityManager = identity,
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayProtectionCache = ReplayProtectionCache(clock = clock),
            trustedDeviceRepository = trustedRepo,
            settingsRepository = settingsRepo,
            dispatchers = ioDispatchers,
            clock = clock
        )
        val qrProtocol = SmartQrPairingProtocol(identity, clock)
        val smart = SmartConnectionCoordinator(
            baseCoordinator = base,
            receiverHotspotController = hotspotController,
            senderWifiConnector = senderConnector,
            existingWifiProvider = existingWifiProvider,
            wifiDirectStrategy = null,
            sessionDiscovery = sessionDiscovery,
            qrProtocol = qrProtocol,
            permissionHelper = permHelper,
            dispatchers = ioDispatchers,
            clock = clock
        )
        return base to smart
    }

    @Test
    fun `end-to-end Smart Hotspot QR pairing authenticates encrypted channel verifies SHA-256 payload and blocks replay`() = runBlocking {
        val clock = FakeClock()
        val receiverHotspot = FakeReceiverHotspotController(
            ssid = "AndroidShare_E2E",
            passphrase = "NeverExposeHotspotSecret!99"
        )
        val receiverDiscovery = FakeSessionDiscovery()
        val (receiverBase, receiverSmart) = buildNode(
            clock = clock,
            hotspotController = receiverHotspot,
            sessionDiscovery = receiverDiscovery
        )
        val (senderBase, senderSmart) = buildNode(clock = clock)

        try {
            // 1. Receiver starts Smart Hotspot + verifies TCP server BEFORE generating signed v3 QR
            val startRes = receiverSmart.startSmartReceiving(
                deviceName = "Receiver Phone",
                preferredPort = 0,
                preferHotspot = true
            )
            assertTrue("Expected receiver start success, got $startRes", startRes is AppResult.Success)
            val qrPayload = (startRes as AppResult.Success).data
            assertEquals(SmartConnectionPhase.WAITING_FOR_QR, receiverSmart.phase.value)
            assertEquals(SmartStrategyType.LOCAL_ONLY_HOTSPOT, receiverSmart.activeStrategy.value)
            assertTrue("TCP server must be listening before QR shown", receiverBase.isTcpServerListening())
            assertTrue(qrPayload.servicePort > 0)

            val qrUri = receiverSmart.activeQrUri.value
            assertTrue(qrUri.startsWith("shareflow://pair?v=3"))

            // 2. Diagnostics report must NEVER expose hotspot passphrase or pairing token
            val safeReport = receiverSmart.diagnostics.value.toSafeReportString()
            assertFalse("Diagnostics leaked passphrase!", safeReport.contains("NeverExposeHotspotSecret!99"))
            assertFalse("Diagnostics leaked pairing token!", safeReport.contains(qrPayload.pairingToken))
            assertTrue("Diagnostics should report SSID availability", safeReport.contains("AndroidShare_E2E"))

            // 3. Sender scans QR -> WifiNetworkSpecifier -> Discovery -> Bound TCP -> Mutual ECDSA+ECDH+AES-256-GCM Auth
            val connectRes = senderSmart.connectViaSmartQr(
                rawQrOrManualCode = qrUri,
                localDeviceName = "Sender Phone"
            )
            assertTrue("Expected sender connect success, got $connectRes", connectRes is AppResult.Success)
            val senderConnected = (connectRes as AppResult.Success).data
            assertEquals(SmartConnectionPhase.CONNECTED, senderSmart.phase.value)
            assertEquals(SmartStrategyType.WIFI_NETWORK_SPECIFIER, senderSmart.activeStrategy.value)

            var waitCount = 0
            while (receiverSmart.phase.value != SmartConnectionPhase.CONNECTED && waitCount < 40) {
                delay(25L)
                waitCount++
            }
            assertEquals(SmartConnectionPhase.CONNECTED, receiverSmart.phase.value)

            val senderChannel = senderBase.activeChannel
            val receiverChannel = receiverBase.activeChannel
            assertNotNull(senderChannel)
            assertNotNull(receiverChannel)
            assertEquals(senderConnected.sasVerificationCode, receiverChannel!!.sasVerificationCode)

            // 4. Verify encrypted AES-256-GCM data transfer + SHA-256 integrity over authenticated channel
            val testPayload = ByteArray(16 * 1024) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
            val expectedSha = MessageDigest.getInstance("SHA-256").digest(testPayload)
            val frameType: Byte = 0x20
            val sendRes = senderChannel!!.sendEncryptedFrame(frameType, testPayload)
            assertTrue("Encrypted frame send failed: $sendRes", sendRes is AppResult.Success)
            val recvRes = receiverChannel.receiveEncryptedFrame()
            assertTrue("Encrypted frame receive failed: $recvRes", recvRes is AppResult.Success)
            val decrypted = (recvRes as AppResult.Success).data
            assertEquals(frameType, decrypted.frameType)
            assertArrayEquals(expectedSha, MessageDigest.getInstance("SHA-256").digest(decrypted.payload))

            // 5. Verify diagnostics Test Connection reports PASS on open encrypted channel
            val testReport = senderSmart.runDiagnosticsConnectionTest()
            assertTrue("Expected PASS from diagnostics test, got: $testReport", testReport.startsWith("PASS:"))

            // 6. Verify single-use QR replay is rejected
            val replayRes = senderSmart.connectViaSmartQr(
                rawQrOrManualCode = qrUri,
                localDeviceName = "Sender Phone"
            )
            assertTrue(replayRes is AppResult.Failure)
            assertEquals(SmartConnectionErrorCode.REPLAYED_QR, senderSmart.lastError.value?.code)
        } finally {
            senderSmart.cancelOrDisconnect(markCancelled = false)
            receiverSmart.cancelOrDisconnect(markCancelled = false)
            senderSmart.shutdown()
            receiverSmart.shutdown()
            senderBase.shutdown()
            receiverBase.shutdown()
        }
    }

    @Test
    fun `handles expired QR user wifi rejection hotspot drop and dynamic wifi fallback cleanly`() = runBlocking {
        val clock = FakeClock()

        // Case A: Dynamic fallback when LocalOnlyHotspot is unsupported -> EXISTING_LOCAL_WIFI
        val unsupportedHotspot = FakeReceiverHotspotController(
            availability = StrategyAvailability.Unsupported("No AP concurrency on hardware")
        )
        val (fallbackBase, fallbackSmart) = buildNode(
            clock = clock,
            hotspotController = unsupportedHotspot
        )
        try {
            val res = fallbackSmart.startSmartReceiving("Fallback Receiver", preferredPort = 0, preferHotspot = true)
            assertTrue(res is AppResult.Success)
            assertEquals(SmartStrategyType.EXISTING_LOCAL_WIFI, fallbackSmart.activeStrategy.value)
            assertEquals(SmartConnectionPhase.WAITING_FOR_QR, fallbackSmart.phase.value)
        } finally {
            fallbackSmart.cancelOrDisconnect()
            fallbackSmart.shutdown()
            fallbackBase.shutdown()
        }

        // Case B: User rejects Android Wi-Fi system approval -> CANCELLED + USER_REJECTED_NETWORK
        val receiverHotspot = FakeReceiverHotspotController()
        val (recBase, recSmart) = buildNode(clock = clock, hotspotController = receiverHotspot)
        val rejectingSenderConnector = FakeSenderWifiConnector(
            failOutcome = WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.USER_REJECTED_NETWORK,
                "User declined Wi-Fi system dialog"
            )
        )
        val (sendBase, sendSmart) = buildNode(clock = clock, senderConnector = rejectingSenderConnector)
        try {
            val startRes = recSmart.startSmartReceiving("Receiver", preferredPort = 0, preferHotspot = true)
            assertTrue(startRes is AppResult.Success)
            val qrUri = recSmart.activeQrUri.value

            val rejectRes = sendSmart.connectViaSmartQr(qrUri, "Sender")
            assertTrue(rejectRes is AppResult.Failure)
            assertEquals(SmartConnectionPhase.CANCELLED, sendSmart.phase.value)
            assertEquals(SmartConnectionErrorCode.USER_REJECTED_NETWORK, sendSmart.lastError.value?.code)

            // Case C: Receiver hotspot drops unexpectedly -> DISCONNECTED + HOTSPOT_STOPPED_UNEXPECTEDLY
            receiverHotspot.triggerUnexpectedDrop()
            delay(100L)
            assertEquals(SmartConnectionPhase.DISCONNECTED, recSmart.phase.value)
            assertEquals(SmartConnectionErrorCode.HOTSPOT_STOPPED_UNEXPECTEDLY, recSmart.lastError.value?.code)

            // Case D: Expired QR -> EXPIRED + STALE_OR_EXPIRED_QR
            clock.advanceBy(300_000L)
            val expiredRes = sendSmart.connectViaSmartQr(qrUri, "Sender")
            assertTrue(expiredRes is AppResult.Failure)
            assertEquals(SmartConnectionPhase.EXPIRED, sendSmart.phase.value)
            assertEquals(SmartConnectionErrorCode.STALE_OR_EXPIRED_QR, sendSmart.lastError.value?.code)
        } finally {
            sendSmart.cancelOrDisconnect()
            recSmart.cancelOrDisconnect()
            sendSmart.shutdown()
            recSmart.shutdown()
            sendBase.shutdown()
            recBase.shutdown()
        }
    }

    @Test
    fun `qr pairing falls back to existing local wifi and direct QR host hint when hotspot join and multicast discovery fail`() = runBlocking {
        val clock = FakeClock()
        val receiverHotspot = FakeReceiverHotspotController(
            ssid = "AndroidShare_Fallback",
            passphrase = "FallbackSecretPassword!11"
        )
        val (recBase, recSmart) = buildNode(clock = clock, hotspotController = receiverHotspot)

        val timeoutSenderConnector = FakeSenderWifiConnector(
            failOutcome = WifiJoinOutcome.Failed(
                SmartConnectionErrorCode.WIFI_JOIN_TIMEOUT,
                "Simulated OEM hotspot join timeout"
            )
        )
        val failingDiscovery = object : SessionServiceDiscovery {
            private val _status = MutableStateFlow("DISCOVERY_BLOCKED")
            override val statusLabel: StateFlow<String> = _status.asStateFlow()
            override suspend fun startSessionAdvertising(payload: SmartQrPairingPayload, ephemeralKeyHash: String) = Unit
            override suspend fun stopSessionAdvertising() = Unit
            override suspend fun discoverReceiverForSession(
                payload: SmartQrPairingPayload,
                boundNetwork: BoundWifiNetworkHandle?,
                timeoutMillis: Long
            ): AppResult<DiscoveredReceiverEndpoint> = AppResult.Failure(
                AppError.Network("Multicast blocked by router")
            )
        }

        val (sendBase, sendSmart) = buildNode(
            clock = clock,
            senderConnector = timeoutSenderConnector,
            sessionDiscovery = failingDiscovery
        )

        try {
            val startRes = recSmart.startSmartReceiving("Fallback Receiver", preferredPort = 0, preferHotspot = true)
            assertTrue("Expected receiver start success, got $startRes", startRes is AppResult.Success)
            val qrUri = recSmart.activeQrUri.value

            val connectRes = sendSmart.connectViaSmartQr(qrUri, "Sender Fallback")
            assertTrue("Expected QR fallback connect success, got $connectRes", connectRes is AppResult.Success)
            assertEquals(SmartConnectionPhase.CONNECTED, sendSmart.phase.value)
            assertEquals(SmartStrategyType.EXISTING_LOCAL_WIFI, sendSmart.activeStrategy.value)
            assertEquals("DIRECT_QR_HOST_FALLBACK", sendSmart.diagnostics.value.nsdDiscoveryStatus)
        } finally {
            sendSmart.cancelOrDisconnect(markCancelled = false)
            recSmart.cancelOrDisconnect(markCancelled = false)
            sendSmart.shutdown()
            recSmart.shutdown()
            sendBase.shutdown()
            recBase.shutdown()
        }
    }
}
