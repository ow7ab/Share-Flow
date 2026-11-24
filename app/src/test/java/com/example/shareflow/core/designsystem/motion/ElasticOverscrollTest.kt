package com.example.shareflow.core.designsystem.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ElasticOverscrollTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `rubber band physics applies smooth non-linear resistance bounded by maxPullPx`() {
        val maxPull = 300f

        assertEquals(0f, ElasticOverscrollPhysics.computeDampedOffsetPx(0f, maxPull), 0.001f)
        assertEquals(0f, ElasticOverscrollPhysics.computeDampedOffsetPx(-40f, maxPull), 0.001f)

        val shortPull = ElasticOverscrollPhysics.computeDampedOffsetPx(50f, maxPull)
        val mediumPull = ElasticOverscrollPhysics.computeDampedOffsetPx(200f, maxPull)
        val longPull = ElasticOverscrollPhysics.computeDampedOffsetPx(600f, maxPull)
        val extremePull = ElasticOverscrollPhysics.computeDampedOffsetPx(5000f, maxPull)

        // Strictly monotonic & never exceeds maxPull
        assertTrue(shortPull > 0f && shortPull < mediumPull)
        assertTrue(mediumPull < longPull && longPull < extremePull)
        assertTrue(extremePull < maxPull)

        // Progressive resistance: marginal gain decreases as pull distance grows
        val slopeNearStart = shortPull / 50f
        val slopeMid = (mediumPull - shortPull) / 150f
        val slopeFar = (longPull - mediumPull) / 400f
        assertTrue(slopeNearStart > slopeMid)
        assertTrue(slopeMid > slopeFar)
    }

    @Test
    fun `horizontal swipe intent is filtered out while vertical pulls are accepted`() {
        assertTrue(ElasticOverscrollPhysics.isPredominantlyVertical(dx = 4f, dy = 40f))
        assertTrue(ElasticOverscrollPhysics.isPredominantlyVertical(dx = 0f, dy = 12f))
        assertFalse(ElasticOverscrollPhysics.isPredominantlyVertical(dx = 60f, dy = 15f))
        assertFalse(ElasticOverscrollPhysics.isPredominantlyVertical(dx = 30f, dy = 30f))
        assertFalse(ElasticOverscrollPhysics.isPredominantlyVertical(dx = 10f, dy = 0f))
    }

    @Test
    fun `fling overshoot stays bounded and ignores low velocity`() {
        val maxPull = 300f
        assertEquals(0f, ElasticOverscrollPhysics.computeFlingOvershootPx(200f, maxPull), 0.001f)
        val moderate = ElasticOverscrollPhysics.computeFlingOvershootPx(1800f, maxPull)
        val extreme = ElasticOverscrollPhysics.computeFlingOvershootPx(50_000f, maxPull)
        assertTrue(moderate > 0f)
        assertTrue(extreme <= maxPull * ElasticOverscrollPhysics.MAX_FLING_OVERSHOOT_RATIO + 0.01f)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `nested scroll connection tracks pull down reverses upward cleanly and ignores horizontal drags`() = runTest {
        val frameClock = object : MonotonicFrameClock {
            private var frameTimeNanos = 0L
            override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
                frameTimeNanos += 8_333_333L // 120Hz frame interval
                return onFrame(frameTimeNanos)
            }
        }
        val state = ElasticOverscrollState(
            scope = CoroutineScope(coroutineContext + frameClock),
            initialMaxPullPx = 300f
        )
        val connection = createElasticOverscrollNestedScrollConnection(
            state = state,
            isEnabled = { true }
        )

        // Intentional horizontal drag must not trigger overscroll
        val horizConsumed = connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(x = 120f, y = 20f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(Offset.Zero, horizConsumed)
        assertEquals(0f, state.offsetPx, 0.001f)

        // Vertical downward pull engages non-linear elastic displacement
        val pull1 = connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(x = 2f, y = 120f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(120f, pull1.y, 0.001f)
        assertTrue(state.offsetPx > 40f)
        val stretchedOffset = state.offsetPx

        // Reversing upward consumes upward delta BEFORE list scrolls
        val reverse = connection.onPreScroll(
            available = Offset(x = 0f, y = -60f),
            source = NestedScrollSource.UserInput
        )
        assertEquals(-60f, reverse.y, 0.001f)
        assertTrue(state.offsetPx < stretchedOffset && state.offsetPx > 0f)

        // Releasing triggers spring consumption of vertical fling velocity and settles cleanly to 0f
        val flingConsumed = connection.onPreFling(Velocity(x = 0f, y = 400f))
        assertEquals(400f, flingConsumed.y, 0.001f)
        assertEquals(0f, state.rawPullPx, 0.001f)
        advanceUntilIdle()
        assertEquals(0f, state.offsetPx, 0.5f)
    }

    @Test
    fun `long scrollable list pulls down smoothly without recomposing content and settles at 60Hz and 120Hz`() {
        var contentRecompositions = 0
        var buttonClicks = 0
        lateinit var overscrollState: ElasticOverscrollState

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            ShareFlowTheme {
                overscrollState = rememberElasticOverscrollState()
                ElasticOverscrollContainer(state = overscrollState) {
                    SideEffect { contentRecompositions++ }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("long_list")
                    ) {
                        item {
                            Button(
                                onClick = { buttonClicks++ },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("action_button")
                            ) {
                                Text("Send")
                            }
                        }
                        items(40) { idx ->
                            Text(
                                text = "Item $idx",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        val initialRecompositions = contentRecompositions

        // Drag downward without lifting yet: verify elastic displacement and ZERO recompositions
        composeTestRule.onNodeWithTag("long_list").performTouchInput {
            down(center)
            moveBy(Offset(0f, 320f))
        }
        composeTestRule.mainClock.advanceTimeBy(8L) // 120Hz single frame
        assertTrue("Expected positive downward displacement during pull", overscrollState.offsetPx > 10f)
        assertEquals("Elastic pull must NOT recompose content", initialRecompositions, contentRecompositions)

        // Release finger and step through 120Hz (8ms) and 60Hz (16ms) frames until settled
        composeTestRule.onNodeWithTag("long_list").performTouchInput { up() }
        repeat(40) { composeTestRule.mainClock.advanceTimeBy(8L) }
        repeat(20) { composeTestRule.mainClock.advanceTimeBy(16L) }
        composeTestRule.mainClock.advanceTimeBy(400L)

        assertEquals("Spring must return cleanly to 0f", 0f, overscrollState.offsetPx, 1f)
        assertEquals("Spring recovery must NOT recompose content", initialRecompositions, contentRecompositions)

        // Buttons remain completely responsive after motion
        composeTestRule.onNodeWithTag("action_button").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        assertEquals(1, buttonClicks)
    }

    @Test
    fun `short non-scrollable screen supports elastic pull down and horizontal swipes do not trigger it`() {
        lateinit var overscrollState: ElasticOverscrollState

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            ShareFlowTheme {
                overscrollState = rememberElasticOverscrollState()
                ElasticOverscrollContainer(state = overscrollState) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("short_page")
                    ) {
                        Column {
                            Text("Short static content")
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        // Horizontal swipe must not displace page vertically
        composeTestRule.onNodeWithTag("short_page").performTouchInput {
            swipeRight(startX = left + 40f, endX = right - 40f, durationMillis = 120L)
        }
        composeTestRule.mainClock.advanceTimeBy(16L)
        assertEquals(0f, overscrollState.offsetPx, 0.5f)

        // Downward drag on short static screen engages elastic pull-down smoothly
        composeTestRule.onNodeWithTag("short_page").performTouchInput {
            down(center)
            moveBy(Offset(0f, 260f))
        }
        composeTestRule.mainClock.advanceTimeBy(8L)
        assertTrue("Short page should displace downward", overscrollState.offsetPx > 10f)

        // Release returns smoothly to 0f
        composeTestRule.onNodeWithTag("short_page").performTouchInput { up() }
        composeTestRule.mainClock.advanceTimeBy(800L)
        assertEquals(0f, overscrollState.offsetPx, 1f)
    }

    @Test
    fun `route change resets active displacement immediately and Reduce Motion disables overscroll`() {
        var currentRoute by mutableStateOf("home")
        var enabled by mutableStateOf(true)
        lateinit var overscrollState: ElasticOverscrollState

        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            ShareFlowTheme {
                overscrollState = rememberElasticOverscrollState()
                ElasticOverscrollContainer(
                    resetKey = currentRoute,
                    enabled = enabled,
                    state = overscrollState
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("routed_page")
                    )
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        // Pull down and trigger navigation while pulled
        composeTestRule.onNodeWithTag("routed_page").performTouchInput {
            down(center)
            moveBy(Offset(0f, 280f))
        }
        composeTestRule.mainClock.advanceTimeBy(8L)
        assertTrue(overscrollState.offsetPx > 10f)

        currentRoute = "settings"
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeByFrame()
        assertEquals(0f, overscrollState.offsetPx, 0.5f)
        composeTestRule.onNodeWithTag("routed_page").performTouchInput { up() }

        // Disable (matching Reduce Motion) -> downward pull produces zero displacement
        enabled = false
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("routed_page").performTouchInput {
            swipeDown(startY = top + 20f, endY = bottom - 20f, durationMillis = 150L)
        }
        composeTestRule.mainClock.advanceTimeBy(16L)
        assertEquals(0f, overscrollState.offsetPx, 0.5f)

        // Verify system Reduce Motion helper reads ANIMATOR_DURATION_SCALE accurately
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(isSystemReduceMotionEnabled(context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(isSystemReduceMotionEnabled(context))
    }
}
