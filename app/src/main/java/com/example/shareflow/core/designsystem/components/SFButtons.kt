package com.example.shareflow.core.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors

/**
 * Share Flow 2.0 Primary Brand Action Button (`SFPrimaryButton`).
 * Features the official Ruby (#CA2851) -> Coral (#FF6766) gradient fill, delicate top specular edge,
 * soft brand shadow, 48dp minimum touch target, and responsive spring press physics.
 */
@Composable
fun SFPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = ShareFlowDesign.shapes.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 12.dp)
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val glass = ShareFlowDesign.glass
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val isInteractive = enabled && !isLoading

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && isInteractive && !reducedMotion) motion.pressScale else 1f,
        animationSpec = motion.buttonSpring(),
        label = "sf_primary_btn_scale"
    )

    val contentColor = if (isInteractive) Color.White else semantic.onDisabled

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = spacing.minTouchTarget)
            .minimumInteractiveComponentSize()
            .shadow(
                elevation = if (isInteractive) 6.dp else 0.dp,
                shape = shape,
                clip = false,
                ambientColor = glass.ambientShadow,
                spotColor = ShareFlowMarpleColors.Ruby.copy(alpha = 0.28f)
            )
            .clip(shape)
            .then(
                if (isInteractive) {
                    Modifier.background(brush = ShareFlowBrandGradients.primaryButtonGradient())
                } else {
                    Modifier.background(color = semantic.disabled)
                }
            )
            .border(
                border = if (isInteractive) {
                    BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.42f),
                                ShareFlowMarpleColors.Cream.copy(alpha = 0.14f)
                            )
                        )
                    )
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                },
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                enabled = isInteractive,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .padding(contentPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = contentColor
                )
                Spacer(modifier = Modifier.width(spacing.xs))
            } else if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(spacing.iconSmall)
                )
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Share Flow 2.0 Secondary Liquid Glass Action Button (`SFSecondaryButton`).
 * Translucent glass surface with crisp rim highlight and Coral/Ruby or destructive semantic tinting.
 */
@Composable
fun SFSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = true,
    isDestructive: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = ShareFlowDesign.shapes.button,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val glass = ShareFlowDesign.glass
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !reducedMotion) motion.pressScale else 1f,
        animationSpec = motion.buttonSpring(),
        label = "sf_secondary_btn_scale"
    )

    val containerColor = when {
        !enabled -> semantic.disabled.copy(alpha = 0.55f)
        isDestructive && !outlined -> MaterialTheme.colorScheme.errorContainer
        isDestructive -> glass.cardBackground
        !outlined -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
        else -> glass.cardBackground
    }

    val contentColor = when {
        !enabled -> semantic.onDisabled
        isDestructive && !outlined -> MaterialTheme.colorScheme.onErrorContainer
        isDestructive -> MaterialTheme.colorScheme.error
        !outlined -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.primary
    }

    val borderStroke = when {
        !enabled -> BorderStroke(1.dp, semantic.disabled)
        isDestructive -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.52f))
        outlined -> BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(
                    glass.borderTop,
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
                )
            )
        )
        else -> BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f))
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .heightIn(min = spacing.minTouchTarget)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(containerColor)
            .border(border = borderStroke, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = contentColor),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .padding(contentPadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(spacing.iconSmall)
                )
                Spacer(modifier = Modifier.width(spacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Share Flow 2.0 Frosted Glass Icon Button (`SFIconButton`).
 * Always enforces a 48dp accessible touch target with soft frosted background and spring press feedback.
 */
@Composable
fun SFIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color? = null,
    shape: Shape = CircleShape,
    iconSize: Dp = 20.dp,
    visualSize: Dp = 42.dp
) {
    val glass = ShareFlowDesign.glass
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !reducedMotion) motion.iconPressScale else 1f,
        animationSpec = motion.buttonSpring(),
        label = "sf_icon_btn_scale"
    )
    val resolvedContainer = containerColor ?: glass.cardBackground

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics {
                this.contentDescription = contentDescription
                this.role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, radius = 24.dp, color = tint),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(visualSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(resolvedContainer)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                    shape = shape
                )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else ShareFlowDesign.semanticColors.onDisabled,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}
