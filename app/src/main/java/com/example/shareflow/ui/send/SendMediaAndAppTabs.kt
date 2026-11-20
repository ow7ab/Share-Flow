package com.example.shareflow.ui.send

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.FileItem
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.MediaImageItem
import com.example.shareflow.domain.model.MediaVideoItem
import com.example.shareflow.domain.model.ShareableAppInfo
import com.example.shareflow.domain.repository.AppSharingRepository

internal fun LazyListScope.sendPhotosTabItems(
    uiState: SendUiState,
    onPickPhotos: () -> Unit,
    onSelectFilter: (SendPhotoFilter, String?) -> Unit,
    onToggleSelectAll: () -> Unit,
    onTogglePhoto: (MediaImageItem) -> Unit,
    onPreviewPhoto: (MediaImageItem) -> Unit
) {
    item(key = "photos_picker_cta") {
        val spacing = ShareFlowDesign.spacing
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SecondaryActionButton(
                text = stringResource(R.string.send_photos_pick_action),
                onClick = onPickPhotos,
                outlined = false,
                leadingIcon = Icons.Filled.Image,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_pick_photos_button")
            )
            Text(
                text = stringResource(R.string.send_photos_original_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    item(key = "photos_filters") {
        val spacing = ShareFlowDesign.spacing
        val visible = uiState.visiblePhotos
        val allSelected = visible.isNotEmpty() && visible.all { it.uriString in uiState.selectedUriSet }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    FilterChip(
                        selected = uiState.photoFilter == SendPhotoFilter.ALL,
                        onClick = { onSelectFilter(SendPhotoFilter.ALL, null) },
                        label = { Text(stringResource(R.string.send_photos_all_filter)) },
                        modifier = Modifier.testTag("send_photos_filter_all")
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.photoFilter == SendPhotoFilter.RECENT,
                        onClick = { onSelectFilter(SendPhotoFilter.RECENT, null) },
                        label = { Text(stringResource(R.string.send_photos_recent_filter)) },
                        modifier = Modifier.testTag("send_photos_filter_recent")
                    )
                }
                if (uiState.availableAlbums.isNotEmpty()) {
                    item {
                        FilterChip(
                            selected = uiState.photoFilter == SendPhotoFilter.ALBUMS,
                            onClick = { onSelectFilter(SendPhotoFilter.ALBUMS, uiState.availableAlbums.firstOrNull()) },
                            label = { Text(stringResource(R.string.send_photos_albums_filter)) },
                            modifier = Modifier.testTag("send_photos_filter_albums")
                        )
                    }
                }
            }
            if (visible.isNotEmpty()) {
                TextButton(
                    onClick = onToggleSelectAll,
                    modifier = Modifier.testTag("send_photos_select_all_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(if (allSelected) R.string.send_deselect_all else R.string.send_select_all),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }

    if (uiState.photoFilter == SendPhotoFilter.ALBUMS && uiState.availableAlbums.isNotEmpty()) {
        item(key = "photos_album_chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(ShareFlowDesign.spacing.xs)) {
                items(uiState.availableAlbums, key = { it }) { album ->
                    FilterChip(
                        selected = uiState.selectedAlbum == album,
                        onClick = { onSelectFilter(SendPhotoFilter.ALBUMS, album) },
                        label = { Text(text = album) }
                    )
                }
            }
        }
    }

    if (uiState.isLoadingPhotos) {
        item(key = "photos_loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    } else if (uiState.visiblePhotos.isEmpty()) {
        item(key = "photos_empty") {
            EmptyState(
                icon = Icons.Filled.Image,
                title = stringResource(R.string.send_photos_empty_title),
                body = stringResource(R.string.send_photos_empty_body),
                actionLabel = stringResource(R.string.send_photos_pick_action),
                onAction = onPickPhotos,
                modifier = Modifier.testTag("send_photos_empty_state")
            )
        }
    } else {
        val rows = uiState.visiblePhotos.chunked(3)
        items(items = rows, key = { row -> row.joinToString("|") { it.uriString } }) { rowPhotos ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ShareFlowDesign.spacing.xs)
            ) {
                rowPhotos.forEach { photo ->
                    val selected = photo.uriString in uiState.selectedUriSet
                    PhotoGridTile(
                        photo = photo,
                        selected = selected,
                        onToggle = { onTogglePhoto(photo) },
                        onPreview = { onPreviewPhoto(photo) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - rowPhotos.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PhotoGridTile(
    photo: MediaImageItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier
) {
    val locale = ShareFlowDesign.locale
    Surface(
        modifier = modifier
            .aspectRatio(0.88f)
            .clickable(onClick = onToggle),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = Uri.parse(photo.uriString),
                contentDescription = photo.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            // Top selection badge + preview icon
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                Surface(
                    onClick = onPreview,
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.55f),
                    modifier = Modifier.size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = photo.displayName,
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            // Bottom size overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                Text(
                    text = ShareFlowFormatters.formatBytes(photo.sizeBytes, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

internal fun LazyListScope.sendVideosTabItems(
    uiState: SendUiState,
    onPickVideos: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleVideo: (MediaVideoItem) -> Unit,
    onPreviewVideo: (MediaVideoItem) -> Unit
) {
    item(key = "videos_picker_cta") {
        val spacing = ShareFlowDesign.spacing
        val allSelected = uiState.videos.isNotEmpty() && uiState.videos.all { it.uriString in uiState.selectedUriSet }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SecondaryActionButton(
                text = stringResource(R.string.send_videos_pick_action),
                onClick = onPickVideos,
                outlined = false,
                leadingIcon = Icons.Filled.VideoLibrary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_pick_videos_button")
            )
            if (uiState.videos.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onToggleSelectAll,
                        modifier = Modifier.testTag("send_videos_select_all_button")
                    ) {
                        Text(stringResource(if (allSelected) R.string.send_deselect_all else R.string.send_select_all))
                    }
                }
            }
        }
    }

    if (uiState.isLoadingVideos) {
        item(key = "videos_loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    } else if (uiState.videos.isEmpty()) {
        item(key = "videos_empty") {
            EmptyState(
                icon = Icons.Filled.VideoLibrary,
                title = stringResource(R.string.send_videos_empty_title),
                body = stringResource(R.string.send_videos_empty_body),
                actionLabel = stringResource(R.string.send_videos_pick_action),
                onAction = onPickVideos,
                modifier = Modifier.testTag("send_videos_empty_state")
            )
        }
    } else {
        items(items = uiState.videos, key = { "vid_${it.uriString}" }) { video ->
            val selected = video.uriString in uiState.selectedUriSet
            VideoSelectionCard(
                video = video,
                selected = selected,
                onToggle = { onToggleVideo(video) },
                onPreview = { onPreviewVideo(video) }
            )
        }
    }
}

@Composable
private fun VideoSelectionCard(
    video: MediaVideoItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onPreview: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(ShareFlowDesign.shapes.card)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = Uri.parse(video.uriString),
                    contentDescription = video.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    imageVector = Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val durationLabel = if (video.durationMs > 0L) formatDurationMs(video.durationMs) else ""
                val sizeLabel = ShareFlowFormatters.formatBytes(video.sizeBytes, locale)
                Text(
                    text = listOf(durationLabel, sizeLabel, video.folderName)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onPreview) {
                Text(stringResource(R.string.send_videos_preview_action))
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

internal fun LazyListScope.sendAppsTabItems(
    uiState: SendUiState,
    appSharingRepository: AppSharingRepository,
    onSearchChange: (String) -> Unit,
    onSortChange: (SendAppSortOption) -> Unit,
    onToggleIncludeSystem: (Boolean) -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleApp: (ShareableAppInfo) -> Unit
) {
    item(key = "apps_controls") {
        val spacing = ShareFlowDesign.spacing
        val extractable = uiState.visibleApps.filter { it.isExtractable }
        val allSelected = extractable.isNotEmpty() && extractable.all { it.packageName in uiState.selectedAppPackages }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            OutlinedTextField(
                value = uiState.appSearchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.send_apps_search_placeholder)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_apps_search_input")
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                item {
                    FilterChip(
                        selected = uiState.appSortOption == SendAppSortOption.NAME,
                        onClick = { onSortChange(SendAppSortOption.NAME) },
                        label = { Text(stringResource(R.string.send_apps_sort_name)) }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.appSortOption == SendAppSortOption.SIZE,
                        onClick = { onSortChange(SendAppSortOption.SIZE) },
                        label = { Text(stringResource(R.string.send_apps_sort_size)) }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.appSortOption == SendAppSortOption.RECENT,
                        onClick = { onSortChange(SendAppSortOption.RECENT) },
                        label = { Text(stringResource(R.string.send_apps_sort_recent)) }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.includeSystemApps,
                        onClick = { onToggleIncludeSystem(!uiState.includeSystemApps) },
                        label = { Text(stringResource(R.string.send_apps_system_filter)) }
                    )
                }
            }
            if (extractable.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onToggleSelectAll,
                        modifier = Modifier.testTag("send_apps_select_all_button")
                    ) {
                        Text(stringResource(if (allSelected) R.string.send_deselect_all else R.string.send_select_all))
                    }
                }
            }
        }
    }

    if (uiState.isLoadingApps) {
        item(key = "apps_loading") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    } else if (uiState.visibleApps.isEmpty()) {
        item(key = "apps_empty") {
            EmptyState(
                icon = Icons.Filled.Android,
                title = stringResource(R.string.apps_empty_title),
                body = stringResource(R.string.apps_empty_body),
                modifier = Modifier.testTag("send_apps_empty_state")
            )
        }
    } else {
        items(items = uiState.visibleApps, key = { "app_${it.packageName}" }) { app ->
            val selected = app.packageName in uiState.selectedAppPackages
            InstalledAppSelectionCard(
                app = app,
                selected = selected,
                appSharingRepository = appSharingRepository,
                onToggle = { onToggleApp(app) }
            )
        }
    }
}

@Composable
private fun InstalledAppSelectionCard(
    app: ShareableAppInfo,
    selected: Boolean,
    appSharingRepository: AppSharingRepository,
    onToggle: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val iconBitmap = remember(app.packageName) {
        runCatching {
            appSharingRepository.loadAppIcon(app.packageName)?.toBitmap(96, 96)?.asImageBitmap()
        }.getOrNull()
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("send_app_item_${app.packageName}"),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Android,
                            contentDescription = app.appName,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeStr = app.totalApkSizeBytes?.let { ShareFlowFormatters.formatBytes(it, locale) } ?: "—"
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "v${app.versionName} (${app.versionCode}) • $sizeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (app.isSplitApk) {
                        StatusPill(
                            label = stringResource(R.string.send_apps_split_badge, app.apkPartCount),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (!app.isExtractable && !app.unavailableReason.isNullOrBlank()) {
                    Text(
                        text = app.unavailableReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                enabled = app.isExtractable,
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

internal fun LazyListScope.sendFilesTabItems(
    uiState: SendUiState,
    onSelectCategory: (FileCategory?) -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onToggleFile: (LocalFileEntry) -> Unit,
    onRemoveSelectedFile: (String) -> Unit
) {
    item(key = "files_category_chips") {
        val spacing = ShareFlowDesign.spacing
        val fileCategories: List<Pair<FileCategory?, Int>> = listOf(
            null to R.string.send_files_filter_all,
            FileCategory.DOCUMENTS to R.string.send_files_filter_docs,
            FileCategory.AUDIO to R.string.send_files_filter_audio,
            FileCategory.ARCHIVES to R.string.send_files_filter_archives,
            FileCategory.APKS to R.string.send_files_filter_apks,
            FileCategory.OTHER to R.string.send_files_filter_other
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(fileCategories) { (category, labelRes) ->
                FilterChip(
                    selected = uiState.activeCategory == category,
                    onClick = { onSelectCategory(category) },
                    label = { Text(stringResource(labelRes)) },
                    modifier = Modifier.testTag("send_cat_${category?.name?.lowercase() ?: "all"}")
                )
            }
        }
    }

    item(key = "files_saf_pickers") {
        val spacing = ShareFlowDesign.spacing
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            SecondaryActionButton(
                text = stringResource(R.string.send_add_files_action),
                onClick = onPickFiles,
                outlined = false,
                leadingIcon = Icons.Filled.Add,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_pick_files_button")
            )
            SecondaryActionButton(
                text = stringResource(R.string.transfer_pick_folder_action),
                onClick = onPickFolder,
                outlined = true,
                leadingIcon = Icons.Filled.CreateNewFolder,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("send_pick_folder_button")
            )
        }
    }

    val browsed = uiState.visibleBrowsedFiles
    if (browsed.isNotEmpty()) {
        item(key = "files_select_all_row") {
            val allSelected = browsed.all { it.uriString in uiState.selectedUriSet }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onToggleSelectAll,
                    modifier = Modifier.testTag("send_files_select_all_button")
                ) {
                    Text(stringResource(if (allSelected) R.string.send_deselect_all else R.string.send_select_all))
                }
            }
        }
        items(items = browsed, key = { "browsed_${it.uriString}" }) { file ->
            val selected = file.uriString in uiState.selectedUriSet
            FileItem(
                file = file,
                onClick = { onToggleFile(file) },
                trailingContent = {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleFile(file) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    )
                },
                modifier = Modifier.testTag("send_browsed_file_${file.displayName}")
            )
        }
    } else if (uiState.filteredFiles.isEmpty()) {
        item(key = "files_empty") {
            EmptyState(
                icon = Icons.Filled.FolderOpen,
                title = stringResource(R.string.send_empty_title),
                body = stringResource(R.string.send_empty_body),
                modifier = Modifier.testTag("send_empty_state")
            )
        }
    } else {
        items(items = uiState.filteredFiles, key = { "sel_${it.uriString}" }) { file ->
            FileItem(
                file = file,
                onClick = {},
                trailingContent = {
                    IconButton(
                        onClick = { onRemoveSelectedFile(file.uriString) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.send_clear_selection)
                        )
                    }
                },
                modifier = Modifier.testTag("send_selected_file_${file.displayName}")
            )
        }
    }
}

@Composable
internal fun PhotoPreviewDialog(
    photo: MediaImageItem,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onDismiss: () -> Unit
) {
    val locale = ShareFlowDesign.locale
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = photo.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ShareFlowDesign.spacing.xs)) {
                AsyncImage(
                    model = Uri.parse(photo.uriString),
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(ShareFlowDesign.shapes.card)
                )
                val dims = if (photo.widthPx > 0 && photo.heightPx > 0) "${photo.widthPx}×${photo.heightPx} • " else ""
                Text(
                    text = "$dims${ShareFlowFormatters.formatBytes(photo.sizeBytes, locale)} • ${photo.albumName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onToggleSelect()
                onDismiss()
            }) {
                Text(
                    text = if (selected) stringResource(R.string.send_deselect_all)
                    else stringResource(R.string.files_action_select)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

@Composable
internal fun VideoPreviewDialog(
    video: MediaVideoItem,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(video.uriString) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(video.uriString)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = video.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(ShareFlowDesign.shapes.card)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

private fun formatDurationMs(durationMs: Long): String {
    val totalSec = (durationMs / 1_000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return "%d:%02d".format(mins, secs)
}
