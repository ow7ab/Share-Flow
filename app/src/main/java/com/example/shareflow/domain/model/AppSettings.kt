package com.example.shareflow.domain.model

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class AccentColor {
    COBALT,
    CYAN,
    EMERALD,
    CORAL,
    VIOLET
}

enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    ARABIC
}

enum class FileOverwritePolicy {
    KEEP_BOTH_RENAME,
    SKIP_EXISTING,
    OVERWRITE_WITH_CONFIRMATION
}

enum class HistoryRetentionPolicy(val days: Int?) {
    DAYS_7(7),
    DAYS_30(30),
    DAYS_90(90),
    KEEP_FOREVER(null)
}

enum class DiscoveryVisibility {
    EVERYONE_NEARBY,
    TRUSTED_ONLY,
    HIDDEN
}

enum class PreferredConnectionMode {
    AUTO,
    WIFI_DIRECT,
    LOCAL_WIFI,
    HOTSPOT
}

/**
 * Pure domain model representing persisted user-configurable ShareFlow settings.
 * Automatic transfer acceptance is strictly disabled by default.
 */
data class AppSettings(
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = false,
    val accentColor: AccentColor = AccentColor.COBALT,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val defaultSaveDirectoryUri: String? = null,
    val transferPort: Int = DEFAULT_TRANSFER_PORT,
    val preferWifiDirect: Boolean = true,
    val verifySha256AfterTransfer: Boolean = true,
    val autoAcceptTrustedDevices: Boolean = false,
    val requireVaultAuthentication: Boolean = true,
    val maxConcurrentTransfers: Int = DEFAULT_CONCURRENT_TRANSFERS,
    val fileOverwritePolicy: FileOverwritePolicy = FileOverwritePolicy.KEEP_BOTH_RENAME,
    val transferNotificationsEnabled: Boolean = true,
    val historyRetentionPolicy: HistoryRetentionPolicy = HistoryRetentionPolicy.DAYS_30,
    val discoveryVisibility: DiscoveryVisibility = DiscoveryVisibility.EVERYONE_NEARBY,
    val preferredConnectionMode: PreferredConnectionMode = PreferredConnectionMode.AUTO,
    val biometricUnlockPreferred: Boolean = true
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "ShareFlow Device"
        const val DEFAULT_TRANSFER_PORT = 48721
        const val MIN_TRANSFER_PORT = 1024
        const val MAX_TRANSFER_PORT = 65535
        const val MIN_DEVICE_NAME_LENGTH = 2
        const val MAX_DEVICE_NAME_LENGTH = 32
        const val DEFAULT_CONCURRENT_TRANSFERS = 2
        const val MIN_CONCURRENT_TRANSFERS = 1
        const val MAX_CONCURRENT_TRANSFERS = 4
    }
}

object SettingsValidator {
    fun sanitizeDeviceName(raw: String): String =
        raw.trim().replace(Regex(" +"), " ")

    fun isValidDeviceName(raw: String): Boolean {
        if (raw.any { it.isISOControl() }) {
            return false
        }
        val sanitized = sanitizeDeviceName(raw)
        return sanitized.length in AppSettings.MIN_DEVICE_NAME_LENGTH..AppSettings.MAX_DEVICE_NAME_LENGTH
    }

    fun isValidTransferPort(port: Int): Boolean =
        port in AppSettings.MIN_TRANSFER_PORT..AppSettings.MAX_TRANSFER_PORT

    fun isValidConcurrentTransfers(count: Int): Boolean =
        count in AppSettings.MIN_CONCURRENT_TRANSFERS..AppSettings.MAX_CONCURRENT_TRANSFERS
}
