package com.example.shareflow.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Share Flow 2.0 8dp-grid spacing & layout dimension tokens inspired by Samsung One UI
 * generous breathing room and Premium Minimal visual rhythm.
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
    val screenHorizontal: Dp = 18.dp,
    val sectionGap: Dp = 22.dp,
    val cardPadding: Dp = 16.dp,
    val featuredCardPadding: Dp = 20.dp,
    val itemGap: Dp = 12.dp,
    val iconSmall: Dp = 18.dp,
    val iconMedium: Dp = 22.dp,
    val iconLarge: Dp = 28.dp,
    val minTouchTarget: Dp = 48.dp,
    val floatingNavHeight: Dp = 68.dp,
    val maxContentWidth: Dp = 720.dp
)

typealias SFDimensions = ShareFlowSpacing

val LocalShareFlowSpacing = staticCompositionLocalOf { ShareFlowSpacing() }
