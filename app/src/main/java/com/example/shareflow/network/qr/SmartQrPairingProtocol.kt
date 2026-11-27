package com.example.shareflow.network.qr

import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartQrPairingPayload
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.DeviceIdentityManager
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface SmartQrParseResult {
    data class Valid(val payload: SmartQrPairingPayload) : SmartQrParseResult
    data class Rejected(
        val errorCode: SmartConnectionErrorCode,
        val message: String
    ) : SmartQrParseResult
}

/**
 * Phase 5 Advanced QR Pairing Protocol (Version 3).
 *
 * - Generates short-lived, single-use pairing sessions signed with the receiver's ECDSA P-256 identity key.
 * - Encodes both scannable `shareflow://pair?v=3&...` URIs and compact `SF3-...` manual pairing codes.
 * - Validates protocol version, expiration, ECDSA signature (tamper resistance), self-pairing, and single-use replay.
 * - Never logs passphrases, pairing tokens, or private keys.
 */
class SmartQrPairingProtocol(
    private val identityManager: DeviceIdentityManager,
    private val clock: Clock = SystemClock(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    private val lock = Any()
    private val consumedSessions = LinkedHashMap<String, Long>()
    private val activeReceiverSessions = LinkedHashMap<String, SmartQrPairingPayload>()

    fun createSessionPayload(
        displayName: String,
        serviceName: String,
        servicePort: Int,
        preferredStrategy: SmartStrategyType,
        ssid: String? = null,
        passphrase: String? = null,
        securityType: HotspotSecurityType = HotspotSecurityType.UNKNOWN,
        hostAddressHint: String? = null,
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ): SmartQrPairingPayload {
        val identity = identityManager.getLocalIdentity()
        val now = clock.nowEpochMillis()
        val safeTtl = ttlMillis.coerceIn(MIN_TTL_MILLIS, MAX_TTL_MILLIS)
        val sessionId = randomHex(16)
        val tokenBytes = ByteArray(18).also { secureRandom.nextBytes(it) }
        val pairingToken = AndroidDeviceIdentityManager.encodeBase64Url(tokenBytes)

        val signingPublicKeyB64 = identity.publicKeyBase64
        val unsigned = SmartQrPairingPayload(
            protocolVersion = TransferProtocol.SMART_QR_PROTOCOL_VERSION,
            sessionId = sessionId,
            deviceId = identity.deviceId,
            displayName = displayName.trim().ifBlank { "ShareFlow" }.take(40),
            publicKeyFingerprint = identity.publicKeyFingerprint.lowercase(),
            ssid = ssid?.takeIf { it.isNotBlank() },
            passphrase = passphrase?.takeIf { it.isNotBlank() },
            securityType = securityType,
            serviceName = serviceName.trim().ifBlank { "ShareFlow-${identity.shortDeviceId}" }.take(48),
            servicePort = servicePort,
            pairingToken = pairingToken,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = now + safeTtl,
            ephemeralPublicKey = signingPublicKeyB64,
            preferredStrategy = preferredStrategy,
            hostAddressHint = hostAddressHint?.takeIf { it.isNotBlank() },
            signature = ""
        )

        val canonical = canonicalSigningInput(unsigned)
        val sigBytes = identityManager.sign(canonical.toByteArray(Charsets.UTF_8))
        val signed = unsigned.copy(
            signature = AndroidDeviceIdentityManager.encodeBase64Url(sigBytes)
        )

        synchronized(lock) {
            pruneLocked(now)
            activeReceiverSessions[sessionId] = signed
        }
        return signed
    }

    fun encodeToUri(payload: SmartQrPairingPayload): String {
        val params = LinkedHashMap<String, String>().apply {
            put("v", payload.protocolVersion.toString())
            put("sid", payload.sessionId)
            put("did", payload.deviceId)
            put("name", payload.displayName)
            put("fp", payload.publicKeyFingerprint)
            payload.ssid?.takeIf { it.isNotBlank() }?.let { put("ssid", it) }
            payload.passphrase?.takeIf { it.isNotBlank() }?.let { put("psk", it) }
            put("sec", payload.securityType.name)
            put("svc", payload.serviceName)
            put("port", payload.servicePort.toString())
            put("tok", payload.pairingToken)
            put("iat", payload.createdAtEpochMillis.toString())
            put("exp", payload.expiresAtEpochMillis.toString())
            put("epk", payload.ephemeralPublicKey)
            put("strat", payload.preferredStrategy.name)
            payload.hostAddressHint?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            put("sig", payload.signature)
        }
        val query = params.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }
        return "$PAIRING_URI_PREFIX?$query"
    }

    fun encodeManualPairingCode(payload: SmartQrPairingPayload): String {
        val uri = encodeToUri(payload)
        return MANUAL_CODE_PREFIX + AndroidDeviceIdentityManager.encodeBase64Url(uri.toByteArray(Charsets.UTF_8))
    }

    fun parseAndValidate(
        rawInput: String,
        localDeviceId: String
    ): SmartQrParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "QR code or pairing code is empty"
            )
        }
        if (trimmed.length > MAX_QR_INPUT_LENGTH) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "QR code or pairing code exceeds maximum allowed length"
            )
        }

        val resolvedUri = if (trimmed.startsWith(MANUAL_CODE_PREFIX, ignoreCase = true)) {
            val encodedPart = trimmed.substring(MANUAL_CODE_PREFIX.length).trim()
            runCatching {
                String(AndroidDeviceIdentityManager.decodeBase64Url(encodedPart), Charsets.UTF_8)
            }.getOrNull() ?: return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "Malformed ShareFlow manual pairing code"
            )
        } else {
            trimmed
        }
        if (resolvedUri.length > MAX_QR_INPUT_LENGTH) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "Decoded pairing URI exceeds maximum allowed length"
            )
        }

        if (!resolvedUri.startsWith("$PAIRING_URI_PREFIX?")) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "Not a ShareFlow pairing QR code"
            )
        }

        val query = resolvedUri.substringAfter('?', "")
        val params = LinkedHashMap<String, String>()
        query.split('&').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = runCatching { urlDecode(part.substring(0, idx)) }.getOrNull() ?: return@forEach
                val value = runCatching { urlDecode(part.substring(idx + 1)) }.getOrNull() ?: return@forEach
                params[key] = value
            }
        }

        val version = params["v"]?.toIntOrNull()
            ?: return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "Missing protocol version in QR payload"
            )
        if (version != TransferProtocol.SMART_QR_PROTOCOL_VERSION) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.INVALID_QR,
                "Unsupported Smart QR protocol version: $version"
            )
        }

        val sessionId = params["sid"]?.takeIf { it.length in 16..128 && it.none(Char::isISOControl) }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Invalid session ID")
        val deviceId = params["did"]?.takeIf { it.length in 12..128 && it.none(Char::isISOControl) }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Invalid device ID")
        val displayName = params["name"]?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Invalid display name")
        val fingerprint = params["fp"]?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' } }?.lowercase()
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Invalid device fingerprint")
        val serviceName = params["svc"]?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Missing or invalid service name")
        val servicePort = params["port"]?.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Invalid service port")
        val pairingToken = params["tok"]?.takeIf { it.length >= 16 }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Missing pairing token")
        val createdAt = params["iat"]?.toLongOrNull() ?: 0L
        val expiresAt = params["exp"]?.toLongOrNull()
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Missing expiration timestamp")
        val epk = params["epk"]?.takeIf { it.length >= 32 }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.INVALID_QR, "Missing ephemeral public key")
        val signature = params["sig"]?.takeIf { it.length >= 32 }
            ?: return SmartQrParseResult.Rejected(SmartConnectionErrorCode.TAMPERED_QR, "Missing QR cryptographic signature")

        val securityType = params["sec"]?.let {
            runCatching { HotspotSecurityType.valueOf(it) }.getOrNull()
        } ?: HotspotSecurityType.UNKNOWN
        val preferredStrategy = params["strat"]?.let {
            runCatching { SmartStrategyType.valueOf(it) }.getOrNull()
        } ?: SmartStrategyType.LOCAL_ONLY_HOTSPOT

        if (deviceId.equals(localDeviceId, ignoreCase = true)) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.SELF_PAIRING,
                "Cannot pair with this device's own QR code"
            )
        }

        val now = clock.nowEpochMillis()
        if (now >= expiresAt) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.STALE_OR_EXPIRED_QR,
                "QR code has expired. Generate a fresh QR session on the receiver."
            )
        }

        synchronized(lock) {
            pruneLocked(now)
            if (consumedSessions.containsKey(sessionId)) {
                return SmartQrParseResult.Rejected(
                    SmartConnectionErrorCode.REPLAYED_QR,
                    "This single-use QR pairing session was already consumed"
                )
            }
        }

        val payload = SmartQrPairingPayload(
            protocolVersion = version,
            sessionId = sessionId,
            deviceId = deviceId,
            displayName = displayName,
            publicKeyFingerprint = fingerprint,
            ssid = params["ssid"]?.takeIf { it.isNotBlank() },
            passphrase = params["psk"]?.takeIf { it.isNotBlank() },
            securityType = securityType,
            serviceName = serviceName,
            servicePort = servicePort,
            pairingToken = pairingToken,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = expiresAt,
            ephemeralPublicKey = epk,
            preferredStrategy = preferredStrategy,
            hostAddressHint = params["host"]?.takeIf { it.isNotBlank() },
            signature = signature
        )

        if (!verifyPayloadSignature(payload)) {
            return SmartQrParseResult.Rejected(
                SmartConnectionErrorCode.TAMPERED_QR,
                "QR cryptographic signature verification failed"
            )
        }

        return SmartQrParseResult.Valid(payload)
    }

    fun verifyPayloadSignature(payload: SmartQrPairingPayload): Boolean {
        return runCatching {
            val pubKeyBytes = AndroidDeviceIdentityManager.decodeBase64Url(payload.ephemeralPublicKey)
            val expectedFp = sha256Hex(pubKeyBytes)
            if (!expectedFp.equals(payload.publicKeyFingerprint, ignoreCase = true)) {
                return@runCatching false
            }
            val sigBytes = AndroidDeviceIdentityManager.decodeBase64Url(payload.signature)
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubKeyBytes))
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonicalSigningInput(payload).toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        }.getOrDefault(false)
    }

    fun markConsumedOnSender(sessionId: String) {
        synchronized(lock) {
            consumedSessions[sessionId] = clock.nowEpochMillis() + REPLAY_RETENTION_MS
        }
    }

    fun invalidateSession(sessionId: String) {
        synchronized(lock) {
            activeReceiverSessions.remove(sessionId)
            consumedSessions[sessionId] = clock.nowEpochMillis() + REPLAY_RETENTION_MS
        }
    }

    fun computeEphemeralKeyHash(ephemeralPublicKeyB64: String): String =
        sha256Hex(ephemeralPublicKeyB64.toByteArray(Charsets.UTF_8)).take(16)

    fun computeDiscoveryHmac(
        pairingToken: String,
        sessionId: String,
        nonce: String,
        port: Int
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val input = "SF3-DISC|$sessionId|$nonce|$port"
        return AndroidDeviceIdentityManager.encodeBase64Url(mac.doFinal(input.toByteArray(Charsets.UTF_8)))
    }

    fun verifyDiscoveryHmac(
        pairingToken: String,
        sessionId: String,
        nonce: String,
        port: Int,
        suppliedHmacB64: String
    ): Boolean {
        val expected = computeDiscoveryHmac(pairingToken, sessionId, nonce, port)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            suppliedHmacB64.toByteArray(Charsets.UTF_8)
        )
    }

    private fun canonicalSigningInput(p: SmartQrPairingPayload): String = listOf(
        p.protocolVersion.toString(),
        p.sessionId,
        p.deviceId,
        p.displayName,
        p.publicKeyFingerprint.lowercase(),
        p.ssid.orEmpty(),
        p.passphrase.orEmpty(),
        p.securityType.name,
        p.serviceName,
        p.servicePort.toString(),
        p.pairingToken,
        p.createdAtEpochMillis.toString(),
        p.expiresAtEpochMillis.toString(),
        p.ephemeralPublicKey,
        p.preferredStrategy.name,
        p.hostAddressHint.orEmpty()
    ).joinToString("|")

    private fun pruneLocked(now: Long) {
        val consumedIt = consumedSessions.entries.iterator()
        while (consumedIt.hasNext()) {
            if (consumedIt.next().value <= now) consumedIt.remove()
        }
        val activeIt = activeReceiverSessions.entries.iterator()
        while (activeIt.hasNext()) {
            if (activeIt.next().value.isExpired(now)) activeIt.remove()
        }
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")

    companion object {
        const val PAIRING_URI_PREFIX = "shareflow://pair"
        const val MANUAL_CODE_PREFIX = "SF3-"
        const val DEFAULT_TTL_MILLIS = 180_000L
        private const val MIN_TTL_MILLIS = 15_000L
        private const val MAX_TTL_MILLIS = 600_000L
        private const val REPLAY_RETENTION_MS = 900_000L
        const val MAX_QR_INPUT_LENGTH = 4096

        fun isSmartV3Payload(rawInput: String): Boolean {
            val trimmed = rawInput.trim()
            if (trimmed.startsWith(MANUAL_CODE_PREFIX, ignoreCase = true)) return true
            if (trimmed.startsWith(PAIRING_URI_PREFIX) &&
                (trimmed.contains("v=3") || trimmed.contains("v=4") || trimmed.contains("epk="))
            ) {
                return true
            }
            return false
        }
    }
}
