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
    fun `qr pairing scan tab shows gallery import and manual SF3 code entry and tools exposes diagnostics`() {
        waitForTag("home_receive_button")
        composeTestRule.onNodeWithTag("home_receive_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.waitForIdle()

        val receiveList = composeTestRule.onNodeWithTag("receive_screen_list")
        receiveList.performScrollToNode(hasTestTag("receive_open_qr_button"))
        composeTestRule.onNodeWithTag("receive_open_qr_button").performClick()
        waitForTag("qr_pairing_screen")
        composeTestRule.waitForIdle()

        // Show tab preserves PIN & Share QR actions
        val qrList = composeTestRule.onNodeWithTag("qr_pairing_screen")
        qrList.performScrollToNode(hasTestTag("qr_pairing_pin_value"))
        composeTestRule.onNodeWithTag("qr_pairing_pin_value").assertIsDisplayed()

        // Switch to Scan tab -> gallery import & manual code entry present
        qrList.performScrollToNode(hasTestTag("qr_tab_scan_button"))
        composeTestRule.onNodeWithTag("qr_tab_scan_button").performClick()
        composeTestRule.waitForIdle()

        qrList.performScrollToNode(hasTestTag("qr_import_gallery_button"))
        composeTestRule.onNodeWithTag("qr_import_gallery_button").assertIsDisplayed()
        qrList.performScrollToNode(hasTestTag("qr_manual_uri_input"))
        composeTestRule.onNodeWithTag("qr_manual_uri_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("qr_manual_connect_button").assertIsDisplayed()

        // Navigate back to Home -> Tools -> Connection Diagnostics card
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("nav_item_tools")
        composeTestRule.onNodeWithTag("nav_item_tools").performClick()
        waitForTag("tools_screen_list")

        val toolsList = composeTestRule.onNodeWithTag("tools_screen_list")
        toolsList.performScrollToNode(hasTestTag("tools_card_connection_diagnostics"))
        composeTestRule.onNodeWithTag("tools_card_connection_diagnostics").assertIsDisplayed()
        composeTestRule.onNodeWithTag("tools_card_connection_diagnostics").performClick()
        waitForTag("connection_diagnostics_screen")
        composeTestRule.onNodeWithTag("connection_diagnostics_screen").assertIsDisplayed()
    }
}
