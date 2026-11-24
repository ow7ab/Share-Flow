package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Share Flow 2.0 Liquid Glass EmptyState surface.
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
    SFGlassCard(
        shape = ShareFlowDesign.shapes.featuredCard,
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.xl),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.80f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(spacing.iconLarge)
                )
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
                SFPrimaryButton(
                    text = actionLabel,
                    onClick = onAction
                )
            }
        }
    }
}

/**
 * Share Flow 2.0 Liquid Glass ErrorState container with recovery action.
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
    SFGlassCard(
        shape = ShareFlowDesign.shapes.featuredCard,
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(
            alpha = if (ShareFlowDesign.glass.isDark) 0.36f else 0.56f
        ),
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.lg),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(52.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(MaterialTheme.colorScheme.errorContainer)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(spacing.iconLarge)
                )
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
                SFPrimaryButton(
                    text = retryLabel ?: stringResource(R.string.action_retry),
                    onClick = onRetry,
                    leadingIcon = Icons.Outlined.Refresh
                )
            }
        }
    }
}

/**
 * Centered loading state delegating directly to Share Flow 2.0 [SFLoadingIndicator].
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    val spacing = ShareFlowDesign.spacing
    val resolvedMessage = message ?: stringResource(R.string.ds_loading_default)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        SFLoadingIndicator(
            message = resolvedMessage,
            indicatorSize = 48.dp
        )
    }
}
