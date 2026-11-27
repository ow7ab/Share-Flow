package com.example.shareflow.network.security

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.network.TransferProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException

/**
 * Authenticated, forward-secret AES-256-GCM encrypted framing channel over a TCP socket.
 * Uses directional keys derived from Ephemeral ECDH P-256 + HKDF-SHA256, strictly monotonic
 * 64-bit sequence numbers for nonce uniqueness, and AAD binding to prevent replay or tampering.
 */
class EncryptedPeerChannel(
    val sessionId: String,
    val remotePeer: PeerDevice,
    val sasVerificationCode: String,
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val sendKey: SecretKey,
    private val receiveKey: SecretKey,
    private val sendDirectionPrefix: ByteArray,
    private val receiveDirectionPrefix: ByteArray
) : Closeable {

    private val sendSequence = AtomicLong(0L)
    private val expectedReceiveSequence = AtomicLong(0L)
    private val sendLock = Any()
    private val receiveLock = Any()
    private val sendCipher: Cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    private val receiveCipher: Cipher = Cipher.getInstance(GCM_TRANSFORMATION)

    val isOpen: Boolean
        get() = !socket.isClosed && socket.isConnected

    fun setReadTimeout(timeoutMillis: Int) {
        runCatching { socket.soTimeout = timeoutMillis.coerceAtLeast(0) }
    }

    fun sendEncryptedFrame(frameType: Byte, plaintext: ByteArray): AppResult<Unit> = runCatching {
        synchronized(sendLock) {
            val seq = sendSequence.getAndIncrement()
            val iv = buildIv(sendDirectionPrefix, seq)
            val aad = buildAad(sessionId, frameType, seq)
            sendCipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            sendCipher.updateAAD(aad)
            val cipherText = sendCipher.doFinal(plaintext)

            output.writeByte(frameType.toInt())
            output.writeLong(seq)
            output.writeInt(cipherText.size)
            output.write(cipherText)
            output.flush()
        }
    }.fold(
        onSuccess = { AppResult.Success(Unit) },
        onFailure = { AppResult.Failure(AppError.Security("Encrypted frame send failed", it)) }
    )

    fun receiveEncryptedFrame(maxPlaintextBytes: Int = MAX_FRAME_BYTES): AppResult<DecryptedFrame> =
        runCatching {
            synchronized(receiveLock) {
                val frameType = input.readByte()
                val seq = input.readLong()
                val expectedSeq = expectedReceiveSequence.get()
                if (seq != expectedSeq) {
                    throw SecurityException("Out-of-order or replayed encrypted frame sequence: expected=$expectedSeq actual=$seq")
                }
                val cipherLen = input.readInt()
                if (cipherLen <= GCM_TAG_BYTES || cipherLen > maxPlaintextBytes + GCM_TAG_BYTES) {
                    throw SecurityException("Invalid encrypted frame size: $cipherLen")
                }
                val cipherText = ByteArray(cipherLen)
                input.readFully(cipherText)

                val iv = buildIv(receiveDirectionPrefix, seq)
                val aad = buildAad(sessionId, frameType, seq)
                receiveCipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                receiveCipher.updateAAD(aad)
                val plain = receiveCipher.doFinal(cipherText)
                expectedReceiveSequence.incrementAndGet()
                DecryptedFrame(frameType = frameType, sequence = seq, payload = plain)
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Security("Encrypted frame verification failed", it)) }
        )

    override fun close() {
        runCatching { socket.close() }
    }

    private fun buildIv(prefix4Bytes: ByteArray, seq: Long): ByteArray {
        val buf = ByteBuffer.allocate(12)
        buf.put(prefix4Bytes, 0, 4)
        buf.putLong(seq)
        return buf.array()
    }

    private fun buildAad(sessionId: String, frameType: Byte, seq: Long): ByteArray {
        val sidBytes = sessionId.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(sidBytes.size + 1 + 8)
        buf.put(sidBytes)
        buf.put(frameType)
        buf.putLong(seq)
        return buf.array()
    }

    companion object {
        private const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = 16
        private const val MAX_FRAME_BYTES = 1024 * 1024

        const val FRAME_AUTH_CONFIRM: Byte = 0x01
        const val FRAME_PING: Byte = 0x02
        const val FRAME_PONG: Byte = 0x03
        const val FRAME_APP_DATA: Byte = 0x10
        const val FRAME_TRANSFER_OFFER: Byte = 0x20
        const val FRAME_TRANSFER_OFFER_RESPONSE: Byte = 0x21
        const val FRAME_TRANSFER_CHUNK: Byte = 0x22
        const val FRAME_TRANSFER_CHUNK_ACK: Byte = 0x23
        const val FRAME_TRANSFER_FILE_DONE: Byte = 0x24
        const val FRAME_TRANSFER_FILE_VERIFIED: Byte = 0x25
        const val FRAME_TRANSFER_CONTROL: Byte = 0x26
        const val FRAME_TRANSFER_SESSION_DONE: Byte = 0x27
        const val FRAME_DISCONNECT: Byte = 0x7F
    }
}

data class DecryptedFrame(
    val frameType: Byte,
    val sequence: Long,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecryptedFrame) return false
        return frameType == other.frameType &&
            sequence == other.sequence &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = frameType.toInt()
        result = 31 * result + sequence.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Callback invoked on the receiver when a cryptographically verified peer requests connection.
 * Returns true if the user (or verified trusted policy) explicitly approves the connection.
 */
fun interface IncomingApprovalCallback {
    suspend fun awaitApproval(request: IncomingConnectionRequest): Boolean
}

/**
 * Callback checking whether a peer is cryptographically trusted by BOTH deviceId AND publicKeyFingerprint.
 * Never trusts a device based solely on display name.
 */
fun interface TrustedFingerprintLookup {
    suspend fun isCryptographicallyTrusted(deviceId: String, publicKeyFingerprint: String): Boolean
}

/**
 * Performs mutual authentication (ECDSA P-256 identity signatures bound to SHA-256 device IDs),
 * Ephemeral ECDH P-256 forward-secret key exchange, HKDF-SHA256 key derivation, replay protection,
 * optional short-lived QR token proof verification, and explicit user approval.
 */
class PeerHandshakeProtocol(
    private val identityManager: DeviceIdentityManager,
    private val pairingTokenManager: PairingTokenManager,
    private val replayCache: ReplayProtectionCache,
    private val clock: Clock = SystemClock(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    /**
     * Initiator (client) side of the mutual authenticated handshake.
     */
    fun performClientHandshake(
        socket: Socket,
        localDisplayName: String,
        targetTransport: ConnectionType,
        sessionId: String = randomHex(16),
        pairingToken: String? = null,
        expectedServerFingerprint: String? = null
    ): AppResult<EncryptedPeerChannel> {
        return try {
            socket.soTimeout = TransferProtocol.SOCKET_READ_TIMEOUT_MS
            tuneTransferSocket(socket)
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_STREAM_BUFFER_BYTES))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_STREAM_BUFFER_BYTES))

            val localIdentity = identityManager.getLocalIdentity()
            val ecdhPair = generateEphemeralEcdhKeyPair()
            val clientNonce = ByteArray(16).also { secureRandom.nextBytes(it) }
            val clientNonceB64 = AndroidDeviceIdentityManager.encodeBase64Url(clientNonce)
            val timestampMs = clock.nowEpochMillis()
            val ecdhPubB64 = AndroidDeviceIdentityManager.encodeBase64Url(ecdhPair.public.encoded)
            val tokenProofB64 = if (!pairingToken.isNullOrBlank()) {
                PairingTokenManager.computeTokenProofBase64(pairingToken, sessionId, clientNonceB64)
            } else {
                ""
            }

            val clientBody = canonicalize(
                "CLIENT_HELLO",
                TransferProtocol.PROTOCOL_VERSION.toString(),
                sessionId,
                localIdentity.deviceId,
                sanitizeField(localDisplayName),
                localIdentity.publicKeyBase64,
                ecdhPubB64,
                clientNonceB64,
                timestampMs.toString(),
                tokenProofB64,
                targetTransport.name
            )
            val clientSigB64 = AndroidDeviceIdentityManager.encodeBase64Url(
                identityManager.sign(clientBody.toByteArray(Charsets.UTF_8))
            )

            writeWireMessage(output, "$clientBody\nSIG=$clientSigB64")

            // Await server response (includes approval wait)
            socket.soTimeout = (TransferProtocol.APPROVAL_TIMEOUT_MS + 10_000L).toInt()
            val serverRaw = readWireMessage(input)
                ?: return AppResult.Failure(AppError.Network("Peer closed connection during handshake"))

            val lines = serverRaw.split('\n')
            val msgType = lines.firstOrNull() ?: ""
            if (msgType.startsWith("SERVER_REJECT|")) {
                val reason = msgType.substringAfter("SERVER_REJECT|").ifBlank { "Connection rejected by peer" }
                return AppResult.Failure(AppError.PermissionDenied(reason))
            }
            if (lines.size != 2 || !lines[0].startsWith("SERVER_HELLO|") || !lines[1].startsWith("SIG=")) {
                return AppResult.Failure(AppError.Security("Malformed handshake response from peer"))
            }

            val serverBody = lines[0]
            val serverSigB64 = lines[1].removePrefix("SIG=")
            val fields = serverBody.split('|')
            if (fields.size != 9) {
                return AppResult.Failure(AppError.Security("Unexpected server handshake field count"))
            }

            val serverVersion = fields[1].toIntOrNull()
                ?: return AppResult.Failure(AppError.Security("Invalid server protocol version"))
            if (serverVersion < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) {
                return AppResult.Failure(AppError.NotSupported("Incompatible peer protocol version $serverVersion"))
            }
            val serverSessionId = fields[2]
            if (serverSessionId != sessionId) {
                return AppResult.Failure(AppError.Security("Session identifier mismatch in peer response"))
            }
            val serverDeviceId = fields[3]
            val serverDisplayName = fields[4]
            val serverIdentityPubB64 = fields[5]
            val serverEcdhPubB64 = fields[6]
            val serverNonceB64 = fields[7]
            val serverTimestampMs = fields[8].toLongOrNull()
                ?: return AppResult.Failure(AppError.Security("Invalid server timestamp"))

            val serverIdentityPubBytes = AndroidDeviceIdentityManager.decodeBase64Url(serverIdentityPubB64)
            val derivedServerId = AndroidDeviceIdentityManager.deriveDeviceId(serverIdentityPubBytes)
            val derivedServerFp = AndroidDeviceIdentityManager.computePublicKeyFingerprint(serverIdentityPubBytes)
            if (derivedServerId != serverDeviceId) {
                return AppResult.Failure(AppError.Security("Peer device ID does not match cryptographic public key"))
            }
            if (!expectedServerFingerprint.isNullOrBlank() &&
                !derivedServerFp.equals(expectedServerFingerprint, ignoreCase = true)
            ) {
                return AppResult.Failure(
                    AppError.Security("Peer cryptographic fingerprint does not match QR code identity")
                )
            }

            when (replayCache.recordAndValidate(serverDeviceId, serverNonceB64, serverTimestampMs)) {
                ReplayCheckResult.Accepted -> Unit
                ReplayCheckResult.ReplayedNonce ->
                    return AppResult.Failure(AppError.Security("Rejected replayed handshake response"))
                ReplayCheckResult.InvalidNonce ->
                    return AppResult.Failure(AppError.Security("Invalid handshake nonce from peer"))
                is ReplayCheckResult.StaleTimestamp ->
                    return AppResult.Failure(AppError.Security("Stale handshake timestamp from peer"))
            }

            val fullTranscript = "$clientBody\n$serverBody".toByteArray(Charsets.UTF_8)
            val serverSigBytes = AndroidDeviceIdentityManager.decodeBase64Url(serverSigB64)
            if (!identityManager.verify(serverIdentityPubBytes, fullTranscript, serverSigBytes)) {
                return AppResult.Failure(AppError.Security("Peer cryptographic signature verification failed"))
            }

            val serverNonce = AndroidDeviceIdentityManager.decodeBase64Url(serverNonceB64)
            val serverEcdhPubBytes = AndroidDeviceIdentityManager.decodeBase64Url(serverEcdhPubB64)
            val sharedSecret = computeEcdhSharedSecret(ecdhPair, serverEcdhPubBytes)
            val keys = deriveSessionKeys(
                sharedSecret = sharedSecret,
                clientNonce = clientNonce,
                serverNonce = serverNonce,
                sessionId = sessionId,
                clientDeviceId = localIdentity.deviceId,
                serverDeviceId = serverDeviceId
            )
            val sasCode = AndroidDeviceIdentityManager.deriveSasCode(fullTranscript)

            val remotePeer = PeerDevice(
                deviceId = serverDeviceId,
                displayName = serverDisplayName,
                connectionType = targetTransport,
                hostAddress = socket.inetAddress?.hostAddress,
                port = socket.port,
                publicKeyFingerprint = derivedServerFp,
                lastSeenEpochMillis = clock.nowEpochMillis()
            )

            socket.soTimeout = TransferProtocol.SOCKET_READ_TIMEOUT_MS
            val channel = EncryptedPeerChannel(
                sessionId = sessionId,
                remotePeer = remotePeer,
                sasVerificationCode = sasCode,
                socket = socket,
                input = input,
                output = output,
                sendKey = keys.clientToServerKey,
                receiveKey = keys.serverToClientKey,
                sendDirectionPrefix = CLIENT_TO_SERVER_IV_PREFIX,
                receiveDirectionPrefix = SERVER_TO_CLIENT_IV_PREFIX
            )

            // Prove key possession on both ends using AES-256-GCM encrypted confirmation frames
            val confirmPayload = "CONFIRM:$sessionId:$sasCode".toByteArray(Charsets.UTF_8)
            val sendConfirm = channel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_AUTH_CONFIRM, confirmPayload)
            if (sendConfirm is AppResult.Failure) {
                channel.close()
                return sendConfirm
            }
            val serverConfirm = channel.receiveEncryptedFrame()
            if (serverConfirm is AppResult.Failure) {
                channel.close()
                return serverConfirm
            }
            val frame = (serverConfirm as AppResult.Success).data
            if (frame.frameType != EncryptedPeerChannel.FRAME_AUTH_CONFIRM ||
                !MessageDigest.isEqual(frame.payload, confirmPayload)
            ) {
                channel.close()
                return AppResult.Failure(AppError.Security("Encrypted key confirmation mismatch"))
            }

            AppResult.Success(channel)
        } catch (t: Throwable) {
            runCatching { socket.close() }
            AppResult.Failure(AppError.Security("Handshake failed: ${t.message ?: "unexpected error"}", t))
        }
    }

    /**
     * Responder (server/receiver) side of the mutual authenticated handshake.
     * Validates client cryptographic identity, replay cache, optional QR token proof, and explicit user approval.
     */
    suspend fun performServerHandshake(
        socket: Socket,
        localDisplayName: String,
        requirePairingToken: Boolean,
        trustedLookup: TrustedFingerprintLookup,
        approvalCallback: IncomingApprovalCallback
    ): AppResult<EncryptedPeerChannel> {
        return try {
            socket.soTimeout = TransferProtocol.SOCKET_READ_TIMEOUT_MS
            tuneTransferSocket(socket)
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), SOCKET_STREAM_BUFFER_BYTES))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), SOCKET_STREAM_BUFFER_BYTES))

            val clientRaw = readWireMessage(input)
                ?: return rejectAndFail(output, socket, "Empty connection request")

            val lines = clientRaw.split('\n')
            if (lines.size != 2 || !lines[0].startsWith("CLIENT_HELLO|") || !lines[1].startsWith("SIG=")) {
                return rejectAndFail(output, socket, "Malformed ShareFlow connection request")
            }

            val clientBody = lines[0]
            val clientSigB64 = lines[1].removePrefix("SIG=")
            val fields = clientBody.split('|')
            if (fields.size != 11) {
                return rejectAndFail(output, socket, "Invalid handshake structure")
            }

            val clientVersion = fields[1].toIntOrNull()
                ?: return rejectAndFail(output, socket, "Invalid protocol version")
            if (clientVersion < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) {
                return rejectAndFail(output, socket, "Unsupported ShareFlow protocol version")
            }

            val sessionId = fields[2]
            val clientDeviceId = fields[3]
            val clientDisplayName = fields[4].ifBlank { "Unknown Device" }
            val clientIdentityPubB64 = fields[5]
            val clientEcdhPubB64 = fields[6]
            val clientNonceB64 = fields[7]
            val clientTimestampMs = fields[8].toLongOrNull()
                ?: return rejectAndFail(output, socket, "Invalid request timestamp")
            val tokenProofB64 = fields[9]
            val transport = runCatching { ConnectionType.valueOf(fields[10]) }
                .getOrDefault(ConnectionType.LOCAL_LAN_NSD)

            val clientIdentityPubBytes = runCatching {
                AndroidDeviceIdentityManager.decodeBase64Url(clientIdentityPubB64)
            }.getOrNull() ?: return rejectAndFail(output, socket, "Corrupted client public key")

            val derivedClientId = AndroidDeviceIdentityManager.deriveDeviceId(clientIdentityPubBytes)
            val derivedClientFp = AndroidDeviceIdentityManager.computePublicKeyFingerprint(clientIdentityPubBytes)
            if (derivedClientId != clientDeviceId) {
                return rejectAndFail(output, socket, "Client device identifier does not match public key")
            }

            when (replayCache.recordAndValidate(clientDeviceId, clientNonceB64, clientTimestampMs)) {
                ReplayCheckResult.Accepted -> Unit
                ReplayCheckResult.ReplayedNonce ->
                    return rejectAndFail(output, socket, "Replay attack detected: duplicate handshake nonce")
                ReplayCheckResult.InvalidNonce ->
                    return rejectAndFail(output, socket, "Invalid client handshake nonce")
                is ReplayCheckResult.StaleTimestamp ->
                    return rejectAndFail(output, socket, "Handshake timestamp outside allowed freshness window")
            }

            val clientSigBytes = runCatching {
                AndroidDeviceIdentityManager.decodeBase64Url(clientSigB64)
            }.getOrNull() ?: return rejectAndFail(output, socket, "Invalid client signature encoding")

            if (!identityManager.verify(
                    clientIdentityPubBytes,
                    clientBody.toByteArray(Charsets.UTF_8),
                    clientSigBytes
                )
            ) {
                return rejectAndFail(output, socket, "Client cryptographic signature verification failed")
            }

            val tokenCheck = pairingTokenManager.verifyAndConsumeTokenProof(
                sessionId = sessionId,
                clientNonceB64 = clientNonceB64,
                suppliedProofB64 = tokenProofB64
            )
            val hasValidQrToken = when (tokenCheck) {
                TokenVerificationResult.Valid -> true
                TokenVerificationResult.NotProvided -> {
                    if (requirePairingToken) {
                        return rejectAndFail(output, socket, "Short-lived QR pairing token required")
                    }
                    false
                }
                TokenVerificationResult.AlreadyConsumed ->
                    return rejectAndFail(output, socket, "Pairing token already used (replay rejected)")
                TokenVerificationResult.UnknownOrExpired ->
                    return rejectAndFail(output, socket, "Pairing token is invalid or expired")
                TokenVerificationResult.InvalidProof ->
                    return rejectAndFail(output, socket, "Unauthorized pairing token proof")
            }

            val localIdentity = identityManager.getLocalIdentity()
            val serverEcdhPair = generateEphemeralEcdhKeyPair()
            val serverNonce = ByteArray(16).also { secureRandom.nextBytes(it) }
            val serverNonceB64 = AndroidDeviceIdentityManager.encodeBase64Url(serverNonce)
            val serverTimestampMs = clock.nowEpochMillis()
            val serverEcdhPubB64 = AndroidDeviceIdentityManager.encodeBase64Url(serverEcdhPair.public.encoded)

            val serverBody = canonicalize(
                "SERVER_HELLO",
                TransferProtocol.PROTOCOL_VERSION.toString(),
                sessionId,
                localIdentity.deviceId,
                sanitizeField(localDisplayName),
                localIdentity.publicKeyBase64,
                serverEcdhPubB64,
                serverNonceB64,
                serverTimestampMs.toString()
            )
            val fullTranscript = "$clientBody\n$serverBody".toByteArray(Charsets.UTF_8)
            val sasCode = AndroidDeviceIdentityManager.deriveSasCode(fullTranscript)

            // Verify cryptographic trust by (deviceId + fingerprint), NEVER by display name alone
            val isTrusted = trustedLookup.isCryptographicallyTrusted(clientDeviceId, derivedClientFp)

            val request = IncomingConnectionRequest(
                requestId = randomHex(12),
                sessionId = sessionId,
                peerDeviceId = clientDeviceId,
                peerDisplayName = clientDisplayName,
                peerPublicKeyFingerprint = derivedClientFp,
                sasVerificationCode = sasCode,
                connectionType = transport,
                isPreviouslyTrusted = isTrusted,
                hasValidQrToken = hasValidQrToken,
                requestedAtEpochMillis = clock.nowEpochMillis()
            )

            val approved = approvalCallback.awaitApproval(request)
            if (!approved) {
                return rejectAndFail(output, socket, "Connection declined by user")
            }

            val serverSigB64 = AndroidDeviceIdentityManager.encodeBase64Url(
                identityManager.sign(fullTranscript)
            )
            writeWireMessage(output, "$serverBody\nSIG=$serverSigB64")

            val clientNonce = AndroidDeviceIdentityManager.decodeBase64Url(clientNonceB64)
            val clientEcdhPubBytes = AndroidDeviceIdentityManager.decodeBase64Url(clientEcdhPubB64)
            val sharedSecret = computeEcdhSharedSecret(serverEcdhPair, clientEcdhPubBytes)
            val keys = deriveSessionKeys(
                sharedSecret = sharedSecret,
                clientNonce = clientNonce,
                serverNonce = serverNonce,
                sessionId = sessionId,
                clientDeviceId = clientDeviceId,
                serverDeviceId = localIdentity.deviceId
            )

            val remotePeer = PeerDevice(
                deviceId = clientDeviceId,
                displayName = clientDisplayName,
                connectionType = transport,
                hostAddress = socket.inetAddress?.hostAddress,
                port = socket.port,
                publicKeyFingerprint = derivedClientFp,
                isTrusted = isTrusted,
                lastSeenEpochMillis = clock.nowEpochMillis()
            )

            val channel = EncryptedPeerChannel(
                sessionId = sessionId,
                remotePeer = remotePeer,
                sasVerificationCode = sasCode,
                socket = socket,
                input = input,
                output = output,
                sendKey = keys.serverToClientKey,
                receiveKey = keys.clientToServerKey,
                sendDirectionPrefix = SERVER_TO_CLIENT_IV_PREFIX,
                receiveDirectionPrefix = CLIENT_TO_SERVER_IV_PREFIX
            )

            val expectedConfirm = "CONFIRM:$sessionId:$sasCode".toByteArray(Charsets.UTF_8)
            val clientConfirm = channel.receiveEncryptedFrame()
            if (clientConfirm is AppResult.Failure) {
                channel.close()
                return clientConfirm
            }
            val frame = (clientConfirm as AppResult.Success).data
            if (frame.frameType != EncryptedPeerChannel.FRAME_AUTH_CONFIRM ||
                !MessageDigest.isEqual(frame.payload, expectedConfirm)
            ) {
                channel.close()
                return AppResult.Failure(AppError.Security("Client encrypted confirmation mismatch"))
            }
            val sendConfirm = channel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_AUTH_CONFIRM, expectedConfirm)
            if (sendConfirm is AppResult.Failure) {
                channel.close()
                return sendConfirm
            }

            AppResult.Success(channel)
        } catch (ce: CancellationException) {
            runCatching { socket.close() }
            throw ce
        } catch (t: Throwable) {
            runCatching { socket.close() }
            AppResult.Failure(AppError.Security("Incoming handshake failed: ${t.message ?: "unexpected error"}", t))
        }
    }

    private fun rejectAndFail(
        output: DataOutputStream,
        socket: Socket,
        reason: String
    ): AppResult.Failure {
        runCatching { writeWireMessage(output, "SERVER_REJECT|${sanitizeField(reason)}") }
        runCatching { socket.close() }
        return AppResult.Failure(AppError.Security(reason))
    }

    private fun writeWireMessage(output: DataOutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= TransferProtocol.MAX_HANDSHAKE_FRAME_BYTES) {
            "Handshake message exceeds limit"
        }
        output.write(TransferProtocol.MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun readWireMessage(input: DataInputStream): String? {
        val magic = ByteArray(4)
        input.readFully(magic)
        if (String(magic, Charsets.US_ASCII) != TransferProtocol.MAGIC_HEADER) {
            throw SecurityException("Invalid ShareFlow wire protocol magic header")
        }
        val length = input.readInt()
        if (length <= 0 || length > TransferProtocol.MAX_HANDSHAKE_FRAME_BYTES) {
            throw SecurityException("Invalid handshake payload length: $length")
        }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun generateEphemeralEcdhKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun computeEcdhSharedSecret(localPair: KeyPair, peerPublicKeyBytes: ByteArray): ByteArray {
        val keySpec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPub = KeyFactory.getInstance("EC").generatePublic(keySpec)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localPair.private)
        agreement.doPhase(peerPub, true)
        return agreement.generateSecret()
    }

    private data class DerivedSessionKeys(
        val clientToServerKey: SecretKey,
        val serverToClientKey: SecretKey
    )

    private fun deriveSessionKeys(
        sharedSecret: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        sessionId: String,
        clientDeviceId: String,
        serverDeviceId: String
    ): DerivedSessionKeys {
        // HKDF-Extract(salt = clientNonce || serverNonce, IKM = sharedSecret)
        val salt = ByteArray(clientNonce.size + serverNonce.size).apply {
            System.arraycopy(clientNonce, 0, this, 0, clientNonce.size)
            System.arraycopy(serverNonce, 0, this, clientNonce.size, serverNonce.size)
        }
        val prk = hmacSha256(key = salt, data = sharedSecret)

        // HKDF-Expand for two independent 32-byte (256-bit) directional AES keys
        val baseInfo = "ShareFlow-v2|$sessionId|$clientDeviceId|$serverDeviceId"
        val c2sBytes = hmacSha256(prk, "$baseInfo|C2S\u0001".toByteArray(Charsets.UTF_8))
        val s2cBytes = hmacSha256(prk, "$baseInfo|S2C\u0001".toByteArray(Charsets.UTF_8))

        return DerivedSessionKeys(
            clientToServerKey = SecretKeySpec(c2sBytes, "AES"),
            serverToClientKey = SecretKeySpec(s2cBytes, "AES")
        )
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun canonicalize(vararg fields: String): String = fields.joinToString("|")

    private fun sanitizeField(raw: String): String =
        raw.replace('|', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(80)

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun tuneTransferSocket(socket: Socket) {
        runCatching { socket.tcpNoDelay = true }
        runCatching { socket.sendBufferSize = SOCKET_TCP_BUFFER_BYTES }
        runCatching { socket.receiveBufferSize = SOCKET_TCP_BUFFER_BYTES }
    }

    companion object {
        private val CLIENT_TO_SERVER_IV_PREFIX = byteArrayOf(0x43, 0x32, 0x53, 0x02)
        private val SERVER_TO_CLIENT_IV_PREFIX = byteArrayOf(0x53, 0x32, 0x43, 0x02)
        private const val SOCKET_STREAM_BUFFER_BYTES = 64 * 1024
        private const val SOCKET_TCP_BUFFER_BYTES = 256 * 1024
    }
}
