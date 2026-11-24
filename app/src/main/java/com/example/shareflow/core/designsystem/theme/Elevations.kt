package com.example.shareflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Share Flow 2.0 elevation, shadow depth, and glass specular stroke tokens.
 * Keeps shadows soft and layered rather than harsh dark drop-shadows.
 */
@Immutable
data class ShareFlowElevation(
    val flat: Dp = 0.dp,
    val card: Dp = 2.dp,
    val glassCard: Dp = 6.dp,
    val raised: Dp = 8.dp,
    val floatingNav: Dp = 16.dp,
    val modal: Dp = 18.dp,
    val glassBorderWidth: Dp = 1.dp,
    val activeBorderWidth: Dp = 1.5.dp,
    val blurRadiusLight: Dp = 18.dp,
    val blurRadiusNav: Dp = 24.dp
)

typealias SFElevations = ShareFlowElevation

val LocalShareFlowElevation = staticCompositionLocalOf { ShareFlowElevation() }
