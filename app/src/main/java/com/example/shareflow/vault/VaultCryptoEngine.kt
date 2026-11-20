package com.example.shareflow.vault

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class AesGcmBox(
    val iv: ByteArray,
    val cipherText: ByteArray
)

data class DerivedPinMaterial(
    val kek: ByteArray,
    val verifier: ByteArray
) {
    fun zeroize() {
        Arrays.fill(kek, 0)
        Arrays.fill(verifier, 0)
    }
}

data class EncryptedFileResult(
    val encryptedFile: File,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long,
    val sha256Hex: String,
    val noncePrefix: ByteArray,
    val chunkCount: Int
)

/**
 * Cryptographic primitives for ShareFlow Private Vault using standard JCA APIs exclusively:
 * - AES-256-GCM (128-bit authentication tag) with strictly unique 96-bit nonces and AAD binding.
 * - PBKDF2WithHmacSHA256 for PIN and Recovery Key KEK derivation (never plaintext PIN storage).
 * - Atomic streaming chunk encryption/decryption (64 KB chunks; zero full-file RAM buffering).
 * - Never logs keys, PINs, IVs, or plaintext contents.
 */
class VaultCryptoEngine(
    private val pinKdfIterations: Int = DEFAULT_PBKDF2_ITERATIONS,
    private val secureRandom: SecureRandom = SecureRandom()
) {
    val configuredIterations: Int get() = pinKdfIterations

    fun generateRandomBytes(length: Int): ByteArray =
        ByteArray(length).also { secureRandom.nextBytes(it) }

    fun generateAes256Key(): ByteArray = generateRandomBytes(AES_KEY_BYTES)

    /**
     * Generates a high-entropy 160-bit recovery key formatted as 8 hyphenated Base32 groups.
     */
    fun generateRecoveryKey(): String {
        val raw = generateRandomBytes(20) // 160 bits
        val sb = StringBuilder(39)
        var buffer = 0
        var bitsLeft = 0
        var charsEmitted = 0
        for (b in raw) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                bitsLeft -= 5
                if (charsEmitted > 0 && charsEmitted % 4 == 0) {
                    sb.append('-')
                }
                sb.append(RECOVERY_ALPHABET[index])
                charsEmitted++
            }
        }
        Arrays.fill(raw, 0)
        return sb.toString()
    }

    fun normalizeRecoveryKey(raw: String): String =
        raw.uppercase().filter { it in RECOVERY_ALPHABET }

    /**
     * Derives 512 bits via PBKDF2WithHmacSHA256:
     * - First 256 bits -> PIN KEK (wraps/unwraps VMK)
     * - Last 256 bits  -> PIN Verifier (stored for constant-time verification; cannot decrypt VMK)
     */
    fun derivePinMaterial(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int = pinKdfIterations
    ): DerivedPinMaterial {
        val spec = PBEKeySpec(pin, salt, iterations, 512)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val full = try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val kek = full.copyOfRange(0, AES_KEY_BYTES)
        val verifier = full.copyOfRange(AES_KEY_BYTES, AES_KEY_BYTES * 2)
        Arrays.fill(full, 0)
        return DerivedPinMaterial(kek = kek, verifier = verifier)
    }

    fun deriveRecoveryKek(
        recoveryKey: String,
        salt: ByteArray,
        iterations: Int = pinKdfIterations
    ): ByteArray {
        val normalized = normalizeRecoveryKey(recoveryKey).toCharArray()
        val spec = PBEKeySpec(normalized, salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            Arrays.fill(normalized, '\u0000')
        }
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun encryptAes256Gcm(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null
    ): AppResult<AesGcmBox> {
        if (keyBytes.size != AES_KEY_BYTES) {
            return AppResult.Failure(AppError.Security("Invalid AES-256 key length"))
        }
        return runCatching {
            val iv = generateRandomBytes(GCM_NONCE_BYTES)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            val ct = cipher.doFinal(plaintext)
            AesGcmBox(iv = iv, cipherText = ct)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Security("AES-256-GCM encryption failed", it)) }
        )
    }

    fun decryptAes256Gcm(
        keyBytes: ByteArray,
        box: AesGcmBox,
        aad: ByteArray? = null
    ): AppResult<ByteArray> {
        if (keyBytes.size != AES_KEY_BYTES) {
            return AppResult.Failure(AppError.Security("Invalid AES-256 key length"))
        }
        if (box.iv.size != GCM_NONCE_BYTES) {
            return AppResult.Failure(AppError.Security("Invalid AES-GCM nonce length"))
        }
        return runCatching {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            val keySpec = SecretKeySpec(keyBytes, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, box.iv))
            if (aad != null && aad.isNotEmpty()) {
                cipher.updateAAD(aad)
            }
            cipher.doFinal(box.cipherText)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { err ->
                if (err is AEADBadTagException) {
                    AppResult.Failure(
                        AppError.IntegrityMismatch(
                            message = "Vault authentication tag verification failed (corrupted or tampered ciphertext).",
                            expectedHash = "",
                            actualHash = ""
                        )
                    )
                } else {
                    AppResult.Failure(AppError.Security("AES-256-GCM decryption failed", err))
                }
            }
        )
    }

    /**
     * Atomically streams plaintext from [inputStream] into an AES-256-GCM encrypted blob at [targetFile].
     * Uses a temporary `.tmp` staging file + `fsync` + atomic rename so interrupted encryption NEVER
     * leaves partial/corrupted files in the vault.
     *
     * Every chunk uses a strictly unique 96-bit nonce `[8-byte random prefix || 4-byte chunk index]`
     * and AAD binding `(itemId || chunkIndex || isFinalChunk)`.
     */
    fun encryptStreamAtomically(
        itemId: String,
        dek: ByteArray,
        inputStream: InputStream,
        targetFile: File,
        chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE_BYTES
    ): AppResult<EncryptedFileResult> {
        if (dek.size != AES_KEY_BYTES) {
            return AppResult.Failure(AppError.Security("Invalid per-file DEK length"))
        }
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        val noncePrefix = generateRandomBytes(NONCE_PREFIX_BYTES)
        val keySpec = SecretKeySpec(dek, "AES")
        val sha256 = MessageDigest.getInstance("SHA-256")

        return try {
            var totalPlainBytes = 0L
            var chunkCount = 0
            val readBuf = ByteArray(chunkSizeBytes)

            FileOutputStream(tempFile).use { fos ->
                DataOutputStream(fos).use { dos ->
                    // Header: magic (4B) + version (1B) + chunkSize (4B) + noncePrefix (8B)
                    dos.write(FILE_MAGIC)
                    dos.writeByte(FILE_FORMAT_VERSION)
                    dos.writeInt(chunkSizeBytes)
                    dos.write(noncePrefix)

                    // Lookahead 1-chunk buffering so we know exact isFinalChunk flag for AAD binding
                    var currentBytes = readChunkFully(inputStream, readBuf)
                    if (currentBytes == 0) {
                        // Support 0-byte empty file as 1 authenticated empty final chunk
                        val nonce = buildChunkNonce(noncePrefix, 0)
                        val aad = buildChunkAad(itemId, 0, isFinal = true)
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
                        cipher.updateAAD(aad)
                        val ct = cipher.doFinal(ByteArray(0))
                        dos.writeBoolean(true)
                        dos.writeInt(ct.size)
                        dos.write(ct)
                        chunkCount = 1
                    } else {
                        val nextBuf = ByteArray(chunkSizeBytes)
                        while (currentBytes > 0) {
                            val nextBytes = readChunkFully(inputStream, nextBuf)
                            val isFinal = nextBytes <= 0

                            sha256.update(readBuf, 0, currentBytes)
                            totalPlainBytes += currentBytes.toLong()

                            val nonce = buildChunkNonce(noncePrefix, chunkCount)
                            val aad = buildChunkAad(itemId, chunkCount, isFinal = isFinal)
                            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
                            cipher.updateAAD(aad)
                            val ct = cipher.doFinal(readBuf, 0, currentBytes)

                            dos.writeBoolean(isFinal)
                            dos.writeInt(ct.size)
                            dos.write(ct)
                            chunkCount++

                            if (!isFinal) {
                                System.arraycopy(nextBuf, 0, readBuf, 0, nextBytes)
                            }
                            currentBytes = nextBytes
                        }
                        Arrays.fill(nextBuf, 0)
                    }
                    Arrays.fill(readBuf, 0)
                    dos.flush()
                    fos.fd.sync()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.delete()
                return AppResult.Failure(AppError.Storage("Failed to atomically commit encrypted vault file"))
            }

            val shaHex = sha256.digest().joinToString("") { "%02x".format(it) }
            AppResult.Success(
                EncryptedFileResult(
                    encryptedFile = targetFile,
                    originalSizeBytes = totalPlainBytes,
                    encryptedSizeBytes = targetFile.length(),
                    sha256Hex = shaHex,
                    noncePrefix = noncePrefix,
                    chunkCount = chunkCount
                )
            )
        } catch (t: Throwable) {
            // Cleanup incomplete temp file on ANY interruption, cancellation, or I/O failure
            runCatching { secureDeleteFile(tempFile) }
            AppResult.Failure(AppError.Storage("Encryption interrupted or failed safely; no partial file stored.", t))
        }
    }

    /**
     * Streams and verifies AES-256-GCM chunks from [encryptedFile] to [outputStream].
     * Enforces header validation, per-chunk AAD + GCM tag authentication, exact chunk count,
     * absence of trailing garbage, and final plaintext SHA-256 verification.
     */
    fun decryptStreamVerified(
        itemId: String,
        dek: ByteArray,
        encryptedFile: File,
        expectedNoncePrefix: ByteArray,
        expectedChunkCount: Int,
        expectedSha256Hex: String,
        outputStream: OutputStream
    ): AppResult<Long> {
        if (dek.size != AES_KEY_BYTES) {
            return AppResult.Failure(AppError.Security("Invalid per-file DEK length"))
        }
        if (!encryptedFile.exists() || !encryptedFile.isFile) {
            return AppResult.Failure(AppError.NotFound("Encrypted vault blob is missing on disk."))
        }

        val keySpec = SecretKeySpec(dek, "AES")
        val sha256 = MessageDigest.getInstance("SHA-256")

        return try {
            var totalDecrypted = 0L
            FileInputStream(encryptedFile).use { fis ->
                DataInputStream(fis).use { dis ->
                    val magic = ByteArray(4)
                    dis.readFully(magic)
                    if (!magic.contentEquals(FILE_MAGIC)) {
                        return AppResult.Failure(
                            AppError.IntegrityMismatch("Corrupted vault file header.", "", "")
                        )
                    }
                    val version = dis.readByte().toInt()
                    if (version != FILE_FORMAT_VERSION) {
                        return AppResult.Failure(
                            AppError.IntegrityMismatch("Unsupported vault blob format version $version.", "", "")
                        )
                    }
                    val chunkSize = dis.readInt()
                    if (chunkSize <= 0 || chunkSize > MAX_ALLOWED_CHUNK_SIZE) {
                        return AppResult.Failure(
                            AppError.IntegrityMismatch("Corrupted chunk size in vault header.", "", "")
                        )
                    }
                    val headerNoncePrefix = ByteArray(NONCE_PREFIX_BYTES)
                    dis.readFully(headerNoncePrefix)
                    if (!constantTimeEquals(headerNoncePrefix, expectedNoncePrefix)) {
                        return AppResult.Failure(
                            AppError.IntegrityMismatch("Vault file nonce prefix mismatch.", "", "")
                        )
                    }

                    var chunkIndex = 0
                    var sawFinal = false
                    while (!sawFinal) {
                        if (chunkIndex >= expectedChunkCount) {
                            return AppResult.Failure(
                                AppError.IntegrityMismatch("Encrypted vault file exceeds expected chunk count.", "", "")
                            )
                        }
                        val isFinal = dis.readBoolean()
                        val ctLen = dis.readInt()
                        if (ctLen < GCM_TAG_BYTES || ctLen > chunkSize + GCM_TAG_BYTES) {
                            return AppResult.Failure(
                                AppError.IntegrityMismatch("Invalid encrypted chunk frame length.", "", "")
                            )
                        }
                        val ct = ByteArray(ctLen)
                        dis.readFully(ct)

                        val nonce = buildChunkNonce(headerNoncePrefix, chunkIndex)
                        val aad = buildChunkAad(itemId, chunkIndex, isFinal = isFinal)
                        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, nonce))
                        cipher.updateAAD(aad)
                        val plainChunk = cipher.doFinal(ct)

                        sha256.update(plainChunk)
                        outputStream.write(plainChunk)
                        totalDecrypted += plainChunk.size.toLong()
                        Arrays.fill(plainChunk, 0)

                        chunkIndex++
                        sawFinal = isFinal
                    }

                    // Reject truncated or trailing unauthenticated bytes
                    if (chunkIndex != expectedChunkCount || dis.read() != -1) {
                        return AppResult.Failure(
                            AppError.IntegrityMismatch("Encrypted vault blob is truncated or has trailing bytes.", "", "")
                        )
                    }
                }
            }

            outputStream.flush()
            val actualSha = sha256.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256Hex.isNotBlank() &&
                !constantTimeEquals(
                    expectedSha256Hex.lowercase().toByteArray(Charsets.UTF_8),
                    actualSha.lowercase().toByteArray(Charsets.UTF_8)
                )
            ) {
                return AppResult.Failure(
                    AppError.IntegrityMismatch(
                        message = "Decrypted file SHA-256 verification failed.",
                        expectedHash = expectedSha256Hex,
                        actualHash = actualSha
                    )
                )
            }
            AppResult.Success(totalDecrypted)
        } catch (e: AEADBadTagException) {
            AppResult.Failure(
                AppError.IntegrityMismatch(
                    message = "Encrypted vault content failed AES-GCM authentication (corrupted or tampered).",
                    expectedHash = expectedSha256Hex,
                    actualHash = ""
                )
            )
        } catch (t: Throwable) {
            AppResult.Failure(
                AppError.IntegrityMismatch(
                    message = "Encrypted vault blob could not be verified or decrypted: ${t.javaClass.simpleName}",
                    expectedHash = expectedSha256Hex,
                    actualHash = ""
                )
            )
        }
    }

    /**
     * Overwrites encrypted blob bytes with random data and zeros, syncs descriptor, and deletes the file.
     * Note: On flash storage (eMMC/UFS), hardware wear-leveling may retain old physical blocks until trimmed,
     * which is why file-level AES-256-GCM encryption + per-file DEK deletion is the primary protection.
     */
    fun secureDeleteFile(file: File): Boolean {
        if (!file.exists()) return true
        return runCatching {
            if (file.isFile) {
                val len = file.length()
                if (len > 0L) {
                    RandomAccessFile(file, "rws").use { raf ->
                        val buf = ByteArray(32 * 1024)
                        // Pass 1: random overwrite
                        var remaining = len
                        raf.seek(0L)
                        while (remaining > 0L) {
                            val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                            secureRandom.nextBytes(buf)
                            raf.write(buf, 0, toWrite)
                            remaining -= toWrite
                        }
                        // Pass 2: zero overwrite
                        Arrays.fill(buf, 0)
                        remaining = len
                        raf.seek(0L)
                        while (remaining > 0L) {
                            val toWrite = minOf(remaining, buf.size.toLong()).toInt()
                            raf.write(buf, 0, toWrite)
                            remaining -= toWrite
                        }
                        raf.fd.sync()
                    }
                }
            }
            file.delete()
        }.getOrDefault(false)
    }

    private fun readChunkFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        return total
    }

    private fun buildChunkNonce(prefix: ByteArray, chunkIndex: Int): ByteArray =
        ByteBuffer.allocate(GCM_NONCE_BYTES)
            .put(prefix, 0, NONCE_PREFIX_BYTES)
            .putInt(chunkIndex)
            .array()

    private fun buildChunkAad(itemId: String, chunkIndex: Int, isFinal: Boolean): ByteArray {
        val idBytes = itemId.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(idBytes.size + 5)
            .put(idBytes)
            .putInt(chunkIndex)
            .put(if (isFinal) 1.toByte() else 0.toByte())
            .array()
    }

    companion object {
        const val AES_KEY_BYTES = 32
        const val GCM_NONCE_BYTES = 12
        const val NONCE_PREFIX_BYTES = 8
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = 16
        const val DEFAULT_CHUNK_SIZE_BYTES = 64 * 1024
        const val MAX_ALLOWED_CHUNK_SIZE = 1024 * 1024
        const val DEFAULT_PBKDF2_ITERATIONS = 210_000
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_FORMAT_VERSION = 1
        private val FILE_MAGIC = byteArrayOf('S'.code.toByte(), 'F'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Unambiguous Base32
    }
}
