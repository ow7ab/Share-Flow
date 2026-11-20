package com.example.shareflow.data.repository

import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.VaultItemDao
import com.example.shareflow.data.local.database.entity.toDomain
import com.example.shareflow.data.local.database.entity.toEntity
import com.example.shareflow.domain.model.VaultItem
import com.example.shareflow.domain.repository.VaultRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class VaultRepositoryImpl(
    private val dao: VaultItemDao,
    private val dispatchers: DispatcherProvider
) : VaultRepository {

    override fun observeVaultItems(): Flow<List<VaultItem>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getVaultItemById(id: String): VaultItem? =
        withContext(dispatchers.io) {
            dao.findById(id)?.toDomain()
        }

    override suspend fun registerEncryptedItem(item: VaultItem): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.upsert(item.toEntity())
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to register vault item", it)) }
            )
        }

    override suspend fun removeVaultItem(id: String): AppResult<Unit> =
        withContext(dispatchers.io) {
            runCatching {
                dao.deleteById(id)
            }.fold(
                onSuccess = { AppResult.Success(Unit) },
                onFailure = { AppResult.Failure(AppError.Storage("Failed to remove vault item", it)) }
            )
        }
}
