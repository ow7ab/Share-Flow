package com.example.shareflow.data.repository

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.TrustedDeviceDao
import com.example.shareflow.data.local.database.entity.toDomain
import com.example.shareflow.data.local.database.entity.toTrustedEntity
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.repository.TrustedDeviceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class TrustedDeviceRepositoryImpl(
    private val dao: TrustedDeviceDao,
    private val dispatchers: DispatcherProvider
) : TrustedDeviceRepository {

    override fun observeTrustedDevices(): Flow<List<PeerDevice>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeTrustedCount(): Flow<Int> = dao.observeCount()

    override suspend fun getTrustedDevice(deviceId: String): PeerDevice? =
        withContext(dispatchers.io) {
            dao.findById(deviceId)?.toDomain()
        }

    override suspend fun saveTrustedDevice(device: PeerDevice): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.upsert(device.toTrustedEntity())
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to save trusted device", it)) }
            )
        }

    override suspend fun revokeTrustedDevice(deviceId: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteById(deviceId)
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to revoke trusted device", it)) }
            )
        }

    override suspend fun clearAllTrustedDevices(): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteAll()
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to revoke all trusted devices", it)) }
            )
        }
}
