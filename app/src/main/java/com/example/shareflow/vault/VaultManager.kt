package com.example.shareflow.vault

import android.content.Context
import android.util.Base64
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.VaultConfigDao
import com.example.shareflow.data.local.database.dao.VaultItemDao
import com.example.shareflow.data.local.database.entity.VaultConfigEntity
import com.example.shareflow.data.local.database.entity.VaultItemEntity
import com.example.shareflow.domain.model.DecryptedVaultItem
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.model.VaultCategory
import com.example.shareflow.domain.model.VaultCreationReceipt
import com.example.shareflow.domain.model.VaultKeystoreHealth
import com.example.shareflow.domain.model.VaultLockState
import com.example.shareflow.domain.model.VaultStatusSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Production coordinator for ShareFlow Private Vault.
 *
 * Key Hierarchy:
 * 1. Per-file random 256-bit DEK -> encrypts file blob in 64 KB AES-256-GCM chunks with unique 96-bit nonces + AAD.
 * 2. Random 256-bit Vault Master Key (VMK) -> wraps every file's DEK & encrypts sensitive file metadata JSON.
 *    Held ONLY in volatile RAM while unlocked and zeroized immediately on lock / timeout / background / process exit.
 * 3. KEKs wrapping VMK:
 *    - PIN KEK (PBKDF2WithHmacSHA256 + 32-byte random salt; zero plaintext PIN storage)
 *    - Android Keystore Hardware KEK (for BiometricPrompt unlock; detects KeyPermanentlyInvalidatedException cleanly)
 *    - Offline Recovery Key KEK (PBKDF2WithHmacSHA256 + 32-byte random salt for PIN recovery)
 */
class VaultManager(
    context: Context,
    private val vaultConfigDao: VaultConfigDao,
    private val vaultItemDao: VaultItemDao,
    private val cryptoEngine: VaultCryptoEngine,
    private val keystoreProvider: VaultKeystoreProvider,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock,
    private val vaultBlobDirProvider: () -> File = {
        File(context.applicationContext.filesDir, VAULT_BLOB_DIR_NAME).apply { mkdirs() }
    }
) {
    private val mutex = Mutex()

    // Volatile in-memory VMK: strictly null when locked or after process restart; zeroized on lock.
    private var unlockedVmk: ByteArray? = null
    private var backgroundedAtEpochMillis: Long? = null
    private var lastActivityEpochMillis: Long = clock.nowEpochMillis()
    private var currentKeystoreHealth: VaultKeystoreHealth =
        if (keystoreProvider.isAvailable()) VaultKeystoreHealth.OK else VaultKeystoreHealth.KEYSTORE_UNAVAILABLE

    private val _status = MutableStateFlow(
        VaultStatusSnapshot(
            lockState = VaultLockState.NOT_INITIALIZED,
            isBiometricEnabled = false,
            autoLockTimeout = VaultAutoLockTimeout.MINUTES_1,
            failedPinAttempts = 0,
            lockoutUntilEpochMillis = 0L,
            keystoreHealth = currentKeystoreHealth,
            encryptedItemCount = 0
        )
    )
    val status: StateFlow<VaultStatusSnapshot> = _status.asStateFlow()

    private val _decryptedItems = MutableStateFlow<List<DecryptedVaultItem>>(emptyList())
    val decryptedItems: StateFlow<List<DecryptedVaultItem>> = _decryptedItems.asStateFlow()

    /** Loads persisted vault configuration status without ever decrypting contents or unlocking. */
    suspend fun refreshStatus(): VaultStatusSnapshot = withContext(dispatchers.io) {
        mutex.withLock {
            refreshStatusLocked()
        }
    }

    /**
     * Creates a brand-new Private Vault with a PIN, optional Android Keystore biometric binding,
     * and a high-entropy one-time offline Recovery Key.
     */
    suspend fun createVault(
        pin: String,
        enableBiometric: Boolean,
        autoLockTimeout: VaultAutoLockTimeout = VaultAutoLockTimeout.MINUTES_1
    ): AppResult<VaultCreationReceipt> = withContext(dispatchers.io) {
        if (!isValidPin(pin)) {
            return@withContext AppResult.Failure(
                AppError.Validation("PIN must be $MIN_PIN_LENGTH to $MAX_PIN_LENGTH digits.")
            )
        }
        mutex.withLock {
            if (vaultConfigDao.getConfig() != null) {
                return@withContext AppResult.Failure(
                    AppError.Validation("Private Vault already exists.")
                )
            }

            val vmk = cryptoEngine.generateAes256Key()
            val pinChars = pin.toCharArray()
            val pinSalt = cryptoEngine.generateRandomBytes(SALT_BYTES)
            val pinMaterial = cryptoEngine.derivePinMaterial(pinChars, pinSalt)
            Arrays.fill(pinChars, '\u0000')

            val wrappedPinRes = cryptoEngine.encryptAes256Gcm(
                keyBytes = pinMaterial.kek,
                plaintext = vmk,
                aad = AAD_VMK_PIN
            )
            if (wrappedPinRes is AppResult.Failure) {
                pinMaterial.zeroize()
                Arrays.fill(vmk, 0)
                return@withContext wrappedPinRes
            }
            val wrappedPin = (wrappedPinRes as AppResult.Success).data

            val recoveryKey = cryptoEngine.generateRecoveryKey()
            val recoverySalt = cryptoEngine.generateRandomBytes(SALT_BYTES)
            val recoveryKek = cryptoEngine.deriveRecoveryKek(recoveryKey, recoverySalt)
            val wrappedRecoveryRes = cryptoEngine.encryptAes256Gcm(
                keyBytes = recoveryKek,
                plaintext = vmk,
                aad = AAD_VMK_RECOVERY
            )
            Arrays.fill(recoveryKek, 0)
            if (wrappedRecoveryRes is AppResult.Failure) {
                pinMaterial.zeroize()
                Arrays.fill(vmk, 0)
                return@withContext wrappedRecoveryRes
            }
            val wrappedRecovery = (wrappedRecoveryRes as AppResult.Success).data

            val keystoreAlias = AndroidVaultKeystoreProvider.DEFAULT_VAULT_KEYSTORE_ALIAS
            var wrappedKeystoreIv: String? = null
            var wrappedKeystoreCt: String? = null
            var biometricBound = false

            if (enableBiometric && keystoreProvider.isAvailable()) {
                val wrapKs = keystoreProvider.wrapKey(keystoreAlias, vmk, AAD_VMK_KEYSTORE)
                if (wrapKs is AppResult.Success) {
                    wrappedKeystoreIv = b64Encode(wrapKs.data.iv)
                    wrappedKeystoreCt = b64Encode(wrapKs.data.cipherText)
                    biometricBound = true
                    currentKeystoreHealth = VaultKeystoreHealth.OK
                }
            }

            val now = clock.nowEpochMillis()
            val config = VaultConfigEntity(
                id = VaultConfigEntity.SINGLETON_ID,
                pinSaltB64 = b64Encode(pinSalt),
                pinVerifierB64 = b64Encode(pinMaterial.verifier),
                pinIterations = cryptoEngine.configuredIterations,
                wrappedVmkPinIvB64 = b64Encode(wrappedPin.iv),
                wrappedVmkPinCtB64 = b64Encode(wrappedPin.cipherText),
                wrappedVmkKeystoreIvB64 = wrappedKeystoreIv,
                wrappedVmkKeystoreCtB64 = wrappedKeystoreCt,
                recoverySaltB64 = b64Encode(recoverySalt),
                wrappedVmkRecoveryIvB64 = b64Encode(wrappedRecovery.iv),
                wrappedVmkRecoveryCtB64 = b64Encode(wrappedRecovery.cipherText),
                biometricEnabled = biometricBound,
                autoLockTimeoutSeconds = autoLockTimeout.seconds,
                failedAttempts = 0,
                lockoutUntilEpochMillis = 0L,
                keystoreKeyAlias = keystoreAlias,
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now
            )
            pinMaterial.zeroize()

            vaultConfigDao.upsertConfig(config)
            vaultBlobDirProvider().mkdirs()

            setUnlockedVmkLocked(vmk)
            lastActivityEpochMillis = now
            refreshDecryptedItemsLocked()
            refreshStatusLocked()

            AppResult.Success(
                VaultCreationReceipt(
                    recoveryKey = recoveryKey,
                    biometricBound = biometricBound
                )
            )
        }
    }

    /**
     * Authenticates using user PIN, enforces exponential lockout after repeated failures,
     * unwraps VMK into volatile memory, and decrypts vault metadata.
     */
    suspend fun unlockWithPin(pin: String): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Private Vault is not initialized."))

            val now = clock.nowEpochMillis()
            if (config.lockoutUntilEpochMillis > now) {
                val waitSec = ((config.lockoutUntilEpochMillis - now + 999L) / 1000L).coerceAtLeast(1L)
                refreshStatusLocked()
                return@withContext AppResult.Failure(
                    AppError.Security("Too many incorrect PIN attempts. Try again in ${waitSec}s.")
                )
            }

            val pinChars = pin.toCharArray()
            val salt = b64Decode(config.pinSaltB64)
            val expectedVerifier = b64Decode(config.pinVerifierB64)
            val derived = cryptoEngine.derivePinMaterial(pinChars, salt, config.pinIterations)
            Arrays.fill(pinChars, '\u0000')

            if (!cryptoEngine.constantTimeEquals(derived.verifier, expectedVerifier)) {
                derived.zeroize()
                val newFailed = config.failedAttempts + 1
                val lockoutUntil = if (newFailed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                    val multiplier = 1L shl (newFailed - MAX_ATTEMPTS_BEFORE_LOCKOUT).coerceAtMost(5)
                    now + (BASE_LOCKOUT_SECONDS * multiplier * 1000L)
                } else {
                    0L
                }
                vaultConfigDao.upsertConfig(
                    config.copy(
                        failedAttempts = newFailed,
                        lockoutUntilEpochMillis = lockoutUntil,
                        updatedAtEpochMillis = now
                    )
                )
                refreshStatusLocked()
                return@withContext AppResult.Failure(
                    AppError.Security(
                        if (lockoutUntil > now) {
                            "Incorrect PIN. Vault temporarily locked after $newFailed failed attempts."
                        } else {
                            "Incorrect PIN."
                        }
                    )
                )
            }

            val unwrapRes = cryptoEngine.decryptAes256Gcm(
                keyBytes = derived.kek,
                box = AesGcmBox(
                    iv = b64Decode(config.wrappedVmkPinIvB64),
                    cipherText = b64Decode(config.wrappedVmkPinCtB64)
                ),
                aad = AAD_VMK_PIN
            )
            derived.zeroize()

            if (unwrapRes is AppResult.Failure) {
                return@withContext AppResult.Failure(
                    AppError.IntegrityMismatch("Vault master key envelope could not be authenticated.", "", "")
                )
            }

            val vmk = (unwrapRes as AppResult.Success).data
            vaultConfigDao.upsertConfig(
                config.copy(
                    failedAttempts = 0,
                    lockoutUntilEpochMillis = 0L,
                    updatedAtEpochMillis = now
                )
            )
            setUnlockedVmkLocked(vmk)
            lastActivityEpochMillis = now
            backgroundedAtEpochMillis = null
            refreshDecryptedItemsLocked()
            refreshStatusLocked()
            AppResult.Success(Unit)
        }
    }

    /**
     * Completes unlock after AndroidX BiometricPrompt succeeded.
     * Unwraps the Android Keystore-protected VMK and explicitly detects Keystore key invalidation.
     */
    suspend fun unlockWithBiometricAfterPromptSuccess(): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Private Vault is not initialized."))

            val ksIv = config.wrappedVmkKeystoreIvB64
            val ksCt = config.wrappedVmkKeystoreCtB64
            if (!config.biometricEnabled || ksIv.isNullOrBlank() || ksCt.isNullOrBlank()) {
                return@withContext AppResult.Failure(
                    AppError.PermissionDenied("Biometric unlock is not enabled for this vault.")
                )
            }

            when (
                val unwrap = keystoreProvider.unwrapKey(
                    alias = config.keystoreKeyAlias,
                    box = AesGcmBox(iv = b64Decode(ksIv), cipherText = b64Decode(ksCt)),
                    aad = AAD_VMK_KEYSTORE
                )
            ) {
                is KeystoreUnwrapResult.Success -> {
                    val now = clock.nowEpochMillis()
                    currentKeystoreHealth = VaultKeystoreHealth.OK
                    vaultConfigDao.upsertConfig(
                        config.copy(
                            failedAttempts = 0,
                            lockoutUntilEpochMillis = 0L,
                            updatedAtEpochMillis = now
                        )
                    )
                    setUnlockedVmkLocked(unwrap.plainBytes)
                    lastActivityEpochMillis = now
                    backgroundedAtEpochMillis = null
                    refreshDecryptedItemsLocked()
                    refreshStatusLocked()
                    AppResult.Success(Unit)
                }

                is KeystoreUnwrapResult.KeyInvalidated -> {
                    // Keystore key invalidated (e.g. new fingerprint enrolled or Keystore wiped).
                    // Disable stale biometric wrapper cleanly WITHOUT destroying user vault files!
                    currentKeystoreHealth = VaultKeystoreHealth.BIOMETRIC_KEY_INVALIDATED
                    vaultConfigDao.upsertConfig(
                        config.copy(
                            biometricEnabled = false,
                            wrappedVmkKeystoreIvB64 = null,
                            wrappedVmkKeystoreCtB64 = null,
                            updatedAtEpochMillis = clock.nowEpochMillis()
                        )
                    )
                    refreshStatusLocked()
                    AppResult.Failure(AppError.Security(unwrap.reason))
                }

                is KeystoreUnwrapResult.Failure -> {
                    refreshStatusLocked()
                    AppResult.Failure(unwrap.error)
                }
            }
        }
    }

    /**
     * Records biometric authentication failure/cancellation. Never unlocks or exposes decrypted metadata.
     */
    suspend fun handleBiometricFailure(reason: String): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            // Strictly remain locked if not already unlocked
            if (unlockedVmk == null) {
                _decryptedItems.value = emptyList()
            }
            refreshStatusLocked()
            AppResult.Failure(AppError.PermissionDenied(reason.ifBlank { "Biometric authentication failed." }))
        }
    }

    /**
     * Changes the Vault PIN by authenticating the current PIN, unwrapping VMK, and re-wrapping VMK
     * under a newly salted PBKDF2-HMAC-SHA256 KEK (existing encrypted blobs remain intact).
     */
    suspend fun changePin(currentPin: String, newPin: String): AppResult<Unit> = withContext(dispatchers.io) {
        if (!isValidPin(newPin)) {
            return@withContext AppResult.Failure(
                AppError.Validation("New PIN must be $MIN_PIN_LENGTH to $MAX_PIN_LENGTH digits.")
            )
        }
        mutex.withLock {
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Private Vault is not initialized."))

            val now = clock.nowEpochMillis()
            if (config.lockoutUntilEpochMillis > now) {
                val waitSec = ((config.lockoutUntilEpochMillis - now + 999L) / 1000L).coerceAtLeast(1L)
                refreshStatusLocked()
                return@withContext AppResult.Failure(
                    AppError.Security("Too many failed attempts. Try again in ${waitSec}s.")
                )
            }

            val curChars = currentPin.toCharArray()
            val curMaterial = cryptoEngine.derivePinMaterial(
                curChars,
                b64Decode(config.pinSaltB64),
                config.pinIterations
            )
            Arrays.fill(curChars, '\u0000')
            if (!cryptoEngine.constantTimeEquals(curMaterial.verifier, b64Decode(config.pinVerifierB64))) {
                curMaterial.zeroize()
                val newFailed = config.failedAttempts + 1
                val lockoutUntil = if (newFailed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                    val multiplier = 1L shl (newFailed - MAX_ATTEMPTS_BEFORE_LOCKOUT).coerceAtMost(5)
                    now + (BASE_LOCKOUT_SECONDS * multiplier * 1000L)
                } else {
                    0L
                }
                vaultConfigDao.upsertConfig(
                    config.copy(
                        failedAttempts = newFailed,
                        lockoutUntilEpochMillis = lockoutUntil,
                        updatedAtEpochMillis = now
                    )
                )
                refreshStatusLocked()
                return@withContext AppResult.Failure(
                    AppError.Security(
                        if (lockoutUntil > now) {
                            "Current PIN is incorrect. Vault temporarily locked after $newFailed failed attempts."
                        } else {
                            "Current PIN is incorrect."
                        }
                    )
                )
            }

            val vmkRes = cryptoEngine.decryptAes256Gcm(
                keyBytes = curMaterial.kek,
                box = AesGcmBox(
                    iv = b64Decode(config.wrappedVmkPinIvB64),
                    cipherText = b64Decode(config.wrappedVmkPinCtB64)
                ),
                aad = AAD_VMK_PIN
            )
            curMaterial.zeroize()
            if (vmkRes is AppResult.Failure) return@withContext vmkRes
            val vmk = (vmkRes as AppResult.Success).data

            val newSalt = cryptoEngine.generateRandomBytes(SALT_BYTES)
            val newChars = newPin.toCharArray()
            val newMaterial = cryptoEngine.derivePinMaterial(newChars, newSalt)
            Arrays.fill(newChars, '\u0000')

            val wrappedNewPinRes = cryptoEngine.encryptAes256Gcm(
                keyBytes = newMaterial.kek,
                plaintext = vmk,
                aad = AAD_VMK_PIN
            )
            if (wrappedNewPinRes is AppResult.Failure) {
                newMaterial.zeroize()
                Arrays.fill(vmk, 0)
                return@withContext wrappedNewPinRes
            }
            val wrappedNewPin = (wrappedNewPinRes as AppResult.Success).data

            vaultConfigDao.upsertConfig(
                config.copy(
                    pinSaltB64 = b64Encode(newSalt),
                    pinVerifierB64 = b64Encode(newMaterial.verifier),
                    pinIterations = cryptoEngine.configuredIterations,
                    wrappedVmkPinIvB64 = b64Encode(wrappedNewPin.iv),
                    wrappedVmkPinCtB64 = b64Encode(wrappedNewPin.cipherText),
                    failedAttempts = 0,
                    lockoutUntilEpochMillis = 0L,
                    updatedAtEpochMillis = clock.nowEpochMillis()
                )
            )
            newMaterial.zeroize()
            setUnlockedVmkLocked(vmk)
            refreshStatusLocked()
            AppResult.Success(Unit)
        }
    }

    /**
     * Recovers access to a forgotten PIN using the high-entropy offline Recovery Key.
     * Unwraps VMK via Recovery KEK and sets a brand-new PIN without losing encrypted files.
     */
    suspend fun recoverWithRecoveryKey(recoveryKey: String, newPin: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            if (!isValidPin(newPin)) {
                return@withContext AppResult.Failure(
                    AppError.Validation("New PIN must be $MIN_PIN_LENGTH to $MAX_PIN_LENGTH digits.")
                )
            }
            mutex.withLock {
                val config = vaultConfigDao.getConfig()
                    ?: return@withContext AppResult.Failure(AppError.NotFound("Private Vault is not initialized."))

                val now = clock.nowEpochMillis()
                if (config.lockoutUntilEpochMillis > now) {
                    val waitSec = ((config.lockoutUntilEpochMillis - now + 999L) / 1000L).coerceAtLeast(1L)
                    refreshStatusLocked()
                    return@withContext AppResult.Failure(
                        AppError.Security("Too many failed attempts. Try again in ${waitSec}s.")
                    )
                }

                val recoveryKek = cryptoEngine.deriveRecoveryKek(
                    recoveryKey = recoveryKey,
                    salt = b64Decode(config.recoverySaltB64),
                    iterations = config.pinIterations
                )
                val vmkRes = cryptoEngine.decryptAes256Gcm(
                    keyBytes = recoveryKek,
                    box = AesGcmBox(
                        iv = b64Decode(config.wrappedVmkRecoveryIvB64),
                        cipherText = b64Decode(config.wrappedVmkRecoveryCtB64)
                    ),
                    aad = AAD_VMK_RECOVERY
                )
                Arrays.fill(recoveryKek, 0)
                if (vmkRes is AppResult.Failure) {
                    val newFailed = config.failedAttempts + 1
                    val lockoutUntil = if (newFailed >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
                        val multiplier = 1L shl (newFailed - MAX_ATTEMPTS_BEFORE_LOCKOUT).coerceAtMost(5)
                        now + (BASE_LOCKOUT_SECONDS * multiplier * 1000L)
                    } else {
                        0L
                    }
                    vaultConfigDao.upsertConfig(
                        config.copy(
                            failedAttempts = newFailed,
                            lockoutUntilEpochMillis = lockoutUntil,
                            updatedAtEpochMillis = now
                        )
                    )
                    refreshStatusLocked()
                    return@withContext AppResult.Failure(
                        AppError.Security(
                            if (lockoutUntil > now) {
                                "Invalid Recovery Key. Vault temporarily locked after $newFailed failed attempts."
                            } else {
                                "Invalid Recovery Key."
                            }
                        )
                    )
                }
                val vmk = (vmkRes as AppResult.Success).data

                val newSalt = cryptoEngine.generateRandomBytes(SALT_BYTES)
                val newChars = newPin.toCharArray()
                val newMaterial = cryptoEngine.derivePinMaterial(newChars, newSalt)
                Arrays.fill(newChars, '\u0000')
                val wrappedPinRes = cryptoEngine.encryptAes256Gcm(newMaterial.kek, vmk, AAD_VMK_PIN)
                if (wrappedPinRes is AppResult.Failure) {
                    newMaterial.zeroize()
                    Arrays.fill(vmk, 0)
                    return@withContext wrappedPinRes
                }
                val wrappedPin = (wrappedPinRes as AppResult.Success).data

                val completedAt = clock.nowEpochMillis()
                vaultConfigDao.upsertConfig(
                    config.copy(
                        pinSaltB64 = b64Encode(newSalt),
                        pinVerifierB64 = b64Encode(newMaterial.verifier),
                        pinIterations = cryptoEngine.configuredIterations,
                        wrappedVmkPinIvB64 = b64Encode(wrappedPin.iv),
                        wrappedVmkPinCtB64 = b64Encode(wrappedPin.cipherText),
                        failedAttempts = 0,
                        lockoutUntilEpochMillis = 0L,
                        updatedAtEpochMillis = completedAt
                    )
                )
                newMaterial.zeroize()
                setUnlockedVmkLocked(vmk)
                lastActivityEpochMillis = completedAt
                refreshDecryptedItemsLocked()
                refreshStatusLocked()
                AppResult.Success(Unit)
            }
        }

    suspend fun setBiometricEnabled(enabled: Boolean): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            val vmk = unlockedVmk
                ?: return@withContext AppResult.Failure(AppError.PermissionDenied("Unlock vault first."))
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Vault config missing."))

            if (enabled) {
                if (!keystoreProvider.isAvailable()) {
                    return@withContext AppResult.Failure(
                        AppError.Security("Android Keystore is unavailable on this device.")
                    )
                }
                val wrapRes = keystoreProvider.wrapKey(config.keystoreKeyAlias, vmk, AAD_VMK_KEYSTORE)
                if (wrapRes is AppResult.Failure) return@withContext wrapRes
                val box = (wrapRes as AppResult.Success).data
                currentKeystoreHealth = VaultKeystoreHealth.OK
                vaultConfigDao.upsertConfig(
                    config.copy(
                        biometricEnabled = true,
                        wrappedVmkKeystoreIvB64 = b64Encode(box.iv),
                        wrappedVmkKeystoreCtB64 = b64Encode(box.cipherText),
                        updatedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
            } else {
                keystoreProvider.deleteKey(config.keystoreKeyAlias)
                vaultConfigDao.upsertConfig(
                    config.copy(
                        biometricEnabled = false,
                        wrappedVmkKeystoreIvB64 = null,
                        wrappedVmkKeystoreCtB64 = null,
                        updatedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
            }
            refreshStatusLocked()
            AppResult.Success(Unit)
        }
    }

    suspend fun setAutoLockTimeout(timeout: VaultAutoLockTimeout): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            if (unlockedVmk == null) {
                return@withContext AppResult.Failure(AppError.PermissionDenied("Unlock vault first."))
            }
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Vault config missing."))
            vaultConfigDao.upsertConfig(
                config.copy(
                    autoLockTimeoutSeconds = timeout.seconds,
                    updatedAtEpochMillis = clock.nowEpochMillis()
                )
            )
            refreshStatusLocked()
            AppResult.Success(Unit)
        }
    }

    /** Immediately locks the vault, zeroizes in-memory VMK, and clears decrypted metadata. */
    suspend fun lock() = withContext(dispatchers.io) {
        mutex.withLock {
            lockLocked()
            refreshStatusLocked()
        }
    }

    suspend fun onAppBackgrounded() = withContext(dispatchers.io) {
        mutex.withLock {
            if (unlockedVmk == null) return@withLock
            val now = clock.nowEpochMillis()
            backgroundedAtEpochMillis = now
            val timeoutSec = vaultConfigDao.getConfig()?.autoLockTimeoutSeconds
                ?: VaultAutoLockTimeout.MINUTES_1.seconds
            if (timeoutSec <= 0L) {
                lockLocked()
                refreshStatusLocked()
            }
        }
    }

    suspend fun checkAutoLock(): Boolean = withContext(dispatchers.io) {
        mutex.withLock {
            if (unlockedVmk == null) return@withLock true
            val config = vaultConfigDao.getConfig() ?: return@withLock true
            val timeoutMs = config.autoLockTimeoutSeconds * 1000L
            val now = clock.nowEpochMillis()
            val bgAt = backgroundedAtEpochMillis
            val shouldLock = if (bgAt != null) {
                (now - bgAt) >= timeoutMs
            } else {
                timeoutMs > 0L && (now - lastActivityEpochMillis) >= timeoutMs
            }
            if (shouldLock) {
                lockLocked()
                refreshStatusLocked()
                true
            } else {
                backgroundedAtEpochMillis = null
                lastActivityEpochMillis = now
                false
            }
        }
    }

    /**
     * Imports a file stream into the Private Vault:
     * 1. Requires unlocked vault.
     * 2. Generates a fresh 256-bit DEK and streams AES-256-GCM encryption atomically to internal blob storage.
     * 3. Wraps DEK with VMK and encrypts sensitive metadata separately with VMK.
     * 4. Commits metadata record to Room only AFTER encrypted blob succeeds cleanly.
     */
    suspend fun importFile(
        displayName: String,
        mimeType: String,
        categoryOverride: VaultCategory? = null,
        openInputStream: () -> InputStream
    ): AppResult<DecryptedVaultItem> = withContext(dispatchers.io) {
        mutex.withLock {
            val vmk = unlockedVmk
                ?: return@withContext AppResult.Failure(AppError.PermissionDenied("Vault is locked."))
            val config = vaultConfigDao.getConfig()
                ?: return@withContext AppResult.Failure(AppError.NotFound("Vault is not initialized."))

            val itemId = UUID.randomUUID().toString()
            val blobFileName = "${itemId.replace("-", "")}.sfv"
            val blobFile = File(vaultBlobDirProvider().apply { mkdirs() }, blobFileName)
            val dek = cryptoEngine.generateAes256Key()
            val category = categoryOverride ?: VaultCategory.fromMimeType(mimeType)

            try {
                val encRes = openInputStream().use { stream ->
                    cryptoEngine.encryptStreamAtomically(
                        itemId = itemId,
                        dek = dek,
                        inputStream = stream,
                        targetFile = blobFile
                    )
                }
                if (encRes is AppResult.Failure) {
                    return@withContext encRes
                }
                val enc = (encRes as AppResult.Success).data

                // Wrap per-file DEK with VMK
                val wrappedDekRes = cryptoEngine.encryptAes256Gcm(
                    keyBytes = vmk,
                    plaintext = dek,
                    aad = "DEK:$itemId".toByteArray(Charsets.UTF_8)
                )
                if (wrappedDekRes is AppResult.Failure) {
                    cryptoEngine.secureDeleteFile(blobFile)
                    return@withContext wrappedDekRes
                }
                val wrappedDek = (wrappedDekRes as AppResult.Success).data

                // Encrypt sensitive metadata separately from file content
                val now = clock.nowEpochMillis()
                val metaJson = JSONObject().apply {
                    put("displayName", displayName)
                    put("mimeType", mimeType)
                    put("sizeBytes", enc.originalSizeBytes)
                    put("sha256", enc.sha256Hex)
                    put("category", category.name)
                    put("createdAt", now)
                }.toString().toByteArray(Charsets.UTF_8)

                val encMetaRes = cryptoEngine.encryptAes256Gcm(
                    keyBytes = vmk,
                    plaintext = metaJson,
                    aad = "META:$itemId".toByteArray(Charsets.UTF_8)
                )
                Arrays.fill(metaJson, 0)
                if (encMetaRes is AppResult.Failure) {
                    cryptoEngine.secureDeleteFile(blobFile)
                    return@withContext encMetaRes
                }
                val encMeta = (encMetaRes as AppResult.Success).data

                // Store strictly opaque non-sensitive placeholder in plaintext Room columns
                val entity = VaultItemEntity(
                    id = itemId,
                    displayName = "vault_${itemId.take(8)}",
                    encryptedFileName = blobFileName,
                    originalMimeType = "application/octet-stream",
                    originalSizeBytes = 0L,
                    sha256Checksum = "",
                    keyAlias = config.keystoreKeyAlias,
                    createdAtEpochMillis = now,
                    category = category.name,
                    encryptedMetadataB64 = b64Encode(encMeta.cipherText),
                    metadataIvB64 = b64Encode(encMeta.iv),
                    wrappedDekB64 = b64Encode(wrappedDek.cipherText),
                    dekIvB64 = b64Encode(wrappedDek.iv),
                    fileNoncePrefixB64 = b64Encode(enc.noncePrefix),
                    encryptedSizeBytes = enc.encryptedSizeBytes,
                    chunkCount = enc.chunkCount
                )
                vaultItemDao.upsert(entity)
                lastActivityEpochMillis = now

                val decrypted = DecryptedVaultItem(
                    id = itemId,
                    displayName = displayName,
                    originalMimeType = mimeType,
                    originalSizeBytes = enc.originalSizeBytes,
                    encryptedSizeBytes = enc.encryptedSizeBytes,
                    sha256Checksum = enc.sha256Hex,
                    category = category,
                    chunkCount = enc.chunkCount,
                    createdAtEpochMillis = now,
                    blobFileName = blobFileName
                )
                refreshDecryptedItemsLocked()
                refreshStatusLocked()
                AppResult.Success(decrypted)
            } finally {
                Arrays.fill(dek, 0)
            }
        }
    }

    /**
     * Decrypts a vault file into memory ONLY after authentication for safe in-app viewing (images/text).
     * Never leaves plaintext copies on disk.
     */
    suspend fun decryptToMemory(itemId: String, maxBytes: Long = MAX_IN_MEMORY_PREVIEW_BYTES): AppResult<ByteArray> =
        withContext(dispatchers.io) {
            mutex.withLock {
                val vmk = unlockedVmk
                    ?: return@withContext AppResult.Failure(AppError.PermissionDenied("Vault is locked."))
                val entity = vaultItemDao.findById(itemId)
                    ?: return@withContext AppResult.Failure(AppError.NotFound("Vault item not found."))
                val meta = decryptItemMetadata(vmk, entity)
                    ?: return@withContext AppResult.Failure(
                        AppError.IntegrityMismatch("Encrypted vault metadata failed authentication.", "", "")
                    )
                if (meta.originalSizeBytes > maxBytes) {
                    return@withContext AppResult.Failure(
                        AppError.Validation("File exceeds in-memory preview limit; export it after confirmation to view externally.")
                    )
                }

                val dekRes = unwrapItemDek(vmk, entity)
                if (dekRes is AppResult.Failure) return@withContext dekRes
                val dek = (dekRes as AppResult.Success).data
                try {
                    val out = ByteArrayOutputStream(meta.originalSizeBytes.toInt().coerceAtLeast(64))
                    val blobFile = File(vaultBlobDirProvider(), entity.encryptedFileName)
                    val decRes = cryptoEngine.decryptStreamVerified(
                        itemId = entity.id,
                        dek = dek,
                        encryptedFile = blobFile,
                        expectedNoncePrefix = b64Decode(entity.fileNoncePrefixB64),
                        expectedChunkCount = entity.chunkCount,
                        expectedSha256Hex = meta.sha256Checksum,
                        outputStream = out
                    )
                    if (decRes is AppResult.Failure) return@withContext decRes
                    lastActivityEpochMillis = clock.nowEpochMillis()
                    AppResult.Success(out.toByteArray())
                } finally {
                    Arrays.fill(dek, 0)
                }
            }
        }

    /**
     * Decrypts and exports a vault file to a user-confirmed destination [OutputStream] with full
     * AES-GCM + SHA-256 verification.
     */
    suspend fun exportFile(
        itemId: String,
        openOutputStream: () -> OutputStream
    ): AppResult<Long> = withContext(dispatchers.io) {
        mutex.withLock {
            val vmk = unlockedVmk
                ?: return@withContext AppResult.Failure(AppError.PermissionDenied("Vault is locked."))
            val entity = vaultItemDao.findById(itemId)
                ?: return@withContext AppResult.Failure(AppError.NotFound("Vault item not found."))
            val meta = decryptItemMetadata(vmk, entity)
                ?: return@withContext AppResult.Failure(
                    AppError.IntegrityMismatch("Encrypted vault metadata failed authentication.", "", "")
                )

            val dekRes = unwrapItemDek(vmk, entity)
            if (dekRes is AppResult.Failure) return@withContext dekRes
            val dek = (dekRes as AppResult.Success).data
            try {
                val blobFile = File(vaultBlobDirProvider(), entity.encryptedFileName)
                val decRes = openOutputStream().use { out ->
                    cryptoEngine.decryptStreamVerified(
                        itemId = entity.id,
                        dek = dek,
                        encryptedFile = blobFile,
                        expectedNoncePrefix = b64Decode(entity.fileNoncePrefixB64),
                        expectedChunkCount = entity.chunkCount,
                        expectedSha256Hex = meta.sha256Checksum,
                        outputStream = out
                    )
                }
                if (decRes is AppResult.Success) {
                    lastActivityEpochMillis = clock.nowEpochMillis()
                }
                decRes
            } finally {
                Arrays.fill(dek, 0)
            }
        }
    }

    /**
     * Securely deletes an encrypted vault item: overwrites blob with random bytes & zeros, syncs,
     * deletes file, and deletes its encrypted metadata + wrapped DEK from Room.
     */
    suspend fun secureDeleteItem(itemId: String): AppResult<Unit> = withContext(dispatchers.io) {
        mutex.withLock {
            if (unlockedVmk == null) {
                return@withContext AppResult.Failure(AppError.PermissionDenied("Vault is locked."))
            }
            val entity = vaultItemDao.findById(itemId)
                ?: return@withContext AppResult.Failure(AppError.NotFound("Vault item not found."))
            val blobFile = File(vaultBlobDirProvider(), entity.encryptedFileName)
            cryptoEngine.secureDeleteFile(blobFile)
            vaultItemDao.deleteById(itemId)
            lastActivityEpochMillis = clock.nowEpochMillis()
            refreshDecryptedItemsLocked()
            refreshStatusLocked()
            AppResult.Success(Unit)
        }
    }

    fun getEncryptedBlobFile(blobFileName: String): File =
        File(vaultBlobDirProvider(), blobFileName)

    private fun unwrapItemDek(vmk: ByteArray, entity: VaultItemEntity): AppResult<ByteArray> {
        if (entity.wrappedDekB64.isBlank() || entity.dekIvB64.isBlank()) {
            return AppResult.Failure(AppError.IntegrityMismatch("Missing wrapped DEK for vault item.", "", ""))
        }
        return cryptoEngine.decryptAes256Gcm(
            keyBytes = vmk,
            box = AesGcmBox(
                iv = b64Decode(entity.dekIvB64),
                cipherText = b64Decode(entity.wrappedDekB64)
            ),
            aad = "DEK:${entity.id}".toByteArray(Charsets.UTF_8)
        )
    }

    private fun decryptItemMetadata(vmk: ByteArray, entity: VaultItemEntity): DecryptedVaultItem? {
        if (entity.encryptedMetadataB64.isBlank() || entity.metadataIvB64.isBlank()) {
            return null
        }
        val plainRes = cryptoEngine.decryptAes256Gcm(
            keyBytes = vmk,
            box = AesGcmBox(
                iv = b64Decode(entity.metadataIvB64),
                cipherText = b64Decode(entity.encryptedMetadataB64)
            ),
            aad = "META:${entity.id}".toByteArray(Charsets.UTF_8)
        )
        if (plainRes !is AppResult.Success) return null
        return runCatching {
            val json = JSONObject(String(plainRes.data, Charsets.UTF_8))
            Arrays.fill(plainRes.data, 0)
            DecryptedVaultItem(
                id = entity.id,
                displayName = json.getString("displayName"),
                originalMimeType = json.getString("mimeType"),
                originalSizeBytes = json.getLong("sizeBytes"),
                encryptedSizeBytes = entity.encryptedSizeBytes,
                sha256Checksum = json.getString("sha256"),
                category = runCatching { VaultCategory.valueOf(json.getString("category")) }
                    .getOrDefault(VaultCategory.DOCUMENTS),
                chunkCount = entity.chunkCount,
                createdAtEpochMillis = json.optLong("createdAt", entity.createdAtEpochMillis),
                blobFileName = entity.encryptedFileName
            )
        }.getOrNull()
    }

    private suspend fun refreshDecryptedItemsLocked() {
        val vmk = unlockedVmk
        if (vmk == null) {
            _decryptedItems.value = emptyList()
            return
        }
        val entities = vaultItemDao.observeAll().first()
        _decryptedItems.value = entities.mapNotNull { decryptItemMetadata(vmk, it) }
    }

    private suspend fun refreshStatusLocked(): VaultStatusSnapshot {
        val config = vaultConfigDao.getConfig()
        val itemCount = vaultItemDao.observeAll().first().size
        val lockState = when {
            config == null -> VaultLockState.NOT_INITIALIZED
            unlockedVmk != null -> VaultLockState.UNLOCKED
            else -> VaultLockState.LOCKED
        }
        val snapshot = VaultStatusSnapshot(
            lockState = lockState,
            isBiometricEnabled = config?.biometricEnabled ?: false,
            autoLockTimeout = VaultAutoLockTimeout.fromSeconds(
                config?.autoLockTimeoutSeconds ?: VaultAutoLockTimeout.MINUTES_1.seconds
            ),
            failedPinAttempts = config?.failedAttempts ?: 0,
            lockoutUntilEpochMillis = config?.lockoutUntilEpochMillis ?: 0L,
            keystoreHealth = currentKeystoreHealth,
            encryptedItemCount = itemCount
        )
        _status.value = snapshot
        return snapshot
    }

    private fun setUnlockedVmkLocked(newVmk: ByteArray) {
        unlockedVmk?.let { Arrays.fill(it, 0) }
        unlockedVmk = newVmk
    }

    private fun lockLocked() {
        unlockedVmk?.let { Arrays.fill(it, 0) }
        unlockedVmk = null
        backgroundedAtEpochMillis = null
        _decryptedItems.value = emptyList()
    }

    private fun b64Encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun b64Decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    companion object {
        const val VAULT_BLOB_DIR_NAME = "shareflow_vault_blobs"
        const val MIN_PIN_LENGTH = 4
        const val MAX_PIN_LENGTH = 12
        const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
        const val BASE_LOCKOUT_SECONDS = 30L
        const val SALT_BYTES = 32
        const val MAX_IN_MEMORY_PREVIEW_BYTES = 16L * 1024L * 1024L

        private val AAD_VMK_PIN = "SHAREFLOW_VAULT_VMK_PIN_V1".toByteArray(Charsets.UTF_8)
        private val AAD_VMK_RECOVERY = "SHAREFLOW_VAULT_VMK_RECOVERY_V1".toByteArray(Charsets.UTF_8)
        private val AAD_VMK_KEYSTORE = "SHAREFLOW_VAULT_VMK_KEYSTORE_V1".toByteArray(Charsets.UTF_8)

        fun isValidPin(pin: String): Boolean =
            pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }
    }
}
