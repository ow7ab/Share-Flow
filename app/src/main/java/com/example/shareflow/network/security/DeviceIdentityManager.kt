package com.example.shareflow.network.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.shareflow.domain.model.LocalDeviceIdentity
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Manages the local device's persistent ECDSA P-256 cryptographic identity.
 * Public key digests derive both [LocalDeviceIdentity.deviceId] and [LocalDeviceIdentity.publicKeyFingerprint],
 * guaranteeing that a peer's identity cannot be spoofed by changing its display name.
 * Never logs or exposes private key material.
 */
interface DeviceIdentityManager {
    fun getLocalIdentity(): LocalDeviceIdentity
    fun sign(data: ByteArray): ByteArray
    fun verify(publicKeyBytes: ByteArray, data: ByteArray, signatureBytes: ByteArray): Boolean
}

class AndroidDeviceIdentityManager(
    private val keyAlias: String = DEFAULT_IDENTITY_KEY_ALIAS,
    private val preferAndroidKeystore: Boolean = true
) : DeviceIdentityManager {

    private val lock = Any()
    @Volatile
    private var cachedPrivateKey: PrivateKey? = null
    @Volatile
    private var cachedIdentity: LocalDeviceIdentity? = null

    override fun getLocalIdentity(): LocalDeviceIdentity {
        cachedIdentity?.let { return it }
        synchronized(lock) {
            cachedIdentity?.let { return it }
            val (keyPair, hardwareBacked) = loadOrGenerateKeyPair()
            val encodedPub = keyPair.public.encoded
            val fingerprint = computePublicKeyFingerprint(encodedPub)
            val deviceId = deriveDeviceId(encodedPub)
            val pubBase64 = encodeBase64Url(encodedPub)
            val identity = LocalDeviceIdentity(
                deviceId = deviceId,
                publicKeyFingerprint = fingerprint,
                publicKeyBase64 = pubBase64,
                isHardwareBackedKeystore = hardwareBacked
            )
            cachedPrivateKey = keyPair.private
            cachedIdentity = identity
            return identity
        }
    }

    override fun sign(data: ByteArray): ByteArray {
        val privateKey = synchronized(lock) {
            cachedPrivateKey ?: run {
                getLocalIdentity()
                cachedPrivateKey
            }
        } ?: throw IllegalStateException("Device identity private key unavailable")

        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    override fun verify(
        publicKeyBytes: ByteArray,
        data: ByteArray,
        signatureBytes: ByteArray
    ): Boolean = verifyEcdsaSignature(publicKeyBytes, data, signatureBytes)

    private fun loadOrGenerateKeyPair(): Pair<KeyPair, Boolean> {
        if (preferAndroidKeystore) {
            val keystoreResult = runCatching {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existingEntry = keyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
                if (existingEntry != null) {
                    KeyPair(existingEntry.certificate.publicKey, existingEntry.privateKey) to true
                } else {
                    val generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        ANDROID_KEYSTORE
                    )
                    val spec = KeyGenParameterSpec.Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE_NAME))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                    generator.initialize(spec)
                    generator.generateKeyPair() to true
                }
            }
            if (keystoreResult.isSuccess) {
                return keystoreResult.getOrThrow()
            }
        }

        // Software JCA P-256 fallback (used in JVM unit tests or environments without AndroidKeyStore)
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(EC_CURVE_NAME))
        return generator.generateKeyPair() to false
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_IDENTITY_KEY_ALIAS = "shareflow_device_identity_ecdsa_p256"
        private const val EC_CURVE_NAME = "secp256r1"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

        fun computePublicKeyFingerprint(publicKeyBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun deriveDeviceId(publicKeyBytes: ByteArray): String =
            computePublicKeyFingerprint(publicKeyBytes).take(16)

        fun verifyEcdsaSignature(
            publicKeyBytes: ByteArray,
            data: ByteArray,
            signatureBytes: ByteArray
        ): Boolean = runCatching {
            val keySpec = X509EncodedKeySpec(publicKeyBytes)
            val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(keySpec)
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(data)
            verifier.verify(signatureBytes)
        }.getOrDefault(false)

        fun deriveSasCode(transcriptBytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(transcriptBytes)
            val rawInt = ((digest[0].toInt() and 0x7F) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
            return "%06d".format(rawInt % 1_000_000)
        }

        fun encodeBase64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun decodeBase64Url(encoded: String): ByteArray =
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
