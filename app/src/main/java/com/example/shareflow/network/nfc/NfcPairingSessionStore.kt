package com.example.shareflow.network.nfc

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ephemeral in-memory session store backing [ShareFlowNfcHceService].
 *
 * Security guarantees:
 * - Never writes pairing URIs, hotspot passphrases, or tokens to disk or SharedPreferences.
 * - Automatically rejects and clears expired sessions on read.
 * - Rejects malformed or oversized payloads before arming HCE.
 */
object NfcPairingSessionStore {

    private data class ArmedNfcSession(
        val payloadBytes: ByteArray,
        val expiresAtEpochMillis: Long
    )

    private val armedRef = AtomicReference<ArmedNfcSession?>(null)

    private val _isArmed = MutableStateFlow(false)
    val isArmed: StateFlow<Boolean> = _isArmed.asStateFlow()

    private val _lastPeerTapEpochMillis = MutableStateFlow(0L)
    val lastPeerTapEpochMillis: StateFlow<Long> = _lastPeerTapEpochMillis.asStateFlow()

    /**
     * Arms the receiver HCE service with a signed ShareFlow pairing URI until [expiresAtEpochMillis].
     * Returns true if armed successfully, false if the URI is invalid, oversized, or already expired.
     */
    fun arm(
        pairingUri: String,
        expiresAtEpochMillis: Long,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val trimmed = pairingUri.trim()
        if (!NfcPairingProtocol.isValidShareFlowPairingText(trimmed) || nowEpochMillis >= expiresAtEpochMillis) {
            clear()
            return false
        }
        val bytes = trimmed.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > NfcPairingProtocol.MAX_PAYLOAD_BYTES) {
            clear()
            return false
        }
        armedRef.set(ArmedNfcSession(payloadBytes = bytes, expiresAtEpochMillis = expiresAtEpochMillis))
        _isArmed.value = true
        return true
    }

    /**
     * Returns the currently armed UTF-8 pairing payload bytes, or `null` if unarmed or expired.
     */
    fun getActivePayloadBytes(nowEpochMillis: Long = System.currentTimeMillis()): ByteArray? {
        val session = armedRef.get() ?: return null
        if (nowEpochMillis >= session.expiresAtEpochMillis) {
            if (armedRef.compareAndSet(session, null)) {
                _isArmed.value = false
            }
            return null
        }
        return session.payloadBytes
    }

    /**
     * Records that a nearby NFC sender successfully read the pairing payload via HCE.
     */
    fun recordPeerTap(nowEpochMillis: Long = System.currentTimeMillis()) {
        _lastPeerTapEpochMillis.value = nowEpochMillis
    }

    /**
     * Immediately disarms HCE and wipes the in-memory pairing payload reference.
     */
    fun clear() {
        val previous = armedRef.getAndSet(null)
        previous?.payloadBytes?.fill(0)
        _isArmed.value = false
    }
}
