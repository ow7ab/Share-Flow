package com.example.shareflow.utilities

import android.content.Context
import android.net.Uri
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.domain.model.DuplicateFileGroup
import com.example.shareflow.domain.model.DuplicateScanReport
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.repository.FileManagerRepository
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Two-stage duplicate file detector:
 * 1. Metadata pre-filter: groups accessible non-directory files by exact byte size, discarding unique sizes.
 * 2. Cryptographic verification: streams SHA-256 hashes (64 KB chunks; never buffers whole files in RAM)
 *    to confirm identical content.
 *
 * Security & Safety:
 * - Never automatically deletes files.
 * - Handles inaccessible URIs and missing files gracefully.
 */
class DuplicateFileFinderEngine(
    private val context: Context,
    private val fileManagerRepository: FileManagerRepository,
    private val checksumVerifier: ChecksumVerifier,
    private val dispatchers: DispatcherProvider
) {
    private val appContext = context.applicationContext

    suspend fun findDuplicates(
        entries: List<LocalFileEntry>,
        onProgress: ((hashedCount: Int, totalCandidates: Int) -> Unit)? = null
    ): DuplicateScanReport = withContext(dispatchers.io) {
        val uniqueByUri = LinkedHashMap<String, LocalFileEntry>()
        var skippedInaccessible = 0
        for (entry in entries) {
            if (entry.isDirectory) continue
            if (!entry.isAccessible) {
                skippedInaccessible++
                continue
            }
            uniqueByUri.putIfAbsent(entry.uriString, entry)
        }

        val inspectable = uniqueByUri.values.toList()

        // Stage 1: Metadata pre-filter by exact byte size (size > 0 required for duplicate content)
        val candidateBuckets = inspectable
            .filter { it.sizeBytes > 0L }
            .groupBy { it.sizeBytes }
            .filterValues { it.size >= 2 }

        val totalCandidates = candidateBuckets.values.sumOf { it.size }
        var hashedCount = 0
        val confirmedGroups = mutableListOf<DuplicateFileGroup>()

        // Stage 2: Cryptographic SHA-256 verification on size-matching candidates only
        for ((sizeBytes, bucket) in candidateBuckets) {
            ensureActive()
            val byHash = LinkedHashMap<String, MutableList<LocalFileEntry>>()
            for (entry in bucket) {
                ensureActive()
                val sha = computeEntrySha256(entry)
                hashedCount++
                onProgress?.invoke(hashedCount, totalCandidates)
                if (sha == null) {
                    skippedInaccessible++
                    continue
                }
                byHash.getOrPut(sha) { mutableListOf() }.add(entry)
            }
            for ((sha, matchingFiles) in byHash) {
                if (matchingFiles.size >= 2) {
                    confirmedGroups += DuplicateFileGroup(
                        sha256Hex = sha,
                        sizeBytes = sizeBytes,
                        files = matchingFiles.sortedBy { it.lastModifiedEpochMillis }
                    )
                }
            }
        }

        DuplicateScanReport(
            totalFilesInspected = inspectable.size,
            candidatesHashed = hashedCount,
            skippedInaccessibleCount = skippedInaccessible,
            groups = confirmedGroups.sortedByDescending { it.reclaimableBytes }
        )
    }

    private suspend fun computeEntrySha256(entry: LocalFileEntry): String? {
        val uri = runCatching { Uri.parse(entry.uriString) }.getOrNull() ?: return null
        if (uri.scheme == "file") {
            val file = uri.path?.let(::File) ?: return null
            if (!file.exists() || !file.isFile || !file.canRead()) return null
            return runCatching {
                FileInputStream(file).use { stream ->
                    (checksumVerifier.computeSha256(stream) as? AppResult.Success)?.data
                }
            }.getOrNull()
        }
        return when (val res = fileManagerRepository.computeSha256(entry.uriString)) {
            is AppResult.Success -> res.data
            is AppResult.Failure -> runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    (checksumVerifier.computeSha256(stream) as? AppResult.Success)?.data
                }
            }.getOrNull()
        }
    }
}
