package com.example.shareflow.core.security

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.InputStream
import java.security.MessageDigest

/**
 * Cryptographic file integrity verifier enforcing real post-transfer verification.
 */
interface ChecksumVerifier {
    fun computeSha256(inputStream: InputStream): AppResult<String>
    fun verifySha256(inputStream: InputStream, expectedHex: String): AppResult<Boolean>
}

class Sha256ChecksumVerifier : ChecksumVerifier {

    override fun computeSha256(inputStream: InputStream): AppResult<String> = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytesRead = inputStream.read(buffer)
        while (bytesRead != -1) {
            if (bytesRead > 0) {
                digest.update(buffer, 0, bytesRead)
            }
            bytesRead = inputStream.read(buffer)
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.Storage("Failed to compute SHA-256 digest", it)) }
    )

    override fun verifySha256(inputStream: InputStream, expectedHex: String): AppResult<Boolean> {
        val normalizedExpected = expectedHex.trim().lowercase()
        if (normalizedExpected.length != SHA256_HEX_LENGTH || !normalizedExpected.all { it in HEX_CHARS }) {
            return AppResult.Failure(AppError.Validation("Invalid expected SHA-256 hash format"))
        }
        return when (val computed = computeSha256(inputStream)) {
            is AppResult.Failure -> computed
            is AppResult.Success -> {
                if (MessageDigest.isEqual(
                        computed.data.toByteArray(Charsets.UTF_8),
                        normalizedExpected.toByteArray(Charsets.UTF_8)
                    )
                ) {
                    AppResult.Success(true)
                } else {
                    AppResult.Failure(
                        AppError.IntegrityMismatch(
                            message = "File checksum verification failed",
                            expectedHash = normalizedExpected,
                            actualHash = computed.data
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
        private const val SHA256_HEX_LENGTH = 64
        private const val HEX_CHARS = "0123456789abcdef"
    }
}
