package com.example.shareflow.network.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import java.nio.charset.StandardCharsets

/**
 * Stateless ISO-DEP APDU & NDEF protocol for ShareFlow NFC Tap-to-Pair.
 *
 * Exchanges the exact ECDSA P-256 signed Smart QR pairing URI (`shareflow://pair?v=3&...`)
 * between an unlocked Receiver running [ShareFlowNfcHceService] and a Sender running
 * Android NFC Reader Mode (`IsoDep`), so NFC pairing inherits full cryptographic verification,
 * single-use replay protection, hotspot auto-join, and AES-256-GCM encryption.
 */
object NfcPairingProtocol {

    /**
     * Proprietary AID: 0xF0 (private range) + ASCII "SHAREFLOW" + protocol version 0x01.
     */
    const val SHAREFLOW_AID_HEX: String = "F05348415245464C4F5701"

    val SHAREFLOW_AID_BYTES: ByteArray = hexToBytes(SHAREFLOW_AID_HEX)

    const val MAX_PAYLOAD_BYTES: Int = 2048
    const val CHUNK_SIZE_BYTES: Int = 200
    private const val MAX_READ_ITERATIONS: Int = 16

    private const val CLA_ISO: Byte = 0x00
    private const val INS_SELECT: Byte = 0xA4.toByte()
    private const val P1_SELECT_BY_NAME: Byte = 0x04
    private const val P2_SELECT_FIRST: Byte = 0x00

    private const val CLA_SHAREFLOW: Byte = 0x80.toByte()
    private const val INS_READ_PAYLOAD: Byte = 0xB0.toByte()

    val SW_OK: ByteArray = byteArrayOf(0x90.toByte(), 0x00)
    val SW_CONDITIONS_NOT_SATISFIED: ByteArray = byteArrayOf(0x69, 0x85.toByte())
    val SW_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A, 0x82.toByte())
    val SW_WRONG_PARAMS: ByteArray = byteArrayOf(0x6B, 0x00)
    val SW_INS_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D, 0x00)

    const val MIME_SHAREFLOW_PAIRING: String = "application/vnd.shareflow.pair"

    /**
     * Builds the ISO 7816-4 SELECT AID command APDU for ShareFlow's HCE service.
     */
    fun buildSelectAidApdu(): ByteArray {
        val aid = SHAREFLOW_AID_BYTES
        val apdu = ByteArray(5 + aid.size + 1)
        apdu[0] = CLA_ISO
        apdu[1] = INS_SELECT
        apdu[2] = P1_SELECT_BY_NAME
        apdu[3] = P2_SELECT_FIRST
        apdu[4] = aid.size.toByte()
        System.arraycopy(aid, 0, apdu, 5, aid.size)
        apdu[5 + aid.size] = 0x00 // Le = max response
        return apdu
    }

    /**
     * Builds a proprietary chunked READ command APDU: `80 B0 <offsetHi> <offsetLo> <length>`.
     */
    fun buildReadChunkApdu(offset: Int, length: Int): ByteArray {
        require(offset in 0..MAX_PAYLOAD_BYTES) { "Invalid NFC read offset: $offset" }
        require(length in 1..CHUNK_SIZE_BYTES) { "Invalid NFC read length: $length" }
        return byteArrayOf(
            CLA_SHAREFLOW,
            INS_READ_PAYLOAD,
            ((offset ushr 8) and 0xFF).toByte(),
            (offset and 0xFF).toByte(),
            (length and 0xFF).toByte()
        )
    }

    /**
     * Checks whether [commandApdu] selects ShareFlow's registered HCE AID.
     */
    fun isSelectShareFlowAid(commandApdu: ByteArray): Boolean {
        if (commandApdu.size < 5) return false
        if (commandApdu[0] != CLA_ISO ||
            commandApdu[1] != INS_SELECT ||
            commandApdu[2] != P1_SELECT_BY_NAME ||
            commandApdu[3] != P2_SELECT_FIRST
        ) {
            return false
        }
        val lc = commandApdu[4].toInt() and 0xFF
        if (lc != SHAREFLOW_AID_BYTES.size) return false
        if (commandApdu.size < 5 + lc) return false
        for (i in 0 until lc) {
            if (commandApdu[5 + i] != SHAREFLOW_AID_BYTES[i]) return false
        }
        return true
    }

    /**
     * Processes a single incoming APDU on the Receiver's [ShareFlowNfcHceService].
     * Returns `(responseApdu, completedTransfer)` where `completedTransfer` is true when the
     * reader has fetched the final byte of the active pairing payload.
     */
    fun handleHostApdu(
        commandApdu: ByteArray?,
        activePayloadBytes: ByteArray?
    ): Pair<ByteArray, Boolean> {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_WRONG_PARAMS to false
        }

        // 1. ISO SELECT AID command
        if (commandApdu[0] == CLA_ISO && commandApdu[1] == INS_SELECT) {
            if (!isSelectShareFlowAid(commandApdu)) {
                return SW_FILE_NOT_FOUND to false
            }
            if (activePayloadBytes == null || activePayloadBytes.isEmpty() || activePayloadBytes.size > MAX_PAYLOAD_BYTES) {
                return SW_CONDITIONS_NOT_SATISFIED to false
            }
            val len = activePayloadBytes.size
            val response = byteArrayOf(
                ((len ushr 8) and 0xFF).toByte(),
                (len and 0xFF).toByte(),
                SW_OK[0],
                SW_OK[1]
            )
            return response to false
        }

        // 2. Chunked READ_PAYLOAD command: 80 B0 <offsetHi> <offsetLo> <le>
        if (commandApdu[0] == CLA_SHAREFLOW && commandApdu[1] == INS_READ_PAYLOAD) {
            if (activePayloadBytes == null || activePayloadBytes.isEmpty() || activePayloadBytes.size > MAX_PAYLOAD_BYTES) {
                return SW_CONDITIONS_NOT_SATISFIED to false
            }
            if (commandApdu.size < 5) {
                return SW_WRONG_PARAMS to false
            }
            val offset = ((commandApdu[2].toInt() and 0xFF) shl 8) or (commandApdu[3].toInt() and 0xFF)
            val requestedLen = (commandApdu[4].toInt() and 0xFF).let { if (it == 0) CHUNK_SIZE_BYTES else it }
            if (offset < 0 || offset >= activePayloadBytes.size || requestedLen <= 0) {
                return SW_WRONG_PARAMS to false
            }
            val remaining = activePayloadBytes.size - offset
            val sliceLen = minOf(remaining, requestedLen, CHUNK_SIZE_BYTES)
            val response = ByteArray(sliceLen + 2)
            System.arraycopy(activePayloadBytes, offset, response, 0, sliceLen)
            response[sliceLen] = SW_OK[0]
            response[sliceLen + 1] = SW_OK[1]
            val reachedEnd = (offset + sliceLen) >= activePayloadBytes.size
            return response to reachedEnd
        }

        return SW_INS_NOT_SUPPORTED to false
    }

    /**
     * Executes the full Sender-side ISO-DEP handshake (`SELECT AID` + bounded chunked `READ_PAYLOAD`)
     * over any APDU [transceive] transport and validates the received ShareFlow pairing text.
     */
    fun readPayloadFromTransceiver(
        transceive: (ByteArray) -> ByteArray
    ): Result<String> {
        return runCatching {
            val selectResponse = transceive(buildSelectAidApdu())
            if (selectResponse.size < 2) {
                error("Truncated NFC SELECT response")
            }
            val sw1 = selectResponse[selectResponse.size - 2]
            val sw2 = selectResponse[selectResponse.size - 1]
            if (sw1 == SW_CONDITIONS_NOT_SATISFIED[0] && sw2 == SW_CONDITIONS_NOT_SATISFIED[1]) {
                error("RECEIVER_NOT_READY")
            }
            if (sw1 != SW_OK[0] || sw2 != SW_OK[1]) {
                error("NFC SELECT rejected (SW=${formatSw(sw1, sw2)})")
            }
            if (selectResponse.size != 4) {
                error("Invalid NFC length header")
            }
            val totalLength = ((selectResponse[0].toInt() and 0xFF) shl 8) or (selectResponse[1].toInt() and 0xFF)
            if (totalLength <= 0 || totalLength > MAX_PAYLOAD_BYTES) {
                error("Invalid NFC payload size: $totalLength")
            }

            val output = ByteArray(totalLength)
            var offset = 0
            var iterations = 0
            while (offset < totalLength) {
                if (iterations++ >= MAX_READ_ITERATIONS) {
                    error("Exceeded NFC chunk read limit")
                }
                val chunkReqLen = minOf(CHUNK_SIZE_BYTES, totalLength - offset)
                val chunkResp = transceive(buildReadChunkApdu(offset, chunkReqLen))
                if (chunkResp.size < 2) {
                    error("Truncated NFC chunk response at offset $offset")
                }
                val cSw1 = chunkResp[chunkResp.size - 2]
                val cSw2 = chunkResp[chunkResp.size - 1]
                if (cSw1 != SW_OK[0] || cSw2 != SW_OK[1]) {
                    error("NFC chunk read failed at offset $offset (SW=${formatSw(cSw1, cSw2)})")
                }
                val dataLen = chunkResp.size - 2
                if (dataLen <= 0 || offset + dataLen > totalLength) {
                    error("Unexpected NFC chunk data length $dataLen at offset $offset")
                }
                System.arraycopy(chunkResp, 0, output, offset, dataLen)
                offset += dataLen
            }

            val decoded = String(output, StandardCharsets.UTF_8).trim()
            if (!isValidShareFlowPairingText(decoded)) {
                error("INVALID_SHAREFLOW_PAYLOAD")
            }
            decoded
        }
    }

    /**
     * Extracts and validates a ShareFlow pairing URI from an optional NDEF message
     * (supports URI records, ShareFlow MIME records, and plain UTF-8 Text records).
     */
    fun extractPairingUriFromNdefMessage(message: NdefMessage?): String? {
        val records = message?.records ?: return null
        for (record in records) {
            val candidate = decodeNdefRecordCandidate(record)
            if (candidate != null && isValidShareFlowPairingText(candidate)) {
                return candidate
            }
        }
        return null
    }

    internal fun decodeNdefRecordCandidate(record: NdefRecord): String? {
        val payload = record.payload ?: return null
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES + 16) return null

        // 1. Standard Android URI extraction (handles TNF_WELL_KNOWN RTD_URI and TNF_ABSOLUTE_URI)
        record.toUri()?.toString()?.trim()?.let { uri ->
            if (isValidShareFlowPairingText(uri)) return uri
        }

        // 2. ShareFlow MIME record
        if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
            val mimeType = String(record.type, StandardCharsets.US_ASCII)
            if (mimeType.equals(MIME_SHAREFLOW_PAIRING, ignoreCase = true) ||
                mimeType.equals("text/plain", ignoreCase = true)
            ) {
                return String(payload, StandardCharsets.UTF_8).trim()
            }
        }

        // 3. Well-known Text record (RTD_TEXT)
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            val statusByte = payload[0].toInt() and 0xFF
            val isUtf16 = (statusByte and 0x80) != 0
            val langLen = statusByte and 0x3F
            if (1 + langLen < payload.size) {
                val charset = if (isUtf16) StandardCharsets.UTF_16 else StandardCharsets.UTF_8
                return String(payload, 1 + langLen, payload.size - 1 - langLen, charset).trim()
            }
        }
        return null
    }

    /**
     * Strict pre-validation before handing NFC bytes to [com.example.shareflow.network.qr.SmartQrPairingProtocol].
     */
    fun isValidShareFlowPairingText(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(StandardCharsets.UTF_8).size > MAX_PAYLOAD_BYTES) {
            return false
        }
        if (trimmed.any { it.code < 0x20 || it.code == 0x7F }) {
            return false
        }
        return trimmed.startsWith("shareflow://pair?", ignoreCase = true) ||
            trimmed.startsWith("SF3-", ignoreCase = false)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string must have even length" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun formatSw(sw1: Byte, sw2: Byte): String =
        "%02X%02X".format(sw1.toInt() and 0xFF, sw2.toInt() and 0xFF)
}
