package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferRecord

/**
 * Transfer record card delegating directly to Share Flow 2.0 [SFTransferCard].
 */
@Composable
fun TransferCard(
    record: TransferRecord,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    SFTransferCard(
        record = record,
        modifier = modifier,
        onClick = onClick,
        onDelete = onDelete
    )
}

/**
 * Discovered/trusted peer device card delegating directly to Share Flow 2.0 [SFDeviceCard].
 */
@Composable
fun DeviceCard(
    device: PeerDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    SFDeviceCard(
        device = device,
        onClick = onClick,
        modifier = modifier,
        selected = selected
    )
}

/**
 * Share Flow 2.0 Liquid Glass file row for a local file or directory ([LocalFileEntry]).
 */
@Composable
fun FileItem(
    file: LocalFileEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    selected: Boolean = false,
    onSelectionChange: ((Boolean) -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val selectedStateText = stringResource(
        if (selected) R.string.ds_state_selected else R.string.ds_state_not_selected
    )
    val subtitle = if (file.isDirectory) {
        stringResource(R.string.ds_file_folder)
    } else {
        ShareFlowFormatters.formatBytes(file.sizeBytes, locale)
    }

    SFGlassCard(
        onClick = onClick,
        selected = selected,
        elevated = selected,
        contentPadding = PaddingValues(horizontal = spacing.md, vertical = spacing.sm),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (selectable) {
                    stateDescription = selectedStateText
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                        } else {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f)
                        }
                    )
            ) {
                Icon(
                    imageVector = fileCategoryIcon(file.category, file.isDirectory),
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = file.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (selectable) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked -> onSelectionChange?.invoke(checked) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = MaterialTheme.colorScheme.primary,
                        checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        uncheckedColor = MaterialTheme.colorScheme.outline
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            } else if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * Share Flow 2.0 Liquid Glass category card for Files, Media, and Storage grids.
 */
@Composable
fun CategoryCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    itemCount: Int? = null,
    totalBytes: Long? = null,
    accentContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    accentContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    SFGlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier.semantics(mergeDescendants = true) {}
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(accentContainerColor.copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentContentColor,
                    modifier = Modifier.size(spacing.iconMedium)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (itemCount != null || totalBytes != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xxs)
                ) {
                    if (itemCount != null) {
                        Text(
                            text = stringResource(
                                R.string.ds_category_items_format,
                                ShareFlowFormatters.formatCount(itemCount, locale)
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (itemCount != null && totalBytes != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (totalBytes != null) {
                        Text(
                            text = ShareFlowFormatters.formatBytes(totalBytes, locale),
                            style = ShareFlowMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun fileCategoryIcon(category: FileCategory, isDirectory: Boolean): ImageVector {
    if (isDirectory) return Icons.Outlined.Folder
    return when (category) {
        FileCategory.IMAGES -> Icons.Outlined.Image
        FileCategory.VIDEOS -> Icons.Outlined.VideoFile
        FileCategory.AUDIO -> Icons.Outlined.AudioFile
        FileCategory.DOCUMENTS -> Icons.Outlined.Description
        FileCategory.APKS -> Icons.Outlined.Android
        FileCategory.ARCHIVES -> Icons.Outlined.Archive
        FileCategory.DOWNLOADS -> Icons.Outlined.ArrowDownward
        FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}
