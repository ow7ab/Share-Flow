package com.example.shareflow.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface KeystoreUnwrapResult {
    data class Success(val plainBytes: ByteArray) : KeystoreUnwrapResult
    data class KeyInvalidated(val reason: String) : KeystoreUnwrapResult
    data class Failure(val error: AppError) : KeystoreUnwrapResult
}

/**
 * Abstraction over Android Keystore for the Private Vault's hardware-protected Key Encryption Key (KEK).
 * Isolates Keystore operations cleanly so key invalidation and hardware unavailability can be tested thoroughly.
 */
interface VaultKeystoreProvider {
    fun isAvailable(): Boolean
    fun ensureKeyExists(alias: String): AppResult<Unit>
    fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox>
    fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult
    fun deleteKey(alias: String): AppResult<Unit>
}

class AndroidVaultKeystoreProvider : VaultKeystoreProvider {

    override fun isAvailable(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        true
    }.getOrDefault(false)

    override fun ensureKeyExists(alias: String): AppResult<Unit> = runCatching {
        getOrCreateSecretKey(alias)
        Unit
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Security("Unable to initialize Android Keystore vault key", it)) }
    )

    override fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox> =
        runCatching {
            val secretKey = getOrCreateSecretKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val ct = cipher.doFinal(plainBytes)
            AesGcmBox(iv = cipher.iv, cipherText = ct)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Security("Android Keystore key wrapping failed", it)) }
        )

    override fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = ks.getKey(alias, null) as? SecretKey
                ?: return KeystoreUnwrapResult.KeyInvalidated(
                    "Android Keystore key was removed or invalidated. Unlock with your PIN to re-bind biometric protection."
                )
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, box.iv))
            if (aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val plain = cipher.doFinal(box.cipherText)
            KeystoreUnwrapResult.Success(plain)
        } catch (e: KeyPermanentlyInvalidatedException) {
            KeystoreUnwrapResult.KeyInvalidated(
                "Biometric enrollment changed and Android Keystore permanently invalidated the biometric key. Unlock with your PIN."
            )
        } catch (e: UnrecoverableKeyException) {
            KeystoreUnwrapResult.KeyInvalidated(
                "Android Keystore key is no longer recoverable. Unlock with your PIN."
            )
        } catch (t: Throwable) {
            KeystoreUnwrapResult.Failure(AppError.Security("Keystore unwrap failed", t))
        }
    }

    override fun deleteKey(alias: String): AppResult<Unit> = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Security("Failed to delete Android Keystore key", it)) }
    )

    private fun getOrCreateSecretKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
        }
        keyGen.init(builder.build())
        return keyGen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        const val DEFAULT_VAULT_KEYSTORE_ALIAS = "shareflow_private_vault_kek_v1"
    }
}
