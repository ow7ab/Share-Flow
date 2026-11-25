package com.example.shareflow.domain.model

import androidx.compose.runtime.Immutable

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
@Immutable
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
    val biometricUnlockPreferred: Boolean = true,
    val cloudUpdateManifestUrl: String = "",
    val hasCompletedOnboarding: Boolean = false,
    val autoCheckUpdatesOnLaunch: Boolean = true
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
        const val MAX_UPDATE_URL_LENGTH = 512
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

    /**
     * Validates an optional HTTPS cloud update manifest URL.
     * Blank input is allowed (clears custom URL). Non-blank input MUST use https:// with a valid public host.
     */
    fun isValidOptionalHttpsUpdateUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.length > AppSettings.MAX_UPDATE_URL_LENGTH || trimmed.any { it.isISOControl() || it.isWhitespace() }) {
            return false
        }
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.trim().orEmpty()
        if (host.isBlank() || !host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (isPrivateOrLoopbackHost(host)) return false
        return true
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        if (clean == "::1" || clean.startsWith("fe80:") || clean.startsWith("fc") || clean.startsWith("fd")) {
            return true
        }
        val ipv4Parts = clean.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            return a == 10 ||
                a == 127 ||
                a == 0 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        return false
    }
}
