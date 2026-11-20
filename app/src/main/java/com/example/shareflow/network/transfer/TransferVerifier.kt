package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.security.ChecksumVerifier
import java.io.File
import java.io.FileInputStream

/**
 * Cryptographic SHA-256 transfer integrity verifier.
 * Computes streaming source hashes before send, verifies finalized destination files after receive,
 * and validates partial staging `.part` files before allowing resume.
 */
class TransferVerifier(
    private val checksumVerifier: ChecksumVerifier,
    private val dataSource: TransferDataSource
) {
    /**
     * Streams the source URI and calculates its full SHA-256 hex digest without loading the file into RAM.
     */
    fun computeSourceFileSha256(uriString: String): AppResult<String> {
        val streamRes = dataSource.openInputStream(uriString)
        if (streamRes is AppResult.Failure) return streamRes
        return (streamRes as AppResult.Success).data.use { stream ->
            checksumVerifier.computeSha256(stream)
        }
    }

    /**
     * Streams a finalized destination [File] from disk and compares its SHA-256 against [expectedSha256].
     * Returns the verified lowercase hex digest on exact match, or [AppError.IntegrityMismatch] otherwise.
     */
    fun verifyFinalizedFile(file: File, expectedSha256: String): AppResult<String> {
        if (!file.exists() || !file.isFile) {
            return AppResult.Failure(AppError.NotFound("Finalized destination file missing during verification"))
        }
        return runCatching {
            FileInputStream(file).use { stream ->
                when (val result = checksumVerifier.verifySha256(stream, expectedSha256)) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> {
                        if (result.data) {
                            AppResult.Success(expectedSha256.trim().lowercase())
                        } else {
                            AppResult.Failure(
                                AppError.IntegrityMismatch(
                                    message = "SHA-256 integrity verification failed",
                                    expectedHash = expectedSha256.trim().lowercase(),
                                    actualHash = ""
                                )
                            )
                        }
                    }
                }
            }
        }.getOrElse { t ->
            AppResult.Failure(AppError.Storage("Unable to read file for SHA-256 verification", t))
        }
    }

    /**
     * Validates an interrupted `.part` staging file before allowing chunked resume.
     * Never trusts Room checkpoints blindly: verifies actual on-disk file existence, bounds, and alignment.
     * Returns a verified `(resumeChunkIndex, resumeByteOffset)` pair.
     */
    fun verifyPartialStagingFile(
        tempFilePath: String?,
        fileSizeBytes: Long,
        chunkSizeBytes: Int,
        recordedChunkIndex: Int,
        recordedBytesTransferred: Long
    ): PartialVerificationResult {
        if (tempFilePath.isNullOrBlank() || chunkSizeBytes <= 0 || fileSizeBytes <= 0L) {
            return PartialVerificationResult.NotResumable("No valid partial staging file recorded")
        }
        val file = File(tempFilePath)
        if (!file.exists() || !file.isFile || !file.canRead() || !file.canWrite()) {
            return PartialVerificationResult.NotResumable("Staging file missing or inaccessible on disk")
        }
        val actualLength = file.length()
        if (actualLength <= 0L || actualLength > fileSizeBytes) {
            return PartialVerificationResult.NotResumable("Staging file length ($actualLength) invalid for file size $fileSizeBytes")
        }
        // Only resume from the largest complete chunk boundary that is <= min(actualLength, recordedBytesTransferred)
        val trustedBytes = minOf(actualLength, recordedBytesTransferred.coerceAtLeast(0L))
        val alignedChunkCount = (trustedBytes / chunkSizeBytes.toLong()).toInt()
            .coerceAtMost(recordedChunkIndex.coerceAtLeast(0))
        val alignedOffset = alignedChunkCount.toLong() * chunkSizeBytes.toLong()
        if (alignedChunkCount <= 0 || alignedOffset <= 0L) {
            return PartialVerificationResult.NotResumable("No complete verified chunk boundary present")
        }
        return PartialVerificationResult.Verified(
            resumeChunkIndex = alignedChunkCount,
            resumeByteOffset = alignedOffset
        )
    }
}

sealed interface PartialVerificationResult {
    data class Verified(
        val resumeChunkIndex: Int,
        val resumeByteOffset: Long
    ) : PartialVerificationResult

    data class NotResumable(val reason: String) : PartialVerificationResult
}
