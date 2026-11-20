package com.example.shareflow.data.files

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSortField
import com.example.shareflow.domain.model.FileSortSpec
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.SortDirection

/**
 * Pure filtering and sorting pipeline for file collections.
 * Deterministic and safe for large directories.
 */
object FileSortAndFilter {

    fun apply(
        entries: List<LocalFileEntry>,
        query: String = "",
        categoryFilter: FileCategory? = null,
        sortSpec: FileSortSpec = FileSortSpec()
    ): List<LocalFileEntry> {
        val trimmedQuery = query.trim().lowercase()
        val filtered = entries.asSequence()
            .filter { entry ->
                categoryFilter == null || entry.isDirectory || entry.matchesCategory(categoryFilter)
            }
            .filter { entry ->
                if (trimmedQuery.isEmpty()) {
                    true
                } else {
                    entry.displayName.lowercase().contains(trimmedQuery) ||
                        entry.mimeType.lowercase().contains(trimmedQuery)
                }
            }
            .toList()

        val baseComparator = when (sortSpec.field) {
            FileSortField.NAME -> compareBy<LocalFileEntry> { it.displayName.lowercase() }
            FileSortField.DATE_MODIFIED -> compareBy<LocalFileEntry> { it.lastModifiedEpochMillis }
                .thenBy { it.displayName.lowercase() }
            FileSortField.SIZE -> compareBy<LocalFileEntry> { it.sizeBytes }
                .thenBy { it.displayName.lowercase() }
            FileSortField.TYPE -> compareBy<LocalFileEntry> { it.category.name }
                .thenBy { it.mimeType.lowercase() }
                .thenBy { it.displayName.lowercase() }
        }

        val directional = if (sortSpec.direction == SortDirection.DESCENDING) {
            baseComparator.reversed()
        } else {
            baseComparator
        }

        val finalComparator = if (sortSpec.foldersFirst) {
            compareByDescending<LocalFileEntry> { it.isDirectory }.then(directional)
        } else {
            directional
        }

        return filtered.sortedWith(finalComparator)
    }
}

/**
 * Jetpack Paging 3 [PagingSource] delivering fixed-size slices of a file collection
 * so directories with thousands of entries remain smooth and memory-bounded in Compose.
 */
class FileListPagingSource(
    private val loadAllEntries: suspend () -> List<LocalFileEntry>,
    private val query: String,
    private val categoryFilter: FileCategory?,
    private val sortSpec: FileSortSpec
) : PagingSource<Int, LocalFileEntry>() {

    @Volatile
    private var cachedSortedEntries: List<LocalFileEntry>? = null

    override fun getRefreshKey(state: PagingState<Int, LocalFileEntry>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        val anchorPage = state.closestPageToPosition(anchorPosition)
        return anchorPage?.prevKey?.plus(1) ?: anchorPage?.nextKey?.minus(1)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LocalFileEntry> {
        return try {
            val allSorted = cachedSortedEntries ?: FileSortAndFilter.apply(
                entries = loadAllEntries(),
                query = query,
                categoryFilter = categoryFilter,
                sortSpec = sortSpec
            ).also { cachedSortedEntries = it }

            val offset = (params.key ?: 0).coerceAtLeast(0)
            if (offset >= allSorted.size) {
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                    nextKey = null
                )
            }

            val endExclusive = (offset + params.loadSize).coerceAtMost(allSorted.size)
            val pageData = allSorted.subList(offset, endExclusive)
            val prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0)
            val nextKey = if (endExclusive < allSorted.size) endExclusive else null

            LoadResult.Page(
                data = pageData,
                prevKey = prevKey,
                nextKey = nextKey
            )
        } catch (t: Throwable) {
            LoadResult.Error(t)
        }
    }
}
