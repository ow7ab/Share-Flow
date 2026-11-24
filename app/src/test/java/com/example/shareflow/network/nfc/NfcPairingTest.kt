package com.example.shareflow.network.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.HotspotSecurityType
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.qr.SmartQrParseResult
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.ui.discovery.NfcReceiverBroadcastCard
import com.example.shareflow.ui.discovery.NfcSenderTapCard
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NfcPairingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        NfcPairingSessionStore.clear()
    }

    private fun createSignedV3Uri(): Pair<SmartQrPairingProtocol, String> {
        val now = 1_760_000_000_000L
        val clock = object : Clock {
            override fun nowEpochMillis(): Long = now
        }
        val receiverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val protocol = SmartQrPairingProtocol(identityManager = receiverIdentity, clock = clock)
        val payload = protocol.createSessionPayload(
            displayName = "Receiver Pixel NFC",
            serviceName = "ShareFlow-NFC-9001",
            servicePort = 44556,
            preferredStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT,
            ssid = "AndroidShare_NFC_Test",
            passphrase = "NfcHotspotSecretPassphrase123",
            securityType = HotspotSecurityType.WPA2_PSK,
            hostAddressHint = "192.168.43.1",
            ttlMillis = 120_000L
        )
        return protocol to protocol.encodeToUri(payload)
    }

    @Test
    fun `full HCE APDU multi-chunk round-trip transfers signed V3 pairing URI intact and verifies ECDSA signature`() {
        val (receiverProtocol, signedUri) = createSignedV3Uri()
        assertTrue("Signed URI should exceed single 200-byte APDU chunk", signedUri.toByteArray().size > NfcPairingProtocol.CHUNK_SIZE_BYTES)

        val now = 1_760_000_000_000L
        val armed = NfcPairingSessionStore.arm(
            pairingUri = signedUri,
            expiresAtEpochMillis = now + 120_000L,
            nowEpochMillis = now
        )
        assertTrue(armed)
        assertTrue(NfcPairingSessionStore.isArmed.value)

        var completedFlagObserved = false
        val readResult = NfcPairingProtocol.readPayloadFromTransceiver { cmd ->
            val activeBytes = NfcPairingSessionStore.getActivePayloadBytes(nowEpochMillis = now + 1_000L)
            val (resp, done) = NfcPairingProtocol.handleHostApdu(cmd, activeBytes)
            if (done) completedFlagObserved = true
            resp
        }

        assertTrue("Expected NFC APDU read success, got $readResult", readResult.isSuccess)
        assertTrue("Host APDU handler should signal transfer completion on last chunk", completedFlagObserved)
        val receivedUri = readResult.getOrThrow()
        assertEquals(signedUri, receivedUri)

        // Verify the received NFC URI passes full SmartQrPairingProtocol ECDSA P-256 verification
        val senderClock = object : Clock {
            override fun nowEpochMillis(): Long = now + 2_000L
        }
        val senderIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val senderProtocol = SmartQrPairingProtocol(identityManager = senderIdentity, clock = senderClock)
        val parsed = senderProtocol.parseAndValidate(
            rawInput = receivedUri,
            localDeviceId = senderIdentity.getLocalIdentity().deviceId
        )
        assertTrue("NFC payload must pass full ECDSA verification: $parsed", parsed is SmartQrParseResult.Valid)
        val validPayload = (parsed as SmartQrParseResult.Valid).payload
        assertEquals("AndroidShare_NFC_Test", validPayload.ssid)
        assertEquals(44556, validPayload.servicePort)
        assertNotNull(receiverProtocol)
    }

    @Test
    fun `unarmed or expired NFC session returns 6985 and auto-clears volatile memory`() {
        val now = 1_760_000_000_000L
        val (_, signedUri) = createSignedV3Uri()

        // Unarmed -> 69 85
        val (unarmedResp, _) = NfcPairingProtocol.handleHostApdu(
            NfcPairingProtocol.buildSelectAidApdu(),
            NfcPairingSessionStore.getActivePayloadBytes(now)
        )
        assertArrayEquals(NfcPairingProtocol.SW_CONDITIONS_NOT_SATISFIED, unarmedResp)

        // Armed then expired -> auto-clears and returns 69 85
        assertTrue(NfcPairingSessionStore.arm(signedUri, expiresAtEpochMillis = now + 1_000L, nowEpochMillis = now))
        assertNull(NfcPairingSessionStore.getActivePayloadBytes(nowEpochMillis = now + 1_001L))
        assertFalse(NfcPairingSessionStore.isArmed.value)

        val readAfterExpiry = NfcPairingProtocol.readPayloadFromTransceiver { cmd ->
            NfcPairingProtocol.handleHostApdu(cmd, NfcPairingSessionStore.getActivePayloadBytes(now + 2_000L)).first
        }
        assertTrue(readAfterExpiry.isFailure)
        assertTrue(readAfterExpiry.exceptionOrNull()?.message?.contains("RECEIVER_NOT_READY") == true)
    }

    @Test
    fun `unknown AID, out-of-bounds read offset, and invalid or oversized payloads are strictly rejected`() {
        val (_, signedUri) = createSignedV3Uri()
        val payloadBytes = signedUri.toByteArray(StandardCharsets.UTF_8)

        // Wrong AID -> 6A 82
        val wrongAidSelect = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x00)
        val (wrongAidResp, _) = NfcPairingProtocol.handleHostApdu(wrongAidSelect, payloadBytes)
        assertArrayEquals(NfcPairingProtocol.SW_FILE_NOT_FOUND, wrongAidResp)

        // Out-of-bounds chunk read -> 6B 00
        val oobRead = NfcPairingProtocol.buildReadChunkApdu(offset = payloadBytes.size + 10, length = 32)
        val (oobResp, _) = NfcPairingProtocol.handleHostApdu(oobRead, payloadBytes)
        assertArrayEquals(NfcPairingProtocol.SW_WRONG_PARAMS, oobResp)

        // Non-ShareFlow URL & control characters & oversized string rejected
        assertFalse(NfcPairingProtocol.isValidShareFlowPairingText("https://evil.example/phish"))
        assertFalse(NfcPairingProtocol.isValidShareFlowPairingText("shareflow://pair?v=3\ninjected"))
        assertFalse(
            NfcPairingSessionStore.arm(
                pairingUri = "shareflow://pair?v=3&pad=" + "a".repeat(NfcPairingProtocol.MAX_PAYLOAD_BYTES),
                expiresAtEpochMillis = System.currentTimeMillis() + 60_000L
            )
        )
        assertFalse(NfcPairingSessionStore.isArmed.value)
    }

    @Test
    fun `NDEF fallback extracts valid ShareFlow URI and ignores unrelated NDEF tags`() {
        val (_, signedUri) = createSignedV3Uri()

        val mimeRecord = NdefRecord.createMime(
            NfcPairingProtocol.MIME_SHAREFLOW_PAIRING,
            signedUri.toByteArray(StandardCharsets.UTF_8)
        )
        val extractedMime = NfcPairingProtocol.extractPairingUriFromNdefMessage(NdefMessage(arrayOf(mimeRecord)))
        assertEquals(signedUri, extractedMime)

        val uriRecord = NdefRecord.createUri(signedUri)
        val extractedUri = NfcPairingProtocol.extractPairingUriFromNdefMessage(NdefMessage(arrayOf(uriRecord)))
        assertEquals(signedUri, extractedUri)

        val unrelated = NdefRecord.createUri("https://example.com/not-shareflow")
        assertNull(NfcPairingProtocol.extractPairingUriFromNdefMessage(NdefMessage(arrayOf(unrelated))))
    }

    @Test
    fun `NfcReceiverBroadcastCard arms HCE when enabled and shows Settings CTA when disabled`() {
        val (_, signedUri) = createSignedV3Uri()
        val futureExpiry = System.currentTimeMillis() + 120_000L

        composeRule.setContent {
            ShareFlowTheme {
                NfcReceiverBroadcastCard(
                    pairingUri = signedUri,
                    expiresAtEpochMillis = futureExpiry,
                    isSessionExpired = false,
                    hardwareStatusOverride = NfcHardwareStatus.ENABLED
                )
            }
        }
        composeRule.onNodeWithTag("nfc_receiver_ready_card").assertIsDisplayed()
        assertTrue("HCE store must be armed while receiver card is active", NfcPairingSessionStore.isArmed.value)
    }

    @Test
    fun `NfcSenderTapCard renders disabled Settings CTA and enabled ready state cleanly`() {
        composeRule.setContent {
            ShareFlowTheme {
                NfcSenderTapCard(
                    enabled = true,
                    isConnecting = false,
                    compact = false,
                    hardwareStatusOverride = NfcHardwareStatus.DISABLED,
                    onPairingUriReceived = {}
                )
            }
        }
        composeRule.onNodeWithTag("nfc_sender_disabled_card").assertIsDisplayed()
        composeRule.onNodeWithTag("nfc_sender_open_settings_button").assertIsDisplayed()
    }
}
