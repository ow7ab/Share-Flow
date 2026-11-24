package com.example.shareflow.network.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Android Host-based Card Emulation (HCE) service for ShareFlow NFC Tap-to-Pair.
 *
 * Bound exclusively by the Android OS (`android.permission.BIND_NFC_SERVICE`) while the device
 * is unlocked (`android:requireDeviceUnlock="true"`). Only responds with an active, unexpired
 * signed pairing URI when the user has ShareFlow's Receive or Show QR screen open.
 */
class ShareFlowNfcHceService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val activePayload = NfcPairingSessionStore.getActivePayloadBytes()
        val (response, completedTransfer) = NfcPairingProtocol.handleHostApdu(
            commandApdu = commandApdu,
            activePayloadBytes = activePayload
        )
        if (completedTransfer) {
            NfcPairingSessionStore.recordPeerTap()
        }
        return response
    }

    override fun onDeactivated(reason: Int) {
        // Stateless per-APDU protocol; no persistent channel resources to release.
    }
}
