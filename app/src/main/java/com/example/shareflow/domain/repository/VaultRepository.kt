package com.example.shareflow.domain.repository

import com.example.shareflow.core.common.AppResult
import com.example.shareflow.domain.model.VaultItem
import kotlinx.coroutines.flow.Flow

/**
 * Contract for the Keystore-encrypted Private Vault repository.
 */
interface VaultRepository {
    fun observeVaultItems(): Flow<List<VaultItem>>
    suspend fun getVaultItemById(id: String): VaultItem?
    suspend fun registerEncryptedItem(item: VaultItem): AppResult<Unit>
    suspend fun removeVaultItem(id: String): AppResult<Unit>
}
