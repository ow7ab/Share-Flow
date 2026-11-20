package com.example.shareflow.network

/**
 * Independent wire protocol constants and immutable handshake descriptors for ShareFlow's
 * peer-to-peer discovery, authenticated pairing, and transfer engine.
 * Contains zero Android UI dependencies.
 */
object TransferProtocol {
    const val PROTOCOL_VERSION: Int = 2
    const val SMART_QR_PROTOCOL_VERSION: Int = 3
    const val MIN_SUPPORTED_PROTOCOL_VERSION: Int = 2
    const val UDP_DISCOVERY_PORT: Int = 45891
    const val SERVICE_TYPE_NSD: String = "_shareflow._tcp."
    const val SERVICE_TYPE_P2P: String = "_shareflow._tcp"
    const val SERVICE_APP_ID: String = "shareflow"
    const val MAGIC_HEADER: String = "SFLW"
    const val DEFAULT_CHUNK_SIZE_BYTES: Int = 64 * 1024
    const val SOCKET_CONNECT_TIMEOUT_MS: Int = 10_000
    const val SOCKET_READ_TIMEOUT_MS: Int = 30_000
    const val APPROVAL_TIMEOUT_MS: Long = 45_000L
    const val REPLAY_WINDOW_MS: Long = 120_000L
    const val MAX_HANDSHAKE_FRAME_BYTES: Int = 32 * 1024
    const val HASH_ALGORITHM: String = "SHA-256"

    // DNS-SD / Wi-Fi Direct TXT keys
    const val TXT_APP = "app"
    const val TXT_VERSION = "ver"
    const val TXT_DEVICE_ID = "did"
    const val TXT_FINGERPRINT = "fp"
    const val TXT_DISPLAY_NAME = "name"
    const val TXT_DEVICE_TYPE = "type"
    const val TXT_PORT = "port"
    const val TXT_SESSION_ID = "sid"
    const val TXT_EPHEMERAL_KEY_HASH = "epk"
}

/**
 * Metadata header exchanged prior to streaming payload bytes.
 */
data class TransferPacketHeader(
    val protocolVersion: Int = TransferProtocol.PROTOCOL_VERSION,
    val sessionId: String,
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val expectedSha256: String
)
