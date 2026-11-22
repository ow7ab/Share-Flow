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
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Primary Material 3 filled action button styled with the Official Share Flow Brand System:
 * - Royal Blue (#1D59BB) primary container with high-contrast white ink.
 * - Selective Signature Gradient luminous edge highlight (Royal Blue -> Sky Blue -> Soft Cream -> Golden Yellow).
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
                color = ShareFlowBrandColors.SkyBlue.copy(alpha = 0.22f)
            )
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = ShareFlowBrandColors.RoyalBlue.copy(alpha = 0.28f),
            disabledContentColor = ShareFlowBrandColors.DeepNavy.copy(alpha = 0.55f)
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
                color = ShareFlowBrandColors.SoftCream
            )
            Spacer(modifier = Modifier.width(spacing.xs))
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (isInteractive) ShareFlowBrandColors.SoftCream else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
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
 * Secondary Material 3 action button styled with Sky Blue (#76B9FF) secondary accents,
 * translucent Liquid Glass depth, and crisp contrast.
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
        ShareFlowBrandColors.RoyalBlue
    }

    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = ShareFlowDesign.shapes.button,
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = ShareFlowBrandColors.GlassSurfaceLowest.copy(alpha = 0.85f),
                contentColor = contentColor,
                disabledContentColor = ShareFlowBrandColors.DeepNavy.copy(alpha = 0.42f)
            ),
            border = BorderStroke(
                width = 1.dp,
                color = when {
                    !enabled -> ShareFlowBrandColors.DeepNavy.copy(alpha = 0.16f)
                    isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
                    else -> ShareFlowBrandColors.SkyBlue
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
                    ShareFlowBrandColors.SkyBlue.copy(alpha = 0.34f)
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
                disabledContainerColor = ShareFlowBrandColors.GlassSurfaceHigh.copy(alpha = 0.65f),
                disabledContentColor = ShareFlowBrandColors.DeepNavy.copy(alpha = 0.42f)
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
