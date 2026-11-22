package com.example.shareflow.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Reusable Material 3 EmptyState surface with icon emblem, title, explanation, and optional CTA.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.32f)),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(spacing.iconLarge)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(spacing.xs))
                PrimaryActionButton(
                    text = actionLabel,
                    onClick = onAction
                )
            }
        }
    }
}

/**
 * Reusable Material 3 ErrorState container with clear recovery CTA and polite live-region semantics.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(spacing.iconLarge)
                    )
                }
            }
            Text(
                text = title ?: stringResource(R.string.ds_error_default_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(spacing.xxs))
                PrimaryActionButton(
                    text = retryLabel ?: stringResource(R.string.action_retry),
                    onClick = onRetry,
                    leadingIcon = Icons.Filled.Refresh
                )
            }
        }
    }
}

/**
 * Reusable Material 3 LoadingState centered indicator with accessible status announcement.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    val spacing = ShareFlowDesign.spacing
    val resolvedMessage = message ?: stringResource(R.string.ds_loading_default)
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val infiniteTransition = rememberInfiniteTransition(label = "shareflow_loading_ring")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shareflow_loading_rotation"
    )

    val haloColor = ShareFlowDesign.semanticColors.cream.copy(alpha = if (ShareFlowDesign.semanticColors.isDark) 0.14f else 0.45f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xl)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Canvas(modifier = Modifier.size(48.dp)) {
            val strokePx = 4.dp.toPx()
            drawCircle(
                color = haloColor,
                radius = size.minDimension * 0.34f
            )
            drawCircle(
                color = trackColor,
                style = Stroke(width = strokePx)
            )
            rotate(degrees = rotation) {
                drawArc(
                    brush = Brush.sweepGradient(ShareFlowBrandGradients.SignatureStops),
                    startAngle = 0f,
                    sweepAngle = 285f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = resolvedMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
