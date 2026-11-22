package com.example.shareflow.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Material 3 Shape scale for ShareFlow.
 */
val ShareFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * Semantic component shape tokens aligned with ShareFlow's rounded Material 3 surfaces.
 */
@Immutable
data class ShareFlowShapeTokens(
    val button: Shape = RoundedCornerShape(14.dp),
    val input: Shape = RoundedCornerShape(14.dp),
    val card: Shape = RoundedCornerShape(18.dp),
    val featuredCard: Shape = RoundedCornerShape(24.dp),
    val dialog: Shape = RoundedCornerShape(28.dp),
    val bottomSheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    val chip: Shape = RoundedCornerShape(12.dp),
    val badge: Shape = RoundedCornerShape(8.dp),
    val progressTrack: Shape = CircleShape,
    val avatar: Shape = CircleShape
)

val LocalShareFlowShapeTokens = staticCompositionLocalOf { ShareFlowShapeTokens() }
