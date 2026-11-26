package com.example.shareflow.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.UpdateDetails
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.ui.settings.CloudUpdateSettingsCard
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
class AutoUpdateSettingsAndBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleOptionalUpdate() = UpdateDetails(
        versionCode = 28,
        versionName = "2.2.0",
        apkDownloadUrl = "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
        sha256 = "b".repeat(64),
        fileSizeBytes = 24_000_000L,
        mandatory = false,
        releaseNotesEn = "Faster transfers and auto-update alerts.",
        releaseNotesAr = "نقل أسرع وتنبيهات تحديث تلقائية غير مزعجة.",
        isUpdateAvailable = true
    )

    @Test
    fun `cloud update settings card displays and toggles auto-check switch in Arabic`() {
        var autoCheckEnabled by mutableStateOf(true)

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.DARK,
                appLanguage = AppLanguage.ARABIC
            ) {
                CloudUpdateSettingsCard(
                    updateState = CloudUpdateState.Idle,
                    onCheckForUpdates = {},
                    onDownloadUpdate = {},
                    onCancelDownload = {},
                    onInstallVerifiedUpdate = {},
                    autoCheckUpdatesOnLaunch = autoCheckEnabled,
                    onAutoCheckUpdatesOnLaunchChanged = { autoCheckEnabled = it }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("settings_auto_check_updates_row").assertIsDisplayed()
        composeTestRule.onNodeWithTag("settings_auto_check_updates_switch")
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()

        composeTestRule.waitForIdle()
        assertFalse(autoCheckEnabled)
        composeTestRule.onNodeWithTag("settings_auto_check_updates_switch").assertIsOff()
    }

    @Test
    fun `non-intrusive update banner displays version opens details and dismisses cleanly`() {
        var openedDetailsCount = 0
        var dismissedCount = 0
        var activeUpdate by mutableStateOf<UpdateDetails?>(sampleOptionalUpdate())

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.LIGHT,
                appLanguage = AppLanguage.ENGLISH
            ) {
                UpdateAvailableBanner(
                    update = activeUpdate,
                    onOpenDetails = { openedDetailsCount++ },
                    onDismiss = {
                        dismissedCount++
                        activeUpdate = null
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("update_available_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_banner_version_subtitle", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertTextContains("v2.2.0", substring = true)

        composeTestRule.onNodeWithTag("update_banner_view_action")
            .assertIsDisplayed()
            .performClick()
        assertEquals(1, openedDetailsCount)

        composeTestRule.onNodeWithTag("update_banner_dismiss_action")
            .assertIsDisplayed()
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, dismissedCount)
        assertTrue(
            composeTestRule.onAllNodesWithTag("update_available_banner", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }
}
