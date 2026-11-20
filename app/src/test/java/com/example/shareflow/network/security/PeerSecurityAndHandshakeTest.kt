package com.example.shareflow.network.security

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.network.qr.QrCodeCodec
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PeerSecurityAndHandshakeTest {

    private class MutableClock(initial: Long = 1_750_000_000_000L) : Clock {
        val current = AtomicLong(initial)
        override fun nowEpochMillis(): Long = current.get()
        fun advanceBy(millis: Long) = current.addAndGet(millis)
    }

    @Test
    fun `mutual authenticated handshake establishes AES-256-GCM encrypted channel with matching SAS`() = runBlocking {
        val clock = MutableClock()
        val serverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val clientIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val serverTokens = PairingTokenManager(clock = clock)
        val clientTokens = PairingTokenManager(clock = clock)

        val serverProtocol = PeerHandshakeProtocol(
            identityManager = serverIdentity,
            pairingTokenManager = serverTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val clientProtocol = PeerHandshakeProtocol(
            identityManager = clientIdentity,
            pairingTokenManager = clientTokens,
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort
        val payload = serverTokens.createPairingPayload(
            localIdentity = serverIdentity.getLocalIdentity(),
            displayName = "Pixel Receiver",
            hostAddress = "127.0.0.1",
            port = port,
            preferredConnectionType = ConnectionType.LOCAL_LAN_NSD
        )

        var observedRequest: IncomingConnectionRequest? = null

        val serverDeferred = async(Dispatchers.IO) {
            val accepted = serverSocket.accept()
            serverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Pixel Receiver",
                requirePairingToken = true,
                trustedLookup = { _, _ -> false },
                approvalCallback = { req ->
                    observedRequest = req
                    true
                }
            )
        }

        val clientDeferred = async(Dispatchers.IO) {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            clientProtocol.performClientHandshake(
                socket = socket,
                localDisplayName = "Galaxy Sender",
                targetTransport = ConnectionType.LOCAL_LAN_NSD,
                sessionId = payload.sessionId,
                pairingToken = payload.pairingToken,
                expectedServerFingerprint = payload.publicKeyFingerprint
            )
        }

        val serverResult = serverDeferred.await()
        val clientResult = clientDeferred.await()
        serverSocket.close()

        assertTrue("Expected server handshake success: $serverResult", serverResult is AppResult.Success)
        assertTrue("Expected client handshake success: $clientResult", clientResult is AppResult.Success)

        val serverChannel = (serverResult as AppResult.Success).data
        val clientChannel = (clientResult as AppResult.Success).data

        // Mutual identity & SAS verification
        assertEquals(payload.sessionId, serverChannel.sessionId)
        assertEquals(payload.sessionId, clientChannel.sessionId)
        assertEquals(serverChannel.sasVerificationCode, clientChannel.sasVerificationCode)
        assertEquals(6, serverChannel.sasVerificationCode.length)
        assertEquals(clientIdentity.getLocalIdentity().deviceId, serverChannel.remotePeer.deviceId)
        assertEquals(serverIdentity.getLocalIdentity().deviceId, clientChannel.remotePeer.deviceId)
        val request = requireNotNull(observedRequest)
        assertTrue(request.hasValidQrToken)
        assertEquals(clientIdentity.getLocalIdentity().publicKeyFingerprint, request.peerPublicKeyFingerprint)

        // Bidirectional AES-256-GCM encrypted frame exchange
        val secretMsg = "Encrypted ShareFlow Payload 2026".toByteArray(Charsets.UTF_8)
        assertTrue(clientChannel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_APP_DATA, secretMsg) is AppResult.Success)
        val receivedOnServer = serverChannel.receiveEncryptedFrame()
        assertTrue(receivedOnServer is AppResult.Success)
        assertArrayEquals(secretMsg, (receivedOnServer as AppResult.Success).data.payload)

        val ackMsg = "ACK_VERIFIED".toByteArray(Charsets.UTF_8)
        assertTrue(serverChannel.sendEncryptedFrame(EncryptedPeerChannel.FRAME_PONG, ackMsg) is AppResult.Success)
        val receivedOnClient = clientChannel.receiveEncryptedFrame()
        assertTrue(receivedOnClient is AppResult.Success)
        assertArrayEquals(ackMsg, (receivedOnClient as AppResult.Success).data.payload)

        serverChannel.close()
        clientChannel.close()
    }

    @Test
    fun `explicit user rejection declines connection cleanly on both devices`() = runBlocking {
        val clock = MutableClock()
        val serverProtocol = PeerHandshakeProtocol(
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val clientProtocol = PeerHandshakeProtocol(
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort

        val serverDeferred = async(Dispatchers.IO) {
            val accepted = serverSocket.accept()
            serverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Receiver",
                requirePairingToken = false,
                trustedLookup = { _, _ -> false },
                approvalCallback = { false } // Explicitly rejected by user
            )
        }

        val clientDeferred = async(Dispatchers.IO) {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            clientProtocol.performClientHandshake(
                socket = socket,
                localDisplayName = "Untrusted Peer",
                targetTransport = ConnectionType.LOCAL_LAN_NSD
            )
        }

        val serverResult = serverDeferred.await()
        val clientResult = clientDeferred.await()
        serverSocket.close()

        assertTrue(serverResult is AppResult.Failure)
        assertTrue(clientResult is AppResult.Failure)
        assertTrue((clientResult as AppResult.Failure).error is AppError.PermissionDenied)
    }

    @Test
    fun `mismatched QR pinned fingerprint rejects impersonating server`() = runBlocking {
        val clock = MutableClock()
        val realServerIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val expectedOtherIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val clientIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)

        val serverProtocol = PeerHandshakeProtocol(
            identityManager = realServerIdentity,
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val clientProtocol = PeerHandshakeProtocol(
            identityManager = clientIdentity,
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort

        val serverDeferred = async(Dispatchers.IO) {
            val accepted = serverSocket.accept()
            serverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Spoofed Same Name",
                requirePairingToken = false,
                trustedLookup = { _, _ -> false },
                approvalCallback = { true }
            )
        }

        val clientDeferred = async(Dispatchers.IO) {
            val socket = Socket(InetAddress.getLoopbackAddress(), port)
            clientProtocol.performClientHandshake(
                socket = socket,
                localDisplayName = "Client",
                targetTransport = ConnectionType.LOCAL_LAN_NSD,
                expectedServerFingerprint = expectedOtherIdentity.getLocalIdentity().publicKeyFingerprint
            )
        }

        val clientResult = clientDeferred.await()
        serverDeferred.await()
        serverSocket.close()

        assertTrue(clientResult is AppResult.Failure)
        assertTrue((clientResult as AppResult.Failure).error is AppError.Security)
        assertTrue(clientResult.error.message.contains("fingerprint", ignoreCase = true))
    }

    @Test
    fun `replay protection blocks duplicate nonces stale timestamps and single-use QR token reuse`() {
        val clock = MutableClock()
        val replayCache = ReplayProtectionCache(clock = clock, windowMillis = 60_000L)
        val nonce = "abcdefghijklmnop123456"

        assertEquals(
            ReplayCheckResult.Accepted,
            replayCache.recordAndValidate("deviceA", nonce, clock.nowEpochMillis())
        )
        assertEquals(
            ReplayCheckResult.ReplayedNonce,
            replayCache.recordAndValidate("deviceA", nonce, clock.nowEpochMillis())
        )
        val stale = replayCache.recordAndValidate("deviceA", "freshNonce9876543210", clock.nowEpochMillis() - 120_000L)
        assertTrue(stale is ReplayCheckResult.StaleTimestamp)

        // Single-use QR token replay & expiry test
        val identity = AndroidDeviceIdentityManager(preferAndroidKeystore = false).getLocalIdentity()
        val tokenManager = PairingTokenManager(clock = clock)
        val payload = tokenManager.createPairingPayload(
            localIdentity = identity,
            displayName = "Receiver",
            hostAddress = "192.168.1.42",
            port = 52891,
            preferredConnectionType = ConnectionType.LOCAL_LAN_NSD,
            ttlMillis = 60_000L
        )

        val uri = tokenManager.encodeToUri(payload)
        val parsed = tokenManager.parseFromUri(uri)
        assertTrue(parsed is AppResult.Success)
        assertEquals(payload.sessionId, (parsed as AppResult.Success).data.sessionId)
        assertEquals(payload.publicKeyFingerprint, parsed.data.publicKeyFingerprint)

        // ISO-18004 QR matrix generation succeeds for full pairing URI
        val qrMatrix = QrCodeCodec.encodeQrMatrix(uri)
        assertNotNull(qrMatrix)
        assertTrue(qrMatrix!!.width >= 21)
        assertEquals(qrMatrix.width, qrMatrix.height)

        val proof = PairingTokenManager.computeTokenProofBase64(payload.pairingToken, payload.sessionId, nonce)
        assertEquals(
            TokenVerificationResult.Valid,
            tokenManager.verifyAndConsumeTokenProof(payload.sessionId, nonce, proof)
        )
        // Second attempt with same token must be rejected as AlreadyConsumed
        assertEquals(
            TokenVerificationResult.AlreadyConsumed,
            tokenManager.verifyAndConsumeTokenProof(payload.sessionId, nonce, proof)
        )

        // Advance past expiry -> parsing URI must reject expired QR code
        clock.advanceBy(61_000L)
        val expiredParse = tokenManager.parseFromUri(uri)
        assertTrue(expiredParse is AppResult.Failure)
        assertTrue((expiredParse as AppResult.Failure).error is AppError.Security)
    }

    @Test
    fun `tampered raw socket handshake message is rejected without establishing session`() = runBlocking {
        val clock = MutableClock()
        val serverProtocol = PeerHandshakeProtocol(
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = ReplayProtectionCache(clock = clock),
            clock = clock
        )
        val attackerIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false).getLocalIdentity()

        val serverSocket = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        val port = serverSocket.localPort

        val serverDeferred = async(Dispatchers.IO) {
            val accepted = serverSocket.accept()
            serverProtocol.performServerHandshake(
                socket = accepted,
                localDisplayName = "Receiver",
                requirePairingToken = false,
                trustedLookup = { _, _ -> false },
                approvalCallback = { true }
            )
        }

        val clientDeferred = async(Dispatchers.IO) {
            Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                val inp = DataInputStream(socket.getInputStream())
                val forgedBody = "CLIENT_HELLO|2|sid12345678901234|${attackerIdentity.deviceId}|FakeTrustedName|${attackerIdentity.publicKeyBase64}|badEcdh|nonce12345678901234|${clock.nowEpochMillis()}||LOCAL_LAN_NSD\nSIG=AAAA"
                val bytes = forgedBody.toByteArray(Charsets.UTF_8)
                out.write("SFLW".toByteArray(Charsets.US_ASCII))
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()

                val magic = ByteArray(4)
                inp.readFully(magic)
                val len = inp.readInt()
                val resp = ByteArray(len)
                inp.readFully(resp)
                String(resp, Charsets.UTF_8)
            }
        }

        val serverResult = serverDeferred.await()
        val serverReply = clientDeferred.await()
        serverSocket.close()

        assertTrue(serverResult is AppResult.Failure)
        assertTrue(serverReply.startsWith("SERVER_REJECT|"))
        assertNotEquals(
            attackerIdentity.publicKeyFingerprint,
            AndroidDeviceIdentityManager(preferAndroidKeystore = false).getLocalIdentity().publicKeyFingerprint
        )
        assertFalse(serverResult is AppResult.Success)
    }
}
