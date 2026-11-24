package com.example.shareflow.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Share Flow 2.0 shape scale — smooth One UI + Apple Liquid Glass continuous rounded geometry.
 */
val ShareFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/**
 * Semantic component shape tokens for Share Flow 2.0 Liquid Glass & One UI surfaces.
 */
@Immutable
data class ShareFlowShapeTokens(
    val button: Shape = RoundedCornerShape(16.dp),
    val input: Shape = RoundedCornerShape(16.dp),
    val card: Shape = RoundedCornerShape(22.dp),
    val featuredCard: Shape = RoundedCornerShape(28.dp),
    val dialog: Shape = RoundedCornerShape(28.dp),
    val bottomSheet: Shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    val navCapsule: Shape = RoundedCornerShape(32.dp),
    val navPill: Shape = RoundedCornerShape(22.dp),
    val chip: Shape = RoundedCornerShape(14.dp),
    val badge: Shape = RoundedCornerShape(10.dp),
    val iconContainer: Shape = RoundedCornerShape(14.dp),
    val snackbar: Shape = RoundedCornerShape(18.dp),
    val progressTrack: Shape = CircleShape,
    val avatar: Shape = CircleShape
)

typealias SFShapeTokens = ShareFlowShapeTokens

val LocalShareFlowShapeTokens = staticCompositionLocalOf { ShareFlowShapeTokens() }
