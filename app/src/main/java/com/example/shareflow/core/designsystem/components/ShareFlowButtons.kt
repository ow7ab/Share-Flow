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
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Primary Material 3 filled action button styled with the ShareFlow Marple Design System:
 * - Light Theme: Ruby (#CA2851) primary container with high-contrast white ink.
 * - Dark Theme: Adapted Ruby/Coral (#FF8198) primary container with deep warm dark ink.
 * - Selective Marple Signature Gradient edge highlight (Ruby -> Coral -> Peach -> Cream).
 * - Meets 48dp accessibility touch-target guidelines.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    useSignatureBorder: Boolean = true
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val isInteractive = enabled && !isLoading
    Button(
        onClick = onClick,
        enabled = isInteractive,
        shape = ShareFlowDesign.shapes.button,
        contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        border = if (useSignatureBorder && isInteractive) {
            BorderStroke(
                width = 1.25.dp,
                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.85f)
            )
        } else {
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = semantic.disabled,
            disabledContentColor = semantic.onDisabled
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = ShareFlowDesign.elevation.card,
            pressedElevation = ShareFlowDesign.elevation.raised,
            disabledElevation = ShareFlowDesign.elevation.flat
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
                tint = if (isInteractive) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    semantic.onDisabled
                },
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
 * Secondary Material 3 action button styled with Marple Coral (#FF6766) secondary accents,
 * theme-aware Light/Dark surface containers, and crisp contrast.
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
    val semantic = ShareFlowDesign.semanticColors
    val contentColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.secondary
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = ShareFlowDesign.shapes.button,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = contentColor,
                disabledContentColor = semantic.onDisabled
            ),
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    !enabled -> semantic.disabled
                    isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f)
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
            border = BorderStroke(
                width = 1.dp,
                color = if (isDestructive) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.34f)
                }
            ),
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
                },
                disabledContainerColor = semantic.disabled,
                disabledContentColor = semantic.onDisabled
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
