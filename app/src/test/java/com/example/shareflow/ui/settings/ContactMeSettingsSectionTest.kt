package com.example.shareflow.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactMeSettingsSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `facebook linkedin whatsapp and email intents match exact official targets`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val captured = mutableListOf<Intent>()

        for (channel in ContactChannel.entries) {
            assertTrue("Channel URI must pass strict validation: $channel", ExternalContactLauncher.isValidChannelUri(channel))
            val result = ExternalContactLauncher.openChannel(
                context = context,
                channel = channel,
                startActivityFn = { captured.add(it) }
            )
            assertEquals(ContactLaunchResult.Launched, result)
        }

        assertEquals(4, captured.size)

        // 1. Facebook
        assertEquals(Intent.ACTION_VIEW, captured[0].action)
        assertEquals("https://www.facebook.com/ow7ab", captured[0].dataString)
        assertTrue(captured[0].hasCategory(Intent.CATEGORY_BROWSABLE))

        // 2. LinkedIn
        assertEquals(Intent.ACTION_VIEW, captured[1].action)
        assertEquals("https://www.linkedin.com/in/ow7ab/", captured[1].dataString)
        assertTrue(captured[1].hasCategory(Intent.CATEGORY_BROWSABLE))

        // 3. WhatsApp
        assertEquals(Intent.ACTION_VIEW, captured[2].action)
        assertEquals("https://wa.me/249963003301", captured[2].dataString)
        assertTrue(captured[2].hasCategory(Intent.CATEGORY_BROWSABLE))

        // 4. Email
        assertEquals(Intent.ACTION_SENDTO, captured[3].action)
        assertEquals("mailto:oaw7ab@gmail.com", captured[3].dataString)
    }

    @Test
    fun `missing target app falls back gracefully for email and reports NoHandlerAvailable without crashing`() {
        val context: Context = ApplicationProvider.getApplicationContext()

        // Social link when neither native app nor browser is installed
        val fbMissing = ExternalContactLauncher.openChannel(
            context = context,
            channel = ContactChannel.FACEBOOK,
            startActivityFn = { throw ActivityNotFoundException("No browser or Facebook app") }
        )
        assertEquals(ContactLaunchResult.NoHandlerAvailable, fbMissing)

        // Email falls back from ACTION_SENDTO to ACTION_VIEW before failing
        val attemptedActions = mutableListOf<String?>()
        val emailFallbackSucceeded = ExternalContactLauncher.openChannel(
            context = context,
            channel = ContactChannel.EMAIL,
            startActivityFn = { intent ->
                attemptedActions.add(intent.action)
                if (intent.action == Intent.ACTION_SENDTO) {
                    throw ActivityNotFoundException("No ACTION_SENDTO handler")
                }
            }
        )
        assertEquals(ContactLaunchResult.Launched, emailFallbackSucceeded)
        assertEquals(listOf(Intent.ACTION_SENDTO, Intent.ACTION_VIEW), attemptedActions)

        // Email when zero handlers exist at all
        val emailCompletelyMissing = ExternalContactLauncher.openChannel(
            context = context,
            channel = ContactChannel.EMAIL,
            startActivityFn = { throw ActivityNotFoundException("No email app installed") }
        )
        assertEquals(ContactLaunchResult.NoHandlerAvailable, emailCompletelyMissing)
    }

    @Test
    fun `uri validator blocks non-https and mismatched hosts`() {
        assertFalse(ExternalContactLauncher.isValidChannelUri(ContactChannel.FACEBOOK, "http://www.facebook.com/ow7ab"))
        assertFalse(ExternalContactLauncher.isValidChannelUri(ContactChannel.FACEBOOK, "https://evil.example/ow7ab"))
        assertFalse(ExternalContactLauncher.isValidChannelUri(ContactChannel.WHATSAPP, "javascript:alert(1)"))
        assertFalse(ExternalContactLauncher.isValidChannelUri(ContactChannel.EMAIL, "https://oaw7ab@gmail.com"))
    }

    @Test
    fun `contact section renders in Arabic RTL dark theme and debounces rapid repeated taps`() {
        var fakeClockMs = 1_000L
        val tappedChannels = mutableListOf<ContactChannel>()

        composeRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.DARK,
                appLanguage = AppLanguage.ARABIC
            ) {
                ContactMeSettingsSection(
                    onShowFeedback = {},
                    onChannelClick = { tappedChannels.add(it) },
                    elapsedRealtimeProvider = { fakeClockMs }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_section_contact_me").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_me_title").assertTextEquals("تواصل معي")
        composeRule.onNodeWithTag("settings_contact_me_subtitle")
            .assertTextEquals("يسعدني تواصلك معي عبر القنوات التالية")

        composeRule.onNodeWithTag("settings_contact_facebook").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_linkedin").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_whatsapp").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_email").assertIsDisplayed()

        // First tap registers immediately
        composeRule.onNodeWithTag("settings_contact_facebook").performClick()
        // Rapid duplicate tap within 200ms is debounced
        fakeClockMs += 200L
        composeRule.onNodeWithTag("settings_contact_facebook").performClick()
        // Tap after debounce window registers normally
        fakeClockMs += ExternalContactLauncher.CLICK_DEBOUNCE_MS
        composeRule.onNodeWithTag("settings_contact_whatsapp").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(ContactChannel.FACEBOOK, ContactChannel.WHATSAPP), tappedChannels)
    }

    @Test
    fun `contact section renders in English LTR light theme directly below about section in settings`() {
        composeRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.LIGHT,
                appLanguage = AppLanguage.ENGLISH
            ) {
                SettingsContent(
                    uiState = SettingsUiState(),
                    onDeviceNameDraftChange = {},
                    onSaveDeviceName = {},
                    onPortDraftChange = {},
                    onSavePort = {},
                    onThemeModeSelected = {},
                    onDynamicColorChanged = {},
                    onAccentColorSelected = {},
                    onAppLanguageSelected = {},
                    onPreferWifiDirectChanged = {},
                    onVerifySha256Changed = {},
                    onAutoAcceptTrustedChanged = {},
                    onRequireVaultAuthChanged = {},
                    onResetToDefaults = {},
                    onFeedbackConsumed = {}
                )
            }
        }
        composeRule.waitForIdle()

        // Scroll to About section first, then verify Contact Me appears directly below it
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_section_about"))
        composeRule.onNodeWithTag("settings_section_about").assertIsDisplayed()

        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("settings_section_contact_me"))
        composeRule.waitForIdle()

        val aboutBounds = composeRule.onNodeWithTag("settings_section_about").fetchSemanticsNode().boundsInRoot
        val contactBounds = composeRule.onNodeWithTag("settings_section_contact_me").fetchSemanticsNode().boundsInRoot
        assertNotNull(aboutBounds)
        assertTrue(
            "Contact Me section must be rendered below About section (contact.top=${contactBounds.top}, about.bottom=${aboutBounds.bottom})",
            contactBounds.top >= aboutBounds.bottom - 1f
        )

        composeRule.onNodeWithTag("settings_section_contact_me").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_me_title").assertTextEquals("Contact Me")
        composeRule.onNodeWithTag("settings_contact_facebook").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_linkedin").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_whatsapp").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_contact_email").assertIsDisplayed()
    }
}
