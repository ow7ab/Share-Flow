package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
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
 *   & Peach ambient luminance — zero pure black, zero harsh gradients, zero per-frame allocations
 *   via `drawWithCache`.
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
            .drawWithCache {
                val primaryCenter = Offset(size.width * 0.18f, size.height * 0.08f)
                val primaryRadius = (size.maxDimension * 0.56f).coerceAtLeast(1f)
                val primaryBrush = Brush.radialGradient(
                    colors = listOf(glass.ambientGlowPrimary, Color.Transparent),
                    center = primaryCenter,
                    radius = primaryRadius
                )

                val secondaryCenter = Offset(size.width * 0.88f, size.height * 0.34f)
                val secondaryRadius = (size.maxDimension * 0.48f).coerceAtLeast(1f)
                val secondaryBrush = Brush.radialGradient(
                    colors = listOf(glass.ambientGlowSecondary, Color.Transparent),
                    center = secondaryCenter,
                    radius = secondaryRadius
                )

                val tertiaryCenter = Offset(size.width * 0.50f, size.height * 0.94f)
                val tertiaryRadius = (size.maxDimension * 0.52f).coerceAtLeast(1f)
                val tertiaryBrush = Brush.radialGradient(
                    colors = listOf(glass.ambientGlowTertiary, Color.Transparent),
                    center = tertiaryCenter,
                    radius = tertiaryRadius
                )

                onDrawBehind {
                    drawCircle(brush = primaryBrush, center = primaryCenter, radius = primaryRadius)
                    drawCircle(brush = secondaryBrush, center = secondaryCenter, radius = secondaryRadius)
                    drawCircle(brush = tertiaryBrush, center = tertiaryCenter, radius = tertiaryRadius)
                }
            },
        content = content
    )
}
