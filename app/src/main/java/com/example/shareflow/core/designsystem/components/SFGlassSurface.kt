package com.example.shareflow.core.designsystem.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Share Flow 2.0 Liquid Glass Card (`SFGlassCard`).
 *
 * Combines Apple Liquid Glass translucency + specular top-to-bottom rim highlight with
 * Samsung One UI generous rounded geometry and Premium Minimal restraint.
 *
 * Performance & 120Hz Scroll Optimization:
 * - Selective backdrop blur (`enableBackdropBlur = true`) uses GPU-accelerated `Modifier.blur`
 *   ONLY on Android 12+ (API 31+) and ONLY on the background layer so text & icons stay razor-sharp.
 * - For scrolling list cards (`enableBackdropBlur = false`), renders base glass + specular top
 *   glaze in a single `drawBehind` pass directly on the `Column` (zero extra `matchParentSize`
 *   child Boxes and zero offscreen shadow layer unless `elevated = true` or explicit `shadowElevation`).
 * - Skips `MutableInteractionSource` / `animateFloatAsState` / `graphicsLayer` allocations
 *   entirely when `onClick == null`.
 */
@Composable
fun SFGlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    elevated: Boolean = false,
    enableBackdropBlur: Boolean = false,
    shape: Shape = ShareFlowDesign.shapes.card,
    containerColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    shadowElevation: Dp? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = ShareFlowDesign.glass
    val elevation = ShareFlowDesign.elevation
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val supportsBlur = enableBackdropBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val resolvedBg = containerColor ?: when {
        elevated -> glass.cardElevatedBackground
        else -> glass.cardBackground
    }
    // Avoid expensive per-item offscreen shadow layers on standard scrolling list cards
    val resolvedShadow = shadowElevation ?: if (elevated) elevation.glassCard else 0.dp

    val borderStroke = remember(selected, glass) {
        if (selected) {
            BorderStroke(
                width = elevation.activeBorderWidth,
                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.85f)
            )
        } else {
            BorderStroke(
                width = elevation.glassBorderWidth,
                brush = Brush.verticalGradient(
                    colors = listOf(glass.borderTop, glass.borderMiddle, glass.borderBottom)
                )
            )
        }
    }

    val glazeBrush = remember(glass) {
        Brush.verticalGradient(
            colors = listOf(
                glass.topGlaze,
                Color.Transparent,
                glass.bottomGlaze
            )
        )
    }

    val interactiveModifier = if (onClick != null) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val pressScale by animateFloatAsState(
            targetValue = if (isPressed && enabled && !reducedMotion) motion.pressScale else 1f,
            animationSpec = motion.buttonSpring(),
            label = "sf_glass_card_press"
        )
        Modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .then(
                if (resolvedShadow > 0.dp) {
                    Modifier.shadow(
                        elevation = resolvedShadow,
                        shape = shape,
                        clip = false,
                        ambientColor = glass.ambientShadow,
                        spotColor = if (selected) glass.spotShadow else glass.ambientShadow
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .border(border = borderStroke, shape = shape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = glass.activeBorder),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
    } else {
        Modifier
            .then(
                if (resolvedShadow > 0.dp) {
                    Modifier.shadow(
                        elevation = resolvedShadow,
                        shape = shape,
                        clip = false,
                        ambientColor = glass.ambientShadow,
                        spotColor = if (selected) glass.spotShadow else glass.ambientShadow
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .border(border = borderStroke, shape = shape)
    }

    if (supportsBlur) {
        Box(modifier = modifier.then(interactiveModifier)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .blur(
                        radiusX = elevation.blurRadiusLight,
                        radiusY = elevation.blurRadiusLight,
                        edgeTreatment = BlurredEdgeTreatment.Rectangle
                    )
                    .background(resolvedBg)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = glazeBrush)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content
            )
        }
    } else {
        // Fast single-node path for scrolling lists & API 24-30: zero matchParentSize overhead
        Column(
            modifier = modifier
                .then(interactiveModifier)
                .drawBehind {
                    drawRect(color = resolvedBg)
                    drawRect(brush = glazeBrush)
                }
                .padding(contentPadding),
            content = content
        )
    }
}
