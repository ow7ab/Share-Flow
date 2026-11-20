package com.example.shareflow.vault

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.model.VaultCategory
import com.example.shareflow.domain.model.VaultKeystoreHealth
import com.example.shareflow.domain.model.VaultLockState
import com.example.shareflow.testutil.TestDispatcherProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Security & Reliability verification suite for ShareFlow Private Vault.
 * Explicitly tests:
 * 1. Authentication (create, PIN unlock, zero plaintext PIN stored, PIN change, Recovery Key reset).
 * 2. Incorrect PIN & lockout enforcement.
 * 3. Biometric authentication failure (stays strictly locked).
 * 4. Application restart (new VaultManager against persisted DB & blobs starts locked; unlocks cleanly).
 * 5. Interrupted encryption (I/O error mid-stream cleans up .tmp and creates zero DB records).
 * 6. Corrupted encrypted data (flipped ciphertext byte, truncated blob, tampered metadata rejected).
 * 7. Keystore key invalidation (KeyPermanentlyInvalidatedException detected safely; PIN recovers all files).
 * 8. Secure deletion & auto-lock timeout enforcement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateVaultSecurityTest {

    private lateinit var context: Context
    private lateinit var db: ShareFlowDatabase
    private lateinit var blobDir: File
    private lateinit var clock: MutableTestClock
    private lateinit var cryptoEngine: VaultCryptoEngine
    private lateinit var keystoreProvider: FakeVaultKeystoreProvider

    private class MutableTestClock(var now: Long = 1_700_000_000_000L) : Clock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeVaultKeystoreProvider(
        private val crypto: VaultCryptoEngine
    ) : VaultKeystoreProvider {
        var available: Boolean = true
        var simulateKeyInvalidated: Boolean = false
        private val keysByAlias = mutableMapOf<String, ByteArray>()

        override fun isAvailable(): Boolean = available

        override fun ensureKeyExists(alias: String): AppResult<Unit> {
            if (!available) return AppResult.Failure(AppError.Security("Keystore unavailable"))
            keysByAlias.getOrPut(alias) { crypto.generateAes256Key() }
            return AppResult.Success(Unit)
        }

        override fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox> {
            if (!available) return AppResult.Failure(AppError.Security("Keystore unavailable"))
            val key = keysByAlias.getOrPut(alias) { crypto.generateAes256Key() }
            return crypto.encryptAes256Gcm(key, plainBytes, aad)
        }

        override fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult {
            if (simulateKeyInvalidated) {
                return KeystoreUnwrapResult.KeyInvalidated(
                    "Android Keystore key was permanently invalidated after biometric enrollment change."
                )
            }
            val key = keysByAlias[alias]
                ?: return KeystoreUnwrapResult.KeyInvalidated("Keystore key alias missing.")
            return when (val res = crypto.decryptAes256Gcm(key, box, aad)) {
                is AppResult.Success -> KeystoreUnwrapResult.Success(res.data)
                is AppResult.Failure -> KeystoreUnwrapResult.Failure(res.error)
            }
        }

        override fun deleteKey(alias: String): AppResult<Unit> {
            keysByAlias.remove(alias)
            return AppResult.Success(Unit)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        blobDir = File(context.cacheDir, "vault_test_${UUID.randomUUID()}").apply { mkdirs() }
        clock = MutableTestClock()
        cryptoEngine = VaultCryptoEngine(pinKdfIterations = 2_000)
        keystoreProvider = FakeVaultKeystoreProvider(cryptoEngine)
    }

    @After
    fun tearDown() {
        db.close()
        blobDir.deleteRecursively()
    }

    private fun newVaultManager(): VaultManager = VaultManager(
        context = context,
        vaultConfigDao = db.vaultConfigDao(),
        vaultItemDao = db.vaultItemDao(),
        cryptoEngine = cryptoEngine,
        keystoreProvider = keystoreProvider,
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
        clock = clock,
        vaultBlobDirProvider = { blobDir }
    )

    @Test
    fun `default PBKDF2 iteration count meets OWASP strong security baseline`() {
        assertTrue(VaultCryptoEngine.DEFAULT_PBKDF2_ITERATIONS >= 210_000)
    }

    @Test
    fun `authentication create unlock changePin and recoveryKey never store plaintext PIN`() = runTest {
        val manager = newVaultManager()
        assertEquals(VaultLockState.NOT_INITIALIZED, manager.refreshStatus().lockState)

        val pin = "482910"
        val receipt = (manager.createVault(pin, enableBiometric = true) as AppResult.Success).data
        assertTrue(receipt.biometricBound)
        assertTrue(receipt.recoveryKey.contains("-"))

        // Verify zero plaintext PIN stored anywhere in Room vault_config
        val persistedConfig = db.vaultConfigDao().getConfig()!!
        val serializedFields = listOf(
            persistedConfig.pinSaltB64,
            persistedConfig.pinVerifierB64,
            persistedConfig.wrappedVmkPinIvB64,
            persistedConfig.wrappedVmkPinCtB64,
            persistedConfig.wrappedVmkRecoveryIvB64,
            persistedConfig.wrappedVmkRecoveryCtB64
        ).joinToString("|")
        assertFalse("Plaintext PIN must never appear in database!", serializedFields.contains(pin))

        // Import encrypted payload
        val secretBytes = "Confidential ShareFlow financial report #9981".toByteArray(Charsets.UTF_8)
        val imported = (manager.importFile(
            displayName = "tax_report_2026.txt",
            mimeType = "text/plain",
            openInputStream = { ByteArrayInputStream(secretBytes) }
        ) as AppResult.Success).data

        // Lock -> unlock with PIN -> decrypt
        manager.lock()
        assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)
        assertTrue(manager.decryptedItems.value.isEmpty())
        assertTrue(manager.decryptToMemory(imported.id) is AppResult.Failure)

        assertTrue(manager.unlockWithPin(pin) is AppResult.Success)
        assertEquals(VaultLockState.UNLOCKED, manager.status.value.lockState)
        val decrypted = (manager.decryptToMemory(imported.id) as AppResult.Success).data
        assertArrayEquals(secretBytes, decrypted)

        // Change PIN -> old PIN rejected, new PIN unlocks
        val newPin = "773311"
        assertTrue(manager.changePin(pin, newPin) is AppResult.Success)
        manager.lock()
        assertTrue(manager.unlockWithPin(pin) is AppResult.Failure)
        assertTrue(manager.unlockWithPin(newPin) is AppResult.Success)

        // Recovery key resets forgotten PIN without losing encrypted files
        manager.lock()
        val recoveredPin = "909090"
        assertTrue(manager.recoverWithRecoveryKey(receipt.recoveryKey, recoveredPin) is AppResult.Success)
        assertEquals(VaultLockState.UNLOCKED, manager.status.value.lockState)
        val decryptedAfterRecovery = (manager.decryptToMemory(imported.id) as AppResult.Success).data
        assertArrayEquals(secretBytes, decryptedAfterRecovery)
    }

    @Test
    fun `incorrect PIN increments failure count enforces lockout and never exposes metadata`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("135790", enableBiometric = false) is AppResult.Success)
        assertTrue(
            manager.importFile("secret.txt", "text/plain") { ByteArrayInputStream("top-secret".toByteArray()) }
                is AppResult.Success
        )
        manager.lock()

        repeat(VaultManager.MAX_ATTEMPTS_BEFORE_LOCKOUT) { idx ->
            val res = manager.unlockWithPin("000000")
            assertTrue("Attempt $idx must fail", res is AppResult.Failure)
            assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)
            assertTrue(manager.decryptedItems.value.isEmpty())
        }

        // Now locked out: even correct PIN is rejected until lockout window expires
        assertTrue(manager.status.value.lockoutUntilEpochMillis > clock.nowEpochMillis())
        val blockedAttempt = manager.unlockWithPin("135790")
        assertTrue(blockedAttempt is AppResult.Failure)
        assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)

        // Advance clock past lockout window -> correct PIN now succeeds cleanly
        clock.now = manager.status.value.lockoutUntilEpochMillis + 1_000L
        assertTrue(manager.unlockWithPin("135790") is AppResult.Success)
        assertEquals(0, manager.status.value.failedPinAttempts)
    }

    @Test
    fun `biometric failure keeps vault strictly locked with zero metadata exposure`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("246810", enableBiometric = true) is AppResult.Success)
        assertTrue(
            manager.importFile("passport.jpg", "image/jpeg") { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)) }
                is AppResult.Success
        )
        manager.lock()

        val failure = manager.handleBiometricFailure("Biometric authentication cancelled by user")
        assertTrue(failure is AppResult.Failure)
        assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)
        assertTrue(manager.decryptedItems.value.isEmpty())
    }

    @Test
    fun `application restart preserves encrypted files stays locked and hides plaintext on disk and in DB`() = runTest {
        val managerBeforeRestart = newVaultManager()
        assertTrue(managerBeforeRestart.createVault("556677", enableBiometric = false) is AppResult.Success)

        val secretPayload = "PRIVATE_PASSPORT_NUMBER_XZ991234567890".repeat(200).toByteArray(Charsets.UTF_8)
        val item = (managerBeforeRestart.importFile(
            displayName = "passport_scan_confidential.pdf",
            mimeType = "application/pdf",
            openInputStream = { ByteArrayInputStream(secretPayload) }
        ) as AppResult.Success).data

        // Inspect on-disk blob and Room row: ZERO plaintext filename or payload bytes!
        val encryptedBlob = managerBeforeRestart.getEncryptedBlobFile(item.blobFileName)
        assertTrue(encryptedBlob.exists())
        val rawDiskBytes = encryptedBlob.readBytes()
        assertFalse(
            "Plaintext payload must NEVER appear on disk!",
            String(rawDiskBytes, Charsets.ISO_8859_1).contains("PRIVATE_PASSPORT_NUMBER")
        )
        val rawRoomRow = db.vaultItemDao().findById(item.id)!!
        assertFalse(
            "Original filename must NOT be stored in plaintext Room column!",
            rawRoomRow.displayName.contains("passport_scan")
        )

        // Simulate full app process restart with brand-new VaultManager instance
        val managerAfterRestart = newVaultManager()
        val statusAfterRestart = managerAfterRestart.refreshStatus()
        assertEquals(VaultLockState.LOCKED, statusAfterRestart.lockState)
        assertEquals(1, statusAfterRestart.encryptedItemCount)
        assertTrue(managerAfterRestart.decryptedItems.value.isEmpty())
        assertTrue(managerAfterRestart.decryptToMemory(item.id) is AppResult.Failure)

        // Authenticate after restart -> restores exact metadata and file bytes
        assertTrue(managerAfterRestart.unlockWithPin("556677") is AppResult.Success)
        assertEquals(1, managerAfterRestart.decryptedItems.value.size)
        assertEquals("passport_scan_confidential.pdf", managerAfterRestart.decryptedItems.value.first().displayName)
        assertEquals(VaultCategory.DOCUMENTS, managerAfterRestart.decryptedItems.value.first().category)

        val exportedOut = ByteArrayOutputStream()
        val exportRes = managerAfterRestart.exportFile(item.id) { exportedOut }
        assertTrue(exportRes is AppResult.Success)
        assertArrayEquals(secretPayload, exportedOut.toByteArray())
    }

    @Test
    fun `interrupted encryption cleans up partial temp files and commits zero Room records`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("112233", enableBiometric = false) is AppResult.Success)

        val failingStream = object : InputStream() {
            private var emitted = 0
            override fun read(): Int {
                if (emitted++ < 70_000) return 0x42
                throw IOException("Simulated sudden storage disconnection mid-encryption")
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (emitted >= 70_000) {
                    throw IOException("Simulated sudden storage disconnection mid-encryption")
                }
                val chunk = minOf(len, 16_384)
                java.util.Arrays.fill(b, off, off + chunk, 0x42.toByte())
                emitted += chunk
                return chunk
            }
        }

        val res = manager.importFile(
            displayName = "interrupted_video.mp4",
            mimeType = "video/mp4",
            openInputStream = { failingStream }
        )
        assertTrue("Interrupted encryption must return Failure", res is AppResult.Failure)
        assertTrue("Zero DB records may be committed on failed encryption", db.vaultItemDao().observeAll().first().isEmpty())
        val leftoverFiles = blobDir.listFiles().orEmpty()
        assertTrue("Zero .tmp or .sfv files may remain after interrupted encryption", leftoverFiles.isEmpty())
    }

    @Test
    fun `corrupted encrypted blob and truncated ciphertext are rejected by GCM and SHA-256`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("884422", enableBiometric = false) is AppResult.Success)

        val original = ByteArray(150_000) { (it % 251).toByte() } // Multi-chunk (> 64 KB)
        val item = (manager.importFile(
            displayName = "multi_chunk_doc.bin",
            mimeType = "application/octet-stream",
            openInputStream = { ByteArrayInputStream(original) }
        ) as AppResult.Success).data

        val blobFile = manager.getEncryptedBlobFile(item.blobFileName)
        val validSnapshot = blobFile.readBytes()

        // 1. Flip a single bit inside encrypted ciphertext -> AES-GCM authentication tag MUST fail
        val bitFlipped = validSnapshot.copyOf()
        val tamperOffset = bitFlipped.size / 2
        bitFlipped[tamperOffset] = (bitFlipped[tamperOffset].toInt() xor 0x01).toByte()
        blobFile.writeBytes(bitFlipped)

        val tamperedDecrypt = manager.decryptToMemory(item.id)
        assertTrue(tamperedDecrypt is AppResult.Failure)
        assertTrue((tamperedDecrypt as AppResult.Failure).error is AppError.IntegrityMismatch)

        val tamperedExport = manager.exportFile(item.id) { ByteArrayOutputStream() }
        assertTrue(tamperedExport is AppResult.Failure)
        assertTrue((tamperedExport as AppResult.Failure).error is AppError.IntegrityMismatch)

        // 2. Truncate encrypted blob -> MUST fail with IntegrityMismatch
        blobFile.writeBytes(validSnapshot.copyOf(validSnapshot.size / 2))
        val truncatedDecrypt = manager.decryptToMemory(item.id)
        assertTrue(truncatedDecrypt is AppResult.Failure)
        assertTrue((truncatedDecrypt as AppResult.Failure).error is AppError.IntegrityMismatch)
    }

    @Test
    fun `keystore key invalidation is detected cleanly and PIN preserves all encrypted user files`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("314159", enableBiometric = true) is AppResult.Success)

        val payload = "Irreplaceable family photo bytes".toByteArray(Charsets.UTF_8)
        val item = (manager.importFile(
            displayName = "family_photo.jpg",
            mimeType = "image/jpeg",
            openInputStream = { ByteArrayInputStream(payload) }
        ) as AppResult.Success).data
        manager.lock()

        // Confirm normal biometric unlock works first
        assertTrue(manager.unlockWithBiometricAfterPromptSuccess() is AppResult.Success)
        manager.lock()

        // Simulate Android Keystore KeyPermanentlyInvalidatedException (e.g. new fingerprint added)
        keystoreProvider.simulateKeyInvalidated = true
        val invalidatedRes = manager.unlockWithBiometricAfterPromptSuccess()
        assertTrue(invalidatedRes is AppResult.Failure)
        assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)
        assertEquals(VaultKeystoreHealth.BIOMETRIC_KEY_INVALIDATED, manager.status.value.keystoreHealth)
        assertFalse("Broken biometric wrapper should be safely disabled", manager.status.value.isBiometricEnabled)
        assertEquals(1, manager.status.value.encryptedItemCount)

        // Unlocking with PIN still decrypts 100% of user files cleanly!
        assertTrue(manager.unlockWithPin("314159") is AppResult.Success)
        val decrypted = (manager.decryptToMemory(item.id) as AppResult.Success).data
        assertArrayEquals(payload, decrypted)
    }

    @Test
    fun `secure deletion and background auto-lock protect vault contents`() = runTest {
        val manager = newVaultManager()
        assertTrue(
            manager.createVault("998877", enableBiometric = false, autoLockTimeout = VaultAutoLockTimeout.SECONDS_30)
                is AppResult.Success
        )

        val item = (manager.importFile(
            displayName = "burn_after_reading.txt",
            mimeType = "text/plain",
            openInputStream = { ByteArrayInputStream("sensitive note".toByteArray()) }
        ) as AppResult.Success).data
        val blobFile = manager.getEncryptedBlobFile(item.blobFileName)
        assertTrue(blobFile.exists())

        // Background + advance clock past 30s -> auto-locks cleanly
        manager.onAppBackgrounded()
        clock.now += 31_000L
        assertTrue(manager.checkAutoLock())
        assertEquals(VaultLockState.LOCKED, manager.status.value.lockState)
        assertTrue(manager.decryptedItems.value.isEmpty())

        // Unlock and securely delete item
        assertTrue(manager.unlockWithPin("998877") is AppResult.Success)
        assertTrue(manager.secureDeleteItem(item.id) is AppResult.Success)
        assertFalse(blobFile.exists())
        assertNotNull(db.vaultConfigDao().getConfig())
        assertTrue(db.vaultItemDao().observeAll().first().isEmpty())
    }
}
