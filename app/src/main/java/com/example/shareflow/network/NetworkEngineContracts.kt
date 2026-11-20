package com.example.shareflow.network

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Independent peer discovery engine interface (Wi-Fi Direct / NSD).
 * Decoupled from Compose/ViewModels; implementations emit only real discovered devices.
 */
interface DiscoveryEngine {
    val discoveredPeers: StateFlow<List<PeerDevice>>
    val isDiscovering: StateFlow<Boolean>

    suspend fun startDiscovery(): AppResult<Unit>
    suspend fun stopDiscovery(): AppResult<Unit>
    suspend fun startAdvertising(deviceName: String, port: Int): AppResult<Unit>
    suspend fun stopAdvertising(): AppResult<Unit>
}

/**
 * Independent peer connection manager interface handling session handshakes and sockets.
 */
interface ConnectionManager {
    val connectionState: StateFlow<ConnectionState>

    suspend fun connectToPeer(peer: PeerDevice): AppResult<String>
    suspend fun disconnect(): AppResult<Unit>
}

/**
 * Independent streaming file transfer engine interface.
 * Must emit real byte counts and verify SHA-256 checksums before completion.
 */
interface FileTransferEngine {
    val activeSessions: StateFlow<List<TransferSession>>

    fun sendFiles(
        peer: PeerDevice,
        documentUris: List<String>
    ): Flow<TransferSession>

    suspend fun cancelSession(sessionId: String): AppResult<Unit>
}
