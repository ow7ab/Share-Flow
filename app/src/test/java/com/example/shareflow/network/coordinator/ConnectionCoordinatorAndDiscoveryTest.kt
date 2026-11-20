package com.example.shareflow.network.coordinator

import android.Manifest
import android.os.Build
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.strategy.ConnectionStrategy
import com.example.shareflow.network.strategy.NetworkPermissionHelper
import com.example.shareflow.network.strategy.PeerDiscoveryRegistry
import com.example.shareflow.network.strategy.ResolvedEndpoint
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectionCoordinatorAndDiscoveryTest {

    private class FakeClock(initial: Long = 1_750_000_000_000L) : Clock {
        val now = AtomicLong(initial)
        override fun nowEpochMillis(): Long = now.get()
    }

    private class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun updateDeviceName(deviceName: String) = AppResult.Success(Unit)
        override suspend fun updateThemeMode(themeMode: com.example.shareflow.domain.model.ThemeMode) = AppResult.Success(Unit)
        override suspend fun updateDynamicColor(enabled: Boolean) = AppResult.Success(Unit)
        override suspend fun updateAccentColor(accentColor: com.example.shareflow.domain.model.AccentColor) = AppResult.Success(Unit)
        override suspend fun updateAppLanguage(language: com.example.shareflow.domain.model.AppLanguage) = AppResult.Success(Unit)
        override suspend fun updateTransferPort(port: Int) = AppResult.Success(Unit)
        override suspend fun updateDefaultSaveDirectoryUri(uriString: String?) = AppResult.Success(Unit)
        override suspend fun updatePreferWifiDirect(enabled: Boolean) = AppResult.Success(Unit)
        override suspend fun updateVerifySha256AfterTransfer(enabled: Boolean) = AppResult.Success(Unit)
        override suspend fun updateAutoAcceptTrustedDevices(enabled: Boolean) = AppResult.Success(Unit)
        override suspend fun updateRequireVaultAuthentication(enabled: Boolean) = AppResult.Success(Unit)
        override suspend fun resetToDefaults() = AppResult.Success(Unit)
    }

    private class InMemoryTrustedRepo : TrustedDeviceRepository {
        private val devices = MutableStateFlow<List<PeerDevice>>(emptyList())
        override fun observeTrustedDevices(): Flow<List<PeerDevice>> = devices
        override fun observeTrustedCount(): Flow<Int> = devices.map { it.size }
        override suspend fun getTrustedDevice(deviceId: String): PeerDevice? =
            devices.value.firstOrNull { it.deviceId == deviceId }
        override suspend fun saveTrustedDevice(device: PeerDevice): AppResult<Unit> {
            devices.value = (devices.value.filterNot { it.deviceId == device.deviceId } + device)
            return AppResult.Success(Unit)
        }
        override suspend fun revokeTrustedDevice(deviceId: String): AppResult<Unit> {
            devices.value = devices.value.filterNot { it.deviceId == deviceId }
            return AppResult.Success(Unit)
        }
    }

    private class TestConnectionStrategy(
        override val connectionType: ConnectionType,
        private val availability: StrategyAvailability = StrategyAvailability.Available,
        private val failDiscovery: Boolean = false
    ) : ConnectionStrategy {
        private val _status = MutableStateFlow(StrategyStatus(connectionType, availability))
        override val status: StateFlow<StrategyStatus> = _status.asStateFlow()

        private val _discoveredPeers = MutableStateFlow<List<PeerDevice>>(emptyList())
        override val discoveredPeers: StateFlow<List<PeerDevice>> = _discoveredPeers.asStateFlow()

        fun emitPeers(peers: List<PeerDevice>) {
            _discoveredPeers.value = peers
        }

        override fun checkAvailability(): StrategyAvailability = availability

        override suspend fun startDiscovery(localIdentity: LocalDeviceIdentity): AppResult<Unit> {
            if (failDiscovery || availability != StrategyAvailability.Available) {
                return AppResult.Failure(AppError.Network("Discovery failed for $connectionType"))
            }
            _status.value = _status.value.copy(isDiscovering = true)
            return AppResult.Success(Unit)
        }

        override suspend fun stopDiscovery(): AppResult<Unit> {
            _status.value = _status.value.copy(isDiscovering = false)
            return AppResult.Success(Unit)
        }

        override suspend fun startAdvertising(
            localIdentity: LocalDeviceIdentity,
            deviceName: String,
            port: Int
        ): AppResult<Unit> {
            _status.value = _status.value.copy(isAdvertising = true)
            return AppResult.Success(Unit)
        }

        override suspend fun stopAdvertising(): AppResult<Unit> {
            _status.value = _status.value.copy(isAdvertising = false)
            return AppResult.Success(Unit)
        }

        override suspend fun prepareEndpointForPeer(
            peer: PeerDevice,
            passphrase: String?
        ): AppResult<ResolvedEndpoint> {
            val host = peer.hostAddress ?: "127.0.0.1"
            val port = peer.port ?: return AppResult.Failure(AppError.Validation("Missing port"))
            return AppResult.Success(ResolvedEndpoint(host, port, connectionType))
        }

        override suspend fun releaseTransport(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private val ioDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Default
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    @Test
    fun `peer discovery registry deduplicates devices filters unsupported protocols and ignores self`() {
        val clock = FakeClock()
        val registry = PeerDiscoveryRegistry(
            localDeviceIdProvider = { "local_self_id" },
            clock = clock,
            peerStaleMillis = 30_000L
        )
        val now = clock.nowEpochMillis()

        val wifiDirectPeer = PeerDevice(
            deviceId = "peer_alpha_0001",
            displayName = "Alpha Phone",
            connectionType = ConnectionType.WIFI_DIRECT,
            hostAddress = null,
            port = 52891,
            p2pDeviceAddress = "AA:BB:CC:DD:EE:01",
            lastSeenEpochMillis = now,
            protocolVersion = 2
        )
        val lanDuplicatePeer = PeerDevice(
            deviceId = "peer_alpha_0001",
            displayName = "Alpha Phone",
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            hostAddress = "192.168.1.50",
            port = 52891,
            publicKeyFingerprint = "a".repeat(64),
            lastSeenEpochMillis = now,
            protocolVersion = 2
        )
        val unsupportedOldPeer = PeerDevice(
            deviceId = "legacy_v1_peer",
            displayName = "Old App",
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            hostAddress = "192.168.1.99",
            port = 52891,
            lastSeenEpochMillis = now,
            protocolVersion = 1
        )
        val selfPeer = PeerDevice(
            deviceId = "local_self_id",
            displayName = "My Own Phone",
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            hostAddress = "192.168.1.10",
            port = 52891,
            lastSeenEpochMillis = now,
            protocolVersion = 2
        )

        registry.updateStrategyPeers(ConnectionType.WIFI_DIRECT, listOf(wifiDirectPeer))
        val merged = registry.updateStrategyPeers(
            ConnectionType.LOCAL_LAN_NSD,
            listOf(lanDuplicatePeer, unsupportedOldPeer, selfPeer)
        )

        // Exactly 1 deduplicated peer — self and v1 unsupported peer filtered out
        assertEquals(1, merged.size)
        assertEquals("peer_alpha_0001", merged.first().deviceId)
        assertEquals("192.168.1.50", merged.first().hostAddress)
        assertEquals("AA:BB:CC:DD:EE:01", merged.first().p2pDeviceAddress)
        assertEquals("a".repeat(64), merged.first().publicKeyFingerprint)
    }

    @Test
    fun `coordinator handles discovery timeout and strategy failure gracefully`() = runBlocking {
        val clock = FakeClock()
        val failingWifiDirect = TestConnectionStrategy(
            connectionType = ConnectionType.WIFI_DIRECT,
            availability = StrategyAvailability.Unsupported("No Wi-Fi Direct hardware"),
            failDiscovery = true
        )
        val lanStrategy = TestConnectionStrategy(connectionType = ConnectionType.LOCAL_LAN_NSD)

        val coordinator = DefaultConnectionCoordinator(
            strategies = mapOf(
                ConnectionType.WIFI_DIRECT to failingWifiDirect,
                ConnectionType.LOCAL_LAN_NSD to lanStrategy
            ),
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayProtectionCache = ReplayProtectionCache(clock = clock),
            trustedDeviceRepository = InMemoryTrustedRepo(),
            settingsRepository = InMemorySettingsRepository(),
            dispatchers = ioDispatchers,
            clock = clock
        )

        // Starting ONLY unsupported Wi-Fi Direct returns Failure and marks DiscoveryStatus.Failed
        val failedStart = coordinator.startDiscovery(setOf(ConnectionType.WIFI_DIRECT), 1_500L)
        assertTrue(failedStart is AppResult.Failure)
        assertTrue(coordinator.discoveryStatus.value is DiscoveryStatus.Failed)

        // Starting with Local LAN succeeds and transitions to TimedOut after timeout window
        val okStart = coordinator.startDiscovery(
            setOf(ConnectionType.WIFI_DIRECT, ConnectionType.LOCAL_LAN_NSD),
            timeoutMillis = 1_000L
        )
        assertTrue(okStart is AppResult.Success)
        assertTrue(coordinator.discoveryStatus.value is DiscoveryStatus.Discovering)

        delay(1_250L)
        assertEquals(DiscoveryStatus.TimedOut, coordinator.discoveryStatus.value)
        coordinator.shutdown()
    }

    @Test
    fun `two physical-style coordinators complete full QR pairing explicit approval trust save and disconnect`() = runBlocking {
        val clock = FakeClock()
        val receiverTrusted = InMemoryTrustedRepo()
        val receiverCoordinator = DefaultConnectionCoordinator(
            strategies = mapOf(
                ConnectionType.LOCAL_LAN_NSD to TestConnectionStrategy(ConnectionType.LOCAL_LAN_NSD)
            ),
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayProtectionCache = ReplayProtectionCache(clock = clock),
            trustedDeviceRepository = receiverTrusted,
            settingsRepository = InMemorySettingsRepository(),
            dispatchers = ioDispatchers,
            clock = clock
        )
        val senderCoordinator = DefaultConnectionCoordinator(
            strategies = mapOf(
                ConnectionType.LOCAL_LAN_NSD to TestConnectionStrategy(ConnectionType.LOCAL_LAN_NSD)
            ),
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayProtectionCache = ReplayProtectionCache(clock = clock),
            trustedDeviceRepository = InMemoryTrustedRepo(),
            settingsRepository = InMemorySettingsRepository(),
            dispatchers = ioDispatchers,
            clock = clock
        )

        // Receiver starts real TCP ServerSocket on ephemeral port 0
        val portResult = receiverCoordinator.startAdvertising("Receiver Pixel", 0, false)
        assertTrue(portResult is AppResult.Success)
        val boundPort = (portResult as AppResult.Success).data
        assertTrue(boundPort > 0)

        // Receiver generates short-lived QR payload pointing to loopback for test
        val qrResult = receiverCoordinator.generateQrPairingSession("Receiver Pixel", boundPort, 60_000L)
        assertTrue(qrResult is AppResult.Success)
        val loopbackPayload = (qrResult as AppResult.Success).data.copy(hostAddress = "127.0.0.1")
        val qrUri = receiverCoordinator.encodePairingUri(loopbackPayload)

        // Sender connects via QR URI while Receiver waits for explicit approval dialog
        val connectJob = async {
            senderCoordinator.connectViaQrPayload(qrUri, "Sender Galaxy")
        }

        // Wait for Receiver's pendingIncomingRequest to arrive
        var pending = receiverCoordinator.pendingIncomingRequest.value
        repeat(50) {
            if (pending != null) return@repeat
            delay(50L)
            pending = receiverCoordinator.pendingIncomingRequest.value
        }
        val pendingRequest = requireNotNull(pending) { "Receiver should emit explicit approval request" }
        assertTrue(pendingRequest.hasValidQrToken)
        assertEquals(senderCoordinator.getLocalIdentity().deviceId, pendingRequest.peerDeviceId)
        assertEquals(senderCoordinator.getLocalIdentity().publicKeyFingerprint, pendingRequest.peerPublicKeyFingerprint)

        // Receiver approves and remembers verified fingerprint
        val approveRes = receiverCoordinator.approveIncomingConnection(pendingRequest.requestId, rememberAsTrusted = true)
        assertTrue(approveRes is AppResult.Success)

        val connectOutcome = connectJob.await()
        assertTrue("Expected connected outcome: $connectOutcome", connectOutcome is AppResult.Success)

        val senderConnected = (connectOutcome as AppResult.Success).data
        assertTrue(senderConnected.isEncrypted)
        assertEquals(receiverCoordinator.getLocalIdentity().deviceId, senderConnected.peer.deviceId)

        // Verify receiver also transitioned to Connected with matching SAS and saved trusted fingerprint
        delay(100L)
        val receiverState = receiverCoordinator.connectionState.value
        assertTrue(receiverState is ConnectionState.Connected)
        assertEquals(
            senderConnected.sasVerificationCode,
            (receiverState as ConnectionState.Connected).sasVerificationCode
        )
        val savedTrusted = receiverTrusted.observeTrustedDevices().first()
        assertEquals(1, savedTrusted.size)
        assertEquals(senderCoordinator.getLocalIdentity().publicKeyFingerprint, savedTrusted.first().publicKeyFingerprint)

        // Clean disconnect
        senderCoordinator.disconnect()
        receiverCoordinator.disconnect()
        receiverCoordinator.stopAdvertising()
        assertTrue(senderCoordinator.connectionState.value is ConnectionState.Idle)

        senderCoordinator.shutdown()
        receiverCoordinator.shutdown()
    }

    @Test
    fun `permission helper adapts to Android 13+ vs Android 12 and handles missing Wi-Fi Direct hardware`() {
        val context = RuntimeEnvironment.getApplication()
        val pm = Shadows.shadowOf(context.packageManager)

        // Android 13+ (API 33+): requires NEARBY_WIFI_DEVICES, never Location
        val helperApi33 = NetworkPermissionHelper(context = context, sdkInt = Build.VERSION_CODES.TIRAMISU)
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            helperApi33.requiredPermissionsFor(ConnectionType.WIFI_DIRECT)
        )
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            helperApi33.requiredPermissionsFor(ConnectionType.HOTSPOT)
        )
        assertFalse(helperApi33.isLocationServicesRequiredForWifiP2p())

        // Android 12 (API 31): requires ACCESS_FINE_LOCATION and Location Services check
        val helperApi31 = NetworkPermissionHelper(context = context, sdkInt = Build.VERSION_CODES.S)
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            helperApi31.requiredPermissionsFor(ConnectionType.WIFI_DIRECT)
        )
        assertTrue(helperApi31.isLocationServicesRequiredForWifiP2p())

        // Device without FEATURE_WIFI_DIRECT gracefully reports Unsupported
        pm.setSystemFeature("android.hardware.wifi.direct", false)
        val avail = helperApi33.evaluateAvailability(ConnectionType.WIFI_DIRECT)
        assertTrue(avail is StrategyAvailability.Unsupported)
    }
}
