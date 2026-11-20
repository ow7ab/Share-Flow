package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.TransferConfig
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Strongly-typed binary wire messages sent exclusively inside ShareFlow's
 * AES-256-GCM authenticated [com.example.shareflow.network.security.EncryptedPeerChannel].
 * Performs zero payload compression to guarantee bit-exact original file transfers.
 */
object TransferWireProtocol {

    const val WIRE_VERSION: Int = 1
    const val MAX_ITEMS_PER_SESSION: Int = 10_000
    const val MAX_RELATIVE_PATH_LENGTH: Int = 512

    data class OfferFileDescriptor(
        val itemId: String,
        val fileName: String,
        val relativePath: String,
        val mimeType: String,
        val fileType: TransferFileType,
        val fileSizeBytes: Long,
        val totalChunks: Int,
        val expectedSha256: String
    )

    data class TransferOffer(
        val sessionId: String,
        val chunkSizeBytes: Int,
        val files: List<OfferFileDescriptor>
    )

    data class ItemResumeDecision(
        val itemId: String,
        val resumeChunkIndex: Int,
        val resumeByteOffset: Long
    )

    data class TransferOfferResponse(
        val sessionId: String,
        val accepted: Boolean,
        val rejectCode: String,
        val rejectMessage: String,
        val resumeDecisions: List<ItemResumeDecision>
    )

    data class ChunkPacket(
        val itemId: String,
        val chunkIndex: Int,
        val byteOffset: Long,
        val chunkSha256: ByteArray,
        val data: ByteArray
    )

    data class ChunkAckPacket(
        val itemId: String,
        val chunkIndex: Int,
        val bytesReceived: Long,
        val accepted: Boolean,
        val errorCode: String,
        val windowCredits: Int
    )

    data class FileDoneMessage(
        val itemId: String,
        val totalChunks: Int,
        val totalBytes: Long
    )

    data class FileVerifiedMessage(
        val itemId: String,
        val verified: Boolean,
        val actualSha256: String,
        val errorMessage: String
    )

    enum class ControlCommand {
        PAUSE,
        RESUME,
        CANCEL,
        RESEND_CHUNK
    }

    data class ControlMessage(
        val sessionId: String,
        val command: ControlCommand,
        val itemId: String = "",
        val chunkIndex: Int = -1
    )

    data class SessionDoneMessage(
        val sessionId: String,
        val status: String
    )

    fun encodeOffer(offer: TransferOffer): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeInt(WIRE_VERSION)
            out.writeUTF(offer.sessionId)
            out.writeInt(offer.chunkSizeBytes)
            out.writeInt(offer.files.size)
            for (file in offer.files) {
                out.writeUTF(file.itemId)
                out.writeUTF(file.fileName)
                out.writeUTF(file.relativePath)
                out.writeUTF(file.mimeType)
                out.writeUTF(file.fileType.name)
                out.writeLong(file.fileSizeBytes)
                out.writeInt(file.totalChunks)
                out.writeUTF(file.expectedSha256)
            }
        }
        return baos.toByteArray()
    }

    fun decodeAndValidateOffer(bytes: ByteArray): AppResult<TransferOffer> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            if (version != WIRE_VERSION) {
                return AppResult.Failure(AppError.NotSupported("Unsupported transfer wire version $version"))
            }
            val sessionId = input.readUTF().trim()
            if (sessionId.isEmpty() || sessionId.length > 128) {
                return AppResult.Failure(AppError.Validation("Invalid transfer session ID"))
            }
            val chunkSize = input.readInt()
            if (chunkSize !in TransferConfig.MIN_CHUNK_SIZE_BYTES..TransferConfig.MAX_CHUNK_SIZE_BYTES) {
                return AppResult.Failure(AppError.Validation("Invalid chunk size: $chunkSize"))
            }
            val count = input.readInt()
            if (count <= 0 || count > MAX_ITEMS_PER_SESSION) {
                return AppResult.Failure(AppError.Validation("Invalid file count in offer: $count"))
            }
            val seenIds = HashSet<String>(count)
            val files = ArrayList<OfferFileDescriptor>(count)
            for (i in 0 until count) {
                val itemId = input.readUTF().trim()
                val fileName = input.readUTF()
                val relativePath = input.readUTF()
                val mimeType = input.readUTF().ifBlank { "application/octet-stream" }
                val fileTypeName = input.readUTF()
                val fileSize = input.readLong()
                val totalChunks = input.readInt()
                val expectedSha = input.readUTF().trim().lowercase()

                if (itemId.isEmpty() || !seenIds.add(itemId)) {
                    return AppResult.Failure(AppError.Validation("Duplicate or empty itemId in transfer offer"))
                }
                val validation = validateFileMetadata(
                    fileName = fileName,
                    relativePath = relativePath,
                    fileSizeBytes = fileSize,
                    totalChunks = totalChunks,
                    chunkSizeBytes = chunkSize,
                    expectedSha256 = expectedSha
                )
                if (validation is AppResult.Failure) return validation

                val fileType = runCatching { TransferFileType.valueOf(fileTypeName) }
                    .getOrDefault(TransferFileType.OTHER)
                files.add(
                    OfferFileDescriptor(
                        itemId = itemId,
                        fileName = fileName.trim(),
                        relativePath = sanitizeRelativePath(relativePath),
                        mimeType = mimeType,
                        fileType = fileType,
                        fileSizeBytes = fileSize,
                        totalChunks = totalChunks,
                        expectedSha256 = expectedSha
                    )
                )
            }
            AppResult.Success(TransferOffer(sessionId = sessionId, chunkSizeBytes = chunkSize, files = files))
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Validation("Malformed transfer offer payload: ${t.message ?: "parse error"}"))
    }

    fun validateFileMetadata(
        fileName: String,
        relativePath: String,
        fileSizeBytes: Long,
        totalChunks: Int,
        chunkSizeBytes: Int,
        expectedSha256: String
    ): AppResult<Unit> {
        if (!FileNameValidator.isValid(fileName)) {
            return AppResult.Failure(AppError.Validation("Invalid or unsafe file name in transfer offer"))
        }
        if (relativePath.length > MAX_RELATIVE_PATH_LENGTH) {
            return AppResult.Failure(AppError.Validation("Relative folder path exceeds allowed length"))
        }
        if (!isSafeRelativePath(relativePath)) {
            return AppResult.Failure(AppError.Security("Rejected unsafe relative path (path traversal blocked)"))
        }
        if (fileSizeBytes < 0L) {
            return AppResult.Failure(AppError.Validation("Negative file size rejected"))
        }
        val expectedChunks = ChunkProcessor.calculateTotalChunks(fileSizeBytes, chunkSizeBytes)
        if (totalChunks != expectedChunks) {
            return AppResult.Failure(
                AppError.Validation("Chunk count mismatch: declared=$totalChunks expected=$expectedChunks")
            )
        }
        if (expectedSha256.length != 64 || !expectedSha256.all { it in HEX_CHARS }) {
            return AppResult.Failure(AppError.Validation("Invalid expected SHA-256 digest in file metadata"))
        }
        return AppResult.Success(Unit)
    }

    fun isSafeRelativePath(raw: String): Boolean {
        if (raw.isEmpty()) return true
        if (raw.startsWith("/") || raw.startsWith("\\") || raw.contains('\u0000') || raw.contains('\\')) {
            return false
        }
        val segments = raw.split('/')
        return segments.all { seg ->
            seg.isNotEmpty() && seg != "." && seg != ".." && FileNameValidator.isValid(seg)
        }
    }

    fun sanitizeRelativePath(raw: String): String {
        val trimmed = raw.trim().trim('/')
        if (trimmed.isEmpty()) return ""
        return trimmed.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
    }

    fun encodeOfferResponse(response: TransferOfferResponse): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.writeUTF(response.sessionId)
            out.writeBoolean(response.accepted)
            out.writeUTF(response.rejectCode)
            out.writeUTF(response.rejectMessage)
            out.writeInt(response.resumeDecisions.size)
            for (d in response.resumeDecisions) {
                out.writeUTF(d.itemId)
                out.writeInt(d.resumeChunkIndex)
                out.writeLong(d.resumeByteOffset)
            }
        }
        return baos.toByteArray()
    }

    fun decodeOfferResponse(bytes: ByteArray): AppResult<TransferOfferResponse> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val sessionId = input.readUTF()
            val accepted = input.readBoolean()
            val rejectCode = input.readUTF()
            val rejectMessage = input.readUTF()
            val count = input.readInt().coerceIn(0, MAX_ITEMS_PER_SESSION)
            val decisions = ArrayList<ItemResumeDecision>(count)
            for (i in 0 until count) {
                decisions.add(
                    ItemResumeDecision(
                        itemId = input.readUTF(),
                        resumeChunkIndex = input.readInt().coerceAtLeast(0),
                        resumeByteOffset = input.readLong().coerceAtLeast(0L)
                    )
                )
            }
            AppResult.Success(
                TransferOfferResponse(
                    sessionId = sessionId,
                    accepted = accepted,
                    rejectCode = rejectCode,
                    rejectMessage = rejectMessage,
                    resumeDecisions = decisions
                )
            )
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed transfer offer response: ${t.message}", t))
    }

    fun encodeChunkPacket(packet: ChunkPacket): ByteArray {
        require(packet.chunkSha256.size == 32) { "Chunk SHA-256 digest must be 32 bytes" }
        val baos = ByteArrayOutputStream(packet.data.size + 96)
        DataOutputStream(baos).use { out ->
            out.writeUTF(packet.itemId)
            out.writeInt(packet.chunkIndex)
            out.writeLong(packet.byteOffset)
            out.write(packet.chunkSha256)
            out.writeInt(packet.data.size)
            out.write(packet.data)
        }
        return baos.toByteArray()
    }

    fun decodeChunkPacket(bytes: ByteArray): AppResult<ChunkPacket> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val itemId = input.readUTF()
            val chunkIndex = input.readInt()
            val byteOffset = input.readLong()
            val sha = ByteArray(32)
            input.readFully(sha)
            val dataLen = input.readInt()
            if (chunkIndex < 0 || byteOffset < 0L || dataLen < 0 || dataLen > TransferConfig.MAX_CHUNK_SIZE_BYTES) {
                return AppResult.Failure(AppError.Validation("Invalid chunk packet bounds"))
            }
            val data = ByteArray(dataLen)
            if (dataLen > 0) {
                input.readFully(data)
            }
            AppResult.Success(
                ChunkPacket(
                    itemId = itemId,
                    chunkIndex = chunkIndex,
                    byteOffset = byteOffset,
                    chunkSha256 = sha,
                    data = data
                )
            )
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Validation("Malformed chunk packet: ${t.message}"))
    }

    fun encodeChunkAck(ack: ChunkAckPacket): ByteArray {
        val baos = ByteArrayOutputStream(64)
        DataOutputStream(baos).use { out ->
            out.writeUTF(ack.itemId)
            out.writeInt(ack.chunkIndex)
            out.writeLong(ack.bytesReceived)
            out.writeBoolean(ack.accepted)
            out.writeUTF(ack.errorCode)
            out.writeInt(ack.windowCredits)
        }
        return baos.toByteArray()
    }

    fun decodeChunkAck(bytes: ByteArray): AppResult<ChunkAckPacket> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            AppResult.Success(
                ChunkAckPacket(
                    itemId = input.readUTF(),
                    chunkIndex = input.readInt(),
                    bytesReceived = input.readLong(),
                    accepted = input.readBoolean(),
                    errorCode = input.readUTF(),
                    windowCredits = input.readInt()
                )
            )
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed chunk ACK: ${t.message}", t))
    }

    fun encodeFileDone(msg: FileDoneMessage): ByteArray {
        val baos = ByteArrayOutputStream(64)
        DataOutputStream(baos).use { out ->
            out.writeUTF(msg.itemId)
            out.writeInt(msg.totalChunks)
            out.writeLong(msg.totalBytes)
        }
        return baos.toByteArray()
    }

    fun decodeFileDone(bytes: ByteArray): AppResult<FileDoneMessage> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            AppResult.Success(
                FileDoneMessage(
                    itemId = input.readUTF(),
                    totalChunks = input.readInt(),
                    totalBytes = input.readLong()
                )
            )
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed file-done packet: ${t.message}", t))
    }

    fun encodeFileVerified(msg: FileVerifiedMessage): ByteArray {
        val baos = ByteArrayOutputStream(128)
        DataOutputStream(baos).use { out ->
            out.writeUTF(msg.itemId)
            out.writeBoolean(msg.verified)
            out.writeUTF(msg.actualSha256)
            out.writeUTF(msg.errorMessage)
        }
        return baos.toByteArray()
    }

    fun decodeFileVerified(bytes: ByteArray): AppResult<FileVerifiedMessage> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            AppResult.Success(
                FileVerifiedMessage(
                    itemId = input.readUTF(),
                    verified = input.readBoolean(),
                    actualSha256 = input.readUTF(),
                    errorMessage = input.readUTF()
                )
            )
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed file-verified packet: ${t.message}", t))
    }

    fun encodeControl(msg: ControlMessage): ByteArray {
        val baos = ByteArrayOutputStream(64)
        DataOutputStream(baos).use { out ->
            out.writeUTF(msg.sessionId)
            out.writeUTF(msg.command.name)
            out.writeUTF(msg.itemId)
            out.writeInt(msg.chunkIndex)
        }
        return baos.toByteArray()
    }

    fun decodeControl(bytes: ByteArray): AppResult<ControlMessage> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val sessionId = input.readUTF()
            val command = ControlCommand.valueOf(input.readUTF())
            val itemId = input.readUTF()
            val chunkIndex = input.readInt()
            AppResult.Success(ControlMessage(sessionId, command, itemId, chunkIndex))
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed transfer control packet: ${t.message}", t))
    }

    fun encodeSessionDone(msg: SessionDoneMessage): ByteArray {
        val baos = ByteArrayOutputStream(64)
        DataOutputStream(baos).use { out ->
            out.writeUTF(msg.sessionId)
            out.writeUTF(msg.status)
        }
        return baos.toByteArray()
    }

    fun decodeSessionDone(bytes: ByteArray): AppResult<SessionDoneMessage> = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            AppResult.Success(SessionDoneMessage(input.readUTF(), input.readUTF()))
        }
    }.getOrElse { t ->
        AppResult.Failure(AppError.Network("Malformed session-done packet: ${t.message}", t))
    }

    fun TransferItem.toOfferDescriptor(): OfferFileDescriptor = OfferFileDescriptor(
        itemId = itemId,
        fileName = fileName,
        relativePath = relativePath,
        mimeType = mimeType,
        fileType = fileType,
        fileSizeBytes = fileSizeBytes,
        totalChunks = totalChunks,
        expectedSha256 = expectedSha256
    )

    private const val HEX_CHARS = "0123456789abcdef"
}
