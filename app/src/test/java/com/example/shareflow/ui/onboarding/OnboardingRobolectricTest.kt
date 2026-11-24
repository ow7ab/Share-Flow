package com.example.shareflow.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `next button progresses through welcome ad-free how-to-use permissions and finishes`() {
        var completed = false
        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ENGLISH) {
                OnboardingScreen(onComplete = { completed = true })
            }
        }

        // Page 1: Welcome
        composeRule.onNodeWithTag("onboarding_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_skip_button").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_next_button").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_page_welcome").assertIsDisplayed()
        assertFalse(completed)

        // Page 2: Dedicated 100% Ad-Free page
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_page_ad_free").assertIsDisplayed()
        assertFalse(completed)

        // Page 3: How to Use instructions
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_page_how_to_use").assertIsDisplayed()
        assertFalse(completed)

        // Page 4: Required Permissions explanation
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("onboarding_page_permissions").assertIsDisplayed()
        assertFalse(completed)

        // Final tap finishes onboarding
        composeRule.onNodeWithTag("onboarding_next_button").performClick()
        composeRule.waitForIdle()
        assertTrue(completed)
    }

    @Test
    fun `skip button completes onboarding immediately`() {
        var completed = false
        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                OnboardingScreen(onComplete = { completed = true })
            }
        }

        composeRule.onNodeWithTag("onboarding_skip_button").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertTrue(completed)
    }

    @Test
    fun `skip button is aligned to top-right in English LTR`() {
        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ENGLISH) {
                OnboardingScreen(onComplete = {})
            }
        }

        val rootBounds = composeRule.onNodeWithTag("onboarding_screen").getBoundsInRoot()
        val skipBounds = composeRule.onNodeWithTag("onboarding_skip_button").getBoundsInRoot()
        val nextBounds = composeRule.onNodeWithTag("onboarding_next_button").getBoundsInRoot()

        val rootCenterX = (rootBounds.left + rootBounds.right) / 2f
        val skipCenterX = (skipBounds.left + skipBounds.right) / 2f
        assertTrue("Skip button must be on the right half in English (LTR)", skipCenterX > rootCenterX)
        assertTrue("Skip button must be near the top above Next button", skipBounds.top < nextBounds.top)
    }

    @Test
    fun `skip button is aligned to top-left in Arabic RTL`() {
        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                OnboardingScreen(onComplete = {})
            }
        }

        val rootBounds = composeRule.onNodeWithTag("onboarding_screen").getBoundsInRoot()
        val skipBounds = composeRule.onNodeWithTag("onboarding_skip_button").getBoundsInRoot()
        val nextBounds = composeRule.onNodeWithTag("onboarding_next_button").getBoundsInRoot()

        val rootCenterX = (rootBounds.left + rootBounds.right) / 2f
        val skipCenterX = (skipBounds.left + skipBounds.right) / 2f
        assertTrue("Skip button must be on the left half in Arabic (RTL)", skipCenterX < rootCenterX)
        assertTrue("Skip button must be near the top above Next button", skipBounds.top < nextBounds.top)
    }

    @Test
    fun `language selector switches app language live and flips skip button between LTR and RTL`() {
        val currentLanguage = androidx.compose.runtime.mutableStateOf(AppLanguage.ENGLISH)
        composeRule.setContent {
            ShareFlowTheme(appLanguage = currentLanguage.value) {
                OnboardingScreen(
                    selectedLanguage = currentLanguage.value,
                    onSelectLanguage = { currentLanguage.value = it },
                    onComplete = {}
                )
            }
        }

        composeRule.onNodeWithTag("onboarding_language_selector").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_lang_arabic").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_lang_english").assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding_lang_system").assertIsDisplayed()

        // Switch live from English (LTR) to Arabic (RTL)
        composeRule.onNodeWithTag("onboarding_lang_arabic").performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(AppLanguage.ARABIC, currentLanguage.value)

        val rootBoundsAr = composeRule.onNodeWithTag("onboarding_screen").getBoundsInRoot()
        val skipBoundsAr = composeRule.onNodeWithTag("onboarding_skip_button").getBoundsInRoot()
        val rootCenterAr = (rootBoundsAr.left + rootBoundsAr.right) / 2f
        val skipCenterAr = (skipBoundsAr.left + skipBoundsAr.right) / 2f
        assertTrue("After selecting Arabic, Skip button must move to top-left (RTL)", skipCenterAr < rootCenterAr)

        // Switch back to English (LTR)
        composeRule.onNodeWithTag("onboarding_lang_english").performClick()
        composeRule.waitForIdle()
        org.junit.Assert.assertEquals(AppLanguage.ENGLISH, currentLanguage.value)

        val rootBoundsEn = composeRule.onNodeWithTag("onboarding_screen").getBoundsInRoot()
        val skipBoundsEn = composeRule.onNodeWithTag("onboarding_skip_button").getBoundsInRoot()
        val rootCenterEn = (rootBoundsEn.left + rootBoundsEn.right) / 2f
        val skipCenterEn = (skipBoundsEn.left + skipBoundsEn.right) / 2f
        assertTrue("After selecting English, Skip button must move to top-right (LTR)", skipCenterEn > rootCenterEn)
    }
}
