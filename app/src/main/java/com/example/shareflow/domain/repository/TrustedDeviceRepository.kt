package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.PeerDevice
import kotlinx.coroutines.flow.Flow

/**
 * Contract for persistent trusted peer device registry backed by Room.
 */
interface TrustedDeviceRepository {
    fun observeTrustedDevices(): Flow<List<PeerDevice>>
    fun observeTrustedCount(): Flow<Int>
    suspend fun getTrustedDevice(deviceId: String): PeerDevice?
    suspend fun saveTrustedDevice(device: PeerDevice): AppResult<Unit>
    suspend fun revokeTrustedDevice(deviceId: String): AppResult<Unit>
    suspend fun clearAllTrustedDevices(): AppResult<Unit> = AppResult.Success(Unit)
}
