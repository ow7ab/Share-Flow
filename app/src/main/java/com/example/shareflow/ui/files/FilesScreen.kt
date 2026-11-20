package com.example.shareflow.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.R
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.designsystem.components.CategoryCard
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PermissionExplanationDialog
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.GrantedDirectory
import com.example.shareflow.domain.model.LocalFileEntry

/**
 * All 8 required ShareFlow File Manager categories.
 */
enum class FilesHubCategory(
    @param:StringRes val titleRes: Int,
    val icon: ImageVector,
    val domainCategory: FileCategory
) {
    IMAGES(R.string.files_category_images, Icons.Filled.Image, FileCategory.IMAGES),
    VIDEOS(R.string.files_category_videos, Icons.Filled.VideoFile, FileCategory.VIDEOS),
    AUDIO(R.string.files_category_audio, Icons.Filled.AudioFile, FileCategory.AUDIO),
    DOCUMENTS(R.string.files_category_documents, Icons.Filled.Description, FileCategory.DOCUMENTS),
    ARCHIVES(R.string.files_category_archives, Icons.Filled.Archive, FileCategory.ARCHIVES),
    APK_FILES(R.string.files_category_apks, Icons.Filled.Android, FileCategory.APKS),
    DOWNLOADS(R.string.files_category_downloads, Icons.Filled.Download, FileCategory.DOWNLOADS),
    OTHER_FILES(R.string.files_category_other, Icons.AutoMirrored.Filled.InsertDriveFile, FileCategory.OTHER)
}

@Composable
fun FilesScreen(
    onOpenCategory: (FilesHubCategory) -> Unit,
    onNavigateToSend: () -> Unit = {},
    onNavigateToStorageAnalyzer: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: FileManagerViewModel = viewModel(
        key = "file_manager_main",
        factory = shareFlowViewModelFactory {
            FileManagerViewModel(
                fileManagerRepository = container.fileManagerRepository,
                storageAnalyzerRepository = container.storageAnalyzerRepository,
                sessionController = container.localSessionController,
                initialCategory = null
            )
        }
    )

    FileManagerHost(
        viewModel = viewModel,
        fixedCategory = null,
        onOpenCategoryRoute = onOpenCategory,
        onNavigateBack = null,
        onNavigateToSend = onNavigateToSend,
        onNavigateToStorageAnalyzer = onNavigateToStorageAnalyzer,
        modifier = modifier
    )
}

@Composable
fun FileCategoryDetailScreen(
    category: FilesHubCategory,
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: FileManagerViewModel = viewModel(
        key = "file_manager_category_${category.name}",
        factory = shareFlowViewModelFactory {
            FileManagerViewModel(
                fileManagerRepository = container.fileManagerRepository,
                storageAnalyzerRepository = container.storageAnalyzerRepository,
                sessionController = container.localSessionController,
                initialCategory = category.domainCategory
            )
        }
    )

    FileManagerHost(
        viewModel = viewModel,
        fixedCategory = category,
        onOpenCategoryRoute = {},
        onNavigateBack = onNavigateBack,
        onNavigateToSend = onNavigateToSend,
        onNavigateToStorageAnalyzer = {},
        modifier = modifier
    )
}

@Composable
internal fun FileManagerHost(
    viewModel: FileManagerViewModel,
    fixedCategory: FilesHubCategory?,
    onOpenCategoryRoute: (FilesHubCategory) -> Unit,
    onNavigateBack: (() -> Unit)?,
    onNavigateToSend: () -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pagedFiles = viewModel.pagedFiles.collectAsLazyPagingItems()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFolderPermissionInfo by rememberSaveable { mutableStateOf(false) }

    val shareChooserTitle = stringResource(R.string.files_share_chooser_title)
    val shareErrorText = stringResource(R.string.files_feedback_share_error)

    val safTreePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onDirectoryPicked(treeUri.toString())
        }
    }

    val targetDirTreePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            viewModel.onDirectoryPicked(treeUri.toString())
            viewModel.executeTransferToDirectory(treeUri.toString())
        }
    }

    val safDocsPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val inspected = SafDocumentInspector.inspectUris(context, uris, FileSource.SAF_DOCUMENT)
            viewModel.onFilesPicked(inspected)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val inspected = SafDocumentInspector.inspectUris(context, uris, FileSource.PHOTO_PICKER)
            viewModel.onFilesPicked(inspected)
        }
    }

    BackHandler(enabled = uiState.isSelectionMode || (fixedCategory == null && uiState.browseMode == FileBrowseMode.DIRECTORY)) {
        when {
            uiState.isSelectionMode -> viewModel.clearSelection()
            fixedCategory == null && uiState.browseMode == FileBrowseMode.DIRECTORY -> viewModel.navigateUpDirectory()
        }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    fun triggerShare(entries: List<LocalFileEntry>) {
        val ok = FileSharesheetHelper.shareFiles(context, entries, shareChooserTitle)
        if (!ok) {
            viewModel.showFeedback(shareErrorText)
        }
    }

    // Dialogs & Sheets
    if (showFolderPermissionInfo) {
        PermissionExplanationDialog(
            title = stringResource(R.string.files_permission_dialog_title),
            rationale = stringResource(R.string.files_permission_dialog_body),
            onGrantClick = {
                showFolderPermissionInfo = false
                safTreePicker.launch(null)
            },
            onDismiss = { showFolderPermissionInfo = false }
        )
    }

    if (uiState.showSortSheet) {
        SortOptionsSheet(
            currentSpec = uiState.sortSpec,
            onSelectSort = viewModel::updateSort,
            onDismiss = { viewModel.openSortSheet(false) }
        )
    }

    if (uiState.showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = viewModel::createFolder,
            onDismiss = { viewModel.openCreateFolderDialog(false) }
        )
    }

    uiState.renameTarget?.let { target ->
        RenameFileDialog(
            initialName = target.displayName,
            onConfirm = { newName -> viewModel.renameFile(target, newName) },
            onDismiss = { viewModel.openRenameDialog(null) }
        )
    }

    if (uiState.showDeleteConfirm) {
        ConfirmationDialog(
            title = stringResource(R.string.files_delete_title, uiState.deleteTargets.size.toString()),
            body = stringResource(R.string.files_delete_body),
            confirmLabel = stringResource(R.string.files_action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDeleteConfirm,
            isDestructive = true,
            confirmTestTag = "files_delete_confirm_button"
        )
    }

    uiState.pendingTransferOperation?.let { op ->
        TargetDirectoryPickerDialog(
            operation = op,
            writableDirectories = uiState.grantedDirectories.filter { it.isAccessible && it.canWrite },
            onSelectDirectory = viewModel::executeTransferToDirectory,
            onPickNewDirectory = { targetDirTreePicker.launch(null) },
            onDismiss = viewModel::cancelTransferOperation
        )
    }

    uiState.detailsTarget?.let { detailsFile ->
        FileDetailsSheet(
            file = detailsFile,
            sha256 = uiState.detailsSha256,
            isComputingSha256 = uiState.isComputingSha256,
            onComputeSha256 = viewModel::computeDetailsSha256,
            onDismiss = viewModel::closeDetails
        )
    }

    uiState.previewTarget?.let { previewFile ->
        FileMediaPreviewDialog(
            file = previewFile,
            textPreview = uiState.previewTextContent,
            isLoadingText = uiState.isPreviewLoading,
            onShare = { triggerShare(listOf(previewFile)) },
            onOpenExternal = { FileSharesheetHelper.openExternally(context, previewFile) },
            onDismiss = viewModel::closePreview
        )
    }

    val screenTitle = when {
        fixedCategory != null -> stringResource(fixedCategory.titleRes)
        uiState.browseMode == FileBrowseMode.DIRECTORY ->
            uiState.breadcrumbs.lastOrNull()?.displayName ?: stringResource(R.string.files_title)
        else -> stringResource(R.string.files_title)
    }

    val topBackAction: (() -> Unit)? = when {
        onNavigateBack != null -> onNavigateBack
        uiState.browseMode == FileBrowseMode.DIRECTORY -> ({ viewModel.navigateUpDirectory() })
        else -> null
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                FileSelectionActionBar(
                    selectedCount = uiState.selectedUris.size,
                    selectedBytes = uiState.selectedTotalBytes,
                    onClose = viewModel::clearSelection,
                    onSelectAll = viewModel::selectAllVisible,
                    onShare = { triggerShare(viewModel.getSelectedEntries()) },
                    onSend = {
                        if (viewModel.queueSelectedForShareFlowSend().isNotEmpty()) {
                            onNavigateToSend()
                        }
                    },
                    onCopy = {
                        viewModel.startTransferOperation(
                            PendingTransferOperation.COPY,
                            viewModel.getSelectedEntries()
                        )
                    },
                    onMove = {
                        viewModel.startTransferOperation(
                            PendingTransferOperation.MOVE,
                            viewModel.getSelectedEntries()
                        )
                    },
                    onDelete = { viewModel.requestDelete(viewModel.getSelectedEntries()) }
                )
            } else {
                ShareFlowTopBar(
                    title = screenTitle,
                    onNavigateBack = topBackAction,
                    actions = {
                        IconButton(
                            onClick = { viewModel.setSearchActive(!uiState.isSearchActive) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("files_search_toggle")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.files_cd_search)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.openSortSheet(true) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("files_sort_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(R.string.files_cd_sort)
                            )
                        }
                        IconButton(
                            onClick = viewModel::toggleViewLayout,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("files_layout_toggle")
                        ) {
                            Icon(
                                imageVector = if (uiState.viewLayout == FileViewLayout.LIST) {
                                    Icons.Filled.GridView
                                } else {
                                    Icons.AutoMirrored.Filled.ViewList
                                },
                                contentDescription = stringResource(
                                    if (uiState.viewLayout == FileViewLayout.LIST) {
                                        R.string.files_cd_view_grid
                                    } else {
                                        R.string.files_cd_view_list
                                    }
                                )
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (uiState.browseMode == FileBrowseMode.DIRECTORY && uiState.currentDirectoryWritable) {
                FloatingActionButton(
                    onClick = { viewModel.openCreateFolderDialog(true) },
                    modifier = Modifier.testTag("files_create_folder_fab")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CreateNewFolder,
                        contentDescription = stringResource(R.string.files_action_create_folder)
                    )
                }
            }
        }
    ) { innerPadding ->
        FileManagerBody(
            uiState = uiState,
            pagedFiles = pagedFiles,
            fixedCategory = fixedCategory,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onClearSearch = { viewModel.setSearchActive(false) },
            onSelectFolder = { showFolderPermissionInfo = true },
            onPickFiles = {
                safDocsPicker.launch(
                    SafDocumentInspector.mimeTypesForCategory(fixedCategory?.domainCategory)
                )
            },
            onPickMedia = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onScanMedia = viewModel::scanAccessibleMedia,
            onOpenCategory = onOpenCategoryRoute,
            onOpenGrantedDirectory = viewModel::openGrantedDirectory,
            onNavigateBreadcrumb = viewModel::navigateToBreadcrumb,
            onNavigateToStorageAnalyzer = onNavigateToStorageAnalyzer,
            onItemClick = { entry ->
                when {
                    uiState.isSelectionMode -> viewModel.toggleSelection(entry.uriString)
                    entry.isDirectory -> viewModel.openSubfolder(entry)
                    else -> viewModel.openPreview(entry)
                }
            },
            onItemLongClick = { entry -> viewModel.toggleSelection(entry.uriString) },
            onToggleSelect = { entry -> viewModel.toggleSelection(entry.uriString) },
            onPreview = viewModel::openPreview,
            onDetails = viewModel::openDetails,
            onRename = { viewModel.openRenameDialog(it) },
            onCopy = { viewModel.startTransferOperation(PendingTransferOperation.COPY, listOf(it)) },
            onMove = { viewModel.startTransferOperation(PendingTransferOperation.MOVE, listOf(it)) },
            onShare = { triggerShare(listOf(it)) },
            onSend = {
                viewModel.queueSingleForShareFlowSend(it)
                onNavigateToSend()
            },
            onDelete = { viewModel.requestDelete(listOf(it)) },
            onRetry = { viewModel.refreshCurrentView() },
            onRegrantFolder = { safTreePicker.launch(null) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
private fun FileManagerBody(
    uiState: FileManagerUiState,
    pagedFiles: LazyPagingItems<LocalFileEntry>,
    fixedCategory: FilesHubCategory?,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSelectFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onScanMedia: () -> Unit,
    onOpenCategory: (FilesHubCategory) -> Unit,
    onOpenGrantedDirectory: (GrantedDirectory) -> Unit,
    onNavigateBreadcrumb: (Int) -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit,
    onItemClick: (LocalFileEntry) -> Unit,
    onItemLongClick: (LocalFileEntry) -> Unit,
    onToggleSelect: (LocalFileEntry) -> Unit,
    onPreview: (LocalFileEntry) -> Unit,
    onDetails: (LocalFileEntry) -> Unit,
    onRename: (LocalFileEntry) -> Unit,
    onCopy: (LocalFileEntry) -> Unit,
    onMove: (LocalFileEntry) -> Unit,
    onShare: (LocalFileEntry) -> Unit,
    onSend: (LocalFileEntry) -> Unit,
    onDelete: (LocalFileEntry) -> Unit,
    onRetry: () -> Unit,
    onRegrantFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val listTag = if (fixedCategory != null) {
        "files_category_detail_${fixedCategory.name.lowercase()}"
    } else {
        "files_screen_list"
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (uiState.isOperationBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (uiState.isSearchActive) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.files_search_placeholder)) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(
                        onClick = onClearSearch,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.files_cd_clear_search)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = spacing.maxContentWidth)
                    .padding(horizontal = spacing.screenHorizontal, vertical = spacing.xs)
                    .testTag("files_search_input")
            )
        }

        if (uiState.browseMode == FileBrowseMode.DIRECTORY && uiState.breadcrumbs.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = spacing.screenHorizontal),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = spacing.maxContentWidth)
                    .testTag("files_breadcrumbs_bar")
            ) {
                itemsIndexed(uiState.breadcrumbs) { idx, crumb ->
                    AssistChip(
                        onClick = { onNavigateBreadcrumb(idx) },
                        label = { Text(crumb.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (idx == 0) {
                            { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            if (uiState.viewLayout == FileViewLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .testTag(listTag),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md
                    ),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    if (fixedCategory == null && uiState.browseMode == FileBrowseMode.OVERVIEW) {
                        overviewHeaderGridItems(
                            uiState = uiState,
                            onSelectFolder = onSelectFolder,
                            onPickFiles = onPickFiles,
                            onPickMedia = onPickMedia,
                            onScanMedia = onScanMedia,
                            onOpenCategory = onOpenCategory,
                            onOpenGrantedDirectory = onOpenGrantedDirectory,
                            onNavigateToStorageAnalyzer = onNavigateToStorageAnalyzer
                        )
                    } else if (fixedCategory != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_cat_actions") {
                            CategoryToolbarActions(
                                onPickFiles = onPickFiles,
                                onPickMedia = onPickMedia,
                                onScanMedia = onScanMedia
                            )
                        }
                    }

                    if (uiState.isPermissionRevoked) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_revoked") {
                            RevokedPermissionCard(onRegrant = onRegrantFolder)
                        }
                    } else if (uiState.errorMessage != null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_error") {
                            ErrorState(message = uiState.errorMessage, onRetry = onRetry)
                        }
                    } else if (uiState.isLoading && pagedFiles.itemCount == 0) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_loading") {
                            LoadingState()
                        }
                    } else if (pagedFiles.itemCount == 0) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_empty") {
                            FilesEmptyPlaceholder(uiState = uiState, fixedCategory = fixedCategory)
                        }
                    } else {
                        items(
                            count = pagedFiles.itemCount,
                            key = { idx -> pagedFiles[idx]?.uriString ?: "grid_$idx" }
                        ) { index ->
                            val item = pagedFiles[index]
                            if (item != null) {
                                FileManagerGridTile(
                                    file = item,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = item.uriString in uiState.selectedUris,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                    onToggleSelect = { onToggleSelect(item) },
                                    onPreview = { onPreview(item) },
                                    onDetails = { onDetails(item) },
                                    onRename = { onRename(item) },
                                    onCopy = { onCopy(item) },
                                    onMove = { onMove(item) },
                                    onShare = { onShare(item) },
                                    onSend = { onSend(item) },
                                    onDelete = { onDelete(item) },
                                    modifier = Modifier.testTag("files_grid_item_${item.displayName}")
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .testTag(listTag),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    if (fixedCategory == null && uiState.browseMode == FileBrowseMode.OVERVIEW) {
                        overviewHeaderListItems(
                            uiState = uiState,
                            onSelectFolder = onSelectFolder,
                            onPickFiles = onPickFiles,
                            onPickMedia = onPickMedia,
                            onScanMedia = onScanMedia,
                            onOpenCategory = onOpenCategory,
                            onOpenGrantedDirectory = onOpenGrantedDirectory,
                            onNavigateToStorageAnalyzer = onNavigateToStorageAnalyzer
                        )
                    } else if (fixedCategory != null) {
                        item(key = "cat_actions") {
                            CategoryToolbarActions(
                                onPickFiles = onPickFiles,
                                onPickMedia = onPickMedia,
                                onScanMedia = onScanMedia
                            )
                        }
                    }

                    if (uiState.isPermissionRevoked) {
                        item(key = "list_revoked") {
                            RevokedPermissionCard(onRegrant = onRegrantFolder)
                        }
                    } else if (uiState.errorMessage != null) {
                        item(key = "list_error") {
                            ErrorState(message = uiState.errorMessage, onRetry = onRetry)
                        }
                    } else if (uiState.isLoading && pagedFiles.itemCount == 0) {
                        item(key = "list_loading") {
                            LoadingState()
                        }
                    } else if (pagedFiles.itemCount == 0) {
                        item(key = "list_empty") {
                            FilesEmptyPlaceholder(uiState = uiState, fixedCategory = fixedCategory)
                        }
                    } else {
                        items(
                            count = pagedFiles.itemCount,
                            key = { idx -> pagedFiles[idx]?.uriString ?: "list_$idx" }
                        ) { index ->
                            val item = pagedFiles[index]
                            if (item != null) {
                                FileManagerListRow(
                                    file = item,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = item.uriString in uiState.selectedUris,
                                    onClick = { onItemClick(item) },
                                    onLongClick = { onItemLongClick(item) },
                                    onToggleSelect = { onToggleSelect(item) },
                                    onPreview = { onPreview(item) },
                                    onDetails = { onDetails(item) },
                                    onRename = { onRename(item) },
                                    onCopy = { onCopy(item) },
                                    onMove = { onMove(item) },
                                    onShare = { onShare(item) },
                                    onSend = { onSend(item) },
                                    onDelete = { onDelete(item) },
                                    modifier = Modifier.testTag("files_list_item_${item.displayName}")
                                )
                            }
                        }
                    }

                    item(key = "files_bottom_spacer") {
                        Spacer(modifier = Modifier.height(spacing.lg))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewHeaderListItems(
    uiState: FileManagerUiState,
    onSelectFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onScanMedia: () -> Unit,
    onOpenCategory: (FilesHubCategory) -> Unit,
    onOpenGrantedDirectory: (GrantedDirectory) -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit
) {
    val categoryRows = FilesHubCategory.entries.chunked(2)

    item(key = "overview_storage") {
        val summary = uiState.storageSummary
        StorageIndicator(
            usedBytes = summary?.usedBytes ?: 0L,
            totalBytes = summary?.totalBytes ?: 0L,
            title = stringResource(R.string.home_section_storage),
            modifier = Modifier
                .clip(ShareFlowDesign.shapes.card)
                .clickable(onClick = onNavigateToStorageAnalyzer)
                .testTag("files_storage_indicator")
        )
    }

    item(key = "overview_actions") {
        OverviewQuickActionsRow(
            onSelectFolder = onSelectFolder,
            onScanMedia = onScanMedia,
            onPickMedia = onPickMedia,
            onPickFiles = onPickFiles
        )
    }

    item(key = "overview_directories") {
        OverviewDirectoriesSection(
            grantedDirectories = uiState.grantedDirectories,
            onOpenGrantedDirectory = onOpenGrantedDirectory
        )
    }

    item(key = "overview_categories_header") {
        SectionHeader(title = stringResource(R.string.files_section_categories))
    }

    categoryRows.forEachIndexed { rowIdx, pair ->
        item(key = "overview_category_row_$rowIdx") {
            CategoryPairRow(
                pair = pair,
                summaries = uiState.categorySummaries,
                onOpenCategory = onOpenCategory
            )
        }
    }

    item(key = "overview_accessible_header") {
        SectionHeader(title = stringResource(R.string.files_section_accessible))
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.overviewHeaderGridItems(
    uiState: FileManagerUiState,
    onSelectFolder: () -> Unit,
    onPickFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onScanMedia: () -> Unit,
    onOpenCategory: (FilesHubCategory) -> Unit,
    onOpenGrantedDirectory: (GrantedDirectory) -> Unit,
    onNavigateToStorageAnalyzer: () -> Unit
) {
    val categoryRows = FilesHubCategory.entries.chunked(2)

    item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_storage") {
        val summary = uiState.storageSummary
        StorageIndicator(
            usedBytes = summary?.usedBytes ?: 0L,
            totalBytes = summary?.totalBytes ?: 0L,
            title = stringResource(R.string.home_section_storage),
            modifier = Modifier
                .clip(ShareFlowDesign.shapes.card)
                .clickable(onClick = onNavigateToStorageAnalyzer)
                .testTag("files_storage_indicator")
        )
    }

    item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_actions") {
        OverviewQuickActionsRow(
            onSelectFolder = onSelectFolder,
            onScanMedia = onScanMedia,
            onPickMedia = onPickMedia,
            onPickFiles = onPickFiles
        )
    }

    item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_directories") {
        OverviewDirectoriesSection(
            grantedDirectories = uiState.grantedDirectories,
            onOpenGrantedDirectory = onOpenGrantedDirectory
        )
    }

    item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_categories_header") {
        SectionHeader(title = stringResource(R.string.files_section_categories))
    }

    categoryRows.forEachIndexed { rowIdx, pair ->
        item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_category_row_$rowIdx") {
            CategoryPairRow(
                pair = pair,
                summaries = uiState.categorySummaries,
                onOpenCategory = onOpenCategory
            )
        }
    }

    item(span = { GridItemSpan(maxLineSpan) }, key = "grid_overview_accessible_header") {
        SectionHeader(title = stringResource(R.string.files_section_accessible))
    }
}

@Composable
private fun OverviewQuickActionsRow(
    onSelectFolder: () -> Unit,
    onScanMedia: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.fillMaxWidth()
    ) {
        item(key = "act_folder") {
            AssistChip(
                onClick = onSelectFolder,
                label = { Text(stringResource(R.string.files_browse_folder_action)) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("files_select_folder_button")
            )
        }
        item(key = "act_scan") {
            AssistChip(
                onClick = onScanMedia,
                label = { Text(stringResource(R.string.files_scan_media_action)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("files_scan_media_button")
            )
        }
        item(key = "act_media") {
            AssistChip(
                onClick = onPickMedia,
                label = { Text(stringResource(R.string.files_pick_media_action)) },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("files_pick_media_button")
            )
        }
        item(key = "act_saf") {
            AssistChip(
                onClick = onPickFiles,
                label = { Text(stringResource(R.string.files_pick_saf_action)) },
                leadingIcon = { Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("files_pick_saf_button")
            )
        }
    }
}

@Composable
private fun OverviewDirectoriesSection(
    grantedDirectories: List<GrantedDirectory>,
    onOpenGrantedDirectory: (GrantedDirectory) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        SectionHeader(title = stringResource(R.string.files_section_directories))
        if (grantedDirectories.isEmpty()) {
            Text(
                text = stringResource(R.string.files_directories_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("files_directories_empty_hint")
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(grantedDirectories, key = { it.treeUriString }) { dir ->
                    Surface(
                        onClick = { onOpenGrantedDirectory(dir) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("files_granted_dir_${dir.displayName}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = dir.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!dir.isAccessible) {
                                StatusPill(
                                    label = stringResource(R.string.files_inaccessible_badge),
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            } else if (!dir.canWrite) {
                                StatusPill(
                                    label = stringResource(R.string.files_readonly_badge),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPairRow(
    pair: List<FilesHubCategory>,
    summaries: Map<FileCategory, CategoryCountSummary>,
    onOpenCategory: (FilesHubCategory) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        pair.forEach { cat ->
            val summaryData = summaries[cat.domainCategory] ?: CategoryCountSummary()
            CategoryCard(
                title = stringResource(cat.titleRes),
                icon = cat.icon,
                itemCount = summaryData.count,
                totalBytes = summaryData.totalBytes,
                accentContainerColor = MaterialTheme.colorScheme.primaryContainer,
                accentContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { onOpenCategory(cat) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("files_category_${cat.name.lowercase()}")
            )
        }
        if (pair.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoryToolbarActions(
    onPickFiles: () -> Unit,
    onPickMedia: () -> Unit,
    onScanMedia: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.fillMaxWidth()
    ) {
        item(key = "cat_pick_saf") {
            AssistChip(
                onClick = onPickFiles,
                label = { Text(stringResource(R.string.files_pick_saf_action)) },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("files_category_pick_button")
            )
        }
        item(key = "cat_pick_media") {
            AssistChip(
                onClick = onPickMedia,
                label = { Text(stringResource(R.string.files_pick_media_action)) },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
        item(key = "cat_scan") {
            AssistChip(
                onClick = onScanMedia,
                label = { Text(stringResource(R.string.files_scan_media_action)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

@Composable
private fun RevokedPermissionCard(onRegrant: () -> Unit) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("files_permission_revoked_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.files_permission_revoked_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = stringResource(R.string.files_permission_revoked_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SecondaryActionButton(
                text = stringResource(R.string.files_action_regrant),
                onClick = onRegrant,
                leadingIcon = Icons.Filled.FolderOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("files_regrant_button")
            )
        }
    }
}

@Composable
private fun FilesEmptyPlaceholder(
    uiState: FileManagerUiState,
    fixedCategory: FilesHubCategory?
) {
    if (uiState.browseMode == FileBrowseMode.DIRECTORY) {
        EmptyState(
            icon = Icons.Filled.FolderOpen,
            title = stringResource(R.string.files_directory_empty_title),
            body = stringResource(R.string.files_directory_empty_body),
            modifier = Modifier.testTag("files_directory_empty")
        )
    } else {
        val label = fixedCategory?.let { stringResource(it.titleRes) }
            ?: stringResource(R.string.files_title)
        EmptyState(
            icon = fixedCategory?.icon ?: Icons.Filled.FolderOpen,
            title = stringResource(R.string.files_category_empty_title, label),
            body = stringResource(R.string.files_category_empty_body),
            modifier = Modifier.testTag("files_category_empty")
        )
    }
}
