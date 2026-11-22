package com.example.shareflow.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
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
 * Reusable determinate progress bar filled with the Marple Signature Gradient:
 * Ruby (#CA2851) -> Coral (#FF6766) -> Peach (#FFB173) -> Cream (#FFE3B3).
 */
@Composable
fun ShareFlowSignatureProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val clamped = progress.coerceIn(0f, 1f)
    val trackShape = ShareFlowDesign.shapes.progressTrack
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = trackShape
            )
    ) {
        if (clamped > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .fillMaxHeight()
                    .clip(trackShape)
                    .background(ShareFlowBrandGradients.signatureHorizontal())
            )
        }
    }
}

/**
 * Reusable indeterminate progress bar with a sweeping
 * Marple Signature Gradient segment (Ruby -> Coral -> Peach -> Cream).
 */
@Composable
fun ShareFlowSignatureProgressBar(
    modifier: Modifier = Modifier,
    height: Dp = 6.dp
) {
    val trackShape = ShareFlowDesign.shapes.progressTrack
    val transition = rememberInfiniteTransition(label = "signature_indeterminate_progress")
    val offsetFraction by transition.animateFloat(
        initialValue = -0.45f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "signature_indeterminate_offset"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = 0.75.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = trackShape
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val segWidth = size.width * 0.42f
            val startX = offsetFraction * size.width
            val radius = size.height / 2f
            drawRoundRect(
                brush = ShareFlowBrandGradients.signatureHorizontal(),
                topLeft = Offset(startX, 0f),
                size = Size(segWidth, size.height),
                cornerRadius = CornerRadius(radius, radius)
            )
        }
    }
}

/**
 * Reusable active-state tab indicator painted with the Marple Signature Gradient.
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
 * Reusable Material 3 storage capacity indicator driven strictly by real used/total byte values.
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
    val animatedProgress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 300),
        label = "storage_progress"
    )

    val barColor = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.75f -> semantic.warningAccent
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
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
                        imageVector = Icons.Filled.Storage,
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

            if (fraction >= 0.9f) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(ShareFlowDesign.shapes.progressTrack),
                    color = MaterialTheme.colorScheme.error,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round
                )
            } else {
                ShareFlowSignatureProgressBar(
                    progress = animatedProgress,
                    height = 10.dp
                )
            }

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
 * Reusable Material 3 active transfer progress card.
 * Always reflects caller-provided real transferred bytes and real throughput; never fakes progress.
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
    val animatedProgress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 200),
        label = "transfer_progress"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isSent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    modifier = Modifier.size(40.dp)
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
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ds_cd_cancel_transfer),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            ShareFlowSignatureProgressBar(
                progress = animatedProgress,
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
