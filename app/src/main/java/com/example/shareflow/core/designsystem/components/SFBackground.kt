package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Share Flow 2.0 root atmospheric canvas (`SFBackground`).
 *
 * - Light Mode: Clean bright warm-neutral base with restrained cream (#FFE3B3) & peach (#FFB173)
 *   ambient light halos so translucent Liquid Glass cards and floating navigation refract naturally.
 * - Dark Mode: Deep Navy (#0D1220) canvas with subtle midnight-sapphire depth and controlled Ruby
 *   & Peach ambient luminance — zero pure black, zero harsh gradients, zero scroll allocations.
 */
@Composable
fun SFBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val glass = ShareFlowDesign.glass

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawBehind {
                // Top-start warm ambient halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glass.ambientGlowPrimary, Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.08f),
                        radius = size.maxDimension * 0.56f
                    ),
                    center = Offset(size.width * 0.18f, size.height * 0.08f),
                    radius = size.maxDimension * 0.56f
                )
                // Mid-end subtle depth wash
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glass.ambientGlowSecondary, Color.Transparent),
                        center = Offset(size.width * 0.88f, size.height * 0.34f),
                        radius = size.maxDimension * 0.48f
                    ),
                    center = Offset(size.width * 0.88f, size.height * 0.34f),
                    radius = size.maxDimension * 0.48f
                )
                // Bottom ambient glow behind floating Liquid Glass bottom bar
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glass.ambientGlowTertiary, Color.Transparent),
                        center = Offset(size.width * 0.50f, size.height * 0.94f),
                        radius = size.maxDimension * 0.52f
                    ),
                    center = Offset(size.width * 0.50f, size.height * 0.94f),
                    radius = size.maxDimension * 0.52f
                )
            },
        content = content
    )
}
