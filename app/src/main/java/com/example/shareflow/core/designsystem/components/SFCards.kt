package com.exampleshareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TabletAndroid
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.PeerConnectionStatus
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.ui.common.ReceivedHistoryAppIcon
import com.example.shareflow.ui.common.TransferDisplayPresentation

/**
 * Share Flow 2.0 Device Card (`SFDeviceCard`) built on [SFGlassCard] with crisp outlined iconography
 * and live connection status badge.
 */
@Composable
fun SFDeviceCard(
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
        DeviceType.PHONE -> Icons.Outlined.Smartphone
        DeviceType.TABLET -> Icons.Outlined.TabletAndroid
        DeviceType.DESKTOP -> Icons.Outlined.Computer
        DeviceType.UNKNOWN -> Icons.Outlined.DevicesOther
    }
    val connectionLabel = when (device.connectionType) {
        ConnectionType.WIFI_DIRECT -> stringResource(R.string.ds_connection_wifi_direct)
        ConnectionType.LOCAL_LAN_NSD -> stringResource(R.string.ds_connection_lan)
        ConnectionType.HOTSPOT -> stringResource(R.string.ds_connection_hotspot)
    }

    SFGlassCard(
        onClick = onClick,
        selected = selected,
        elevated = selected,
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = selectedStateText
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = ShareFlowDesign.shapes.avatar,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
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
                        imageVector = Icons.Outlined.Wifi,
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
                    PeerConnectionStatus.AVAILABLE -> stringResource(R.string.peer_status_available)
                    PeerConnectionStatus.CONNECTING -> stringResource(R.string.peer_status_connecting)
                    PeerConnectionStatus.AWAITING_APPROVAL -> stringResource(R.string.peer_status_awaiting_approval)
                    PeerConnectionStatus.CONNECTED -> stringResource(R.string.peer_status_connected)
                    PeerConnectionStatus.REJECTED -> stringResource(R.string.peer_status_rejected)
                    PeerConnectionStatus.UNAVAILABLE -> stringResource(R.string.peer_status_unavailable)
                }
                val (peerPillContainer, peerPillContent) = when (device.connectionStatus) {
                    PeerConnectionStatus.CONNECTED ->
                        semantic.successContainer to semantic.success
                    PeerConnectionStatus.REJECTED ->
                        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                    PeerConnectionStatus.CONNECTING,
                    PeerConnectionStatus.AWAITING_APPROVAL ->
                        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
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
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Share Flow 2.0 Transfer History Card (`SFTransferCard`) built on [SFGlassCard].
 */
@Composable
fun SFTransferCard(
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

    SFGlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(ShareFlowDesign.shapes.iconContainer)
                        .background(
                            if (isSent) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                            }
                        )
                ) {
                    Icon(
                        imageVector = if (isSent) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                        contentDescription = stringResource(
                            if (isSent) R.string.history_direction_sent else R.string.history_direction_received
                        ),
                        tint = if (isSent) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(20.dp)
                    )
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
                            imageVector = Icons.Outlined.Verified,
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
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.ds_cd_delete_record),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Share Flow 2.0 Settings Row (`SFSettingsItem`) — Samsung One UI clean grouped row
 * with optional outlined icon badge, title, subtitle, trailing content or chevron, and divider.
 */
@Composable
fun SFSettingsItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    showDivider: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = spacing.minTouchTarget)
                .then(
                    if (onClick != null) {
                        Modifier
                            .clip(ShareFlowDesign.shapes.chip)
                            .clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            if (leadingIcon != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(ShareFlowDesign.shapes.iconContainer)
                        .background(iconContainerColor)
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
            )
        }
    }
}
