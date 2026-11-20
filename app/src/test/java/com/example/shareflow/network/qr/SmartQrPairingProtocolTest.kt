package com.example.shareflow.network.qr

import com.example.shareflow.core.common.Clock
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartQrPairingProtocolTest {

    private class FakeClock(initial: Long = 1_760_000_000_000L) : Clock {
        val now = AtomicLong(initial)
        override fun nowEpochMillis(): Long = now.get()
        fun advanceBy(millis: Long) {
            now.addAndGet(millis)
        }
    }

    @Test
    fun `v3 signed URI and manual SF3 code round-trip preserves all fields and verifies ECDSA signature`() {
        val clock = FakeClock()
        val receiverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val senderIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val receiverProtocol = SmartQrPairingProtocol(receiverIdentity, clock)
        val senderProtocol = SmartQrPairingProtocol(senderIdentity, clock)

        val payload = receiverProtocol.createSessionPayload(
            displayName = "Pixel Receiver",
            serviceName = "ShareFlow-ABCD-1234",
            servicePort = 45678,
            preferredStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT,
            ssid = "AndroidShare_9912",
            passphrase = "s3cr3tPassphrase!99",
            securityType = HotspotSecurityType.WPA2_PSK,
            hostAddressHint = "192.168.137.1",
            ttlMillis = 120_000L
        )

        val uri = receiverProtocol.encodeToUri(payload)
        assertTrue(SmartQrPairingProtocol.isSmartV3Payload(uri))

        val parsedFromUri = senderProtocol.parseAndValidate(
            rawInput = uri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue("Expected valid URI parse, got $parsedFromUri", parsedFromUri is SmartQrParseResult.Valid)
        val decodedUri = (parsedFromUri as SmartQrParseResult.Valid).payload
        assertEquals(TransferProtocol.SMART_QR_PROTOCOL_VERSION, decodedUri.protocolVersion)
        assertEquals(payload.sessionId, decodedUri.sessionId)
        assertEquals(payload.deviceId, decodedUri.deviceId)
        assertEquals("AndroidShare_9912", decodedUri.ssid)
        assertEquals("s3cr3tPassphrase!99", decodedUri.passphrase)
        assertEquals(HotspotSecurityType.WPA2_PSK, decodedUri.securityType)
        assertEquals(45678, decodedUri.servicePort)
        assertEquals(payload.pairingToken, decodedUri.pairingToken)
        assertEquals(payload.ephemeralPublicKey, decodedUri.ephemeralPublicKey)

        // Manual SF3-... code round-trip
        val manualCode = receiverProtocol.encodeManualPairingCode(payload)
        assertTrue(manualCode.startsWith(SmartQrPairingProtocol.MANUAL_CODE_PREFIX))
        assertTrue(SmartQrPairingProtocol.isSmartV3Payload(manualCode))

        val parsedFromManual = senderProtocol.parseAndValidate(
            rawInput = manualCode,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue("Expected valid manual code parse, got $parsedFromManual", parsedFromManual is SmartQrParseResult.Valid)
        assertEquals(payload.sessionId, (parsedFromManual as SmartQrParseResult.Valid).payload.sessionId)
    }

    @Test
    fun `rejects expired tampered replayed invalid and self-paired QR codes`() {
        val clock = FakeClock()
        val receiverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val senderIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val receiverProtocol = SmartQrPairingProtocol(receiverIdentity, clock)
        val senderProtocol = SmartQrPairingProtocol(senderIdentity, clock)

        val payload = receiverProtocol.createSessionPayload(
            displayName = "Galaxy Receiver",
            serviceName = "ShareFlow-TEST-0001",
            servicePort = 41234,
            preferredStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT,
            ssid = "LocalShare_77",
            passphrase = "UniqueSecretPassword88",
            securityType = HotspotSecurityType.WPA2_PSK,
            ttlMillis = 30_000L
        )
        val uri = receiverProtocol.encodeToUri(payload)

        // 1. Self-pairing rejection
        val selfRes = receiverProtocol.parseAndValidate(
            rawInput = uri,
            localDeviceId = receiverIdentity.getLocalIdentity().deviceId
        )
        assertTrue(selfRes is SmartQrParseResult.Rejected)
        assertEquals(SmartConnectionErrorCode.SELF_PAIRING, (selfRes as SmartQrParseResult.Rejected).errorCode)

        // 2. Tampered port rejected by ECDSA signature verification
        val tamperedUri = uri.replace("port=41234", "port=49999")
        val tamperedRes = senderProtocol.parseAndValidate(
            rawInput = tamperedUri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue(tamperedRes is SmartQrParseResult.Rejected)
        assertEquals(SmartConnectionErrorCode.TAMPERED_QR, (tamperedRes as SmartQrParseResult.Rejected).errorCode)

        // 3. Invalid URI / garbage input rejected
        val invalidRes = senderProtocol.parseAndValidate(
            rawInput = "https://example.com/not-shareflow",
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue(invalidRes is SmartQrParseResult.Rejected)
        assertEquals(SmartConnectionErrorCode.INVALID_QR, (invalidRes as SmartQrParseResult.Rejected).errorCode)

        // 4. Single-use replay rejected after markConsumedOnSender
        val firstValid = senderProtocol.parseAndValidate(
            rawInput = uri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue(firstValid is SmartQrParseResult.Valid)
        senderProtocol.markConsumedOnSender(payload.sessionId)

        val replayRes = senderProtocol.parseAndValidate(
            rawInput = uri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue(replayRes is SmartQrParseResult.Rejected)
        assertEquals(SmartConnectionErrorCode.REPLAYED_QR, (replayRes as SmartQrParseResult.Rejected).errorCode)

        // 5. Expired QR rejected
        val freshPayload = receiverProtocol.createSessionPayload(
            displayName = "Galaxy Receiver",
            serviceName = "ShareFlow-TEST-0002",
            servicePort = 41235,
            preferredStrategy = SmartStrategyType.EXISTING_LOCAL_WIFI,
            ttlMillis = 15_000L
        )
        val freshUri = receiverProtocol.encodeToUri(freshPayload)
        clock.advanceBy(20_000L)
        val expiredRes = senderProtocol.parseAndValidate(
            rawInput = freshUri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue(expiredRes is SmartQrParseResult.Rejected)
        assertEquals(SmartConnectionErrorCode.STALE_OR_EXPIRED_QR, (expiredRes as SmartQrParseResult.Rejected).errorCode)
    }

    @Test
    fun `redacted summary never leaks secrets and QR bitmap encodes and decodes cleanly`() {
        val receiverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val protocol = SmartQrPairingProtocol(receiverIdentity)
        val payload = protocol.createSessionPayload(
            displayName = "ShareFlow Pro",
            serviceName = "ShareFlow-BMP-01",
            servicePort = 42000,
            preferredStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT,
            ssid = "AndroidShare_555",
            passphrase = "DO_NOT_LOG_THIS_PASSWORD_123",
            securityType = HotspotSecurityType.WPA2_PSK
        )

        val redacted = payload.toRedactedSummary()
        assertFalse("Redacted summary must never contain passphrase", redacted.contains("DO_NOT_LOG_THIS_PASSWORD_123"))
        assertFalse("Redacted summary must never contain pairing token", redacted.contains(payload.pairingToken))

        val uri = protocol.encodeToUri(payload)
        val bitmap = QrCodeCodec.renderQrBitmap(payload = uri, sizePx = 512)
        assertNotNull("QR bitmap should render cleanly", bitmap)
        val decoded = QrCodeCodec.decodeFromBitmap(bitmap!!)
        bitmap.recycle()
        assertEquals(uri, decoded)
    }
}
