# ShareFlow Architecture

ShareFlow is a native Android offline-first file sharing, device discovery, media management, device migration, and privacy vault application built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room (v4)**, **DataStore**, **Media3**, **CameraX + ML Kit**, **Android Storage Access Framework (SAF / DocumentFile)**, **Biometric**, and **Android Keystore**.

## Package Architecture (Modular Clean Architecture + MVVM)

```text
app/src/main/java/com/example/
├── MainActivity.kt                           # SplashScreen + edge-to-edge Activity + high-refresh display setup
├── ShareFlowApplication.kt                   # Application entry point initializing DefaultAppContainer & Coil ImageLoader
└── shareflow/
    ├── core/
    │   ├── common/                           # AppResult, AppError, DispatcherProvider, Clock, Formatters, SafDocumentInspector
    │   ├── designsystem/                     # Material 3 theme (Color, Type, Shape, Spacing, Motion, Elevations) & SF* UI components
    │   ├── di/                               # Constructor-injected AppContainer, DefaultAppContainer & ViewModel factory
    │   ├── permissions/                      # PermissionManager & PermissionRequestCoordinator
    │   └── security/                         # AndroidKeystoreCipher (AES-256-GCM) & Sha256ChecksumVerifier
    ├── domain/
    │   ├── model/                            # Pure Kotlin domain models (AppSettings, PeerDevice, TransferModels, VaultModels, etc.)
    │   └── repository/                       # Repository interfaces (Settings, History, Transfer, Discovery, Vault, Files, Storage, Apps)
    ├── data/
    │   ├── apps/                             # ApplicationInstallationManager & PackageInstallStatusReceiver
    │   ├── files/                            # SafStorageEngine (recursive & single-query SAF), MediaStoreScanner, ReceivedMediaStorePublisher
    │   ├── local/
    │   │   ├── database/                     # ShareFlowDatabase (Room v4, non-destructive migrations 1→2→3→4), Entities & DAOs
    │   │   └── preferences/                  # SettingsDataStore & StartupAppearanceCache
    │   ├── repository/                       # Concrete Room, DataStore, SAF, AppSharing, Discovery & Transfer repositories
    │   └── update/                           # CloudUpdateManager, OkHttpCloudUpdateHttpClient, GitHubReleasesParser, UpdatePreferencesStore, UpdateManager & fcm/ (ShareFlowFirebaseMessagingService, ShareFlowUpdateNotificationManager)
    ├── network/
    │   ├── coordinator/                      # ConnectionCoordinator, SmartConnectionCoordinator, UnifiedConnectionManager
    │   ├── discovery/                        # SessionServiceDiscovery (NSD/mDNS)
    │   ├── nfc/                              # ShareFlowNfcHceService, NfcPairingManager & NfcPairingProtocol
    │   ├── qr/                               # QrCodeCodec & SmartQrPairingProtocol
    │   ├── security/                         # EncryptedPeerChannel (ECDH P-256 + HKDF-SHA256 + AES-256-GCM), DeviceIdentityManager, ReplayProtectionCache
    │   ├── strategy/                         # ExistingLocalWiFi, LocalOnlyHotspot, WiFiDirect, WiFiNetworkSpecifier strategies
    │   └── transfer/                         # TransferManager, TransferSender, TransferReceiver, ChunkProcessor, TransferRecoveryManager, TransferVerifier
    ├── media/                                # MediaCenterRepository, MusicPlaybackController & ShareFlowMediaSessionService
    ├── migration/                            # ContactsMigrationExporter for device-to-device migration
    ├── utilities/                            # DuplicateFileFinderEngine, ZipArchiveEngine & UtilitiesRepository
    ├── vault/                                # VaultManager, VaultCryptoEngine, VaultKeystoreProvider & VaultBiometricAuthenticator
    ├── navigation/                           # ShareFlowApp, ShareFlowRoutes & TopLevelDestination adaptive NavBar/NavRail shell
    └── ui/                                   # Compose screens & ViewModels (home, hub, send, receive, discovery, transfer, files, media, apps, vault, migration, utilities, history, settings, onboarding, update, viewer)
```

## Database & Persistence

`ShareFlowDatabase` (`shareflow_local.db`, Room **version 4**) uses explicit non-destructive migrations (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`) across 11 entities:
- **Transfers & Peers:** `transfer_history`, `trusted_devices`, `transfer_sessions`, `transfer_items`
- **Media:** `media_playback_state`, `music_playlists`, `music_playlist_tracks`
- **Vault:** `vault_items`, `vault_config`
- **Files & Storage:** `favorite_files`, `storage_snapshots`

## Security & Integrity Principles

- **Encrypted P2P Channel:** `EncryptedPeerChannel` derives directional AES-256-GCM keys via Ephemeral ECDH P-256 + HKDF-SHA256, enforces monotonic 64-bit sequence numbers and AAD binding, and uses `ReplayProtectionCache` + SAS verification codes.
- **Zero Plaintext Secrets in Vault:** Private Vault uses per-file DEKs wrapped by a Vault Master Key (PIN PBKDF2 + AndroidKeyStore + optional biometric unwrap) and encrypted metadata.
- **No Destructive Migrations:** `ShareFlowDatabase` explicitly preserves user history, trusted peers, recovery state, media state, and encrypted vault entries across upgrades.
- **Verified Transfers & Updates:** `Sha256ChecksumVerifier` verifies transferred files, and `CloudUpdateManager` enforces public `https://` URLs, SHA-256 verification, downgrade blocking, and signing certificate matching.
- **Least-Privilege Manifest & Storage:** `android:allowBackup="false"`, `android:usesCleartextTraffic="false"`, `NEARBY_WIFI_DEVICES` with `neverForLocation` (API 33+), `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` capped at `maxSdkVersion="32"`, and file access scoped through SAF & MediaStore without broad storage permissions.
