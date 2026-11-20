package com.example.shareflow.domain.model

enum class VaultCategory {
    PHOTOS,
    VIDEOS,
    DOCUMENTS;

    companion object {
        fun fromMimeType(mimeType: String): VaultCategory = when {
            mimeType.startsWith("image/", ignoreCase = true) -> PHOTOS
            mimeType.startsWith("video/", ignoreCase = true) -> VIDEOS
            else -> DOCUMENTS
        }
    }
}

enum class VaultAutoLockTimeout(val seconds: Long) {
    IMMEDIATE(0L),
    SECONDS_30(30L),
    MINUTES_1(60L),
    MINUTES_5(300L),
    MINUTES_15(900L);

    companion object {
        fun fromSeconds(seconds: Long): VaultAutoLockTimeout =
            entries.minByOrNull { kotlin.math.abs(it.seconds - seconds) } ?: MINUTES_1
    }
}

enum class VaultLockState {
    /** Vault has not been created yet. */
    NOT_INITIALIZED,

    /** Vault exists and all file content & sensitive metadata are locked/encrypted at rest. */
    LOCKED,

    /** Vault is authenticated and VMK is held in volatile memory until lock/timeout/exit. */
    UNLOCKED
}

enum class VaultKeystoreHealth {
    OK,
    BIOMETRIC_KEY_INVALIDATED,
    KEYSTORE_UNAVAILABLE
}

/**
 * Decrypted view of a Vault item — constructed ONLY while the Vault is unlocked with a valid VMK.
 */
data class DecryptedVaultItem(
    val id: String,
    val displayName: String,
    val originalMimeType: String,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val sha256Checksum: String,
    val category: VaultCategory,
    val chunkCount: Int,
    val createdAtEpochMillis: Long,
    val blobFileName: String
)

/**
 * Result of initial vault creation: includes the one-time high-entropy offline Recovery Key.
 */
data class VaultCreationReceipt(
    val recoveryKey: String,
    val biometricBound: Boolean
)

/**
 * Current security status & configuration summary of the Private Vault (contains zero secrets).
 */
data class VaultStatusSnapshot(
    val lockState: VaultLockState,
    val isBiometricEnabled: Boolean,
    val autoLockTimeout: VaultAutoLockTimeout,
    val failedPinAttempts: Int,
    val lockoutUntilEpochMillis: Long,
    val keystoreHealth: VaultKeystoreHealth,
    val encryptedItemCount: Int
)
