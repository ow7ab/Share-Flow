package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.ui.common.ReceivedHistoryAppIcon
import com.example.shareflow.ui.common.TransferDisplayPresentation

/**
 * Reusable Material 3 card displaying a real [TransferRecord] with direction, size, peer, and verification state.
 */
@Composable
fun TransferCard(
    record: TransferRecord,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isSent = record.direction == TransferDirection.SENT

    val statusLabel = when (record.status) {
        TransferStatus.QUEUED -> stringResource(R.string.ds_status_queued)
        TransferStatus.IN_PROGRESS -> stringResource(R.string.ds_status_in_progress)
        TransferStatus.VERIFYING -> stringResource(R.string.ds_status_verifying)
        TransferStatus.COMPLETED_VERIFIED -> stringResource(R.string.ds_status_completed)
        TransferStatus.FAILED -> stringResource(R.string.ds_status_failed)
        TransferStatus.CANCELLED -> stringResource(R.string.ds_status_cancelled)
    }

    val (statusContainer, statusContent) = when (record.status) {
        TransferStatus.COMPLETED_VERIFIED -> semantic.verifiedContainer to semantic.verifiedBadge
        TransferStatus.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        TransferStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    }

    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            val isReceivedApp = !isSent && (
                record.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
                    record.fileName.endsWith(".apk", ignoreCase = true)
                )
            if (isReceivedApp) {
                ReceivedHistoryAppIcon(
                    apkPathOrUri = record.localUri,
                    appDisplayName = TransferDisplayPresentation.cleanAppDisplayName(record.fileName),
                    recordKey = record.id,
                    iconSize = 44.dp,
                    modifier = Modifier.testTag("history_received_app_icon_${record.id}")
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isSent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSent) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(
                                if (isSent) R.string.history_direction_sent else R.string.history_direction_received
                            ),
                            tint = if (isSent) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            }
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = record.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ShareFlowFormatters.formatBytes(record.fileSizeBytes, locale),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = record.peerDeviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        label = statusLabel,
                        containerColor = statusContainer,
                        contentColor = statusContent
                    )
                    if (record.isChecksumVerified) {
                        Icon(
                            imageVector = Icons.Filled.Verified,
                            contentDescription = stringResource(R.string.history_status_verified),
                            tint = semantic.verifiedBadge,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.ds_cd_delete_record),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            shape = ShareFlowDesign.shapes.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { cardContent() }
    } else {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            shape = ShareFlowDesign.shapes.card,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) { cardContent() }
    }
}

/**
 * Reusable Material 3 card displaying a discovered or trusted [PeerDevice].
 */
@Composable
fun DeviceCard(
    device: PeerDevice,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val selectedStateText = stringResource(
        if (selected) R.string.ds_state_selected else R.string.ds_state_not_selected
    )

    val deviceIcon = when (device.deviceType) {
        DeviceType.PHONE -> Icons.Filled.Smartphone
        DeviceType.TABLET -> Icons.Filled.TabletAndroid
        DeviceType.DESKTOP -> Icons.Filled.Computer
        DeviceType.UNKNOWN -> Icons.Filled.DevicesOther
    }
    val connectionLabel = when (device.connectionType) {
        ConnectionType.WIFI_DIRECT -> stringResource(R.string.ds_connection_wifi_direct)
        ConnectionType.LOCAL_LAN_NSD -> stringResource(R.string.ds_connection_lan)
        ConnectionType.HOTSPOT -> stringResource(R.string.ds_connection_hotspot)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = selectedStateText
            },
        shape = ShareFlowDesign.shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = if (selected) {
            BorderStroke(
                width = 1.5.dp,
                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.85f)
            )
        } else {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = ShareFlowDesign.shapes.avatar,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = deviceIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(spacing.iconMedium)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (device.isTrusted) {
                        StatusPill(
                            label = stringResource(R.string.ds_device_trusted),
                            containerColor = semantic.verifiedContainer,
                            contentColor = semantic.verifiedBadge
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = connectionLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = stringResource(R.string.discovery_device_id_format, device.shortDeviceId),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val peerStatusLabel = when (device.connectionStatus) {
                    com.example.shareflow.domain.model.PeerConnectionStatus.AVAILABLE ->
                        stringResource(R.string.peer_status_available)
                    com.example.shareflow.domain.model.PeerConnectionStatus.CONNECTING ->
                        stringResource(R.string.peer_status_connecting)
                    com.example.shareflow.domain.model.PeerConnectionStatus.AWAITING_APPROVAL ->
                        stringResource(R.string.peer_status_awaiting_approval)
                    com.example.shareflow.domain.model.PeerConnectionStatus.CONNECTED ->
                        stringResource(R.string.peer_status_connected)
                    com.example.shareflow.domain.model.PeerConnectionStatus.REJECTED ->
                        stringResource(R.string.peer_status_rejected)
                    com.example.shareflow.domain.model.PeerConnectionStatus.UNAVAILABLE ->
                        stringResource(R.string.peer_status_unavailable)
                }
                val (peerPillContainer, peerPillContent) = when (device.connectionStatus) {
                    com.example.shareflow.domain.model.PeerConnectionStatus.CONNECTED ->
                        semantic.verifiedContainer to semantic.verifiedBadge
                    com.example.shareflow.domain.model.PeerConnectionStatus.REJECTED ->
                        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    com.example.shareflow.domain.model.PeerConnectionStatus.CONNECTING,
                    com.example.shareflow.domain.model.PeerConnectionStatus.AWAITING_APPROVAL ->
                        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    else ->
                        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                }
                StatusPill(
                    label = peerStatusLabel,
                    containerColor = peerPillContainer,
                    contentColor = peerPillContent
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Reusable Material 3 list row for a local file or directory ([LocalFileEntry]).
 * Supports optional multi-selection checkbox and trailing action slot.
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

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (selectable) {
                    stateDescription = selectedStateText
                }
            },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = if (selected) {
            BorderStroke(
                width = 1.25.dp,
                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.85f)
            )
        } else {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = fileCategoryIcon(file.category, file.isDirectory),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
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
                    modifier = Modifier.minimumInteractiveComponentSize()
                )
            } else if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}

/**
 * Reusable Material 3 rounded category card for Files, Media, and Storage grids.
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

    Card(
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = ShareFlowDesign.shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = accentContainerColor,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentContentColor,
                        modifier = Modifier.size(spacing.iconMedium)
                    )
                }
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
    if (isDirectory) return Icons.Filled.Folder
    return when (category) {
        FileCategory.IMAGES -> Icons.Filled.Image
        FileCategory.VIDEOS -> Icons.Filled.VideoFile
        FileCategory.AUDIO -> Icons.Filled.AudioFile
        FileCategory.DOCUMENTS -> Icons.Filled.Description
        FileCategory.APKS -> Icons.Filled.Android
        FileCategory.ARCHIVES -> Icons.Filled.Archive
        FileCategory.DOWNLOADS -> Icons.Filled.ArrowDownward
        FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}
