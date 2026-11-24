package com.example.shareflow.ui.files

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry

@Composable
fun FileSelectionActionBar(
    selectedCount: Int,
    selectedBytes: Long,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("files_selection_bar"),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = ShareFlowDesign.elevation.raised
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xs, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("files_selection_close")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.files_cd_close_selection)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            R.string.files_selected_count_format,
                            ShareFlowFormatters.formatCount(selectedCount, locale)
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("files_selection_count")
                    )
                    Text(
                        text = ShareFlowFormatters.formatBytes(selectedBytes, locale),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
                IconButton(
                    onClick = onSelectAll,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("files_selection_select_all")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SelectAll,
                        contentDescription = stringResource(R.string.files_cd_select_all)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.xs, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SelectionIconButton(
                    icon = Icons.Filled.Share,
                    label = stringResource(R.string.files_action_share),
                    onClick = onShare,
                    testTag = "files_selection_share"
                )
                SelectionIconButton(
                    icon = Icons.AutoMirrored.Filled.Send,
                    label = stringResource(R.string.home_action_send),
                    onClick = onSend,
                    testTag = "files_selection_send"
                )
                SelectionIconButton(
                    icon = Icons.Filled.ContentCopy,
                    label = stringResource(R.string.files_action_copy),
                    onClick = onCopy,
                    testTag = "files_selection_copy"
                )
                SelectionIconButton(
                    icon = Icons.AutoMirrored.Filled.DriveFileMove,
                    label = stringResource(R.string.files_action_move),
                    onClick = onMove,
                    testTag = "files_selection_move"
                )
                SelectionIconButton(
                    icon = Icons.Filled.Delete,
                    label = stringResource(R.string.files_action_delete),
                    onClick = onDelete,
                    testTag = "files_selection_delete"
                )
            }
        }
    }
}

@Composable
private fun SelectionIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(imageVector = icon, contentDescription = label)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerListRow(
    file: LocalFileEntry,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    var menuExpanded by remember { mutableStateOf(false) }

    val subtitle = if (file.isDirectory) {
        stringResource(R.string.ds_file_folder)
    } else {
        ShareFlowFormatters.formatBytes(file.sizeBytes, locale)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            FileThumbnailBadge(file = file, size = 44.dp)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!file.isAccessible) {
                        StatusPill(
                            label = stringResource(R.string.files_inaccessible_badge),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            } else {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("file_item_menu_${file.displayName}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.files_cd_more_options)
                        )
                    }
                    FileItemActionMenu(
                        file = file,
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onSelect = { menuExpanded = false; onToggleSelect() },
                        onPreview = { menuExpanded = false; onPreview() },
                        onDetails = { menuExpanded = false; onDetails() },
                        onRename = { menuExpanded = false; onRename() },
                        onCopy = { menuExpanded = false; onCopy() },
                        onMove = { menuExpanded = false; onMove() },
                        onShare = { menuExpanded = false; onShare() },
                        onSend = { menuExpanded = false; onSend() },
                        onDelete = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerGridTile(
    file: LocalFileEntry,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShareFlowDesign.shapes.card)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = ShareFlowDesign.shapes.card,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
                contentAlignment = Alignment.Center
            ) {
                FileThumbnailBadge(file = file, size = 64.dp)
                if (isSelectionMode) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                    )
                } else {
                    Box(modifier = Modifier.align(Alignment.TopEnd)) {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.files_cd_more_options),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        FileItemActionMenu(
                            file = file,
                            expanded = menuExpanded,
                            onDismiss = { menuExpanded = false },
                            onSelect = { menuExpanded = false; onToggleSelect() },
                            onPreview = { menuExpanded = false; onPreview() },
                            onDetails = { menuExpanded = false; onDetails() },
                            onRename = { menuExpanded = false; onRename() },
                            onCopy = { menuExpanded = false; onCopy() },
                            onMove = { menuExpanded = false; onMove() },
                            onShare = { menuExpanded = false; onShare() },
                            onSend = { menuExpanded = false; onSend() },
                            onDelete = { menuExpanded = false; onDelete() }
                        )
                    }
                }
            }
            Text(
                text = file.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (file.isDirectory) {
                    stringResource(R.string.ds_file_folder)
                } else {
                    ShareFlowFormatters.formatBytes(file.sizeBytes, locale)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun FileThumbnailBadge(
    file: LocalFileEntry,
    size: androidx.compose.ui.unit.Dp
) {
    val isImage = !file.isDirectory && (file.category == FileCategory.IMAGES || file.mimeType.startsWith("image/"))
    val isVideo = !file.isDirectory && (file.category == FileCategory.VIDEOS || file.mimeType.startsWith("video/"))
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetSizePx = remember(size, density) {
        with(density) { (size.toPx() * 1.35f).toInt().coerceIn(96, 256) }
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(size)
    ) {
        if (isImage || isVideo) {
            val request = com.example.shareflow.core.common.rememberThumbnailImageRequest(
                uriString = file.uriString,
                targetSizePx = targetSizePx,
                isVideo = isVideo
            )
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = request,
                    contentDescription = file.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
                if (isVideo) {
                    Icon(
                        imageVector = Icons.Filled.VideoFile,
                        contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.size(size * 0.42f)
                    )
                }
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = categoryIcon(file.category, file.isDirectory),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(size * 0.5f)
                )
            }
        }
    }
}

@Composable
private fun FileItemActionMenu(
    file: LocalFileEntry,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onDetails: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_action_select)) },
            leadingIcon = { Icon(Icons.Filled.SelectAll, contentDescription = null) },
            onClick = onSelect
        )
        if (!file.isDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_preview)) },
                leadingIcon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                onClick = onPreview
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.files_action_details)) },
            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
            onClick = onDetails
        )
        if (!file.isDirectory) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_share)) },
                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                onClick = onShare
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_send)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                onClick = onSend
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_copy)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = onCopy
            )
            if (file.capabilities.canDelete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_action_move)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                    onClick = onMove
                )
            }
        }
        if (file.capabilities.canRename || file.capabilities.canWrite) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_rename)) },
                leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null) },
                onClick = onRename
            )
        }
        if (file.capabilities.canDelete || file.capabilities.canWrite) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_action_delete)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = onDelete
            )
        }
    }
}

fun categoryIcon(category: FileCategory, isDirectory: Boolean): ImageVector {
    if (isDirectory) return Icons.Filled.Folder
    return when (category) {
        FileCategory.IMAGES -> Icons.Filled.Image
        FileCategory.VIDEOS -> Icons.Filled.VideoFile
        FileCategory.AUDIO -> Icons.Filled.AudioFile
        FileCategory.DOCUMENTS -> Icons.Filled.Description
        FileCategory.ARCHIVES -> Icons.Filled.Archive
        FileCategory.APKS -> Icons.Filled.Android
        FileCategory.DOWNLOADS -> Icons.Filled.Download
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
