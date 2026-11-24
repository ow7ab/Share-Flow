package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.TransferDirection

/**
 * Determinate brand-gradient progress bar delegating to Share Flow 2.0 [SFProgressIndicator].
 */
@Composable
fun ShareFlowSignatureProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    SFProgressIndicator(
        progress = progress,
        modifier = modifier,
        height = height
    )
}

/**
 * Indeterminate brand-gradient progress bar delegating to Share Flow 2.0 [SFProgressIndicator].
 */
@Composable
fun ShareFlowSignatureProgressBar(
    modifier: Modifier = Modifier,
    height: Dp = 6.dp
) {
    SFProgressIndicator(
        modifier = modifier,
        height = height
    )
}

/**
 * Active-state tab indicator painted with the official brand gradient.
 */
@Composable
fun ShareFlowSignatureTabIndicator(
    modifier: Modifier = Modifier,
    height: Dp = 3.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(ShareFlowBrandGradients.signatureHorizontal())
    )
}

/**
 * Share Flow 2.0 Liquid Glass storage capacity card driven strictly by real used/total byte values.
 */
@Composable
fun StorageIndicator(
    usedBytes: Long,
    totalBytes: Long,
    modifier: Modifier = Modifier,
    title: String? = null
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    val safeTotal = totalBytes.coerceAtLeast(0L)
    val safeUsed = usedBytes.coerceIn(0L, safeTotal)
    val availableBytes = (safeTotal - safeUsed).coerceAtLeast(0L)
    val fraction = if (safeTotal <= 0L) 0f else (safeUsed.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)

    val barColor = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> semantic.warningAccent
        else -> MaterialTheme.colorScheme.primary
    }

    SFGlassCard(
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storage,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(spacing.iconSmall)
                    )
                    Text(
                        text = title ?: stringResource(R.string.module_storage_analyzer_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = ShareFlowFormatters.formatPercentage(fraction, locale),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            SFProgressIndicator(
                progress = fraction,
                height = 10.dp,
                isError = fraction >= 0.9f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.ds_storage_used_format,
                        ShareFlowFormatters.formatBytes(safeUsed, locale),
                        ShareFlowFormatters.formatBytes(safeTotal, locale)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.ds_storage_available_format,
                        ShareFlowFormatters.formatBytes(availableBytes, locale)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Share Flow 2.0 Liquid Glass active transfer progress card.
 * Always reflects caller-provided real transferred bytes and real throughput.
 */
@Composable
fun TransferProgressCard(
    fileName: String,
    peerName: String,
    direction: TransferDirection,
    bytesTransferred: Long,
    totalBytes: Long,
    bytesPerSecond: Long,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    onCancel: (() -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val isSent = direction == TransferDirection.SENT

    val safeTotal = totalBytes.coerceAtLeast(0L)
    val safeTransferred = bytesTransferred.coerceIn(0L, safeTotal)
    val fraction = if (safeTotal <= 0L) 0f else (safeTransferred.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)

    SFGlassCard(
        elevated = true,
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
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

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (statusLabel != null) "$peerName • $statusLabel" else peerName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onCancel != null) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.ds_cd_cancel_transfer),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            SFProgressIndicator(
                progress = fraction,
                height = 8.dp
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.ds_transfer_progress_format,
                        ShareFlowFormatters.formatBytes(safeTransferred, locale),
                        ShareFlowFormatters.formatBytes(safeTotal, locale)
                    ),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ShareFlowFormatters.formatTransferSpeed(bytesPerSecond, locale),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = ShareFlowFormatters.formatPercentage(fraction, locale),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
