package com.example.shareflow.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Share Flow 2.0 Determinate Progress Indicator (`SFProgressIndicator`).
 * Smoothly animates transfer & storage progress with the official 4-color brand gradient
 * (Ruby #CA2851 -> Coral #FF6766 -> Peach #FFB173 -> Cream #FFE3B3) and a subtle glass rim.
 */
@Composable
fun SFProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    isError: Boolean = false
) {
    val clamped = progress.coerceIn(0f, 1f)
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val animatedProgress by animateFloatAsState(
        targetValue = clamped,
        animationSpec = motion.progressSpec(reducedMotion),
        label = "sf_progress_determinate"
    )
    val trackShape = ShareFlowDesign.shapes.progressTrack
    val glass = ShareFlowDesign.glass

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (glass.isDark) 0.72f else 0.85f)
            )
            .border(
                width = 0.75.dp,
                color = glass.borderMiddle,
                shape = trackShape
            )
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            }
    ) {
        if (animatedProgress > 0.005f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(trackShape)
                    .then(
                        if (isError) {
                            Modifier.background(MaterialTheme.colorScheme.error)
                        } else {
                            Modifier.background(ShareFlowBrandGradients.signatureHorizontal())
                        }
                    )
            )
        }
    }
}

/**
 * Share Flow 2.0 Indeterminate Progress Indicator (`SFProgressIndicator`).
 * Sweeps a luminous brand-gradient segment smoothly while respecting reduced-motion accessibility.
 */
@Composable
fun SFProgressIndicator(
    modifier: Modifier = Modifier,
    height: Dp = 6.dp
) {
    val trackShape = ShareFlowDesign.shapes.progressTrack
    val glass = ShareFlowDesign.glass
    val reducedMotion = ShareFlowDesign.reducedMotion

    val offsetFraction = if (reducedMotion) {
        0.28f
    } else {
        val transition = rememberInfiniteTransition(label = "sf_indeterminate_progress")
        val animated by transition.animateFloat(
            initialValue = -0.45f,
            targetValue = 1.05f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sf_indeterminate_offset"
        )
        animated
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(trackShape)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (glass.isDark) 0.72f else 0.85f)
            )
            .border(
                width = 0.75.dp,
                color = glass.borderMiddle,
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
 * Share Flow 2.0 Minimal Brand Loading Indicator (`SFLoadingIndicator`).
 * Smooth brand-gradient sweep ring with optional accessible status label and reduced-motion support.
 */
@Composable
fun SFLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
    indicatorSize: Dp = 44.dp
) {
    val spacing = ShareFlowDesign.spacing
    val reducedMotion = ShareFlowDesign.reducedMotion
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val haloColor = ShareFlowDesign.semanticColors.cream.copy(
        alpha = if (ShareFlowDesign.semanticColors.isDark) 0.12f else 0.40f
    )

    val rotation = if (reducedMotion) {
        45f
    } else {
        val infiniteTransition = rememberInfiniteTransition(label = "sf_loading_ring")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1050, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sf_loading_rotation"
        )
        animated
    }

    Column(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Canvas(modifier = Modifier.size(indicatorSize)) {
            val strokePx = 3.5.dp.toPx()
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
                    sweepAngle = 275f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }
        if (message != null) {
            Text(
                text = message.ifBlank { stringResource(R.string.ds_loading_default) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = spacing.md)
            )
        }
    }
}
