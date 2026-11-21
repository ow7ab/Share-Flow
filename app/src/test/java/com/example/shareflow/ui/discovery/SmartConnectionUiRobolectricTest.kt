package com.example.shareflow.ui.discovery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartConnectionUiRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMillis: Long = 5_000L) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeTestRule
                .onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `receive screen hides premature smart QR and opens diagnostics screen cleanly`() {
        waitForTag("home_receive_button")
        composeTestRule.onNodeWithTag("home_receive_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.waitForIdle()

        // Smart phase pill and hotspot toggle must be displayed; premature QR must NOT appear before ready
        composeTestRule.onNodeWithTag("receive_smart_phase_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("receive_hotspot_switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("receive_diagnostics_button").assertIsDisplayed()
        assertTrue(
            "Smart QR must not render before hotspot & TCP server are verified ready",
            composeTestRule.onAllNodesWithTag("receive_live_smart_qr_card", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        // Open Connection Diagnostics from Receive top bar
        composeTestRule.onNodeWithTag("receive_diagnostics_button").performClick()
        waitForTag("connection_diagnostics_screen")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("diagnostics_phase_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_test_connection_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("diagnostics_copy_report_button").assertIsDisplayed()

        // Run Test Connection action and verify result row renders cleanly
        composeTestRule.onNodeWithTag("diagnostics_test_connection_button").performClick()
        composeTestRule.waitForIdle()
        val diagList = composeTestRule.onNodeWithTag("connection_diagnostics_screen")
        diagList.performScrollToNode(hasTestTag("diagnostics_test_result_row"))
        composeTestRule.onNodeWithTag("diagnostics_test_result_row").assertIsDisplayed()
    }

    @Test
    fun `unified receive keeps QR inline and tools exposes connection diagnostics without duplicate qr scanner`() {
        waitForTag("home_receive_button")
        composeTestRule.onNodeWithTag("home_receive_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.waitForIdle()

        // Redundant bottom QR jump-out card must be absent; all receiving stays inline
        assertTrue(
            composeTestRule.onAllNodesWithTag("receive_qr_card", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
        composeTestRule.onNodeWithTag("receive_toggle_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("receive_direct_p2p_badge").assertIsDisplayed()

        // Navigate back to Home -> Tools -> verify no duplicate standalone QR scanner, diagnostics opens cleanly
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("nav_item_tools")
        composeTestRule.onNodeWithTag("nav_item_tools").performClick()
        waitForTag("tools_screen_list")

        assertTrue(
            composeTestRule.onAllNodesWithTag("tools_card_qr_scanner", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        val toolsList = composeTestRule.onNodeWithTag("tools_screen_list")
        toolsList.performScrollToNode(hasTestTag("tools_card_connection_diagnostics"))
        composeTestRule.onNodeWithTag("tools_card_connection_diagnostics").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_card_connection_diagnostics").performClick()
        waitForTag("connection_diagnostics_screen")
        composeTestRule.onNodeWithTag("connection_diagnostics_screen").assertIsDisplayed()
    }

    @Test
    fun `receive shows quick pairing guide and settings exposes Sudanese cloud update checker`() {
        waitForTag("home_receive_button")
        composeTestRule.onNodeWithTag("home_receive_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.waitForIdle()

        val receiveList = composeTestRule.onNodeWithTag("receive_screen_list")
        receiveList.performScrollToNode(hasTestTag("receive_quick_guide_card"))
        composeTestRule.onNodeWithTag("receive_quick_guide_card").assertIsDisplayed()

        // Return Home and open Settings
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("nav_item_settings")
        composeTestRule.onNodeWithTag("nav_item_settings").performClick()
        waitForTag("settings_screen_list")
        composeTestRule.waitForIdle()

        val settingsList = composeTestRule.onNodeWithTag("settings_screen_list")
        settingsList.performScrollToNode(hasTestTag("settings_section_cloud_update"))
        composeTestRule.onNodeWithTag("settings_section_cloud_update").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_sudan_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_current_version").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_update_url_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_check_updates_button").assertIsDisplayed()
    }
}
