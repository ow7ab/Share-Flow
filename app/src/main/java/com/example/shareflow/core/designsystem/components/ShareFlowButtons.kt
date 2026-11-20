package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Primary Material 3 filled action button meeting 48dp accessibility touch-target guidelines.
 * Supports optional leading icon and indeterminate loading feedback.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val spacing = ShareFlowDesign.spacing
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = ShareFlowDesign.shapes.button,
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .heightIn(min = spacing.minTouchTarget)
            .minimumInteractiveComponentSize()
            .semantics { role = Role.Button }
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(modifier = Modifier.width(spacing.xs))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall)
            )
            Spacer(modifier = Modifier.width(spacing.xs))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Secondary Material 3 action button supporting tonal or outlined styling and destructive emphasis.
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = true,
    isDestructive: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val spacing = ShareFlowDesign.spacing
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = ShareFlowDesign.shapes.button,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
            border = BorderStroke(
                width = 1.dp,
                color = if (isDestructive) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outline
                }
            ),
            modifier = modifier
                .heightIn(min = spacing.minTouchTarget)
                .minimumInteractiveComponentSize()
                .semantics { role = Role.Button }
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(spacing.iconSmall)
                )
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = ShareFlowDesign.shapes.button,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isDestructive) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                contentColor = if (isDestructive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            ),
            modifier = modifier
                .heightIn(min = spacing.minTouchTarget)
                .minimumInteractiveComponentSize()
                .semantics { role = Role.Button }
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(spacing.iconSmall)
                )
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
