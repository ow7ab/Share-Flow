package com.example.shareflow.network.transfer

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Streaming source accessor allowing zero-copy / bounded-RAM reads from Android SAF content URIs
 * as well as local [File] URIs in production and JVM tests.
 */
interface TransferDataSource {
    fun openInputStream(uriString: String): AppResult<InputStream>
    fun getFileSizeBytes(uriString: String): AppResult<Long>
    fun isReadable(uriString: String): Boolean
}

class AndroidTransferDataSource(
    private val context: Context
) : TransferDataSource {

    override fun openInputStream(uriString: String): AppResult<InputStream> = runCatching {
        val uri = Uri.parse(uriString)
        val stream: InputStream? = if (uri.scheme == "file" || uri.scheme == null) {
            val file = File(uri.path ?: uriString)
            if (!file.exists() || !file.canRead()) {
                return AppResult.Failure(AppError.NotFound("Source file is missing or unreadable"))
            }
            FileInputStream(file)
        } else {
            context.contentResolver.openInputStream(uri)
        }
        stream ?: return AppResult.Failure(AppError.PermissionDenied("Unable to open source URI stream"))
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.Storage("Failed to open transfer source stream", it)) }
    )

    override fun getFileSizeBytes(uriString: String): AppResult<Long> = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file" || uri.scheme == null) {
            val file = File(uri.path ?: uriString)
            if (!file.exists() || !file.isFile) {
                return AppResult.Failure(AppError.NotFound("Source file does not exist"))
            }
            return AppResult.Success(file.length())
        }
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val len = afd.length
            if (len >= 0L) return AppResult.Success(len)
        }
        // Fallback: query OpenableColumns.SIZE
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                return AppResult.Success(cursor.getLong(0).coerceAtLeast(0L))
            }
        }
        return AppResult.Failure(AppError.Storage("Unable to determine source file size"))
    }.getOrElse { t ->
        AppResult.Failure(AppError.Storage("Error querying file size", t))
    }

    override fun isReadable(uriString: String): Boolean = when (val res = openInputStream(uriString)) {
        is AppResult.Success -> {
            runCatching { res.data.close() }
            true
        }
        is AppResult.Failure -> false
    }
}

/**
 * Abstraction for checking available destination disk storage before accepting a transfer.
 */
interface StorageSpaceChecker {
    fun getAvailableBytes(directory: File): Long
}

class StatFsStorageSpaceChecker : StorageSpaceChecker {
    override fun getAvailableBytes(directory: File): Long = runCatching {
        if (!directory.exists()) directory.mkdirs()
        val stat = StatFs(directory.absolutePath)
        stat.availableBytes
    }.getOrDefault(0L)
}

/**
 * Streaming chunk reader/writer, per-chunk SHA-256 authenticator, duplicate-safe file allocator,
 * and atomic finalization processor. Never loads entire files into memory.
 */
class ChunkProcessor(
    private val dataSource: TransferDataSource,
    private val storageSpaceChecker: StorageSpaceChecker = StatFsStorageSpaceChecker()
) {
    private val destinationLock = Any()

    fun hasSufficientStorage(
        destinationRoot: File,
        requiredBytes: Long,
        safetyMarginBytes: Long
    ): Boolean {
        val available = storageSpaceChecker.getAvailableBytes(destinationRoot)
        val needed = requiredBytes.coerceAtLeast(0L) + safetyMarginBytes.coerceAtLeast(0L)
        return available >= needed
    }

    fun getAvailableStorageBytes(destinationRoot: File): Long =
        storageSpaceChecker.getAvailableBytes(destinationRoot)

    /**
     * Streams chunks sequentially from [sourceUri] starting at [startChunkIndex], invoking [onChunk]
     * with a bounded buffer of at most [chunkSizeBytes] bytes and its SHA-256 digest.
     */
    suspend fun streamSourceChunks(
        sourceUri: String,
        fileSizeBytes: Long,
        chunkSizeBytes: Int,
        startChunkIndex: Int,
        onChunk: suspend (chunkIndex: Int, byteOffset: Long, data: ByteArray, sha256: ByteArray) -> AppResult<Unit>
    ): AppResult<Unit> {
        val streamResult = dataSource.openInputStream(sourceUri)
        if (streamResult is AppResult.Failure) return streamResult

        val startOffset = startChunkIndex.toLong() * chunkSizeBytes.toLong()
        if (startOffset > fileSizeBytes) {
            runCatching { (streamResult as AppResult.Success).data.close() }
            return AppResult.Failure(AppError.Validation("Resume offset exceeds file size"))
        }

        val input = (streamResult as AppResult.Success).data
        return input.use { stream ->
            // Skip to resume offset reliably using bounded skip loop
            if (startOffset > 0L) {
                val skipped = skipFully(stream, startOffset)
                if (!skipped) {
                    return@use AppResult.Failure(AppError.Storage("Unable to seek source stream to resume offset $startOffset"))
                }
            }

            if (fileSizeBytes == 0L) {
                return@use AppResult.Success(Unit)
            }

            val buffer = ByteArray(chunkSizeBytes)
            var currentOffset = startOffset
            var chunkIndex = startChunkIndex

            while (currentOffset < fileSizeBytes) {
                val remaining = (fileSizeBytes - currentOffset).coerceAtMost(chunkSizeBytes.toLong()).toInt()
                val bytesRead = readExact(stream, buffer, remaining)
                if (bytesRead <= 0) {
                    return@use AppResult.Failure(
                        AppError.Storage("Unexpected EOF while streaming source file at offset $currentOffset")
                    )
                }
                val chunkBytes = if (bytesRead == buffer.size) {
                    buffer.copyOf(bytesRead)
                } else {
                    buffer.copyOf(bytesRead)
                }
                val digest = computeChunkSha256(chunkBytes)
                val emitResult = onChunk(chunkIndex, currentOffset, chunkBytes, digest)
                if (emitResult is AppResult.Failure) {
                    return@use emitResult
                }
                currentOffset += bytesRead.toLong()
                chunkIndex++
            }
            AppResult.Success(Unit)
        }
    }

    /**
     * Reads a single specific chunk by index (used when resending a corrupted or NACK'd chunk).
     */
    fun readSingleChunk(
        sourceUri: String,
        fileSizeBytes: Long,
        chunkSizeBytes: Int,
        chunkIndex: Int
    ): AppResult<Pair<ByteArray, ByteArray>> {
        val offset = chunkIndex.toLong() * chunkSizeBytes.toLong()
        if (offset < 0L || offset >= fileSizeBytes) {
            return AppResult.Failure(AppError.Validation("Requested chunk index $chunkIndex out of bounds"))
        }
        val expectedLength = (fileSizeBytes - offset).coerceAtMost(chunkSizeBytes.toLong()).toInt()
        val streamRes = dataSource.openInputStream(sourceUri)
        if (streamRes is AppResult.Failure) return streamRes
        return (streamRes as AppResult.Success).data.use { stream ->
            if (!skipFully(stream, offset)) {
                return@use AppResult.Failure(AppError.Storage("Unable to seek to chunk $chunkIndex"))
            }
            val buf = ByteArray(expectedLength)
            val read = readExact(stream, buf, expectedLength)
            if (read != expectedLength) {
                return@use AppResult.Failure(AppError.Storage("Short read for chunk $chunkIndex"))
            }
            AppResult.Success(buf to computeChunkSha256(buf))
        }
    }

    /**
     * Allocates or resolves the temporary staging `.part` file for an incoming item.
     */
    fun resolveTempPartFile(destinationRoot: File, sessionId: String, itemId: String): File {
        val stagingDir = File(destinationRoot, ".shareflow_staging/$sessionId")
        if (!stagingDir.exists()) stagingDir.mkdirs()
        val safeItem = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(stagingDir, "$safeItem.part")
    }

    /**
     * Writes an authenticated chunk to the staging `.part` file at its exact byte offset.
     */
    fun writeVerifiedChunkToTempFile(
        tempFile: File,
        byteOffset: Long,
        data: ByteArray,
        expectedChunkSha256: ByteArray
    ): AppResult<Long> {
        if (!verifyChunkSha256(data, expectedChunkSha256)) {
            return AppResult.Failure(
                AppError.IntegrityMismatch(
                    message = "Corrupted chunk detected at offset $byteOffset",
                    expectedHash = toHex(expectedChunkSha256),
                    actualHash = toHex(computeChunkSha256(data))
                )
            )
        }
        return runCatching {
            tempFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.seek(byteOffset)
                raf.write(data)
                raf.length()
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to write chunk to staging file", it)) }
        )
    }

    /**
     * Ensures a 0-byte staging file exists and flushes descriptor to storage before finalization.
     */
    fun syncTempFile(tempFile: File, expectedLengthBytes: Long): AppResult<Unit> = runCatching {
        tempFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        RandomAccessFile(tempFile, "rw").use { raf ->
            if (raf.length() != expectedLengthBytes) {
                return AppResult.Failure(
                    AppError.Storage(
                        "Staging file length mismatch: expected=$expectedLengthBytes actual=${raf.length()}"
                    )
                )
            }
            raf.fd.sync()
        }
        AppResult.Success(Unit)
    }.getOrElse { t ->
        AppResult.Failure(AppError.Storage("Failed to fsync staging file", t))
    }

    /**
     * Finalizes a verified staging `.part` file into its destination folder while strictly
     * preserving relative folder structure and NEVER silently overwriting an existing file.
     * Duplicate filenames automatically allocate `name (1).ext`, `name (2).ext`, etc.
     */
    fun finalizeToNonOverwritingDestination(
        tempFile: File,
        destinationRoot: File,
        relativePath: String,
        fileName: String
    ): AppResult<File> = synchronized(destinationLock) {
        if (!TransferWireProtocol.isSafeRelativePath(relativePath)) {
            return AppResult.Failure(AppError.Security("Unsafe relative destination path blocked"))
        }
        val cleanRoot = destinationRoot.canonicalFile
        val targetDir = if (relativePath.isBlank()) {
            cleanRoot
        } else {
            File(cleanRoot, relativePath).canonicalFile
        }
        if (!targetDir.path.startsWith(cleanRoot.path)) {
            return AppResult.Failure(AppError.Security("Destination directory escapes root boundary"))
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return AppResult.Failure(AppError.Storage("Failed to create destination directory"))
        }

        val uniqueDestination = allocateUniqueFile(targetDir, fileName)
            ?: return AppResult.Failure(AppError.Storage("Unable to allocate unique destination file"))

        return runCatching {
            if (tempFile.renameTo(uniqueDestination)) {
                return AppResult.Success(uniqueDestination)
            }
            // Cross-filesystem fallback: streaming copy + fsync + delete temp
            FileInputStream(tempFile).use { input ->
                RandomAccessFile(uniqueDestination, "rw").use { outRaf ->
                    val buffer = ByteArray(64 * 1024)
                    var read = input.read(buffer)
                    while (read != -1) {
                        if (read > 0) outRaf.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                    outRaf.fd.sync()
                }
            }
            tempFile.delete()
            AppResult.Success(uniqueDestination)
        }.getOrElse { t ->
            runCatching { uniqueDestination.delete() }
            AppResult.Failure(AppError.Storage("Failed to finalize received file", t))
        }
    }

    /**
     * Reserves a brand-new, non-existing file path atomically so no existing file is ever overwritten.
     */
    private fun allocateUniqueFile(targetDir: File, originalFileName: String): File? {
        val cleanName = originalFileName.trim()
        val dotIndex = cleanName.lastIndexOf('.')
        val (baseName, extension) = if (dotIndex > 0 && dotIndex < cleanName.lastIndex) {
            cleanName.substring(0, dotIndex) to cleanName.substring(dotIndex)
        } else {
            cleanName to ""
        }

        var attempt = 0
        while (attempt < MAX_DUPLICATE_ATTEMPTS) {
            val candidateName = if (attempt == 0) {
                cleanName
            } else {
                "$baseName ($attempt)$extension"
            }
            val candidate = File(targetDir, candidateName)
            if (!candidate.exists()) {
                // Atomically reserve placeholder only if file does not exist
                if (runCatching { candidate.createNewFile() }.getOrDefault(false)) {
                    return candidate
                }
            }
            attempt++
        }
        return null
    }

    fun cleanupTempFile(tempFilePath: String?) {
        if (tempFilePath.isNullOrBlank()) return
        runCatching { File(tempFilePath).delete() }
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = stream.read(skipBuf, 0, remaining.coerceAtMost(skipBuf.size.toLong()).toInt())
                if (read == -1) return false
                remaining -= read.toLong()
            }
        }
        return true
    }

    private fun readExact(stream: InputStream, buffer: ByteArray, targetBytes: Int): Int {
        var totalRead = 0
        while (totalRead < targetBytes) {
            val read = stream.read(buffer, totalRead, targetBytes - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }

    companion object {
        private const val MAX_DUPLICATE_ATTEMPTS = 10_000

        fun calculateTotalChunks(fileSizeBytes: Long, chunkSizeBytes: Int): Int {
            if (fileSizeBytes <= 0L) return 0
            val cs = chunkSizeBytes.coerceAtLeast(1).toLong()
            return ((fileSizeBytes + cs - 1L) / cs).toInt()
        }

        fun computeChunkSha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        fun verifyChunkSha256(data: ByteArray, expectedDigest: ByteArray): Boolean {
            if (expectedDigest.size != 32) return false
            return MessageDigest.isEqual(computeChunkSha256(data), expectedDigest)
        }

        fun toHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun defaultReceiveRoot(context: Context): File {
            val appExternal = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val base = appExternal ?: context.filesDir
            return File(base, "ShareFlowReceived").apply { if (!exists()) mkdirs() }
        }
    }
}
