package com.example.shareflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared 8dp-grid spacing and layout tokens for consistent Material 3 rhythm across modules.
 */
@Immutable
data class ShareFlowSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val screenHorizontal: Dp = 16.dp,
    val sectionGap: Dp = 20.dp,
    val cardPadding: Dp = 16.dp,
    val itemGap: Dp = 12.dp,
    val iconSmall: Dp = 18.dp,
    val iconMedium: Dp = 22.dp,
    val iconLarge: Dp = 28.dp,
    val minTouchTarget: Dp = 48.dp,
    val maxContentWidth: Dp = 720.dp
)

@Immutable
data class ShareFlowElevation(
    val flat: Dp = 0.dp,
    val card: Dp = 1.dp,
    val raised: Dp = 3.dp,
    val modal: Dp = 6.dp
)

val LocalShareFlowSpacing = staticCompositionLocalOf { ShareFlowSpacing() }
val LocalShareFlowElevation = staticCompositionLocalOf { ShareFlowElevation() }
