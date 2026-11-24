package com.example.shareflow.core.designsystem.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Pure, frame-rate-independent physics model for ShareFlow's One UI-inspired Elastic Pull-Down
 * overscroll motion.
 *
 * Designed for 60Hz, 90Hz, and 120Hz displays with zero allocations on the hot drag path:
 * - Hyperbolic asymptotic rubber-band resistance (`c * x * D / (D + c * x)`) provides crisp
 *   initial finger tracking (`c = 0.55`) and progressive resistance without unnatural stretching.
 * - Critically-adjacent spring return (`stiffness = 380f`, `dampingRatio = 0.86f`) delivers a
 *   swift, organic settle back to `0f` with no repetitive bounce or jitter.
 */
object ElasticOverscrollPhysics {
    const val RESISTANCE_COEFFICIENT: Float = 0.55f
    const val MAX_PULL_VIEWPORT_RATIO: Float = 0.24f
    const val MIN_MAX_PULL_DP: Float = 96f
    const val DEFAULT_MAX_PULL_DP: Float = 168f

    const val RETURN_SPRING_STIFFNESS: Float = 380f
    const val RETURN_SPRING_DAMPING_RATIO: Float = 0.86f
    const val VISIBILITY_THRESHOLD_PX: Float = 0.5f

    const val HORIZONTAL_INTENT_SLOPE_RATIO: Float = 1.2f
    const val MIN_FLING_OVERSHOOT_VELOCITY_PX: Float = 520f
    const val FLING_OVERSHOOT_VELOCITY_FACTOR: Float = 0.018f
    const val MAX_FLING_OVERSHOOT_RATIO: Float = 0.26f

    /**
     * Computes the maximum visual pull-down distance in pixels for a given container height.
     */
    fun computeMaxPullPx(
        containerHeightPx: Float,
        minPullPx: Float,
        maxPullCapPx: Float
    ): Float {
        if (containerHeightPx <= 0f) return maxPullCapPx.coerceAtLeast(minPullPx)
        val proportional = containerHeightPx * MAX_PULL_VIEWPORT_RATIO
        return proportional.coerceIn(minPullPx, maxPullCapPx.coerceAtLeast(minPullPx))
    }

    /**
     * Maps raw downward overscroll distance (`rawPullPx >= 0`) to visually displaced pixels using
     * a smooth, strictly monotonic asymptotic rubber-band curve bounded by `maxPullPx`.
     */
    fun computeDampedOffsetPx(
        rawPullPx: Float,
        maxPullPx: Float,
        resistanceCoefficient: Float = RESISTANCE_COEFFICIENT
    ): Float {
        if (rawPullPx <= 0f || maxPullPx <= 0f || resistanceCoefficient <= 0f) return 0f
        val scaled = rawPullPx * resistanceCoefficient
        return (scaled * maxPullPx) / (maxPullPx + scaled)
    }

    /**
     * Returns true when a gesture delta is predominantly vertical rather than an intentional
     * horizontal swipe (e.g., pager swipe, carousel scroll, slider, or edge navigation).
     */
    fun isPredominantlyVertical(dx: Float, dy: Float): Boolean {
        val absY = abs(dy)
        val absX = abs(dx)
        return absY > 0f && absY >= absX * HORIZONTAL_INTENT_SLOPE_RATIO
    }

    /**
     * Converts residual downward fling velocity arriving at the top edge into a gentle,
     * tightly bounded cushion displacement.
     */
    fun computeFlingOvershootPx(
        availableVelocityY: Float,
        maxPullPx: Float
    ): Float {
        if (availableVelocityY <= MIN_FLING_OVERSHOOT_VELOCITY_PX || maxPullPx <= 0f) return 0f
        val excessVelocity = availableVelocityY - MIN_FLING_OVERSHOOT_VELOCITY_PX
        val target = excessVelocity * FLING_OVERSHOOT_VELOCITY_FACTOR
        val cap = maxPullPx * MAX_FLING_OVERSHOOT_RATIO
        return min(target, cap)
    }

    fun returnSpringSpec(): SpringSpec<Float> = spring(
        dampingRatio = RETURN_SPRING_DAMPING_RATIO,
        stiffness = RETURN_SPRING_STIFFNESS,
        visibilityThreshold = VISIBILITY_THRESHOLD_PX
    )
}

/**
 * Reusable state holder managing ShareFlow's GPU-composited elastic pull-down animation.
 *
 * Note: [offsetPx] is backed by [Animatable.value] and should be read exclusively inside
 * `Modifier.graphicsLayer { translationY = state.offsetPx }` so drag and 120Hz spring frames
 * never trigger Jetpack Compose recomposition or relayout.
 */
@Stable
class ElasticOverscrollState(
    private val scope: CoroutineScope,
    initialMaxPullPx: Float
) {
    private val offsetAnimatable = Animatable(0f)
    private var activeSpringJob: Job? = null

    internal var maxPullPx: Float = initialMaxPullPx.coerceAtLeast(1f)
    internal var rawPullPx: Float = 0f
        private set

    /**
     * Current vertical translation in pixels. Read only in the Draw / Layer phase (`graphicsLayer`)
     * to guarantee 0 recompositions per frame during dragging and spring recovery.
     */
    val offsetPx: Float
        get() = offsetAnimatable.value

    val isPulled: Boolean
        get() = rawPullPx > 0f || offsetAnimatable.value > ElasticOverscrollPhysics.VISIBILITY_THRESHOLD_PX

    /**
     * Consumes upward drag deltas while the page is already pulled down, smoothly tracking
     * the finger back toward `0f` before allowing the underlying list to scroll.
     */
    fun consumeUpwardDrag(deltaY: Float): Float {
        if (deltaY >= 0f || rawPullPx <= 0f) return 0f
        cancelSpring()
        val previousRaw = rawPullPx
        val nextRaw = (previousRaw + deltaY).coerceAtLeast(0f)
        val consumedRaw = nextRaw - previousRaw // negative value
        rawPullPx = nextRaw
        snapToCurrentRaw()
        return consumedRaw
    }

    /**
     * Applies downward overscroll delta from user input and updates the GPU translation
     * synchronously for zero finger-to-frame latency.
     */
    fun applyDownwardPull(deltaY: Float): Float {
        if (deltaY <= 0f) return 0f
        cancelSpring()
        rawPullPx += deltaY
        snapToCurrentRaw()
        return deltaY
    }

    /**
     * Releases the pull and returns the page smoothly to `0f` using calibrated spring physics.
     */
    fun animateReturnToRest(initialVelocityPxPerSec: Float = 0f) {
        if (rawPullPx <= 0f && offsetAnimatable.value <= ElasticOverscrollPhysics.VISIBILITY_THRESHOLD_PX) {
            rawPullPx = 0f
            return
        }
        rawPullPx = 0f
        cancelSpring()
        activeSpringJob = scope.launch {
            try {
                // Dampen outgoing velocity so a fast release snaps smoothly without harsh bounce
                val clampedVelocity = (initialVelocityPxPerSec * 0.25f).coerceIn(-1400f, 600f)
                offsetAnimatable.animateTo(
                    targetValue = 0f,
                    initialVelocity = clampedVelocity,
                    animationSpec = ElasticOverscrollPhysics.returnSpringSpec()
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /**
     * Applies a subtle cushioned overshoot when a fast downward fling reaches the top boundary,
     * then settles smoothly back to `0f`.
     */
    fun animateFlingOvershoot(availableVelocityY: Float) {
        if (rawPullPx > 0f) {
            animateReturnToRest(initialVelocityPxPerSec = availableVelocityY)
            return
        }
        val overshootPx = ElasticOverscrollPhysics.computeFlingOvershootPx(
            availableVelocityY = availableVelocityY,
            maxPullPx = maxPullPx
        )
        if (overshootPx <= ElasticOverscrollPhysics.VISIBILITY_THRESHOLD_PX) return

        cancelSpring()
        activeSpringJob = scope.launch {
            try {
                offsetAnimatable.animateTo(
                    targetValue = overshootPx,
                    animationSpec = spring(
                        dampingRatio = 1.0f,
                        stiffness = 750f,
                        visibilityThreshold = ElasticOverscrollPhysics.VISIBILITY_THRESHOLD_PX
                    )
                )
                offsetAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = ElasticOverscrollPhysics.returnSpringSpec()
                )
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    /**
     * Immediately resets displacement to `0f` (used on navigation transitions or Reduce Motion toggle).
     */
    fun resetImmediately() {
        cancelSpring()
        rawPullPx = 0f
        if (offsetAnimatable.value != 0f) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { offsetAnimatable.snapTo(0f) }
            }
        }
    }

    /**
     * Safety-net release after pointer cancellation or non-fling lift without racing [NestedScrollConnection.onPreFling].
     */
    internal fun ensureReleasedAfterFrame() {
        scope.launch {
            yield()
            if (rawPullPx > 0f) {
                animateReturnToRest()
            }
        }
    }

    private fun snapToCurrentRaw() {
        val target = ElasticOverscrollPhysics.computeDampedOffsetPx(
            rawPullPx = rawPullPx,
            maxPullPx = maxPullPx
        )
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { offsetAnimatable.snapTo(target) }
        }
    }

    private fun cancelSpring() {
        activeSpringJob?.cancel()
        activeSpringJob = null
    }
}

/**
 * Checks whether the Android system "Remove animations" / "Reduce motion" preference is active
 * (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`).
 */
fun isSystemReduceMotionEnabled(context: Context): Boolean = runCatching {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    )
    scale <= 0f
}.getOrDefault(false)

/**
 * Observes the system Reduce Motion setting and re-evaluates whenever the Activity resumes.
 */
@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var reduceMotion by remember(context) {
        mutableStateOf(isSystemReduceMotionEnabled(context))
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reduceMotion = isSystemReduceMotionEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    return reduceMotion
}

@Composable
fun rememberElasticOverscrollState(
    maxPullCap: Dp = ElasticOverscrollPhysics.DEFAULT_MAX_PULL_DP.dp
): ElasticOverscrollState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val initialMaxPullPx = remember(density, maxPullCap) {
        with(density) { maxPullCap.toPx() }
    }
    return remember(scope, initialMaxPullPx) {
        ElasticOverscrollState(
            scope = scope,
            initialMaxPullPx = initialMaxPullPx
        )
    }
}

/**
 * Creates a [NestedScrollConnection] coordinating cleanly with `LazyColumn`, `LazyVerticalGrid`,
 * and `verticalScroll` containers while filtering out horizontal swipe intents.
 */
internal fun createElasticOverscrollNestedScrollConnection(
    state: ElasticOverscrollState,
    isEnabled: () -> Boolean
): NestedScrollConnection = object : NestedScrollConnection {

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isEnabled()) return Offset.Zero
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (state.rawPullPx > 0f && available.y < 0f) {
            val consumedY = state.consumeUpwardDrag(available.y)
            return Offset(x = 0f, y = consumedY)
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (!isEnabled()) return Offset.Zero
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y > 0f) {
            val alreadyPulled = state.rawPullPx > 0f
            if (alreadyPulled || ElasticOverscrollPhysics.isPredominantlyVertical(available.x, available.y)) {
                val consumedY = state.applyDownwardPull(available.y)
                return Offset(x = 0f, y = consumedY)
            }
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!isEnabled()) return Velocity.Zero
        if (state.rawPullPx > 0f) {
            state.animateReturnToRest(initialVelocityPxPerSec = available.y)
            return Velocity(x = 0f, y = available.y)
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!isEnabled()) return Velocity.Zero
        if (state.rawPullPx > 0f) {
            state.animateReturnToRest(initialVelocityPxPerSec = available.y)
            return Velocity(x = 0f, y = available.y)
        }
        if (available.y > ElasticOverscrollPhysics.MIN_FLING_OVERSHOOT_VELOCITY_PX &&
            ElasticOverscrollPhysics.isPredominantlyVertical(available.x, available.y)
        ) {
            state.animateFlingOvershoot(availableVelocityY = available.y)
        }
        return Velocity.Zero
    }
}

/**
 * Reusable ShareFlow One UI-inspired Elastic Pull-Down / Spring Overscroll container.
 *
 * Wrap a screen or navigation host once to enable:
 * - Whole-page elastic pull-down with non-linear hyperbolic resistance and theme-matched empty
 *   top reveal space.
 * - GPU-accelerated `graphicsLayer { translationY }` updates with zero recompositions per frame.
 * - Native support for long scrollable lists (`LazyColumn`, `LazyVerticalGrid`, `verticalScroll`)
 *   AND short/non-scrollable pages without interfering with taps, buttons, dialogs, text fields,
 *   or horizontal swipes.
 * - Automatic compliance with Android system Reduce Motion settings and instant reset on route change.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ElasticOverscrollContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    resetKey: Any? = Unit,
    revealBackgroundColor: Color = MaterialTheme.colorScheme.background,
    state: ElasticOverscrollState = rememberElasticOverscrollState(),
    content: @Composable () -> Unit
) {
    val reduceMotionEnabled = rememberReduceMotionEnabled()
    val effectiveEnabled = enabled && !reduceMotionEnabled
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val minPullPx = remember(density) { with(density) { ElasticOverscrollPhysics.MIN_MAX_PULL_DP.dp.toPx() } }
    val maxPullCapPx = remember(density) { with(density) { ElasticOverscrollPhysics.DEFAULT_MAX_PULL_DP.dp.toPx() } }

    DisposableEffect(state, resetKey, effectiveEnabled) {
        state.resetImmediately()
        onDispose {}
    }

    val nestedScrollConnection = remember(state, effectiveEnabled) {
        createElasticOverscrollNestedScrollConnection(
            state = state,
            isEnabled = { effectiveEnabled }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(revealBackgroundColor)
            .clipToBounds()
            .onSizeChanged { size ->
                val heightPx = size.height.toFloat()
                if (heightPx > 0f) {
                    state.maxPullPx = ElasticOverscrollPhysics.computeMaxPullPx(
                        containerHeightPx = heightPx,
                        minPullPx = minPullPx,
                        maxPullCapPx = maxPullCapPx
                    )
                }
            }
            .nestedScroll(nestedScrollConnection)
            // Non-interfering Final-pass fallback:
            // 1. Enables elastic pull-down on short/non-scrollable pages where no child consumed vertical drag.
            // 2. Guarantees spring release if any pointer sequence ends or is cancelled while stretched.
            .pointerInput(state, effectiveEnabled, resetKey, touchSlop) {
                if (!effectiveEnabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    var activePointerId = down.id
                    var totalDx = 0f
                    var totalDy = 0f
                    var fallbackDragging = false

                    while (true) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Final)
                        val trackedChange = event.changes.firstOrNull { it.id == activePointerId }
                            ?: event.changes.firstOrNull()

                        val anyPressed = event.changes.any { it.pressed }
                        if (!anyPressed || trackedChange == null) {
                            if (fallbackDragging) {
                                state.animateReturnToRest()
                            } else if (state.rawPullPx > 0f) {
                                state.ensureReleasedAfterFrame()
                            }
                            break
                        }

                        activePointerId = trackedChange.id
                        if (!trackedChange.isConsumed) {
                            val delta = trackedChange.positionChange()
                            if (delta != Offset.Zero) {
                                totalDx += delta.x
                                totalDy += delta.y
                                if (!fallbackDragging) {
                                    if (totalDy > touchSlop &&
                                        ElasticOverscrollPhysics.isPredominantlyVertical(totalDx, totalDy)
                                    ) {
                                        fallbackDragging = true
                                        state.applyDownwardPull(totalDy - touchSlop)
                                    }
                                } else {
                                    if (delta.y > 0f) {
                                        state.applyDownwardPull(delta.y)
                                    } else if (delta.y < 0f) {
                                        state.consumeUpwardDrag(delta.y)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .testTag("shareflow_elastic_overscroll_container")
    ) {
        // Suppress platform stretch distortion inside this container so our crisp GPU whole-page
        // translation acts as the single unified One UI overscroll effect without double-stretching.
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = state.offsetPx
                    }
                    .testTag("shareflow_elastic_overscroll_content")
            ) {
                content()
            }
        }
    }
}
