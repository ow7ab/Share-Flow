package com.example.shareflow.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.R
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.rememberThumbnailImageRequest
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PermissionExplanationDialog
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.components.SFSnackbar
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.navigation.ShareFlowRoutes
import com.example.shareflow.ui.files.FileDetailsSheet
import com.example.shareflow.ui.files.FileSharesheetHelper
import com.example.shareflow.ui.files.FullBleedMediaCard
import com.example.shareflow.ui.files.isVideoMedia
import com.example.shareflow.ui.viewer.CustomFileViewerDialog

@Composable
fun MediaScreen(
    onNavigateToRoute: (String) -> Unit,
    onNavigateToSend: () -> Unit = { onNavigateToRoute(ShareFlowRoutes.SEND) },
    onOpenVault: () -> Unit = { onNavigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
    topBarClearance: Dp = 0.dp,
    bottomBarClearance: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val factory = remember(container) {
        shareFlowViewModelFactory {
            MediaStudioViewModel(
                fileManagerRepository = container.fileManagerRepository,
                sessionController = container.localSessionController
            )
        }
    }
    val viewModel: MediaStudioViewModel = viewModel(
        key = "media_studio_main",
        factory = factory
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
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

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val inspected = SafDocumentInspector.inspectUris(context, uris, FileSource.PHOTO_PICKER)
            viewModel.onMediaPicked(inspected)
        }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    if (showFolderPermissionInfo) {
        PermissionExplanationDialog(
            title = stringResource(R.string.files_persistent_access_title),
            rationale = stringResource(R.string.files_persistent_access_body),
            onGrantClick = {
                showFolderPermissionInfo = false
                safTreePicker.launch(null)
            },
            onDismiss = { showFolderPermissionInfo = false }
        )
    }

    uiState.detailsTarget?.let { detailsFile ->
        FileDetailsSheet(
            file = detailsFile,
            sha256 = null,
            isComputingSha256 = false,
            onComputeSha256 = {},
            onDismiss = viewModel::closeDetails
        )
    }

    uiState.previewTarget?.let { previewFile ->
        CustomFileViewerDialog(
            file = previewFile,
            onSendWithShareFlow = {
                viewModel.closePreview()
                viewModel.queueSingleForSend(previewFile)
                onNavigateToSend()
            },
            onShareExternal = {
                val ok = FileSharesheetHelper.shareFiles(context, listOf(previewFile), shareChooserTitle)
                if (!ok) viewModel.showFeedback(shareErrorText)
            },
            onOpenExternal = { FileSharesheetHelper.openExternally(context, previewFile) },
            onMoveToVaultSuccess = { message -> viewModel.showFeedback(message) },
            onOpenVaultSetup = {
                viewModel.closePreview()
                onOpenVault()
            },
            onDismiss = viewModel::closePreview
        )
    }

    MediaStudioContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onNavigateToRoute = onNavigateToRoute,
        onSelectTab = viewModel::selectTab,
        onOpenAlbum = viewModel::openAlbum,
        onCloseAlbum = viewModel::closeSelectedAlbum,
        onGrantFolderAccess = { showFolderPermissionInfo = true },
        onImportMedia = {
            photoPicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
            )
        },
        onPreview = viewModel::openPreview,
        onDetails = viewModel::openDetails,
        onShare = { entry ->
            val ok = FileSharesheetHelper.shareFiles(context, listOf(entry), shareChooserTitle)
            if (!ok) viewModel.showFeedback(shareErrorText)
        },
        onSend = { entry ->
            viewModel.queueSingleForSend(entry)
            onNavigateToSend()
        },
        onDelete = viewModel::deleteEntry,
        topBarClearance = topBarClearance,
        bottomBarClearance = bottomBarClearance,
        modifier = modifier
    )
}

@Composable
internal fun MediaStudioContent(
    uiState: MediaStudioUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateToRoute: (String) -> Unit = {},
    onSelectTab: (MediaStudioTab) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    onCloseAlbum: () -> Unit = {},
    onGrantFolderAccess: () -> Unit = {},
    onImportMedia: () -> Unit = {},
    onPreview: (LocalFileEntry) -> Unit = {},
    onDetails: (LocalFileEntry) -> Unit = {},
    onShare: (LocalFileEntry) -> Unit = {},
    onSend: (LocalFileEntry) -> Unit = {},
    onDelete: (LocalFileEntry) -> Unit = {},
    topBarClearance: Dp = 0.dp,
    bottomBarClearance: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomBarClearance),
                snackbar = { data -> SFSnackbar(snackbarData = data) }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = spacing.maxContentWidth)
                    .testTag("media_screen_list"),
                contentPadding = PaddingValues(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    top = topBarClearance + spacing.md,
                    bottom = spacing.md + bottomBarClearance
                ),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                // 1. Header
                item(span = { GridItemSpan(maxLineSpan) }, key = "media_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SectionHeader(
                            title = stringResource(R.string.media_title),
                            subtitle = stringResource(R.string.media_subtitle),
                            modifier = Modifier.weight(1f)
                        )
                        if (uiState.allMedia.isNotEmpty()) {
                            StatusPill(
                                label = stringResource(
                                    R.string.files_auto_indexed_count_format,
                                    uiState.allMedia.size
                                ),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // 2. Dedicated Studio Players Row (preserves full Image Gallery, Video Player, Music Player)
                item(span = { GridItemSpan(maxLineSpan) }, key = "media_quick_players") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        MediaPlayerQuickTile(
                            title = stringResource(R.string.media_photos_title),
                            icon = Icons.Outlined.PhotoLibrary,
                            onClick = { onNavigateToRoute(ShareFlowRoutes.IMAGE_GALLERY) },
                            testTag = "media_card_photos",
                            modifier = Modifier.weight(1f)
                        )
                        MediaPlayerQuickTile(
                            title = stringResource(R.string.media_videos_title),
                            icon = Icons.Outlined.PlayCircle,
                            onClick = { onNavigateToRoute(ShareFlowRoutes.VIDEO_PLAYER) },
                            testTag = "media_card_videos",
                            modifier = Modifier.weight(1f)
                        )
                        MediaPlayerQuickTile(
                            title = stringResource(R.string.media_music_title),
                            icon = Icons.Outlined.LibraryMusic,
                            onClick = { onNavigateToRoute(ShareFlowRoutes.MUSIC_PLAYER) },
                            testTag = "media_card_music",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3. Organized Media Studio Category Tabs: All Media, Images, Videos, Albums
                item(span = { GridItemSpan(maxLineSpan) }, key = "media_studio_tabs") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("media_studio_tab_bar")
                    ) {
                        item(key = "tab_all") {
                            FilterChip(
                                selected = uiState.activeTab == MediaStudioTab.ALL_MEDIA,
                                onClick = { onSelectTab(MediaStudioTab.ALL_MEDIA) },
                                label = { Text(stringResource(R.string.media_studio_tab_all)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Collections,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("media_studio_tab_all")
                            )
                        }
                        item(key = "tab_images") {
                            FilterChip(
                                selected = uiState.activeTab == MediaStudioTab.IMAGES,
                                onClick = { onSelectTab(MediaStudioTab.IMAGES) },
                                label = { Text(stringResource(R.string.media_studio_tab_images)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Image,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("media_studio_tab_images")
                            )
                        }
                        item(key = "tab_videos") {
                            FilterChip(
                                selected = uiState.activeTab == MediaStudioTab.VIDEOS,
                                onClick = { onSelectTab(MediaStudioTab.VIDEOS) },
                                label = { Text(stringResource(R.string.media_studio_tab_videos)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.VideoLibrary,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("media_studio_tab_videos")
                            )
                        }
                        item(key = "tab_albums") {
                            FilterChip(
                                selected = uiState.activeTab == MediaStudioTab.ALBUMS,
                                onClick = { onSelectTab(MediaStudioTab.ALBUMS) },
                                label = { Text(stringResource(R.string.media_studio_tab_albums)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoAlbum,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("media_studio_tab_albums")
                            )
                        }
                    }
                }

                // 4. Persistent Folder Access Banner (shown once until user links a folder)
                if (uiState.grantedDirectories.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "media_persistent_access_card") {
                        SFGlassCard(
                            contentPadding = PaddingValues(spacing.md),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("media_persistent_access_card")
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FolderOpen,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.files_persistent_access_title),
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.files_persistent_access_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    PrimaryActionButton(
                                        text = stringResource(R.string.media_studio_grant_folder),
                                        onClick = onGrantFolderAccess,
                                        leadingIcon = Icons.Outlined.FolderOpen,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("media_grant_folder_button")
                                    )
                                    SecondaryActionButton(
                                        text = stringResource(R.string.media_studio_import_media),
                                        onClick = onImportMedia,
                                        outlined = true,
                                        leadingIcon = Icons.Outlined.PhotoLibrary,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("media_import_button")
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Active Album Back Header (when inside a selected album)
                if (uiState.activeTab == MediaStudioTab.ALBUMS && uiState.activeAlbum != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "media_album_back_row") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            AssistChip(
                                onClick = onCloseAlbum,
                                label = { Text(stringResource(R.string.media_studio_back_to_albums)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("media_album_back_chip")
                            )
                            Text(
                                text = uiState.activeAlbum?.name.orEmpty(),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 6. Media Grid Content
                when {
                    uiState.isLoading && uiState.allMedia.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "media_loading") {
                            LoadingState()
                        }
                    }

                    uiState.activeTab == MediaStudioTab.ALBUMS && uiState.activeAlbum == null -> {
                        if (uiState.albums.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "media_albums_empty") {
                                EmptyState(
                                    icon = Icons.Outlined.PhotoAlbum,
                                    title = stringResource(R.string.media_studio_empty_title),
                                    body = stringResource(R.string.media_studio_empty_albums),
                                    actionLabel = stringResource(R.string.media_studio_import_media),
                                    onAction = onImportMedia,
                                    modifier = Modifier.testTag("media_studio_empty_state")
                                )
                            }
                        } else {
                            items(
                                items = uiState.albums,
                                key = { "album_${it.name}" }
                            ) { album ->
                                MediaAlbumTile(
                                    album = album,
                                    onClick = { onOpenAlbum(album.name) },
                                    modifier = Modifier.testTag("media_album_tile_${album.name}")
                                )
                            }
                        }
                    }

                    uiState.displayedItems.isEmpty() -> {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "media_items_empty") {
                            val emptyBody = when (uiState.activeTab) {
                                MediaStudioTab.IMAGES -> stringResource(R.string.media_studio_empty_images)
                                MediaStudioTab.VIDEOS -> stringResource(R.string.media_studio_empty_videos)
                                else -> stringResource(R.string.media_studio_empty_body)
                            }
                            EmptyState(
                                icon = Icons.Outlined.PhotoLibrary,
                                title = stringResource(R.string.media_studio_empty_title),
                                body = emptyBody,
                                actionLabel = stringResource(R.string.media_studio_import_media),
                                onAction = onImportMedia,
                                modifier = Modifier.testTag("media_studio_empty_state")
                            )
                        }
                    }

                    else -> {
                        items(
                            items = uiState.displayedItems,
                            key = { "media_${it.uriString}" }
                        ) { entry ->
                            FullBleedMediaCard(
                                file = entry,
                                isSelectionMode = false,
                                isSelected = false,
                                onClick = { onPreview(entry) },
                                onLongClick = { onDetails(entry) },
                                onToggleSelect = { onSend(entry) },
                                onPreview = { onPreview(entry) },
                                onDetails = { onDetails(entry) },
                                onRename = {},
                                onCopy = {},
                                onMove = {},
                                onShare = { onShare(entry) },
                                onSend = { onSend(entry) },
                                onDelete = { onDelete(entry) },
                                modifier = Modifier.testTag("media_studio_item_${entry.displayName}")
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }, key = "media_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun MediaPlayerQuickTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    SFGlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        modifier = modifier.testTag(testTag)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MediaAlbumTile(
    album: MediaStudioAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val targetSizePx = remember(density) {
        with(density) { 180.dp.toPx().toInt().coerceIn(220, 420) }
    }
    val coverRequest = rememberThumbnailImageRequest(
        uriString = album.coverItem.uriString,
        targetSizePx = targetSizePx,
        isVideo = album.coverItem.isVideoMedia()
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .clip(ShareFlowDesign.shapes.card)
            .clickable(role = Role.Button, onClick = onClick),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = coverRequest,
                contentDescription = album.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.78f)
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.media_studio_album_items_format, album.itemCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
