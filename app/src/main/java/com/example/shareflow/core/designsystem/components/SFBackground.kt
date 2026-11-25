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
 * Share Flow root canvas (`SFBackground`).
 *
 * - Light Mode (Pearl White): Calm warm-neutral Pearl White (#F8F7F4) canvas with zero pink or
 *   peach background gradients so pure white (#FFFFFF) cards separate cleanly with soft borders.
 * - Dark Mode (Deep Charcoal): Deep Charcoal (#171717) canvas — zero pure black (#000000),
 *   zero navy cast, and zero exaggerated ambient glows so #222222 cards remain sharp and legible.
 */
@Composable
fun SFBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val glass = ShareFlowDesign.glass
    val hasAmbientGlow = glass.ambientGlowPrimary.alpha > 0f ||
        glass.ambientGlowSecondary.alpha > 0f ||
        glass.ambientGlowTertiary.alpha > 0f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(
                if (hasAmbientGlow) {
                    Modifier.drawWithCache {
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
                            if (glass.ambientGlowPrimary.alpha > 0f) {
                                drawCircle(brush = primaryBrush, center = primaryCenter, radius = primaryRadius)
                            }
                            if (glass.ambientGlowSecondary.alpha > 0f) {
                                drawCircle(brush = secondaryBrush, center = secondaryCenter, radius = secondaryRadius)
                            }
                            if (glass.ambientGlowTertiary.alpha > 0f) {
                                drawCircle(brush = tertiaryBrush, center = tertiaryCenter, radius = tertiaryRadius)
                            }
                        }
                    }
                } else {
                    Modifier
                }
            ),
        content = content
    )
}
