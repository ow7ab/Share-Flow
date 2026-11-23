package com.example.shareflow.ui.update

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.UpdateDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateAvailableDialogRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleUpdate(mandatory: Boolean) = UpdateDetails(
        versionCode = 25,
        versionName = "2.1.0",
        apkDownloadUrl = "https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/Share-Flow.apk",
        sha256 = "a".repeat(64),
        fileSizeBytes = 25_000_000L,
        mandatory = mandatory,
        releaseNotes = "New ShareFlow logo, unified glass nav bar, and faster startup.",
        releaseNotesEn = "New ShareFlow logo, unified glass nav bar, and faster startup.",
        releaseNotesAr = "شعار ShareFlow الجديد وشريط تنقل زجاجي موحد وتشغيل أسرع.",
        isUpdateAvailable = true
    )

    @Test
    fun `optional update dialog displays versionName releaseNotes Download Now and Later actions`() {
        var downloadTriggeredFor: UpdateDetails? = null
        var dismissed = false

        composeTestRule.setContent {
            ShareFlowTheme {
                UpdateAvailableDialog(
                    update = sampleUpdate(mandatory = false),
                    downloadState = CloudUpdateState.Idle,
                    onDownloadNow = { downloadTriggeredFor = it },
                    onDismiss = { dismissed = true }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("update_available_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_dialog_version_name").assertIsDisplayed().assertTextContains("v2.1.0")
        composeTestRule.onNodeWithTag("update_dialog_release_notes").assertIsDisplayed()
            .assertTextContains("New ShareFlow logo", substring = true)
        assertTrue(
            composeTestRule.onAllNodesWithTag("update_dialog_mandatory_badge", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        composeTestRule.onNodeWithTag("update_dialog_download_button").assertIsDisplayed().performClick()
        assertEquals("2.1.0", downloadTriggeredFor?.versionName)

        composeTestRule.onNodeWithTag("update_dialog_later_button").assertIsDisplayed().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `mandatory update dialog displays mandatory badge and hides dismiss button`() {
        var downloadCount = 0

        composeTestRule.setContent {
            ShareFlowTheme {
                UpdateAvailableDialog(
                    update = sampleUpdate(mandatory = true),
                    downloadState = CloudUpdateState.Idle,
                    onDownloadNow = { downloadCount++ },
                    onDismiss = { error("Mandatory dialog must never dismiss") }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("update_available_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_dialog_mandatory_badge").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithTag("update_dialog_later_button", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )

        composeTestRule.onNodeWithTag("update_dialog_download_button").assertIsDisplayed().performClick()
        assertEquals(1, downloadCount)
    }
}
