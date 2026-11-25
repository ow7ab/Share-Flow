package com.example.shareflow.ui.media

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import com.example.shareflow.ui.viewer.toViewerFileEntry
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageGalleryScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    modifier: Modifier = Modifier,
    providedViewModel: ImageGalleryViewModel? = null
) {
    val container = LocalAppContainer.current
    val viewModel: ImageGalleryViewModel = providedViewModel
        ?: androidx.lifecycle.viewmodel.compose.viewModel(
            factory = shareFlowViewModelFactory {
                ImageGalleryViewModel(container.mediaCenterRepository)
            }
        )
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    var vaultInspectImage by remember { mutableStateOf<MediaImageItem?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addPickedUris(uris.map { it.toString() })
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    fun shareImage(image: MediaImageItem) {
        val shareIntent = container.mediaCenterRepository.createShareImageIntent(image)
        if (shareIntent != null) {
            runCatching {
                context.startActivity(
                    Intent.createChooser(shareIntent, context.getString(R.string.media_gallery_share))
                )
            }
        }
    }

    vaultInspectImage?.let { img ->
        val entry = remember(img) { img.toViewerFileEntry() }
        CustomFileViewerDialog(
            file = entry,
            onSendWithShareFlow = {
                vaultInspectImage = null
                viewModel.closeViewer()
                container.localSessionController.addSelectedFiles(listOf(entry))
                onNavigateToSend()
            },
            onShareExternal = { shareImage(img) },
            onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
            onOpenVaultSetup = {
                vaultInspectImage = null
                viewModel.closeViewer()
                onOpenVault()
            },
            onDismiss = { vaultInspectImage = null }
        )
    }

    val viewerIdx = uiState.viewerIndex
    if (viewerIdx != null && uiState.visibleImages.isNotEmpty()) {
        BackHandler { viewModel.closeViewer() }
        FullScreenImageViewer(
            images = uiState.visibleImages,
            initialIndex = viewerIdx,
            showMetadata = uiState.showMetadataSheet,
            onPageChanged = viewModel::updateViewerPage,
            onClose = viewModel::closeViewer,
            onToggleMetadata = viewModel::toggleMetadataSheet,
            onShare = ::shareImage,
            onSendWithShareFlow = { image ->
                viewModel.closeViewer()
                container.localSessionController.addSelectedFiles(listOf(image.toViewerFileEntry()))
                onNavigateToSend()
            },
            onMoveToVault = { image -> vaultInspectImage = image },
            onRequestDelete = viewModel::requestDelete
        )
    } else {
        Scaffold(
            modifier = modifier
                .fillMaxSize()
                .testTag("image_gallery_screen"),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(hostState = snackbarHostState) { data ->
                    com.example.shareflow.core.designsystem.components.SFSnackbar(snackbarData = data)
                }
            },
            topBar = {
                ShareFlowTopBar(
                    title = stringResource(R.string.media_gallery_title),
                    onNavigateBack = onNavigateBack,
                    actions = {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("gallery_add_photos_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.media_gallery_pick_photos)
                            )
                        }
                        IconButton(
                            onClick = viewModel::refresh,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("gallery_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_refresh)
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (uiState.albums.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gallery_album_chips"),
                        contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        item(key = "album_all") {
                            FilterChip(
                                selected = uiState.selectedAlbum == null,
                                onClick = { viewModel.selectAlbum(null) },
                                label = {
                                    Text(
                                        text = stringResource(
                                            R.string.media_gallery_all_photos,
                                            uiState.allImages.size
                                        )
                                    )
                                },
                                shape = ShareFlowDesign.shapes.chip,
                                colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                                border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = uiState.selectedAlbum == null),
                                modifier = Modifier.testTag("gallery_album_chip_all")
                            )
                        }
                        items(uiState.albums, key = { it.first }) { (albumName, count) ->
                            val albumSelected = uiState.selectedAlbum == albumName
                            FilterChip(
                                selected = albumSelected,
                                onClick = { viewModel.selectAlbum(albumName) },
                                label = { Text(text = "$albumName ($count)") },
                                shape = ShareFlowDesign.shapes.chip,
                                colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                                border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = albumSelected),
                                modifier = Modifier.testTag("gallery_album_chip_${albumName.lowercase().replace(' ', '_')}")
                            )
                        }
                    }
                }

                when {
                    uiState.isLoading -> {
                        LoadingState(
                            message = stringResource(R.string.media_gallery_loading),
                            modifier = Modifier.testTag("gallery_loading")
                        )
                    }
                    uiState.errorMessage != null -> {
                        Box(modifier = Modifier.padding(spacing.screenHorizontal)) {
                            ErrorState(
                                message = uiState.errorMessage.orEmpty(),
                                onRetry = viewModel::refresh,
                                modifier = Modifier.testTag("gallery_error")
                            )
                        }
                    }
                    uiState.visibleImages.isEmpty() -> {
                        Box(modifier = Modifier.padding(spacing.screenHorizontal)) {
                            EmptyState(
                                icon = Icons.Filled.PhotoLibrary,
                                title = stringResource(R.string.media_gallery_empty_title),
                                body = stringResource(R.string.media_gallery_empty_body),
                                actionLabel = stringResource(R.string.media_gallery_pick_photos),
                                onAction = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.testTag("gallery_empty_state")
                            )
                        }
                    }
                    else -> {
                        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 112.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("gallery_image_grid"),
                            contentPadding = PaddingValues(
                                start = spacing.sm,
                                top = spacing.sm,
                                end = spacing.sm,
                                bottom = spacing.md + navBarBottom
                            ),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            itemsIndexed(
                                items = uiState.visibleImages,
                                key = { _, img -> img.uriString }
                            ) { index, image ->
                                GalleryGridTile(
                                    image = image,
                                    index = index,
                                    formattedSize = ShareFlowFormatters.formatBytes(image.sizeBytes, locale),
                                    onClick = { viewModel.openViewer(index) },
                                    onDetails = {
                                        viewModel.openViewer(index)
                                        viewModel.toggleMetadataSheet(true)
                                    },
                                    onShare = { shareImage(image) },
                                    onSend = {
                                        container.localSessionController.addSelectedFiles(
                                            listOf(image.toViewerFileEntry())
                                        )
                                        onNavigateToSend()
                                    },
                                    onMoveToVault = { vaultInspectImage = image },
                                    onDelete = { viewModel.requestDelete(image) },
                                    modifier = Modifier.testTag("gallery_grid_item_$index")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.pendingDeleteImage?.let { pending ->
        ConfirmationDialog(
            title = stringResource(R.string.media_gallery_delete_title),
            body = stringResource(R.string.media_gallery_delete_body, pending.displayName),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDelete,
            isDestructive = true,
            confirmTestTag = "gallery_confirm_delete_button",
            dismissTestTag = "gallery_cancel_delete_button"
        )
    }

    if (uiState.showMetadataSheet) {
        uiState.currentViewerImage?.let { img ->
            ImageMetadataDialog(
                image = img,
                formattedSize = ShareFlowFormatters.formatBytes(img.sizeBytes, locale),
                onDismiss = { viewModel.toggleMetadataSheet(false) }
            )
        }
    }
}

@Composable
private fun GalleryGridTile(
    image: MediaImageItem,
    index: Int,
    formattedSize: String,
    onClick: () -> Unit,
    onDetails: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onMoveToVault: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val thumbnailRequest = com.example.shareflow.core.common.rememberThumbnailImageRequest(
        uriString = image.uriString,
        targetSizePx = 280,
        isVideo = false
    )
    val cardShape = ShareFlowDesign.shapes.card
    Surface(
        modifier = modifier
            .aspectRatio(0.92f)
            .clip(cardShape)
            .clickable(onClick = onClick),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 1. Full-bleed edge-to-edge thumbnail
            AsyncImage(
                model = thumbnailRequest,
                contentDescription = stringResource(R.string.files_category_images),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // 2. Bottom gradient scrim displaying ONLY file size (no file name)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.66f)
                            )
                        )
                    )
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(
                    text = formattedSize,
                    style = ShareFlowMonoStyle.copy(
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .testTag("gallery_tile_size_$index")
                )
            }

            // 3. Top-end action icon
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_tile_action_$index")
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.44f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.files_cd_more_options),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_preview)) },
                        leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                        onClick = { menuExpanded = false; onClick() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_details)) },
                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        onClick = { menuExpanded = false; onDetails() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_send)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                        onClick = { menuExpanded = false; onSend() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_action_move_to_vault)) },
                        leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        onClick = { menuExpanded = false; onMoveToVault() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FullScreenImageViewer(
    images: List<MediaImageItem>,
    initialIndex: Int,
    showMetadata: Boolean,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    onToggleMetadata: (Boolean) -> Unit,
    onShare: (MediaImageItem) -> Unit,
    onSendWithShareFlow: (MediaImageItem) -> Unit,
    onMoveToVault: (MediaImageItem) -> Unit,
    onRequestDelete: (MediaImageItem) -> Unit
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
        pageCount = { images.size }
    )

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
    }

    val activeImage = images.getOrNull(pagerState.currentPage)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("gallery_fullscreen_viewer")
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = images[page]
            ZoomableImagePage(image = item)
        }

        // Top overlay bar with real status bar + display cutout insets
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("gallery_viewer_close_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_navigate_back),
                    tint = Color.White
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeImage?.displayName.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${pagerState.currentPage + 1} / ${images.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            if (activeImage != null) {
                IconButton(
                    onClick = { onToggleMetadata(!showMetadata) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_viewer_info_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.media_gallery_metadata),
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { onShare(activeImage) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_viewer_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(R.string.media_gallery_share),
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { onRequestDelete(activeImage) },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_viewer_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = Color.White
                    )
                }
            }
        }

        // Bottom Send + Move to Private Vault action dock with real navigation bar insets
        if (activeImage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = { onMoveToVault(activeImage) },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_viewer_vault_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.viewer_action_move_to_vault),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = { onSendWithShareFlow(activeImage) },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("gallery_viewer_send_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.viewer_action_send_shareflow),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomableImagePage(image: MediaImageItem) {
    val context = LocalContext.current
    var scale by remember(image.uriString) { mutableFloatStateOf(1f) }
    var offset by remember(image.uriString) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(image.uriString) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(image.uriString) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale > 1f) {
                        Offset(
                            x = (offset.x + pan.x).coerceIn(-600f * nextScale, 600f * nextScale),
                            y = (offset.y + pan.y).coerceIn(-600f * nextScale, 600f * nextScale)
                        )
                    } else {
                        Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(Uri.parse(image.uriString))
                .build(),
            contentDescription = image.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}

@Composable
private fun ImageMetadataDialog(
    image: MediaImageItem,
    formattedSize: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("gallery_metadata_dialog"),
        title = { Text(text = stringResource(R.string.media_gallery_metadata)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetadataLine(stringResource(R.string.media_meta_name), image.displayName)
                MetadataLine(stringResource(R.string.media_meta_album), image.albumName)
                MetadataLine(stringResource(R.string.media_meta_mime), image.mimeType)
                MetadataLine(stringResource(R.string.media_meta_size), formattedSize)
                if (image.widthPx > 0 && image.heightPx > 0) {
                    MetadataLine(
                        stringResource(R.string.media_meta_resolution),
                        "${image.widthPx} × ${image.heightPx}"
                    )
                }
                image.cameraModel?.let {
                    MetadataLine(stringResource(R.string.media_meta_camera), it)
                }
                image.orientationLabel?.let {
                    MetadataLine(stringResource(R.string.media_meta_orientation), it)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("gallery_metadata_close_button")
            ) {
                Text(text = stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
