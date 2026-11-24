package com.example.shareflow.network.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Real Android NFC hardware state inspected from [NfcAdapter].
 */
enum class NfcHardwareStatus {
    UNSUPPORTED,
    DISABLED,
    ENABLED
}

/**
 * Actionable NFC tap error categories surfaced to the Sender pairing UI.
 */
enum class NfcReadError {
    RECEIVER_NOT_READY,
    INVALID_TAG,
    CONNECTION_INTERRUPTED
}

/**
 * Coordinates real Android NFC hardware detection, Settings deep-linking, and Reader Mode
 * (`NfcAdapter.enableReaderMode`) for instant Tap-to-Pair alongside QR scanning.
 */
object NfcPairingManager {

    private const val ISO_DEP_TIMEOUT_MS = 4_000
    private val mainHandler = Handler(Looper.getMainLooper())

    fun inspectHardwareStatus(context: Context): NfcHardwareStatus {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
            ?: return NfcHardwareStatus.UNSUPPORTED
        return if (runCatching { adapter.isEnabled }.getOrDefault(false)) {
            NfcHardwareStatus.ENABLED
        } else {
            NfcHardwareStatus.DISABLED
        }
    }

    fun openNfcSettings(context: Context): Boolean {
        val primary = Intent(Settings.ACTION_NFC_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(primary) }.isSuccess) {
            return true
        }
        val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(fallback) }.isSuccess
    }

    fun findHostActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * Enables real Android NFC Reader Mode on [activity] to read ShareFlow HCE receivers via
     * ISO-DEP (with NDEF fallback). Always pair with [disableReaderMode] in lifecycle cleanup.
     */
    fun enableReaderMode(
        activity: Activity,
        onReadingStarted: () -> Unit = {},
        onPairingUriRead: (String) -> Unit,
        onError: (NfcReadError) -> Unit
    ): Boolean {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull()
            ?: return false
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            return false
        }

        val callback = NfcAdapter.ReaderCallback { tag ->
            mainHandler.post { onReadingStarted() }
            readShareFlowPairingTag(
                tag = tag,
                onSuccess = { uri -> mainHandler.post { onPairingUriRead(uri) } },
                onFailure = { err -> mainHandler.post { onError(err) } }
            )
        }

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        return runCatching {
            adapter.enableReaderMode(activity, callback, flags, null)
            true
        }.getOrDefault(false)
    }

    fun disableReaderMode(activity: Activity) {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(activity) }.getOrNull() ?: return
        runCatching { adapter.disableReaderMode(activity) }
    }

    private fun readShareFlowPairingTag(
        tag: Tag,
        onSuccess: (String) -> Unit,
        onFailure: (NfcReadError) -> Unit
    ) {
        // 1. Primary path: Android HCE ISO-DEP APDU exchange
        val isoDep = IsoDep.get(tag)
        if (isoDep != null) {
            try {
                isoDep.connect()
                isoDep.timeout = ISO_DEP_TIMEOUT_MS
                val result = NfcPairingProtocol.readPayloadFromTransceiver { apdu ->
                    isoDep.transceive(apdu)
                }
                result.fold(
                    onSuccess = { uri ->
                        onSuccess(uri)
                        return
                    },
                    onFailure = { throwable ->
                        val msg = throwable.message.orEmpty()
                        val mapped = when {
                            msg.contains("RECEIVER_NOT_READY") -> NfcReadError.RECEIVER_NOT_READY
                            msg.contains("INVALID_SHAREFLOW_PAYLOAD") -> NfcReadError.INVALID_TAG
                            else -> NfcReadError.CONNECTION_INTERRUPTED
                        }
                        // Before reporting error, try NDEF fallback if present on the same tag
                        val ndefUri = tryReadNdefFallback(tag)
                        if (ndefUri != null) {
                            onSuccess(ndefUri)
                            return
                        }
                        onFailure(mapped)
                        return
                    }
                )
            } catch (_: Exception) {
                val ndefUri = tryReadNdefFallback(tag)
                if (ndefUri != null) {
                    onSuccess(ndefUri)
                    return
                }
                onFailure(NfcReadError.CONNECTION_INTERRUPTED)
                return
            } finally {
                runCatching { isoDep.close() }
            }
        }

        // 2. Secondary path: NDEF tag carrying a signed ShareFlow pairing URI
        val ndefUri = tryReadNdefFallback(tag)
        if (ndefUri != null) {
            onSuccess(ndefUri)
        } else {
            onFailure(NfcReadError.INVALID_TAG)
        }
    }

    private fun tryReadNdefFallback(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            ndef.connect()
            val message = ndef.ndefMessage ?: ndef.cachedNdefMessage
            NfcPairingProtocol.extractPairingUriFromNdefMessage(message)
        } catch (_: Exception) {
            runCatching { NfcPairingProtocol.extractPairingUriFromNdefMessage(ndef.cachedNdefMessage) }.getOrNull()
        } finally {
            runCatching { ndef.close() }
        }
    }
}
