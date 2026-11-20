# ShareFlow Architecture Foundation

ShareFlow is a native Android offline-first file sharing, device discovery, media management, and privacy utility built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, **DataStore**, **Media3**, **Android Storage Access Framework (DocumentFile)**, and **Android Keystore**.

## Package Architecture (Modular Clean Architecture + MVVM)

```text
app/src/main/java/com/example/
├── MainActivity.kt                           # Edge-to-edge Activity entry point
├── ShareFlowApplication.kt                   # Application entry point initializing AppContainer
└── shareflow/
    ├── core/
    │   ├── common/                           # AppResult, AppError, DispatcherProvider, Clock, Formatters
    │   ├── designsystem/
    │   │   ├── components/                   # Reusable Material 3 foundation components
    │   │   └── theme/                        # Color, Typography (local Google Fonts), Spacing, Shapes, Theme
    │   ├── di/                               # Constructor-injected AppContainer & ViewModel factory
    │   └── security/                         # AndroidKeystoreCipher (AES-256-GCM) & Sha256ChecksumVerifier
    ├── domain/
    │   ├── model/                            # Pure Kotlin domain models (zero Android dependencies)
    │   └── repository/                       # Repository interfaces (Settings, History, Devices, Vault, Files, Storage, Apps)
    ├── data/
    │   ├── local/
    │   │   ├── database/                     # ShareFlowDatabase (Room v1, non-destructive), Entities, DAOs
    │   │   └── preferences/                  # DataStore Preferences configuration & keys
    │   └── repository/                       # Concrete Room & DataStore repository implementations
    ├── network/                              # UI-independent protocol constants & Discovery/Connection/Transfer engine contracts
    ├── navigation/                           # Navigation Compose routes, 17-module catalog, adaptive NavBar/NavRail shell
    └── ui/
        ├── home/                             # Home Dashboard + HomeViewModel
        ├── hub/                              # Files & Utilities Hub and Media Suite Hub
        ├── history/                          # Transfer History + HistoryViewModel (Room-backed)
        ├── settings/                         # Settings + SettingsViewModel (DataStore-backed)
        └── module/                           # Honest architectural readiness screen for upcoming modules
```

## Security & Integrity Principles

- **Zero Plaintext Secrets**: Private Vault encryption uses `AndroidKeystoreCipher` (AES-256-GCM via `AndroidKeyStore`) and never logs keys or plaintext bytes.
- **No Destructive Migrations**: `ShareFlowDatabase` forbids destructive schema resets to preserve user history and encrypted vault references.
- **Verified Transfers Only**: `Sha256ChecksumVerifier` provides constant-time SHA-256 verification required before marking transfers complete.
- **Least-Privilege Manifest**: Zero unnecessary Android permissions are declared in the foundation phase; `android:allowBackup="false"` protects local data.
- **No Simulated Data**: Transfer History starts empty, and unimplemented modules explicitly show architectural status without fake devices or fake transfer progress.
