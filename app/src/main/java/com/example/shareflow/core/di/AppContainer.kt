package com.example.shareflow.core.di

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DefaultDispatcherProvider
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.core.security.AndroidKeystoreCipher
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.core.security.KeystoreCipher
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.local.preferences.shareFlowSettingsDataStore
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.data.repository.AndroidFileManagerRepository
import com.example.shareflow.data.repository.AndroidStorageAnalyzerRepository
import com.example.shareflow.data.repository.ConnectionRepositoryImpl
import com.example.shareflow.data.repository.DeviceDiscoveryRepositoryImpl
import com.example.shareflow.data.repository.SettingsRepositoryImpl
import com.example.shareflow.data.repository.TransferHistoryRepositoryImpl
import com.example.shareflow.data.repository.TransferRepositoryImpl
import com.example.shareflow.data.repository.TrustedDeviceRepositoryImpl
import com.example.shareflow.data.repository.VaultRepositoryImpl
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.repository.AppSharingRepository
import com.example.shareflow.domain.repository.ConnectionRepository
import com.example.shareflow.domain.repository.DeviceDiscoveryRepository
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.SettingsRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.domain.repository.TransferHistoryRepository
import com.example.shareflow.domain.repository.TransferRepository
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import com.example.shareflow.domain.repository.VaultRepository
import com.example.shareflow.network.LocalSessionController
import com.example.shareflow.network.coordinator.ConnectionCoordinator
import com.example.shareflow.network.coordinator.DefaultConnectionCoordinator
import com.example.shareflow.network.coordinator.SmartConnectionCoordinator
import com.example.shareflow.network.discovery.AndroidSessionServiceDiscovery
import com.example.shareflow.network.discovery.SessionServiceDiscovery
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.DeviceIdentityManager
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.strategy.ExistingLocalWiFiStrategy
import com.example.shareflow.network.strategy.LocalHotspotStrategy
import com.example.shareflow.network.strategy.LocalNetworkStrategy
import com.example.shareflow.network.strategy.LocalOnlyHotspotStrategy
import com.example.shareflow.network.strategy.NetworkPermissionHelper
import com.example.shareflow.network.strategy.WiFiDirectStrategy
import com.example.shareflow.network.strategy.WiFiNetworkSpecifierStrategy
import com.example.shareflow.network.transfer.AndroidTransferDataSource
import com.example.shareflow.network.transfer.ChunkProcessor
import com.example.shareflow.network.transfer.StatFsStorageSpaceChecker
import com.example.shareflow.network.transfer.TransferDataSource
import com.example.shareflow.network.transfer.TransferManager
import com.example.shareflow.network.transfer.TransferQueue
import com.example.shareflow.network.transfer.TransferReceiver
import com.example.shareflow.network.transfer.TransferRecoveryManager
import com.example.shareflow.network.transfer.TransferScheduler
import com.example.shareflow.media.MediaCenterRepository
import com.example.shareflow.media.music.MusicPlaybackController
import com.example.shareflow.network.transfer.TransferSender
import com.example.shareflow.network.transfer.TransferVerifier
import com.example.shareflow.migration.ContactsMigrationExporter
import com.example.shareflow.utilities.DuplicateFileFinderEngine
import com.example.shareflow.utilities.UtilitiesRepository
import com.example.shareflow.utilities.ZipArchiveEngine
import com.example.shareflow.vault.AndroidVaultKeystoreProvider
import com.example.shareflow.vault.VaultCryptoEngine
import com.example.shareflow.vault.VaultKeystoreProvider
import com.example.shareflow.vault.VaultManager

/**
 * Constructor-injected Dependency Injection graph for ShareFlow.
 * Allows swapping implementations cleanly in unit and Robolectric tests.
 */
interface AppContainer {
    val dispatchers: DispatcherProvider
    val clock: Clock
    val keystoreCipher: KeystoreCipher
    val checksumVerifier: ChecksumVerifier
    val database: ShareFlowDatabase
    val settingsRepository: SettingsRepository
    val transferHistoryRepository: TransferHistoryRepository
    val transferRepository: TransferRepository
    val trustedDeviceRepository: TrustedDeviceRepository
    val vaultRepository: VaultRepository
    val storageAnalyzerRepository: StorageAnalyzerRepository
    val fileManagerRepository: FileManagerRepository
    val appSharingRepository: AppSharingRepository
    val localSessionController: LocalSessionController
    val networkPermissionHelper: NetworkPermissionHelper
    val deviceIdentityManager: DeviceIdentityManager
    val connectionCoordinator: ConnectionCoordinator
    val smartQrPairingProtocol: SmartQrPairingProtocol
    val localOnlyHotspotStrategy: LocalOnlyHotspotStrategy
    val wifiNetworkSpecifierStrategy: WiFiNetworkSpecifierStrategy
    val existingLocalWiFiStrategy: ExistingLocalWiFiStrategy
    val sessionServiceDiscovery: SessionServiceDiscovery
    val smartConnectionCoordinator: SmartConnectionCoordinator
    val deviceDiscoveryRepository: DeviceDiscoveryRepository
    val connectionRepository: ConnectionRepository
    val transferDataSource: TransferDataSource
    val chunkProcessor: ChunkProcessor
    val transferVerifier: TransferVerifier
    val transferRecoveryManager: TransferRecoveryManager
    val transferManager: TransferManager
    val mediaCenterRepository: MediaCenterRepository
    val musicPlaybackController: MusicPlaybackController
    val vaultCryptoEngine: VaultCryptoEngine
    val vaultKeystoreProvider: VaultKeystoreProvider
    val vaultManager: VaultManager
    val duplicateFileFinderEngine: DuplicateFileFinderEngine
    val zipArchiveEngine: ZipArchiveEngine
    val utilitiesRepository: UtilitiesRepository
    val contactsMigrationExporter: ContactsMigrationExporter
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext

    override val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    override val clock: Clock = SystemClock()
    override val keystoreCipher: KeystoreCipher = AndroidKeystoreCipher()
    override val checksumVerifier: ChecksumVerifier = Sha256ChecksumVerifier()

    override val database: ShareFlowDatabase by lazy {
        ShareFlowDatabase.create(appContext)
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            dataStore = appContext.shareFlowSettingsDataStore,
            dispatchers = dispatchers
        )
    }

    override val transferHistoryRepository: TransferHistoryRepository by lazy {
        TransferHistoryRepositoryImpl(
            dao = database.transferHistoryDao(),
            dispatchers = dispatchers
        )
    }

    override val transferRepository: TransferRepository by lazy {
        TransferRepositoryImpl(
            dao = database.transferSessionDao(),
            dispatchers = dispatchers
        )
    }

    override val trustedDeviceRepository: TrustedDeviceRepository by lazy {
        TrustedDeviceRepositoryImpl(
            dao = database.trustedDeviceDao(),
            dispatchers = dispatchers
        )
    }

    override val vaultRepository: VaultRepository by lazy {
        VaultRepositoryImpl(
            dao = database.vaultItemDao(),
            dispatchers = dispatchers
        )
    }

    override val storageAnalyzerRepository: StorageAnalyzerRepository by lazy {
        AndroidStorageAnalyzerRepository(dispatchers = dispatchers)
    }

    override val fileManagerRepository: FileManagerRepository by lazy {
        AndroidFileManagerRepository(
            context = appContext,
            checksumVerifier = checksumVerifier,
            dispatchers = dispatchers
        )
    }

    override val appSharingRepository: AppSharingRepository by lazy {
        AndroidAppSharingRepository(
            context = appContext,
            transferHistoryRepository = transferHistoryRepository,
            checksumVerifier = checksumVerifier,
            ioDispatcher = dispatchers.io
        )
    }

    override val localSessionController: LocalSessionController by lazy {
        LocalSessionController(
            context = appContext,
            dispatchers = dispatchers
        )
    }

    override val networkPermissionHelper: NetworkPermissionHelper by lazy {
        NetworkPermissionHelper(context = appContext)
    }

    override val deviceIdentityManager: DeviceIdentityManager by lazy {
        AndroidDeviceIdentityManager()
    }

    private val wifiDirectStrategyInstance: WiFiDirectStrategy by lazy {
        WiFiDirectStrategy(
            context = appContext,
            permissionHelper = networkPermissionHelper,
            clock = clock
        )
    }

    private val defaultConnectionCoordinator: DefaultConnectionCoordinator by lazy {
        val strategies = mapOf(
            ConnectionType.WIFI_DIRECT to wifiDirectStrategyInstance,
            ConnectionType.LOCAL_LAN_NSD to LocalNetworkStrategy(
                context = appContext,
                permissionHelper = networkPermissionHelper,
                clock = clock
            ),
            ConnectionType.HOTSPOT to LocalHotspotStrategy(
                context = appContext,
                permissionHelper = networkPermissionHelper
            )
        )
        DefaultConnectionCoordinator(
            strategies = strategies,
            identityManager = deviceIdentityManager,
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayProtectionCache = ReplayProtectionCache(clock = clock),
            trustedDeviceRepository = trustedDeviceRepository,
            settingsRepository = settingsRepository,
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val connectionCoordinator: ConnectionCoordinator
        get() = defaultConnectionCoordinator

    override val smartQrPairingProtocol: SmartQrPairingProtocol by lazy {
        SmartQrPairingProtocol(
            identityManager = deviceIdentityManager,
            clock = clock
        )
    }

    override val localOnlyHotspotStrategy: LocalOnlyHotspotStrategy by lazy {
        LocalOnlyHotspotStrategy(
            context = appContext,
            permissionHelper = networkPermissionHelper,
            clock = clock
        )
    }

    override val wifiNetworkSpecifierStrategy: WiFiNetworkSpecifierStrategy by lazy {
        WiFiNetworkSpecifierStrategy(
            context = appContext,
            permissionHelper = networkPermissionHelper
        )
    }

    override val existingLocalWiFiStrategy: ExistingLocalWiFiStrategy by lazy {
        ExistingLocalWiFiStrategy(
            context = appContext,
            permissionHelper = networkPermissionHelper
        )
    }

    override val sessionServiceDiscovery: SessionServiceDiscovery by lazy {
        AndroidSessionServiceDiscovery(
            context = appContext,
            senderWifiConnector = wifiNetworkSpecifierStrategy,
            dispatchers = dispatchers
        )
    }

    override val smartConnectionCoordinator: SmartConnectionCoordinator by lazy {
        SmartConnectionCoordinator(
            baseCoordinator = defaultConnectionCoordinator,
            receiverHotspotController = localOnlyHotspotStrategy,
            senderWifiConnector = wifiNetworkSpecifierStrategy,
            existingWifiProvider = existingLocalWiFiStrategy,
            wifiDirectStrategy = wifiDirectStrategyInstance,
            sessionDiscovery = sessionServiceDiscovery,
            qrProtocol = smartQrPairingProtocol,
            permissionHelper = networkPermissionHelper,
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val deviceDiscoveryRepository: DeviceDiscoveryRepository by lazy {
        DeviceDiscoveryRepositoryImpl(coordinator = connectionCoordinator)
    }

    override val connectionRepository: ConnectionRepository by lazy {
        ConnectionRepositoryImpl(coordinator = connectionCoordinator)
    }

    override val transferDataSource: TransferDataSource by lazy {
        AndroidTransferDataSource(context = appContext)
    }

    override val chunkProcessor: ChunkProcessor by lazy {
        ChunkProcessor(
            dataSource = transferDataSource,
            storageSpaceChecker = StatFsStorageSpaceChecker()
        )
    }

    override val transferVerifier: TransferVerifier by lazy {
        TransferVerifier(
            checksumVerifier = checksumVerifier,
            dataSource = transferDataSource
        )
    }

    override val transferRecoveryManager: TransferRecoveryManager by lazy {
        TransferRecoveryManager(
            transferRepository = transferRepository,
            dataSource = transferDataSource,
            verifier = transferVerifier,
            chunkProcessor = chunkProcessor,
            clock = clock
        )
    }

    override val transferManager: TransferManager by lazy {
        TransferManager(
            connectionCoordinator = connectionCoordinator,
            transferRepository = transferRepository,
            transferQueue = TransferQueue(),
            transferScheduler = TransferScheduler(dispatchers = dispatchers),
            transferSender = TransferSender(
                chunkProcessor = chunkProcessor,
                verifier = transferVerifier,
                transferRepository = transferRepository,
                clock = clock
            ),
            transferReceiver = TransferReceiver(
                chunkProcessor = chunkProcessor,
                verifier = transferVerifier,
                recoveryManager = transferRecoveryManager,
                transferRepository = transferRepository,
                clock = clock
            ),
            recoveryManager = transferRecoveryManager,
            destinationRootProvider = { ChunkProcessor.defaultReceiveRoot(appContext) },
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val mediaCenterRepository: MediaCenterRepository by lazy {
        MediaCenterRepository(
            context = appContext,
            fileManagerRepository = fileManagerRepository,
            mediaPlaybackDao = database.mediaPlaybackDao(),
            musicPlaylistDao = database.musicPlaylistDao(),
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val musicPlaybackController: MusicPlaybackController by lazy {
        MusicPlaybackController(
            context = appContext,
            mediaCenterRepository = mediaCenterRepository,
            dispatchers = dispatchers
        )
    }

    override val vaultCryptoEngine: VaultCryptoEngine by lazy {
        VaultCryptoEngine()
    }

    override val vaultKeystoreProvider: VaultKeystoreProvider by lazy {
        AndroidVaultKeystoreProvider()
    }

    override val vaultManager: VaultManager by lazy {
        VaultManager(
            context = appContext,
            vaultConfigDao = database.vaultConfigDao(),
            vaultItemDao = database.vaultItemDao(),
            cryptoEngine = vaultCryptoEngine,
            keystoreProvider = vaultKeystoreProvider,
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val duplicateFileFinderEngine: DuplicateFileFinderEngine by lazy {
        DuplicateFileFinderEngine(
            context = appContext,
            fileManagerRepository = fileManagerRepository,
            checksumVerifier = checksumVerifier,
            dispatchers = dispatchers
        )
    }

    override val zipArchiveEngine: ZipArchiveEngine by lazy {
        ZipArchiveEngine(
            context = appContext,
            checksumVerifier = checksumVerifier,
            dispatchers = dispatchers
        )
    }

    override val utilitiesRepository: UtilitiesRepository by lazy {
        UtilitiesRepository(
            context = appContext,
            storageAnalyzerRepository = storageAnalyzerRepository,
            fileManagerRepository = fileManagerRepository,
            favoriteFileDao = database.favoriteFileDao(),
            storageSnapshotDao = database.storageSnapshotDao(),
            dispatchers = dispatchers,
            clock = clock
        )
    }

    override val contactsMigrationExporter: ContactsMigrationExporter by lazy {
        ContactsMigrationExporter(
            context = appContext,
            checksumVerifier = checksumVerifier,
            dispatchers = dispatchers
        )
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided. Ensure ShareFlowApplication initializes DefaultAppContainer.")
}

/**
 * Lightweight, type-safe ViewModel factory builder for constructor injection.
 */
inline fun <reified VM : ViewModel> shareFlowViewModelFactory(
    crossinline creator: () -> VM
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VM::class.java)) {
            return creator() as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
