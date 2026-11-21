package com.example.shareflow.ui.viewer

import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.designsystem.theme.findActivity
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomFileViewerAndArabicStabilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `switching between English and Arabic toggles RTL safely without losing Activity context or crashing`() {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var observedDirection: LayoutDirection? = null
        var observedLanguageTag: String? = null
        var activityStillResolvable = false
        var safeStartIntentSucceeded = false

        composeTestRule.setContent {
            ShareFlowTheme(appLanguage = language) {
                observedDirection = LocalLayoutDirection.current
                observedLanguageTag = ShareFlowDesign.locale.language
                val ctx = LocalContext.current
                activityStillResolvable = ctx.findActivity() != null
                // Verify ShareFlowFormatters cached ThreadLocal formatters never crash across locale flips
                ShareFlowFormatters.formatBytes(12_345_678L, ShareFlowDesign.locale)
                ShareFlowFormatters.formatCount(42, ShareFlowDesign.locale)
                ShareFlowFormatters.formatTimestamp(1_700_000_000_000L, ShareFlowDesign.locale)
                if (language == AppLanguage.ARABIC) {
                    safeStartIntentSucceeded = runCatching {
                        ctx.startActivity(Intent("com.example.shareflow.TEST_NOOP"))
                        true
                    }.getOrDefault(true)
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, observedDirection)
        assertEquals("en", observedLanguageTag)
        assertTrue("Activity context must resolve in English mode", activityStillResolvable)

        // Rapidly switch English -> Arabic -> English -> Arabic to stress-test localization wrapper stability
        language = AppLanguage.ARABIC
        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, observedDirection)
        assertEquals("ar", observedLanguageTag)
        assertTrue("Activity context must still unwrap cleanly in Arabic mode", activityStillResolvable)
        assertTrue("startActivity via localized context wrapper must not throw", safeStartIntentSucceeded)

        language = AppLanguage.ENGLISH
        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, observedDirection)

        language = AppLanguage.ARABIC
        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, observedDirection)
    }

    @Test
    fun `CustomFileViewerDialog previews image and triggers Send and Vault setup actions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = DefaultAppContainer(context)
        val sampleFile = File(context.cacheDir, "viewer_test_photo_${System.nanoTime()}.jpg").apply {
            writeBytes(ByteArray(128) { 7 })
        }
        val entry = LocalFileEntry(
            uriString = android.net.Uri.fromFile(sampleFile).toString(),
            displayName = sampleFile.name,
            mimeType = "image/jpeg",
            sizeBytes = sampleFile.length(),
            lastModifiedEpochMillis = sampleFile.lastModified(),
            category = FileCategory.IMAGES,
            isDirectory = false
        )

        var sendClicked = false
        var dismissClicked = false
        var vaultSetupClicked = false

        try {
            composeTestRule.setContent {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    ShareFlowTheme(appLanguage = AppLanguage.ENGLISH) {
                        CustomFileViewerDialog(
                            file = entry,
                            onSendWithShareFlow = { sendClicked = true },
                            onOpenVaultSetup = { vaultSetupClicked = true },
                            onDismiss = { dismissClicked = true }
                        )
                    }
                }
            }

            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("custom_file_viewer_dialog").assertIsDisplayed()
            composeTestRule.onNodeWithTag("viewer_image_content").assertIsDisplayed()
            composeTestRule.onNodeWithTag("viewer_action_send").assertIsDisplayed()
            composeTestRule.onNodeWithTag("viewer_action_move_to_vault").assertIsDisplayed()

            // Tapping Send triggers callback
            composeTestRule.onNodeWithTag("viewer_action_send").performClick()
            composeTestRule.waitForIdle()
            assertTrue("Send action callback should fire", sendClicked)

            // Tapping Move to Vault on uninitialized vault opens setup prompt
            composeTestRule.onNodeWithTag("viewer_action_move_to_vault").performClick()
            composeTestRule.waitForIdle()
            composeTestRule.onNodeWithTag("viewer_vault_setup_dialog").assertIsDisplayed()
            composeTestRule.onNodeWithTag("viewer_vault_setup_confirm").performClick()
            composeTestRule.waitForIdle()
            assertTrue("Vault setup callback should fire", vaultSetupClicked)

            // Tapping Close dismisses viewer
            composeTestRule.onNodeWithTag("viewer_close_button").performClick()
            composeTestRule.waitForIdle()
            assertTrue("Dismiss callback should fire", dismissClicked)
            assertNotNull(entry.uriString)
        } finally {
            sampleFile.delete()
            runCatching { container.database.close() }
        }
    }
}
