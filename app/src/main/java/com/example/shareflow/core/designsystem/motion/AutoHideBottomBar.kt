package com.example.shareflow.core.designsystem.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Smart Auto-Hide Bottom Navigation Bar controller.
 *
 * Guarantees jitter-free, gesture-accurate hide/reveal behavior:
 * - Tracks real vertical user scroll only ([NestedScrollSource.UserInput]), ignoring taps/clicks.
 * - Applies hysteresis thresholds ([hideThresholdPx] downward, [showThresholdPx] upward) so
 *   minor finger tremors never cause flickering.
 * - Immediately reveals the bar when reaching the top of a scrollable container.
 * - Automatically reveals the bar after a brief idle pause ([idleRevealDelayMs]) when hidden.
 * - Immediately reveals on tab switches, route transitions, or returning from sub-screens.
 */
@Stable
class AutoHideBottomBarState(
    private val hideThresholdPx: Float,
    private val showThresholdPx: Float,
    val idleRevealDelayMs: Long = DEFAULT_IDLE_REVEAL_DELAY_MS
) {
    var isVisible: Boolean by mutableStateOf(true)
        private set

    internal var lastScrollSignal: Long by mutableLongStateOf(0L)
        private set

    private var accumulatedDeltaY: Float = 0f

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                onUserScrollDelta(available.y)
            }
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            if (source == NestedScrollSource.UserInput && available.y > TOP_OVERSCROLL_REVEAL_EPSILON_PX) {
                // Reached top boundary while pulling downward -> ensure bottom bar is visible.
                showImmediately()
            }
            return Offset.Zero
        }
    }

    /**
     * Processes a vertical user scroll delta in pixels:
     * - Negative [deltaY] = finger moving up / content scrolling down -> hide after [hideThresholdPx].
     * - Positive [deltaY] = finger moving down / content scrolling up -> show after [showThresholdPx].
     */
    fun onUserScrollDelta(deltaY: Float) {
        if (kotlin.math.abs(deltaY) < MIN_NOISE_DELTA_PX) return

        // Reset directional accumulator whenever scroll direction reverses
        if ((deltaY < 0f && accumulatedDeltaY > 0f) || (deltaY > 0f && accumulatedDeltaY < 0f)) {
            accumulatedDeltaY = 0f
        }
        accumulatedDeltaY += deltaY
        lastScrollSignal += 1L

        if (accumulatedDeltaY <= -hideThresholdPx) {
            if (isVisible) {
                isVisible = false
            }
            accumulatedDeltaY = 0f
        } else if (accumulatedDeltaY >= showThresholdPx) {
            if (!isVisible) {
                isVisible = true
            }
            accumulatedDeltaY = 0f
        }
    }

    /**
     * Immediately reveals the navigation bar and clears any accumulated downward drag.
     * Invoked on tab switches, route navigation, top-of-page arrival, and idle timer expiry.
     */
    fun showImmediately() {
        accumulatedDeltaY = 0f
        if (!isVisible) {
            isVisible = true
        }
    }

    /**
     * Called when the idle timer expires without further scroll gestures.
     */
    fun onIdleRevealTriggered() {
        if (!isVisible) {
            accumulatedDeltaY = 0f
            isVisible = true
        }
    }

    companion object {
        val DEFAULT_HIDE_THRESHOLD: Dp = 24.dp
        val DEFAULT_SHOW_THRESHOLD: Dp = 18.dp
        const val DEFAULT_IDLE_REVEAL_DELAY_MS: Long = 2_400L
        private const val MIN_NOISE_DELTA_PX: Float = 0.75f
        private const val TOP_OVERSCROLL_REVEAL_EPSILON_PX: Float = 2.0f
    }
}

@Composable
fun rememberAutoHideBottomBarState(
    routeKey: Any?,
    enabled: Boolean = true,
    hideThreshold: Dp = AutoHideBottomBarState.DEFAULT_HIDE_THRESHOLD,
    showThreshold: Dp = AutoHideBottomBarState.DEFAULT_SHOW_THRESHOLD,
    idleRevealDelayMs: Long = AutoHideBottomBarState.DEFAULT_IDLE_REVEAL_DELAY_MS
): AutoHideBottomBarState {
    val density = LocalDensity.current
    val hidePx = with(density) { hideThreshold.toPx() }
    val showPx = with(density) { showThreshold.toPx() }

    val state = remember(hidePx, showPx, idleRevealDelayMs) {
        AutoHideBottomBarState(
            hideThresholdPx = hidePx,
            showThresholdPx = showPx,
            idleRevealDelayMs = idleRevealDelayMs
        )
    }

    // Always restore bottom bar immediately on tab switch, screen transition, or when disabled
    LaunchedEffect(routeKey, enabled) {
        state.showImmediately()
    }

    // Gently auto-reveal after a calm idle period if user stopped scrolling mid-page
    LaunchedEffect(enabled, state.isVisible, state.lastScrollSignal) {
        if (enabled && !state.isVisible && state.idleRevealDelayMs > 0L) {
            delay(state.idleRevealDelayMs)
            state.onIdleRevealTriggered()
        }
    }

    return state
}
