package com.example.shareflow.data.files

import androidx.paging.PagingSource
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.repository.AndroidFileManagerRepository
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.FileSortField
import com.example.shareflow.domain.model.FileSortSpec
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.SortDirection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileManagerRepositoryAndPagingTest {

    private fun testDispatchers(dispatcher: CoroutineDispatcher) = object : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }

    @Test
    fun `large directory paging slices 1500 entries into bounded pages with search and sort`() = runTest {
        val largeDirectory = (1..1500).map { index ->
            LocalFileEntry(
                uriString = "content://com.android.externalstorage.documents/document/file_$index",
                displayName = if (index <= 10) "Folder_${index.toString().padStart(2, '0')}" else "Report_${index.toString().padStart(4, '0')}.pdf",
                mimeType = if (index <= 10) "vnd.android.document/directory" else "application/pdf",
                sizeBytes = if (index <= 10) 0L else index * 1024L,
                lastModifiedEpochMillis = 1_700_000_000_000L + index * 1_000L,
                category = FileCategory.DOCUMENTS,
                isDirectory = index <= 10,
                source = FileSource.SAF_TREE,
                capabilities = FileCapabilities(canRead = true, canWrite = true, canRename = true, canDelete = true)
            )
        }

        val pagingSource = FileListPagingSource(
            loadAllEntries = { largeDirectory },
            query = "",
            categoryFilter = null,
            sortSpec = FileSortSpec(field = FileSortField.SIZE, direction = SortDirection.DESCENDING, foldersFirst = true)
        )

        // Page 1: first 60 items — first 10 must be folders, followed by largest files descending
        val firstLoad = pagingSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 60, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page

        assertEquals(60, firstLoad.data.size)
        assertNull(firstLoad.prevKey)
        assertEquals(60, firstLoad.nextKey)
        assertTrue(firstLoad.data.take(10).all { it.isDirectory })
        assertEquals("Report_1500.pdf", firstLoad.data[10].displayName)
        assertEquals("Report_1451.pdf", firstLoad.data[59].displayName)

        // Traverse remaining pages to verify full coverage without duplicates or OOM
        val seenUris = LinkedHashSet<String>()
        seenUris.addAll(firstLoad.data.map { it.uriString })
        var nextKey = firstLoad.nextKey
        var pageCount = 1
        while (nextKey != null) {
            val page = pagingSource.load(
                PagingSource.LoadParams.Append(key = nextKey, loadSize = 60, placeholdersEnabled = false)
            ) as PagingSource.LoadResult.Page
            seenUris.addAll(page.data.map { it.uriString })
            nextKey = page.nextKey
            pageCount++
        }

        assertEquals(25, pageCount)
        assertEquals(1500, seenUris.size)

        // Search inside large directory
        val searchSource = FileListPagingSource(
            loadAllEntries = { largeDirectory },
            query = "Report_149",
            categoryFilter = FileCategory.DOCUMENTS,
            sortSpec = FileSortSpec(field = FileSortField.NAME, direction = SortDirection.ASCENDING)
        )
        val searchPage = searchSource.load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 60, placeholdersEnabled = false)
        ) as PagingSource.LoadResult.Page
        assertEquals(10, searchPage.data.size)
        assertEquals("Report_1490.pdf", searchPage.data.first().displayName)
        assertEquals("Report_1499.pdf", searchPage.data.last().displayName)
    }

    @Test
    fun `missing and inaccessible content uris return typed AppError failures without crashing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val context = RuntimeEnvironment.getApplication()
        val repository = AndroidFileManagerRepository(
            context = context,
            checksumVerifier = Sha256ChecksumVerifier(),
            dispatchers = testDispatchers(dispatcher)
        )

        val missingUri = "content://com.shareflow.nonexistent.provider/document/missing_404"

        val textPreview = repository.readTextPreview(missingUri)
        assertTrue(textPreview is AppResult.Failure)
        val previewErr = (textPreview as AppResult.Failure).error
        assertTrue(previewErr is AppError.NotFound || previewErr is AppError.Storage || previewErr is AppError.PermissionDenied)

        val shaResult = repository.computeSha256(missingUri)
        assertTrue(shaResult is AppResult.Failure)

        val deleteResult = repository.deleteFiles(listOf(missingUri))
        assertTrue(deleteResult is AppResult.Success)
        val opResult = (deleteResult as AppResult.Success).data
        assertEquals(0, opResult.succeededCount)
        assertEquals(1, opResult.failedCount)

        val copyResult = repository.copyFiles(
            sourceUris = listOf(missingUri),
            targetDirectoryUriString = "content://com.shareflow.nonexistent.provider/tree/missing_dir"
        )
        assertTrue(copyResult is AppResult.Failure)
    }

    @Test
    fun `file name validator rejects traversal slashes control chars and empty names`() {
        assertTrue(FileNameValidator.isValid("Quarterly Report 2026.pdf"))
        assertTrue(FileNameValidator.isValid("ملف_المشروع.zip"))
        assertFalse(FileNameValidator.isValid(""))
        assertFalse(FileNameValidator.isValid("   "))
        assertFalse(FileNameValidator.isValid("."))
        assertFalse(FileNameValidator.isValid(".."))
        assertFalse(FileNameValidator.isValid("../secret.txt"))
        assertFalse(FileNameValidator.isValid("folder/subfile.txt"))
        assertFalse(FileNameValidator.isValid("bad\\name.apk"))
        assertFalse(FileNameValidator.isValid("null\u0000byte.txt"))
        assertFalse(FileNameValidator.isValid("a".repeat(256)))
    }
}
