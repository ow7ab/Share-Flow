package com.example.shareflow.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.permissions.ShareFlowPermissionFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PermissionManagerAndUnifiedSendTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private fun waitForTag(tag: String, timeoutMillis: Long = 10_000L) {
        composeTestRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeTestRule.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun `PermissionManager enforces Play-policy least privilege across API levels and tracks denials`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        val api33Manager = PermissionManager(
            context = context,
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            permissionChecker = { false },
            canInstallPackagesChecker = { false }
        )
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            api33Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT)
        )
        assertEquals(
            listOf(Manifest.permission.CAMERA),
            api33Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.QR_CAMERA)
        )
        // Zero broad storage or QUERY_ALL_PACKAGES runtime permissions
        assertTrue(api33Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.PHOTOS_MEDIA).isEmpty())
        assertTrue(api33Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.VIDEOS_MEDIA).isEmpty())
        assertTrue(api33Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.APP_DISCOVERY).isEmpty())

        // API <= 32 uses ACCESS_FINE_LOCATION for Wi-Fi Direct / LocalOnlyHotspot
        val api31Manager = PermissionManager(
            context = context,
            sdkInt = Build.VERSION_CODES.S,
            permissionChecker = { false }
        )
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            api31Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT)
        )
        assertTrue(api31Manager.requiredRuntimePermissions(ShareFlowPermissionFeature.NOTIFICATIONS).isEmpty())

        // Denial loop prevention: stops re-prompting after 2 denials and resets upon grant
        assertTrue(api33Manager.evaluateFeature(ShareFlowPermissionFeature.QR_CAMERA).canAskRuntimePermissionAgain)
        api33Manager.recordPermissionResult(ShareFlowPermissionFeature.QR_CAMERA, granted = false)
        api33Manager.recordPermissionResult(ShareFlowPermissionFeature.QR_CAMERA, granted = false)
        assertFalse(api33Manager.evaluateFeature(ShareFlowPermissionFeature.QR_CAMERA).canAskRuntimePermissionAgain)
        api33Manager.recordPermissionResult(ShareFlowPermissionFeature.QR_CAMERA, granted = true)
        assertTrue(api33Manager.evaluateFeature(ShareFlowPermissionFeature.QR_CAMERA).canAskRuntimePermissionAgain)
    }

    @Test
    fun `Unified Send screen displays all 4 SHAREit tabs Photos Videos Apps Files cleanly`() {
        waitForTag("home_send_button")
        composeTestRule.onNodeWithTag("home_send_button").performClick()

        waitForTag("send_category_selector")
        composeTestRule.onNodeWithTag("send_tab_photos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_tab_videos").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_tab_apps").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_tab_files").assertIsDisplayed()

        // Photos tab CTA visible
        waitForTag("send_pick_photos_button")
        composeTestRule.onNodeWithTag("send_pick_photos_button").assertIsDisplayed()

        // Switch to Videos tab
        composeTestRule.onNodeWithTag("send_tab_videos").performClick()
        waitForTag("send_pick_videos_button")
        composeTestRule.onNodeWithTag("send_pick_videos_button").assertIsDisplayed()

        // Switch to Apps tab
        composeTestRule.onNodeWithTag("send_tab_apps").performClick()
        waitForTag("send_apps_search_input")
        composeTestRule.onNodeWithTag("send_apps_search_input").assertIsDisplayed()

        // Switch to Files tab
        composeTestRule.onNodeWithTag("send_tab_files").performClick()
        waitForTag("send_pick_files_button")
        composeTestRule.onNodeWithTag("send_pick_files_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("send_pick_folder_button").assertIsDisplayed()
    }
}
