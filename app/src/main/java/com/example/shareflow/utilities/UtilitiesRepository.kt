package com.example.shareflow.utilities

import android.content.Context
import android.net.Uri
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.data.local.database.dao.FavoriteFileDao
import com.example.shareflow.data.local.database.dao.StorageSnapshotDao
import com.example.shareflow.data.local.database.entity.FavoriteFileEntity
import com.example.shareflow.data.local.database.entity.StorageSnapshotEntity
import com.example.shareflow.data.local.database.entity.toDomain
import com.example.shareflow.domain.model.FavoriteFileItem
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.StorageAnalysisReport
import com.example.shareflow.domain.model.StorageCategoryUsage
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.model.StorageTrendPoint
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Coordinates ShareFlow Utilities backed by real Android APIs & Room persistence:
 * - Storage Analyzer (StatFs totals + accessible category breakdown + large files + Room storage trends)
 * - Large Files Finder & Recent Files
 * - Favorites persistence (removing a favorite never deletes the actual user file)
 * - Safe temporary cache cleanup for Settings Local Data Management
 */
class UtilitiesRepository(
    context: Context,
    private val storageAnalyzerRepository: StorageAnalyzerRepository,
    private val fileManagerRepository: FileManagerRepository,
    private val favoriteFileDao: FavoriteFileDao,
    private val storageSnapshotDao: StorageSnapshotDao,
    private val dispatchers: DispatcherProvider,
    private val clock: Clock
) {
    private val appContext = context.applicationContext

    fun observeFavorites(): Flow<List<FavoriteFileItem>> =
        favoriteFileDao.observeAll().map { entities ->
            entities.map { entity -> entity.toDomain(isAccessible = isUriStillAccessible(entity.uriString)) }
        }

    fun observeFavoriteUris(): Flow<Set<String>> =
        favoriteFileDao.observeFavoriteUris().map { it.toSet() }

    fun observeStorageTrends(limit: Int = 14): Flow<List<StorageTrendPoint>> =
        storageSnapshotDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    suspend fun toggleFavorite(entry: LocalFileEntry): AppResult<Boolean> = withContext(dispatchers.io) {
        runCatching {
            val existing = favoriteFileDao.findByUri(entry.uriString)
            if (existing != null) {
                favoriteFileDao.deleteByUri(entry.uriString)
                false
            } else {
                favoriteFileDao.upsert(
                    FavoriteFileEntity(
                        uriString = entry.uriString,
                        displayName = entry.displayName,
                        mimeType = entry.mimeType,
                        sizeBytes = entry.sizeBytes,
                        category = entry.category.name,
                        addedAtEpochMillis = clock.nowEpochMillis()
                    )
                )
                true
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Storage("Unable to update favorite", it)) }
        )
    }

    suspend fun addFavoriteByUri(uriString: String): AppResult<FavoriteFileItem> = withContext(dispatchers.io) {
        when (val inspected = fileManagerRepository.inspectFile(uriString)) {
            is AppResult.Failure -> inspected
            is AppResult.Success -> {
                val entry = inspected.data
                val now = clock.nowEpochMillis()
                val entity = FavoriteFileEntity(
                    uriString = entry.uriString,
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    sizeBytes = entry.sizeBytes,
                    category = entry.category.name,
                    addedAtEpochMillis = now
                )
                favoriteFileDao.upsert(entity)
                AppResult.Success(entity.toDomain(isAccessible = entry.isAccessible))
            }
        }
    }

    /**
     * Removes a favorite bookmark from Room ONLY — never touches or deletes the actual file on device.
     */
    suspend fun removeFavorite(uriString: String): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching { favoriteFileDao.deleteByUri(uriString) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to remove favorite", it)) }
        )
    }

    suspend fun clearAllFavorites(): AppResult<Unit> = withContext(dispatchers.io) {
        runCatching { favoriteFileDao.deleteAll() }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to clear favorites", it)) }
        )
    }

    /**
     * Scans all currently accessible files across MediaStore and user-granted SAF directories.
     */
    suspend fun collectAccessibleFiles(): List<LocalFileEntry> = withContext(dispatchers.io) {
        val byUri = LinkedHashMap<String, LocalFileEntry>()
        for (category in listOf(
            FileCategory.IMAGES,
            FileCategory.VIDEOS,
            FileCategory.AUDIO,
            FileCategory.DOCUMENTS,
            FileCategory.ARCHIVES,
            FileCategory.APKS,
            FileCategory.DOWNLOADS,
            FileCategory.OTHER
        )) {
            val res = fileManagerRepository.queryByCategory(category)
            if (res is AppResult.Success) {
                for (entry in res.data) {
                    if (!entry.isDirectory && entry.isAccessible) {
                        byUri.putIfAbsent(entry.uriString, entry)
                    }
                }
            }
        }
        byUri.values.toList()
    }

    suspend fun loadRecentFiles(limit: Int = 100): List<LocalFileEntry> = withContext(dispatchers.io) {
        collectAccessibleFiles()
            .sortedByDescending { it.lastModifiedEpochMillis }
            .take(limit)
    }

    suspend fun loadLargeFiles(
        minSizeBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
        categoryFilter: FileCategory? = null
    ): List<LocalFileEntry> = withContext(dispatchers.io) {
        collectAccessibleFiles()
            .filter { entry ->
                entry.sizeBytes >= minSizeBytes &&
                    (categoryFilter == null || entry.matchesCategory(categoryFilter))
            }
            .sortedByDescending { it.sizeBytes }
    }

    /**
     * Performs a complete storage analysis:
     * 1. Reads real device StatFs total/used/available bytes.
     * 2. Aggregates accessible media/file category sizes and counts.
     * 3. Extracts top large files above [largeFileThresholdBytes].
     * 4. Records a timestamped snapshot in Room (throttled when unchanged) to build real storage trends.
     */
    suspend fun analyzeStorage(
        largeFileThresholdBytes: Long = DEFAULT_LARGE_FILE_THRESHOLD_BYTES,
        recordTrendSnapshot: Boolean = true
    ): AppResult<StorageAnalysisReport> = withContext(dispatchers.io) {
        val baseRes = storageAnalyzerRepository.refreshStorageSummary()
        if (baseRes is AppResult.Failure) return@withContext baseRes
        val base = (baseRes as AppResult.Success).data

        val allFiles = collectAccessibleFiles()
        val breakdown = FileCategory.entries.mapNotNull { cat ->
            val matching = allFiles.filter { it.category == cat }
            if (matching.isEmpty()) {
                null
            } else {
                StorageCategoryUsage(
                    category = cat,
                    bytesUsed = matching.sumOf { it.sizeBytes.coerceAtLeast(0L) },
                    itemCount = matching.size
                )
            }
        }.sortedByDescending { it.bytesUsed }

        val enrichedSummary = StorageSummary(
            totalBytes = base.totalBytes,
            availableBytes = base.availableBytes,
            usedBytes = base.usedBytes,
            breakdown = breakdown
        )

        if (recordTrendSnapshot && base.totalBytes > 0L) {
            val latest = storageSnapshotDao.getRecent(limit = 1).firstOrNull()
            val now = clock.nowEpochMillis()
            val shouldRecord = latest == null ||
                (now - latest.capturedAtEpochMillis) >= MIN_TREND_INTERVAL_MS ||
                kotlin.math.abs(latest.usedBytes - base.usedBytes) >= 1024L * 1024L
            if (shouldRecord) {
                storageSnapshotDao.insert(
                    StorageSnapshotEntity(
                        capturedAtEpochMillis = now,
                        totalBytes = base.totalBytes,
                        usedBytes = base.usedBytes,
                        availableBytes = base.availableBytes
                    )
                )
                storageSnapshotDao.pruneKeepingLatest(keepCount = 30)
            }
        }

        val trends = storageSnapshotDao.getRecent(limit = 14).map { it.toDomain() }
        val largeFiles = allFiles
            .filter { it.sizeBytes >= largeFileThresholdBytes }
            .sortedByDescending { it.sizeBytes }
            .take(25)

        AppResult.Success(
            StorageAnalysisReport(
                summary = enrichedSummary,
                largeFiles = largeFiles,
                largeFileThresholdBytes = largeFileThresholdBytes,
                trends = trends,
                scannedAccessibleFilesCount = allFiles.size
            )
        )
    }

    /**
     * Clears only ShareFlow's internal temporary cache directories (never touches Private Vault blobs,
     * Room databases, or user files in shared storage).
     */
    suspend fun clearTemporaryCache(): AppResult<Long> = withContext(dispatchers.io) {
        runCatching {
            var freed = 0L
            val cacheDir = appContext.cacheDir
            if (cacheDir != null && cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { child ->
                    freed += deleteRecursivelyCountingBytes(child)
                }
            }
            freed
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(AppError.Storage("Failed to clear temporary cache", it)) }
        )
    }

    suspend fun computeTemporaryCacheBytes(): Long = withContext(dispatchers.io) {
        val cacheDir = appContext.cacheDir ?: return@withContext 0L
        directoryBytes(cacheDir)
    }

    private fun deleteRecursivelyCountingBytes(target: File): Long {
        if (!target.exists()) return 0L
        if (target.isFile) {
            val len = target.length()
            return if (target.delete()) len else 0L
        }
        var sum = 0L
        target.listFiles()?.forEach { child -> sum += deleteRecursivelyCountingBytes(child) }
        target.delete()
        return sum
    }

    private fun directoryBytes(target: File): Long {
        if (!target.exists()) return 0L
        if (target.isFile) return target.length()
        return target.listFiles()?.sumOf { directoryBytes(it) } ?: 0L
    }

    private fun isUriStillAccessible(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return false
            return file.exists() && file.canRead()
        }
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    companion object {
        const val DEFAULT_LARGE_FILE_THRESHOLD_BYTES: Long = 10L * 1024L * 1024L // 10 MB
        private const val MIN_TREND_INTERVAL_MS: Long = 60_000L
    }
}
