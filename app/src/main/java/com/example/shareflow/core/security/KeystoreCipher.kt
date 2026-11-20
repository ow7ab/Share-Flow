package com.example.shareflow.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Container for AES-256-GCM encrypted bytes and initialization vector.
 */
data class EncryptedPayload(
    val iv: ByteArray,
    val cipherText: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedPayload) return false
        return iv.contentEquals(other.iv) && cipherText.contentEquals(other.cipherText)
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + cipherText.contentHashCode()
        return result
    }
}

/**
 * Cryptographic abstraction backed by Android Keystore for the Private Vault and sensitive metadata.
 * Never logs key material, IVs, or plaintext payloads.
 */
interface KeystoreCipher {
    fun isKeystoreAvailable(): Boolean
    fun encrypt(keyAlias: String, plainBytes: ByteArray): AppResult<EncryptedPayload>
    fun decrypt(keyAlias: String, payload: EncryptedPayload): AppResult<ByteArray>
    fun deleteKey(keyAlias: String): AppResult<Unit>
}

class AndroidKeystoreCipher : KeystoreCipher {

    override fun isKeystoreAvailable(): Boolean = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        true
    }.getOrDefault(false)

    override fun encrypt(keyAlias: String, plainBytes: ByteArray): AppResult<EncryptedPayload> {
        if (keyAlias.isBlank()) {
            return AppResult.Failure(AppError.Validation("Key alias must not be blank"))
        }
        return runCatching {
            val secretKey = getOrCreateSecretKey(keyAlias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val encrypted = cipher.doFinal(plainBytes)
            val iv = cipher.iv
            EncryptedPayload(iv = iv, cipherText = encrypted)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Security("Encryption failed", it)) }
        )
    }

    override fun decrypt(keyAlias: String, payload: EncryptedPayload): AppResult<ByteArray> {
        if (keyAlias.isBlank()) {
            return AppResult.Failure(AppError.Validation("Key alias must not be blank"))
        }
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val secretKey = keyStore.getKey(keyAlias, null) as? SecretKey
                ?: throw IllegalStateException("Secret key not found in keystore")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            cipher.doFinal(payload.cipherText)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Security("Decryption failed", it)) }
        )
    }

    override fun deleteKey(keyAlias: String): AppResult<Unit> {
        if (keyAlias.isBlank()) {
            return AppResult.Failure(AppError.Validation("Key alias must not be blank"))
        }
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Security("Key deletion failed", it)) }
        )
    }

    private fun getOrCreateSecretKey(keyAlias: String): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        const val VAULT_MASTER_KEY_ALIAS = "shareflow_vault_master_aes256"
    }
}
