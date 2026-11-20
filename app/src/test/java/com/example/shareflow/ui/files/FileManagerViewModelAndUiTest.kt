package com.example.shareflow.ui.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.FileCapabilities
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileNameValidator
import com.example.shareflow.domain.model.FileOperationFailure
import com.example.shareflow.domain.model.FileOperationResult
import com.example.shareflow.domain.model.FileSortField
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.SortDirection
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.domain.repository.StorageAnalyzerRepository
import com.example.shareflow.network.LocalSessionController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileManagerViewModelAndUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testDispatchers(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
    }

    private fun createSessionController(): LocalSessionController =
        LocalSessionController(RuntimeEnvironment.getApplication(), testDispatchers())

    private fun createStorageRepo(): StorageAnalyzerRepository = object : StorageAnalyzerRepository {
        private val summary = StorageSummary(
            totalBytes = 128_000_000_000L,
            availableBytes = 64_000_000_000L,
            usedBytes = 64_000_000_000L,
            breakdown = emptyList()
        )
        override fun observeStorageSummary(): Flow<AppResult<StorageSummary>> = flowOf(AppResult.Success(summary))
        override suspend fun refreshStorageSummary(): AppResult<StorageSummary> = AppResult.Success(summary)
    }

    @Test
    fun `permission denial and revoked folder access surface permission revoked state`() = runTest(testDispatcher) {
        val fakeRepo = InMemoryFileManagerRepository(
            denyMediaScan = true,
            revokedTreeUris = setOf("content://tree/revoked")
        )
        val viewModel = FileManagerViewModel(
            fileManagerRepository = fakeRepo,
            storageAnalyzerRepository = createStorageRepo(),
            sessionController = createSessionController()
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPermissionRevoked)
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.openGrantedDirectory(
            GrantedDirectory(
                treeUriString = "content://tree/revoked",
                displayName = "RevokedFolder",
                canWrite = false,
                isAccessible = false
            )
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPermissionRevoked)
    }

    @Test
    fun `file operations rename createFolder copy move delete and multi-selection work end to end`() =
        runTest(testDispatcher) {
            val dirUri = "content://tree/work"
            val destUri = "content://tree/archive"
            val fileA = sampleFile("content://doc/a", "alpha.txt", 1_200L, FileCategory.DOCUMENTS, canWrite = true)
            val fileB = sampleFile("content://doc/b", "beta.zip", 4_800L, FileCategory.ARCHIVES, canWrite = true)
            val inaccessible = sampleFile(
                uri = "content://doc/locked",
                name = "locked.apk",
                size = 8_000L,
                category = FileCategory.APKS,
                canWrite = false,
                isAccessible = false
            )

            val fakeRepo = InMemoryFileManagerRepository(
                directories = mutableMapOf(
                    dirUri to mutableListOf(fileA, fileB, inaccessible),
                    destUri to mutableListOf()
                ),
                granted = mutableListOf(
                    GrantedDirectory(dirUri, "Work", canWrite = true, isAccessible = true),
                    GrantedDirectory(destUri, "Archive", canWrite = true, isAccessible = true)
                ),
                inaccessibleUris = setOf(inaccessible.uriString)
            )

            val viewModel = FileManagerViewModel(
                fileManagerRepository = fakeRepo,
                storageAnalyzerRepository = createStorageRepo(),
                sessionController = createSessionController()
            )
            advanceUntilIdle()

            // Open SAF directory
            viewModel.openGrantedDirectory(GrantedDirectory(dirUri, "Work", canWrite = true, isAccessible = true))
            advanceUntilIdle()
            assertEquals(FileBrowseMode.DIRECTORY, viewModel.uiState.value.browseMode)
            assertEquals(3, viewModel.uiState.value.visibleEntriesCount)

            // Create Folder
            viewModel.createFolder("Invoices")
            advanceUntilIdle()
            assertTrue(fakeRepo.directories[dirUri]!!.any { it.isDirectory && it.displayName == "Invoices" })

            // Rename permitted file
            viewModel.renameFile(fileA, "alpha_renamed.txt")
            advanceUntilIdle()
            assertTrue(fakeRepo.directories[dirUri]!!.any { it.displayName == "alpha_renamed.txt" })

            // Multi-select
            viewModel.toggleSelection(fileA.uriString)
            viewModel.toggleSelection(fileB.uriString)
            assertTrue(viewModel.uiState.value.isSelectionMode)
            assertEquals(2, viewModel.uiState.value.selectedUris.size)
            assertEquals(6_000L, viewModel.uiState.value.selectedTotalBytes)

            // Copy selected files to Archive
            viewModel.startTransferOperation(PendingTransferOperation.COPY, viewModel.getSelectedEntries())
            viewModel.executeTransferToDirectory(destUri)
            advanceUntilIdle()
            assertEquals(2, fakeRepo.directories[destUri]!!.size)
            assertFalse(viewModel.uiState.value.isSelectionMode)

            // Move including an inaccessible file -> accessible succeeds, inaccessible fails safely without deletion
            viewModel.toggleSelection(fileB.uriString)
            viewModel.toggleSelection(inaccessible.uriString)
            viewModel.startTransferOperation(PendingTransferOperation.MOVE, viewModel.getSelectedEntries())
            viewModel.executeTransferToDirectory(destUri)
            advanceUntilIdle()
            assertTrue(fakeRepo.directories[dirUri]!!.none { it.uriString == fileB.uriString })
            assertTrue(fakeRepo.directories[dirUri]!!.any { it.uriString == inaccessible.uriString })

            // Delete with confirmation workflow
            viewModel.requestDelete(listOf(fileA))
            assertTrue(viewModel.uiState.value.showDeleteConfirm)
            viewModel.dismissDeleteConfirm()
            assertFalse(viewModel.uiState.value.showDeleteConfirm)
            assertTrue(fakeRepo.directories[dirUri]!!.any { it.uriString == fileA.uriString })

            viewModel.requestDelete(listOf(fileA))
            viewModel.confirmDelete()
            advanceUntilIdle()
            assertTrue(fakeRepo.directories[dirUri]!!.none { it.uriString == fileA.uriString })

            // Sort & Search
            viewModel.onSearchQueryChange("Invoices")
            assertEquals(1, viewModel.uiState.value.visibleEntriesCount)
            viewModel.setSearchActive(false)
            viewModel.updateSort(FileSortField.SIZE, SortDirection.DESCENDING)
            assertEquals(FileSortField.SIZE, viewModel.uiState.value.sortSpec.field)
        }

    @Test
    fun `ui displays multi-select contextual bar confirmation dialog and list grid toggle`() =
        runTest(testDispatcher) {
            val file1 = sampleFile("content://doc/img1", "photo.jpg", 2048L, FileCategory.IMAGES, canWrite = true)
            val file2 = sampleFile("content://doc/arc1", "backup.zip", 4096L, FileCategory.ARCHIVES, canWrite = true)
            val fakeRepo = InMemoryFileManagerRepository(
                mediaItems = mutableListOf(file1, file2)
            )
            val viewModel = FileManagerViewModel(
                fileManagerRepository = fakeRepo,
                storageAnalyzerRepository = createStorageRepo(),
                sessionController = createSessionController()
            )

            composeTestRule.setContent {
                ShareFlowTheme {
                    FileManagerHost(
                        viewModel = viewModel,
                        fixedCategory = null,
                        onOpenCategoryRoute = {},
                        onNavigateBack = null,
                        onNavigateToSend = {},
                        onNavigateToStorageAnalyzer = {}
                    )
                }
            }
            advanceUntilIdle()
            composeTestRule.waitForIdle()

            // Verify storage indicator & all 8 categories exist
            composeTestRule.onNodeWithTag("files_storage_indicator").assertIsDisplayed()
            composeTestRule.onNodeWithTag("files_screen_list")
                .performScrollToNode(hasTestTag("files_category_archives"))
            composeTestRule.onNodeWithTag("files_category_archives").assertIsDisplayed()

            // Toggle to Grid view and back to List view
            composeTestRule.onNodeWithTag("files_layout_toggle").performClick()
            composeTestRule.waitForIdle()
            assertEquals(FileViewLayout.GRID, viewModel.uiState.value.viewLayout)
            composeTestRule.onNodeWithTag("files_layout_toggle").performClick()
            composeTestRule.waitForIdle()
            assertEquals(FileViewLayout.LIST, viewModel.uiState.value.viewLayout)

            // Trigger multi-selection and verify contextual action bar + delete confirmation dialog
            viewModel.toggleSelection(file1.uriString)
            viewModel.toggleSelection(file2.uriString)
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("files_selection_bar").assertIsDisplayed()
            composeTestRule.onNodeWithTag("files_selection_delete").performClick()
            composeTestRule.waitForIdle()

            composeTestRule.onNodeWithTag("files_delete_confirm_button").assertIsDisplayed()
            composeTestRule.onNodeWithTag("files_delete_confirm_button").performClick()
            advanceUntilIdle()
            composeTestRule.waitForIdle()

            assertTrue(fakeRepo.mediaItems.isEmpty())
            assertTrue(composeTestRule.onAllNodesWithTag("files_selection_bar").fetchSemanticsNodes().isEmpty())
        }

    private fun sampleFile(
        uri: String,
        name: String,
        size: Long,
        category: FileCategory,
        canWrite: Boolean,
        isAccessible: Boolean = true
    ): LocalFileEntry = LocalFileEntry(
        uriString = uri,
        displayName = name,
        mimeType = when (category) {
            FileCategory.IMAGES -> "image/jpeg"
            FileCategory.ARCHIVES -> "application/zip"
            FileCategory.APKS -> "application/vnd.android.package-archive"
            else -> "text/plain"
        },
        sizeBytes = size,
        lastModifiedEpochMillis = 1_700_000_000_000L,
        category = category,
        isDirectory = false,
        source = FileSource.SAF_TREE,
        capabilities = FileCapabilities(
            canRead = isAccessible,
            canWrite = canWrite,
            canRename = canWrite,
            canDelete = canWrite
        ),
        isAccessible = isAccessible
    )

    private class InMemoryFileManagerRepository(
        val mediaItems: MutableList<LocalFileEntry> = mutableListOf(),
        val directories: MutableMap<String, MutableList<LocalFileEntry>> = mutableMapOf(),
        val granted: MutableList<GrantedDirectory> = mutableListOf(),
        val denyMediaScan: Boolean = false,
        val revokedTreeUris: Set<String> = emptySet(),
        val inaccessibleUris: Set<String> = emptySet()
    ) : FileManagerRepository {

        private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        private val grantedFlow = MutableStateFlow(granted.toList())

        override fun observeStorageChanges(): Flow<Unit> = changes
        override fun observeGrantedDirectories(): Flow<List<GrantedDirectory>> = grantedFlow

        override suspend fun registerGrantedDirectory(treeUriString: String): AppResult<GrantedDirectory> {
            if (treeUriString in revokedTreeUris) {
                return AppResult.Failure(AppError.PermissionDenied("Access denied"))
            }
            val dir = GrantedDirectory(treeUriString, "Picked", canWrite = true, isAccessible = true)
            granted.add(dir)
            grantedFlow.value = granted.toList()
            return AppResult.Success(dir)
        }

        override suspend fun releaseGrantedDirectory(treeUriString: String): AppResult<Unit> {
            granted.removeAll { it.treeUriString == treeUriString }
            grantedFlow.value = granted.toList()
            return AppResult.Success(Unit)
        }

        override suspend fun scanAccessibleMedia(): AppResult<List<LocalFileEntry>> {
            if (denyMediaScan) {
                return AppResult.Failure(AppError.PermissionDenied("MediaStore permission denied"))
            }
            return AppResult.Success(mediaItems.toList())
        }

        override suspend fun listDirectory(treeUriString: String): AppResult<List<LocalFileEntry>> {
            if (treeUriString in revokedTreeUris) {
                return AppResult.Failure(AppError.PermissionDenied("Folder permission revoked"))
            }
            val items = directories[treeUriString]
                ?: return AppResult.Failure(AppError.NotFound("Directory missing"))
            return AppResult.Success(items.toList())
        }

        override suspend fun inspectFile(documentUriString: String): AppResult<LocalFileEntry> {
            if (documentUriString in inaccessibleUris) {
                return AppResult.Failure(AppError.PermissionDenied("File inaccessible"))
            }
            val found = findEntry(documentUriString)
                ?: return AppResult.Failure(AppError.NotFound("File not found"))
            return AppResult.Success(found)
        }

        override suspend fun queryByCategory(category: FileCategory): AppResult<List<LocalFileEntry>> =
            AppResult.Success(mediaItems.filter { it.matchesCategory(category) })

        override suspend fun createFolder(parentDirectoryUriString: String, folderName: String): AppResult<LocalFileEntry> {
            if (!FileNameValidator.isValid(folderName)) {
                return AppResult.Failure(AppError.Validation("Invalid folder name"))
            }
            val list = directories[parentDirectoryUriString]
                ?: return AppResult.Failure(AppError.NotFound("Parent missing"))
            val folder = LocalFileEntry(
                uriString = "$parentDirectoryUriString/$folderName",
                displayName = folderName,
                mimeType = "vnd.android.document/directory",
                sizeBytes = 0L,
                lastModifiedEpochMillis = System.currentTimeMillis(),
                category = FileCategory.OTHER,
                isDirectory = true,
                capabilities = FileCapabilities(canRead = true, canWrite = true, canRename = true, canDelete = true, canCreateChildren = true)
            )
            list.add(folder)
            directories[folder.uriString] = mutableListOf()
            return AppResult.Success(folder)
        }

        override suspend fun renameFile(documentUriString: String, newName: String): AppResult<LocalFileEntry> {
            if (!FileNameValidator.isValid(newName)) {
                return AppResult.Failure(AppError.Validation("Invalid file name"))
            }
            if (documentUriString in inaccessibleUris) {
                return AppResult.Failure(AppError.PermissionDenied("Cannot rename"))
            }
            directories.values.forEach { list ->
                val idx = list.indexOfFirst { it.uriString == documentUriString }
                if (idx >= 0) {
                    val updated = list[idx].copy(displayName = newName)
                    list[idx] = updated
                    return AppResult.Success(updated)
                }
            }
            return AppResult.Failure(AppError.NotFound("File missing"))
        }

        override suspend fun copyFiles(
            sourceUris: List<String>,
            targetDirectoryUriString: String
        ): AppResult<FileOperationResult> {
            val dest = directories[targetDirectoryUriString]
                ?: return AppResult.Failure(AppError.NotFound("Target missing"))
            val ok = mutableListOf<String>()
            val fail = mutableListOf<FileOperationFailure>()
            for (uri in sourceUris) {
                if (uri in inaccessibleUris) {
                    fail.add(FileOperationFailure(uri, null, "Permission denied"))
                    continue
                }
                val entry = findEntry(uri)
                if (entry == null) {
                    fail.add(FileOperationFailure(uri, null, "File not found"))
                    continue
                }
                val copiedUri = "$targetDirectoryUriString/copy_${entry.displayName}"
                dest.add(entry.copy(uriString = copiedUri))
                ok.add(copiedUri)
            }
            return AppResult.Success(FileOperationResult(ok, fail))
        }

        override suspend fun moveFiles(
            sourceUris: List<String>,
            targetDirectoryUriString: String
        ): AppResult<FileOperationResult> {
            val dest = directories[targetDirectoryUriString]
                ?: return AppResult.Failure(AppError.NotFound("Target missing"))
            val ok = mutableListOf<String>()
            val fail = mutableListOf<FileOperationFailure>()
            for (uri in sourceUris) {
                if (uri in inaccessibleUris) {
                    fail.add(FileOperationFailure(uri, null, "Permission denied"))
                    continue
                }
                val entry = findEntry(uri)
                if (entry == null) {
                    fail.add(FileOperationFailure(uri, null, "File not found"))
                    continue
                }
                val movedUri = "$targetDirectoryUriString/moved_${entry.displayName}"
                dest.add(entry.copy(uriString = movedUri))
                removeEntry(uri)
                ok.add(movedUri)
            }
            return AppResult.Success(FileOperationResult(ok, fail))
        }

        override suspend fun deleteFiles(documentUris: List<String>): AppResult<FileOperationResult> {
            val ok = mutableListOf<String>()
            val fail = mutableListOf<FileOperationFailure>()
            for (uri in documentUris) {
                if (uri in inaccessibleUris) {
                    fail.add(FileOperationFailure(uri, null, "Permission denied"))
                } else if (removeEntry(uri)) {
                    ok.add(uri)
                } else {
                    fail.add(FileOperationFailure(uri, null, "File not found"))
                }
            }
            return AppResult.Success(FileOperationResult(ok, fail))
        }

        override suspend fun readTextPreview(documentUriString: String, maxBytes: Int): AppResult<String> =
            if (documentUriString in inaccessibleUris) {
                AppResult.Failure(AppError.PermissionDenied("Permission denied"))
            } else {
                AppResult.Success("Preview text content")
            }

        override suspend fun computeSha256(documentUriString: String): AppResult<String> =
            AppResult.Success("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

        private fun findEntry(uri: String): LocalFileEntry? =
            mediaItems.firstOrNull { it.uriString == uri }
                ?: directories.values.asSequence().flatten().firstOrNull { it.uriString == uri }

        private fun removeEntry(uri: String): Boolean {
            var removed = mediaItems.removeAll { it.uriString == uri }
            directories.values.forEach { list ->
                if (list.removeAll { it.uriString == uri }) removed = true
            }
            return removed
        }
    }
}
