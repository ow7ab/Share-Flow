package com.example.shareflow.data.repository

import android.os.Environment
import android.os.StatFs
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Zero-permission real storage analyzer backed by Android's [StatFs].
 * Reports actual on-device internal storage capacity without requesting broad storage permissions,
 * executing filesystem inspection strictly off the Main Thread.
 */
class AndroidStorageAnalyzerRepository(
    private val dispatchers: DispatcherProvider
) : StorageAnalyzerRepository {

    private val storageState = MutableStateFlow<AppResult<StorageSummary>?>(null)

    override fun observeStorageSummary(): Flow<AppResult<StorageSummary>> = flow {
        val current = storageState.value ?: refreshStorageSummary()
        emit(current)
        emitAll(storageState.filterNotNull())
    }.distinctUntilChanged()

    override suspend fun refreshStorageSummary(): AppResult<StorageSummary> =
        withContext(dispatchers.io) {
            val result = readStatFsSync()
            storageState.value = result
            result
        }

    private fun readStatFsSync(): AppResult<StorageSummary> = runCatching {
        val dataDir = Environment.getDataDirectory()
        val statFs = StatFs(dataDir.path)
        val total = statFs.totalBytes.coerceAtLeast(0L)
        val available = statFs.availableBytes.coerceIn(0L, total)
        val used = (total - available).coerceAtLeast(0L)
        StorageSummary(
            totalBytes = total,
            availableBytes = available,
            usedBytes = used,
            breakdown = emptyList()
        )
    }.fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Failure(AppError.Storage("Unable to inspect device storage", it)) }
    )
}
