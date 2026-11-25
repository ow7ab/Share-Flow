package com.example.shareflow.ui.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureTabIndicator
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.network.transfer.TransferQueue
import com.example.shareflow.ui.files.FileSharesheetHelper
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import com.example.shareflow.ui.viewer.toViewerFileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    onNavigateBack: () -> Unit,
    onContinueToConfirm: () -> Unit,
    onOpenAppSharing: (() -> Unit)? = null,
    onOpenVault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel: SendViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            SendViewModel(
                sessionController = container.localSessionController,
                trustedDeviceRepository = container.trustedDeviceRepository,
                settingsRepository = container.settingsRepository,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository,
                transferManager = container.transferManager,
                mediaCenterRepository = container.mediaCenterRepository,
                appSharingRepository = container.appSharingRepository,
                fileManagerRepository = container.fileManagerRepository,
                unifiedConnectionManager = container.unifiedConnectionManager
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var previewPhoto by remember { mutableStateOf<MediaImageItem?>(null) }
    var previewVideo by remember { mutableStateOf<MediaVideoItem?>(null) }
    var previewFileEntry by remember { mutableStateOf<LocalFileEntry?>(null) }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    // Zero-permission Android Photo Picker for original-quality Photos
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onPhotoPickerResult(uris.map { it.toString() })
        }
    }

    // Zero-permission Android Photo Picker for full-resolution Videos
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onVideoPickerResult(uris.map { it.toString() })
        }
    }

    // Zero-permission SAF Pickers for Documents, Audio, Archives, APKs, Folders
    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val inspected = SafDocumentInspector.inspectUris(context, uris)
            viewModel.addSelectedFiles(inspected)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            scope.launch {
                val expanded = withContext(Dispatchers.IO) {
                    TransferQueue.expandSafDirectoryTree(context, treeUri)
                }
                viewModel.addSelectedDirectorySources(expanded)
            }
        }
    }

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val shareChooserTitle = stringResource(R.string.files_share_chooser_title)

    previewPhoto?.let { photo ->
        val entry = remember(photo) { photo.toViewerFileEntry() }
        val isSelected = photo.uriString in uiState.selectedUriSet
        CustomFileViewerDialog(
            file = entry,
            primaryShareLabel = stringResource(
                if (isSelected) R.string.viewer_action_remove_send else R.string.viewer_action_select_send
            ),
            onSendWithShareFlow = {
                viewModel.togglePhotoSelection(photo)
                previewPhoto = null
            },
            onShareExternal = { FileSharesheetHelper.shareFiles(context, listOf(entry), shareChooserTitle) },
            onOpenExternal = { FileSharesheetHelper.openExternally(context, entry) },
            onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenVaultSetup = {
                previewPhoto = null
                onOpenVault()
            },
            onDismiss = { previewPhoto = null }
        )
    }

    previewVideo?.let { video ->
        val entry = remember(video) { video.toViewerFileEntry() }
        val isSelected = video.uriString in uiState.selectedUriSet
        CustomFileViewerDialog(
            file = entry,
            primaryShareLabel = stringResource(
                if (isSelected) R.string.viewer_action_remove_send else R.string.viewer_action_select_send
            ),
            onSendWithShareFlow = {
                viewModel.toggleVideoSelection(video)
                previewVideo = null
            },
            onShareExternal = { FileSharesheetHelper.shareFiles(context, listOf(entry), shareChooserTitle) },
            onOpenExternal = { FileSharesheetHelper.openExternally(context, entry) },
            onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenVaultSetup = {
                previewVideo = null
                onOpenVault()
            },
            onDismiss = { previewVideo = null }
        )
    }

    previewFileEntry?.let { entry ->
        val isSelected = entry.uriString in uiState.selectedUriSet
        CustomFileViewerDialog(
            file = entry,
            primaryShareLabel = stringResource(
                if (isSelected) R.string.viewer_action_remove_send else R.string.viewer_action_select_send
            ),
            onSendWithShareFlow = {
                viewModel.toggleFileSelection(entry)
                previewFileEntry = null
            },
            onShareExternal = { FileSharesheetHelper.shareFiles(context, listOf(entry), shareChooserTitle) },
            onOpenExternal = { FileSharesheetHelper.openExternally(context, entry) },
            onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenVaultSetup = {
                previewFileEntry = null
                onOpenVault()
            },
            onDismiss = { previewFileEntry = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.send_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState.selectedCount > 0) {
                        TextButton(
                            onClick = viewModel::clearSelection,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("send_clear_selection_button")
                        ) {
                            Text(text = stringResource(R.string.send_clear_selection))
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Surface(
                tonalElevation = ShareFlowDesign.elevation.raised,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    if (uiState.isExtractingApps) {
                        ShareFlowSignatureProgressBar(
                            modifier = Modifier.fillMaxWidth(),
                            height = 5.dp
                        )
                        Text(
                            text = stringResource(R.string.send_apps_extracting_status),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = spacing.screenHorizontal, vertical = 4.dp)
                        )
                    }
                    // Selected items compact removable chips preview
                    if (uiState.allSelectedFiles.isNotEmpty() || uiState.selectedAppPackages.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            modifier = Modifier.testTag("send_selected_chips_strip")
                        ) {
                            items(
                                items = uiState.installedApps.filter { it.packageName in uiState.selectedAppPackages },
                                key = { "chip_app_${it.packageName}" }
                            ) { app ->
                                SelectedPreviewPill(
                                    label = app.appName,
                                    onRemove = { viewModel.toggleAppSelection(app) }
                                )
                            }
                            items(
                                items = uiState.allSelectedFiles.take(12),
                                key = { "chip_file_${it.uriString}" }
                            ) { file ->
                                SelectedPreviewPill(
                                    label = file.displayName.substringAfterLast('/'),
                                    onRemove = { viewModel.removeSelectedFile(file.uriString) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "${stringResource(R.string.send_selected_count_label)}: ${
                                    ShareFlowFormatters.formatCount(uiState.selectedCount, locale)
                                }",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("send_selected_counter")
                            )
                            Text(
                                text = "${stringResource(R.string.send_total_size_label)}: ${
                                    ShareFlowFormatters.formatBytes(uiState.totalSelectedBytes, locale)
                                }",
                                style = ShareFlowMonoStyle,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("send_total_size")
                            )
                        }
                        PrimaryActionButton(
                            text = stringResource(R.string.send_continue_action),
                            onClick = {
                                viewModel.prepareSelectionAndContinue(onReady = onContinueToConfirm)
                            },
                            enabled = uiState.selectedCount > 0 && !uiState.isExtractingApps,
                            leadingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                            modifier = Modifier.testTag("send_continue_button")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Unified 4-Category SHAREit Selector Tabs: Photos | Videos | Apps | Files
            val tabs = listOf(
                Triple(SendContentTab.PHOTOS, R.string.send_tab_photos, Icons.Filled.Image),
                Triple(SendContentTab.VIDEOS, R.string.send_tab_videos, Icons.Filled.VideoLibrary),
                Triple(SendContentTab.APPS, R.string.send_tab_apps, Icons.Filled.Apps),
                Triple(SendContentTab.FILES, R.string.send_tab_files, Icons.Filled.Folder)
            )
            val selectedTabIndex = tabs.indexOfFirst { it.first == uiState.activeTab }.coerceAtLeast(0)
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = {
                    ShareFlowSignatureTabIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = false)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = spacing.maxContentWidth)
                    .testTag("send_category_selector")
            ) {
                tabs.forEach { (tab, labelRes, icon) ->
                    Tab(
                        selected = uiState.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                text = stringResource(labelRes),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = stringResource(labelRes),
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.testTag("send_tab_${tab.name.lowercase()}")
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .testTag("send_screen_list"),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    when (uiState.activeTab) {
                        SendContentTab.PHOTOS -> {
                            sendPhotosTabItems(
                                uiState = uiState,
                                onPickPhotos = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onSelectFilter = viewModel::selectPhotoFilter,
                                onToggleSelectAll = viewModel::toggleSelectAllVisiblePhotos,
                                onTogglePhoto = viewModel::togglePhotoSelection,
                                onPreviewPhoto = { previewPhoto = it }
                            )
                        }

                        SendContentTab.VIDEOS -> {
                            sendVideosTabItems(
                                uiState = uiState,
                                onPickVideos = {
                                    videoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                onToggleSelectAll = viewModel::toggleSelectAllVideos,
                                onToggleVideo = viewModel::toggleVideoSelection,
                                onPreviewVideo = { previewVideo = it }
                            )
                        }

                        SendContentTab.APPS -> {
                            sendAppsTabItems(
                                uiState = uiState,
                                appSharingRepository = container.appSharingRepository,
                                onSearchChange = viewModel::updateAppSearchQuery,
                                onSortChange = viewModel::setAppSortOption,
                                onToggleIncludeSystem = viewModel::setIncludeSystemApps,
                                onToggleSelectAll = viewModel::toggleSelectAllVisibleApps,
                                onToggleApp = viewModel::toggleAppSelection
                            )
                        }

                        SendContentTab.FILES -> {
                            sendFilesTabItems(
                                uiState = uiState,
                                onSelectCategory = viewModel::selectCategory,
                                onPickFiles = {
                                    safPickerLauncher.launch(
                                        SafDocumentInspector.mimeTypesForCategory(uiState.activeCategory)
                                    )
                                },
                                onPickFolder = { folderPickerLauncher.launch(null) },
                                onToggleSelectAll = viewModel::toggleSelectAllVisibleFiles,
                                onToggleFile = viewModel::toggleFileSelection,
                                onPreviewFile = { previewFileEntry = it },
                                onRemoveSelectedFile = viewModel::removeSelectedFile
                            )
                        }
                    }

                    item(key = "send_bottom_spacer") {
                        Spacer(modifier = Modifier.height(spacing.md))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedPreviewPill(
    label: String,
    onRemove: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.send_clear_selection),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
