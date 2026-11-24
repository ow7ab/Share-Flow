package com.example.shareflow.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/**
 * Share Flow 2.0 Motion System — purposeful, restrained, spring-driven micro-interactions
 * tuned for 60Hz–120Hz displays and reduced-motion accessibility compliance.
 */
@Immutable
data class SFMotionTokens(
    val durationQuickMs: Int = 150,
    val durationStandardMs: Int = 220,
    val durationGentleMs: Int = 300,
    val pressScale: Float = 0.97f,
    val iconPressScale: Float = 0.93f,
    val navSelectedIconScale: Float = 1.08f
) {
    fun <T> buttonSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun <T> navSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun <T> gentleSpring(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    fun progressSpec(reducedMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reducedMotion) {
            tween(durationMillis = 0)
        } else {
            tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing)
        }

    fun dpSpec(reducedMotion: Boolean): FiniteAnimationSpec<Dp> =
        if (reducedMotion) {
            tween(durationMillis = 0)
        } else {
            navSpring()
        }

    fun screenEnter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) {
            fadeIn(tween(durationMillis = 0))
        } else {
            fadeIn(tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> fullWidth / 16 }
                )
        }

    fun screenExit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) {
            fadeOut(tween(durationMillis = 0))
        } else {
            fadeOut(tween(durationMillis = durationQuickMs, easing = FastOutSlowInEasing))
        }

    fun screenPopEnter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) {
            fadeIn(tween(durationMillis = 0))
        } else {
            fadeIn(tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing),
                    initialOffsetX = { fullWidth -> -fullWidth / 16 }
                )
        }

    fun screenPopExit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) {
            fadeOut(tween(durationMillis = 0))
        } else {
            fadeOut(tween(durationMillis = durationQuickMs, easing = FastOutSlowInEasing)) +
                slideOutHorizontally(
                    animationSpec = tween(durationMillis = durationQuickMs, easing = FastOutSlowInEasing),
                    targetOffsetX = { fullWidth -> fullWidth / 18 }
                )
        }

    fun overlayEnter(reducedMotion: Boolean): EnterTransition =
        if (reducedMotion) {
            fadeIn(tween(durationMillis = 0))
        } else {
            fadeIn(tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing)) +
                scaleIn(
                    initialScale = 0.94f,
                    animationSpec = tween(durationMillis = durationStandardMs, easing = FastOutSlowInEasing)
                )
        }

    fun overlayExit(reducedMotion: Boolean): ExitTransition =
        if (reducedMotion) {
            fadeOut(tween(durationMillis = 0))
        } else {
            fadeOut(tween(durationMillis = durationQuickMs)) +
                scaleOut(targetScale = 0.96f, animationSpec = tween(durationMillis = durationQuickMs))
        }
}

val LocalSFMotion = staticCompositionLocalOf { SFMotionTokens() }
val LocalSFReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads Android's system accessibility animator scale cleanly so Share Flow 2.0 respects
 * "Remove animations" / reduced motion preferences without crashing in unit/Robolectric tests.
 */
@Composable
fun rememberReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            scale == 0f
        }.getOrDefault(false)
    }
}
