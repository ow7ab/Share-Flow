package com.example.shareflow.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFlowNavigationRobolectricTest {

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
    fun `home dashboard displays header actions and all five required sections`() {
        waitForTag("home_hero_card")

        // Header: logo, device name, connection status, settings shortcut
        composeTestRule.onNodeWithTag("home_app_logo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_device_name_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_connection_status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_settings_shortcut").assertIsDisplayed()

        // Main actions: Send & Receive
        composeTestRule.onNodeWithTag("home_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_receive_button").assertIsDisplayed()

        // Scroll through all 5 Home Dashboard sections
        val list = composeTestRule.onNodeWithTag("home_dashboard_list")
        list.performScrollToNode(hasTestTag("home_section_nearby_devices"))
        composeTestRule.onNodeWithTag("home_section_nearby_devices").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("home_section_recent_transfers"))
        composeTestRule.onNodeWithTag("home_section_recent_transfers").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("home_section_recent_files"))
        composeTestRule.onNodeWithTag("home_section_recent_files").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("home_section_storage"))
        composeTestRule.onNodeWithTag("home_section_storage").assertIsDisplayed()

        list.performScrollToNode(hasTestTag("home_section_quick_tools"))
        composeTestRule.onNodeWithTag("home_section_quick_tools").assertIsDisplayed()
    }

    @Test
    fun `navigates across all five bottom navigation tabs and verifies screens`() {
        waitForTag("home_hero_card")

        // 1. Files tab — all 7 categories + drill-in
        composeTestRule.onNodeWithTag("nav_item_files").performClick()
        waitForTag("files_screen_list")
        val filesList = composeTestRule.onNodeWithTag("files_screen_list")
        listOf(
            "files_category_images",
            "files_category_videos",
            "files_category_audio",
            "files_category_documents",
            "files_category_archives",
            "files_category_apk_files",
            "files_category_downloads",
            "files_category_other_files"
        ).forEach { categoryTag ->
            filesList.performScrollToNode(hasTestTag(categoryTag))
            composeTestRule.onNodeWithTag(categoryTag).assertIsDisplayed()
        }
        filesList.performScrollToNode(hasTestTag("files_category_documents"))
        composeTestRule.onNodeWithTag("files_category_documents").performClick()
        waitForTag("files_category_detail_documents")
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("files_screen_list")

        // 2. Media tab — Photos, Videos, Music
        composeTestRule.onNodeWithTag("nav_item_media").performClick()
        waitForTag("media_screen_list")
        composeTestRule.onNodeWithTag("media_card_photos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_videos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_music").assertIsDisplayed()
        composeTestRule.onNodeWithTag("media_card_photos").performClick()
        waitForTag("top_bar_back_button")
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("media_screen_list")

        // 3. Tools tab — Private Vault, Storage Analyzer, Compression, Duplicate Finder, Phone Migration, History
        composeTestRule.onNodeWithTag("nav_item_tools").performClick()
        waitForTag("tools_screen_list")
        val toolsList = composeTestRule.onNodeWithTag("tools_screen_list")
        listOf(
            "tools_card_private_vault",
            "tools_card_storage_analyzer",
            "tools_card_compression",
            "tools_card_duplicate_finder",
            "tools_card_phone_migration",
            "tools_card_transfer_history"
        ).forEach { toolTag ->
            toolsList.performScrollToNode(hasTestTag(toolTag))
            composeTestRule.onNodeWithTag(toolTag).assertIsDisplayed()
        }

        // Open Storage Analyzer real screen
        toolsList.performScrollToNode(hasTestTag("tools_card_storage_analyzer"))
        composeTestRule.onNodeWithTag("tools_card_storage_analyzer").performClick()
        waitForTag("storage_analyzer_screen")
        composeTestRule.onNodeWithTag("storage_analyzer_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("tools_screen_list")

        // Open Transfer History from Tools
        toolsList.performScrollToNode(hasTestTag("tools_card_transfer_history"))
        composeTestRule.onNodeWithTag("tools_card_transfer_history").performClick()
        waitForTag("history_empty_state")
        composeTestRule.onNodeWithTag("history_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("tools_screen_list")

        // 4. Settings tab — all 7 sections
        composeTestRule.onNodeWithTag("nav_item_settings").performClick()
        waitForTag("settings_list")
        val settingsList = composeTestRule.onNodeWithTag("settings_list")
        listOf(
            "settings_section_appearance",
            "settings_section_language",
            "settings_section_device_info",
            "settings_section_transfer",
            "settings_section_privacy",
            "settings_section_permissions",
            "settings_section_about"
        ).forEach { sectionTag ->
            settingsList.performScrollToNode(hasTestTag(sectionTag))
            composeTestRule.onNodeWithTag(sectionTag).assertIsDisplayed()
        }

        // 5. Return to Home tab
        composeTestRule.onNodeWithTag("nav_item_home").performClick()
        waitForTag("home_hero_card")
        composeTestRule.onNodeWithTag("home_hero_card").assertIsDisplayed()
    }

    @Test
    fun `navigates to send receive qr pairing and settings shortcut from home`() {
        waitForTag("home_hero_card")

        // Send screen
        composeTestRule.onNodeWithTag("home_send_button").performClick()
        waitForTag("send_screen_list")
        composeTestRule.onNodeWithTag("send_category_selector").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_selected_counter").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_total_size").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_continue_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_continue_button").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("home_hero_card")

        // Receive screen & QR pairing entry
        composeTestRule.onNodeWithTag("home_receive_button").performClick()
        waitForTag("receive_screen_list")
        val receiveList = composeTestRule.onNodeWithTag("receive_screen_list")
        composeTestRule.onNodeWithTag("receive_toggle_button").assertIsDisplayed()
        receiveList.performScrollToNode(hasTestTag("receive_connection_status_card"))
        composeTestRule.onNodeWithTag("receive_connection_status_card").assertIsDisplayed()
        receiveList.performScrollToNode(hasTestTag("receive_discovery_status_card"))
        composeTestRule.onNodeWithTag("receive_discovery_status_card").assertIsDisplayed()

        // Open QR Pairing
        receiveList.performScrollToNode(hasTestTag("receive_qr_card"))
        composeTestRule.onNodeWithTag("receive_open_qr_button").performClick()
        waitForTag("qr_pairing_screen")
        val qrList = composeTestRule.onNodeWithTag("qr_pairing_screen")
        qrList.performScrollToNode(hasTestTag("qr_pairing_pin_value"))
        composeTestRule.onNodeWithTag("qr_pairing_pin_value").assertIsDisplayed()
        qrList.performScrollToNode(hasTestTag("qr_regenerate_pin_button"))
        composeTestRule.onNodeWithTag("qr_regenerate_pin_button").performClick()
        qrList.performScrollToNode(hasTestTag("qr_pairing_pin_value"))
        composeTestRule.onNodeWithTag("qr_pairing_pin_value").assertIsDisplayed()

        // Navigate back to Receive -> Home
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("receive_screen_list")
        composeTestRule.onNodeWithTag("top_bar_back_button").performClick()
        waitForTag("home_hero_card")

        // Settings shortcut in Home header
        composeTestRule.onNodeWithTag("home_settings_shortcut").performClick()
        waitForTag("settings_list")
        composeTestRule.onNodeWithTag("settings_section_appearance").assertIsDisplayed()
    }
}
