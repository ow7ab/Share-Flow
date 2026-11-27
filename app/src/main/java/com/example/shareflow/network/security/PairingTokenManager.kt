package com.example.shareflow.network.security

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.LocalDeviceIdentity
import com.example.shareflow.domain.model.LocalHotspotInfo
import com.example.shareflow.domain.model.PairingPayload
import com.example.shareflow.network.TransferProtocol
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and verifies cryptographically random session identifiers, short-lived single-use
 * pairing tokens, and structured `shareflow://pair` QR code payloads.
 * Never logs raw tokens or hotspot passphrases.
 */
class PairingTokenManager(
    private val clock: Clock = SystemClock(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val lock = Any()
    private val issuedPayloads = LinkedHashMap<String, IssuedTokenRecord>()

    private data class IssuedTokenRecord(
        val payload: PairingPayload,
        val consumed: Boolean = false
    )

    fun createPairingPayload(
        localIdentity: LocalDeviceIdentity,
        displayName: String,
        hostAddress: String?,
        port: Int,
        preferredConnectionType: ConnectionType,
        hotspotInfo: LocalHotspotInfo? = null,
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ): PairingPayload {
        val now = clock.nowEpochMillis()
        val safeTtl = ttlMillis.coerceIn(MIN_TTL_MILLIS, MAX_TTL_MILLIS)
        val sessionId = randomHex(16)
        val tokenBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        val pairingToken = AndroidDeviceIdentityManager.encodeBase64Url(tokenBytes)

        val effectiveType = if (hotspotInfo != null) ConnectionType.HOTSPOT else preferredConnectionType
        val effectiveHost = hotspotInfo?.hostAddress ?: hostAddress
        val effectivePort = hotspotInfo?.port ?: port

        val payload = PairingPayload(
            protocolVersion = TransferProtocol.PROTOCOL_VERSION,
            sessionId = sessionId,
            deviceId = localIdentity.deviceId,
            displayName = displayName.trim().ifBlank { "ShareFlow" }.take(40),
            publicKeyFingerprint = localIdentity.publicKeyFingerprint,
            pairingToken = pairingToken,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + safeTtl,
            hostAddress = effectiveHost,
            port = effectivePort,
            preferredConnectionType = effectiveType,
            hotspotSsid = hotspotInfo?.ssid,
            hotspotPassphrase = hotspotInfo?.passphrase
        )

        synchronized(lock) {
            pruneExpiredLocked(now)
            issuedPayloads[sessionId] = IssuedTokenRecord(payload = payload, consumed = false)
            if (issuedPayloads.size > MAX_ACTIVE_TOKENS) {
                val oldestKey = issuedPayloads.entries.firstOrNull()?.key
                if (oldestKey != null) issuedPayloads.remove(oldestKey)
            }
        }
        return payload
    }

    /**
     * Validates a client's HMAC proof for a short-lived QR pairing token and marks it consumed
     * on success so the same QR token cannot be replayed.
     */
    fun verifyAndConsumeTokenProof(
        sessionId: String,
        clientNonceB64: String,
        suppliedProofB64: String
    ): TokenVerificationResult {
        if (sessionId.isBlank() || suppliedProofB64.isBlank()) {
            return TokenVerificationResult.NotProvided
        }
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            pruneExpiredLocked(now)
            val record = issuedPayloads[sessionId]
                ?: return TokenVerificationResult.UnknownOrExpired
            if (record.consumed) {
                return TokenVerificationResult.AlreadyConsumed
            }
            if (record.payload.isExpired(now)) {
                issuedPayloads.remove(sessionId)
                return TokenVerificationResult.UnknownOrExpired
            }

            val expectedProof = computeTokenProof(
                pairingToken = record.payload.pairingToken,
                sessionId = sessionId,
                clientNonceB64 = clientNonceB64
            )
            val suppliedBytes = runCatching {
                AndroidDeviceIdentityManager.decodeBase64Url(suppliedProofB64)
            }.getOrNull() ?: return TokenVerificationResult.InvalidProof

            if (!MessageDigest.isEqual(expectedProof, suppliedBytes)) {
                return TokenVerificationResult.InvalidProof
            }

            issuedPayloads[sessionId] = record.copy(consumed = true)
            return TokenVerificationResult.Valid
        }
    }

    fun registerExternalPairingSession(payload: PairingPayload) {
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            pruneExpiredLocked(now)
            issuedPayloads[payload.sessionId] = IssuedTokenRecord(payload = payload, consumed = false)
            if (issuedPayloads.size > MAX_ACTIVE_TOKENS) {
                val oldestKey = issuedPayloads.entries.firstOrNull()?.key
                if (oldestKey != null) issuedPayloads.remove(oldestKey)
            }
        }
    }

    fun invalidateSession(sessionId: String) {
        synchronized(lock) {
            issuedPayloads.remove(sessionId)
        }
    }

    fun invalidateAll() {
        synchronized(lock) {
            issuedPayloads.clear()
        }
    }

    fun encodeToUri(payload: PairingPayload): String {
        val params = LinkedHashMap<String, String>().apply {
            put("v", payload.protocolVersion.toString())
            put("sid", payload.sessionId)
            put("did", payload.deviceId)
            put("fp", payload.publicKeyFingerprint)
            put("tok", payload.pairingToken)
            put("iat", payload.createdAtEpochMillis.toString())
            put("exp", payload.expiresAtEpochMillis.toString())
            put("name", payload.displayName)
            payload.hostAddress?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            put("port", payload.port.toString())
            put("mode", payload.preferredConnectionType.name)
            payload.hotspotSsid?.takeIf { it.isNotBlank() }?.let { put("ssid", it) }
            payload.hotspotPassphrase?.takeIf { it.isNotBlank() }?.let { put("psk", it) }
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        return "$PAIRING_URI_PREFIX?$query"
    }

    fun parseFromUri(rawUri: String): AppResult<PairingPayload> {
        val trimmed = rawUri.trim()
        if (trimmed.length > MAX_QR_INPUT_LENGTH) {
            return AppResult.Failure(AppError.Validation("Pairing QR payload exceeds maximum allowed length"))
        }
        if (!trimmed.startsWith("$PAIRING_URI_PREFIX?")) {
            return AppResult.Failure(AppError.Validation("Not a valid ShareFlow pairing QR code"))
        }
        val queryPart = trimmed.substringAfter('?', "")
        if (queryPart.isBlank()) {
            return AppResult.Failure(AppError.Validation("Pairing QR payload is empty"))
        }

        val params = LinkedHashMap<String, String>()
        for (pair in queryPart.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = runCatching { urlDecode(pair.substring(0, idx)) }.getOrElse {
                    return AppResult.Failure(AppError.Validation("Malformed URL encoding in pairing QR code"))
                }
                val value = runCatching { urlDecode(pair.substring(idx + 1)) }.getOrElse {
                    return AppResult.Failure(AppError.Validation("Malformed URL encoding in pairing QR code"))
                }
                params[key] = value
            }
        }

        val version = params["v"]?.toIntOrNull()
            ?: return AppResult.Failure(AppError.Validation("Missing protocol version in QR code"))
        if (version < TransferProtocol.MIN_SUPPORTED_PROTOCOL_VERSION) {
            return AppResult.Failure(AppError.NotSupported("Unsupported ShareFlow QR version $version"))
        }

        val sessionId = params["sid"]?.takeIf { it.length in 16..128 && it.none(Char::isISOControl) }
            ?: return AppResult.Failure(AppError.Validation("Missing or invalid session ID in QR code"))
        val deviceId = params["did"]?.takeIf { it.length in 12..128 && it.none(Char::isISOControl) }
            ?: return AppResult.Failure(AppError.Validation("Missing device identifier in QR code"))
        val fingerprint = params["fp"]?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }
            ?: return AppResult.Failure(AppError.Validation("Missing cryptographic device fingerprint in QR code"))
        val token = params["tok"]?.takeIf { it.length in 16..128 && it.none(Char::isISOControl) }
            ?: return AppResult.Failure(AppError.Validation("Missing pairing token in QR code"))
        val createdAt = params["iat"]?.toLongOrNull() ?: 0L
        val expiresAt = params["exp"]?.toLongOrNull()
            ?: return AppResult.Failure(AppError.Validation("Missing token expiry in QR code"))
        val name = params["name"]?.filterNot(Char::isISOControl)?.trim()?.take(64)?.takeIf { it.isNotBlank() }
            ?: "ShareFlow Device"
        val host = params["host"]?.trim()?.takeIf { it.isNotBlank() }
        if (host != null && (host.length > 253 || host.any { it.isISOControl() || it.isWhitespace() })) {
            return AppResult.Failure(AppError.Validation("Invalid host address in pairing QR code"))
        }
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return AppResult.Failure(AppError.Validation("Invalid port in pairing QR code"))
        val mode = params["mode"]?.let { raw ->
            runCatching { ConnectionType.valueOf(raw) }.getOrNull()
        } ?: ConnectionType.LOCAL_LAN_NSD

        val now = clock.nowEpochMillis()
        if (now >= expiresAt) {
            return AppResult.Failure(AppError.Security("Pairing QR code has expired. Regenerate a new QR code on the receiver."))
        }

        return AppResult.Success(
            PairingPayload(
                protocolVersion = version,
                sessionId = sessionId,
                deviceId = deviceId,
                displayName = name,
                publicKeyFingerprint = fingerprint.lowercase(),
                pairingToken = token,
                createdAtEpochMillis = createdAt,
                expiresAtEpochMillis = expiresAt,
                hostAddress = host,
                port = port,
                preferredConnectionType = mode,
                hotspotSsid = params["ssid"]?.takeIf { it.isNotBlank() },
                hotspotPassphrase = params["psk"]?.takeIf { it.isNotBlank() }
            )
        )
    }

    private fun pruneExpiredLocked(now: Long) {
        val it = issuedPayloads.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.payload.isExpired(now)) {
                it.remove()
            }
        }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        const val PAIRING_URI_PREFIX = "shareflow://pair"
        const val DEFAULT_TTL_MILLIS = 120_000L
        private const val MIN_TTL_MILLIS = 15_000L
        private const val MAX_TTL_MILLIS = 600_000L
        private const val MAX_ACTIVE_TOKENS = 16
        const val MAX_QR_INPUT_LENGTH = 4096

        fun computeTokenProof(
            pairingToken: String,
            sessionId: String,
            clientNonceB64: String
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pairingToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            mac.update(sessionId.toByteArray(Charsets.UTF_8))
            mac.update('|'.code.toByte())
            mac.update(clientNonceB64.toByteArray(Charsets.UTF_8))
            return mac.doFinal()
        }

        fun computeTokenProofBase64(
            pairingToken: String,
            sessionId: String,
            clientNonceB64: String
        ): String = AndroidDeviceIdentityManager.encodeBase64Url(
            computeTokenProof(pairingToken, sessionId, clientNonceB64)
        )
    }
}

sealed interface TokenVerificationResult {
    data object Valid : TokenVerificationResult
    data object NotProvided : TokenVerificationResult
    data object UnknownOrExpired : TokenVerificationResult
    data object AlreadyConsumed : TokenVerificationResult
    data object InvalidProof : TokenVerificationResult
}
