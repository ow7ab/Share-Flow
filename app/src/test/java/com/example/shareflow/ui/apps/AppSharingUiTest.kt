package com.example.shareflow.ui.apps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.ApkExtractionStatus
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ReceivedApkIntegrityStatus
import com.example.shareflow.domain.model.ShareableAppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp")
class AppSharingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleApps = listOf(
        ShareableAppInfo(
            packageName = "com.example.notes",
            appName = "Quick Notes",
            versionName = "2.1.0",
            versionCode = 21L,
            baseApkPath = "/data/app/notes/base.apk",
            splitApkPaths = emptyList(),
            totalApkSizeBytes = 12L * 1024L * 1024L,
            isSystemApp = false,
            isSplitApk = false,
            extractionStatus = ApkExtractionStatus.EXTRACTABLE
        ),
        ShareableAppInfo(
            packageName = "com.example.camera",
            appName = "Studio Camera",
            versionName = "4.0.2",
            versionCode = 402L,
            baseApkPath = "/data/app/cam/base.apk",
            splitApkPaths = listOf("/data/app/cam/split_arm64.apk"),
            totalApkSizeBytes = 28L * 1024L * 1024L,
            isSystemApp = false,
            isSplitApk = true,
            extractionStatus = ApkExtractionStatus.SPLIT_APK_EXTRACTABLE
        ),
        ShareableAppInfo(
            packageName = "com.example.locked",
            appName = "Protected Banking",
            versionName = "5.0.0",
            versionCode = 500L,
            baseApkPath = null,
            splitApkPaths = emptyList(),
            totalApkSizeBytes = null,
            isSystemApp = false,
            isSplitApk = false,
            extractionStatus = ApkExtractionStatus.UNAVAILABLE,
            unavailableReason = "Base APK file is protected or unreadable on this device."
        )
    )

    @Test
    fun `installed apps tab displays metadata split and unavailable notices and supports multi-select send`() {
        var searchEntered = ""
        var toggledPkg: String? = null
        var sendClicked = false

        val state = AppSharingUiState(
            activeTab = AppSharingTab.INSTALLED,
            isLoadingInstalled = false,
            allInstalledApps = sampleApps,
            filteredInstalledApps = sampleApps,
            selectedPackages = setOf("com.example.notes"),
            selectedCount = 1,
            selectedTotalBytes = 12L * 1024L * 1024L
        )

        composeRule.setContent {
            ShareFlowTheme {
                AppSharingContent(
                    uiState = state,
                    onNavigateBack = {},
                    onSelectTab = {},
                    onSearchQueryChange = { searchEntered = it },
                    onToggleSystemApps = {},
                    onRefreshInstalled = {},
                    onToggleAppSelection = { toggledPkg = it.packageName },
                    onClearSelection = {},
                    onExtractAndSend = { sendClicked = true },
                    onRequestInstallReview = {},
                    onDismissInstallReview = {},
                    onConfirmInstall = {},
                    onConsumeFeedback = {},
                    iconLoader = { null }
                )
            }
        }

        // Search input callback works
        composeRule.onNodeWithTag("apps_search_field").performTextInput("Camera")
        assertEquals("Camera", searchEntered)

        // Scroll to standalone & split apps and verify metadata + split explanation
        composeRule.onNodeWithTag("apps_installed_list")
            .performScrollToNode(hasTestTag("app_item_com.example.notes"))
        composeRule.onNodeWithText("Quick Notes").assertIsDisplayed()
        composeRule.onNodeWithText("com.example.notes").assertIsDisplayed()
        composeRule.onNodeWithTag("app_checkbox_com.example.notes").assertIsEnabled()

        composeRule.onNodeWithTag("apps_installed_list")
            .performScrollToNode(hasTestTag("app_item_com.example.camera"))
        composeRule.onNodeWithText("Studio Camera").assertIsDisplayed()
        composeRule.onNodeWithTag("app_split_note_com.example.camera", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("app_item_com.example.camera").performClick()
        assertEquals("com.example.camera", toggledPkg)

        // Scroll to unavailable app and verify clear explanation + disabled checkbox
        composeRule.onNodeWithTag("apps_installed_list")
            .performScrollToNode(hasTestTag("app_item_com.example.locked"))
        composeRule.onNodeWithTag("app_unavailable_reason_com.example.locked", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("app_checkbox_com.example.locked").assertIsNotEnabled()

        // Bottom bar selection summary & Extract & Send CTA
        composeRule.onNodeWithTag("apps_selection_summary").assertIsDisplayed()
        composeRule.onNodeWithTag("apps_extract_and_send_button").assertIsEnabled().performClick()
        assertTrue(sendClicked)
    }

    @Test
    fun `received APKs tab enables install only for verified APKs and requires explicit approval dialog`() {
        val verifiedApk = ReceivedApkInfo(
            id = "rec_ok",
            fileName = "Quick_Notes_v2.1.0.apk",
            filePath = "/storage/ShareFlowReceived/Quick_Notes_v2.1.0.apk",
            fileSizeBytes = 8L * 1024L * 1024L,
            packageName = "com.example.notes",
            appLabel = "Quick Notes",
            versionName = "2.1.0",
            versionCode = 21L,
            expectedSha256 = "a".repeat(64),
            actualSha256 = "a".repeat(64),
            integrityStatus = ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE,
            statusDetail = "SHA-256 checksum and Android APK archive verified.",
            receivedAtEpochMillis = 1_760_000_000_000L,
            peerDeviceName = "Pixel 9"
        )
        val tamperedApk = ReceivedApkInfo(
            id = "rec_bad",
            fileName = "Tampered.apk",
            filePath = "/storage/ShareFlowReceived/Tampered.apk",
            fileSizeBytes = 4L * 1024L * 1024L,
            packageName = null,
            appLabel = null,
            versionName = null,
            versionCode = null,
            expectedSha256 = "b".repeat(64),
            actualSha256 = "c".repeat(64),
            integrityStatus = ReceivedApkIntegrityStatus.CHECKSUM_MISMATCH,
            statusDetail = "SHA-256 checksum does not match.",
            receivedAtEpochMillis = 1_760_000_000_000L,
            peerDeviceName = "Pixel 9"
        )

        var requestedInstallId: String? = null
        var confirmedInstall = false
        var uiState by mutableStateOf(
            AppSharingUiState(
                activeTab = AppSharingTab.RECEIVED,
                isLoadingInstalled = false,
                receivedApks = listOf(verifiedApk, tamperedApk),
                pendingInstallApk = null
            )
        )

        composeRule.setContent {
            ShareFlowTheme {
                AppSharingContent(
                    uiState = uiState,
                    onNavigateBack = {},
                    onSelectTab = {},
                    onSearchQueryChange = {},
                    onToggleSystemApps = {},
                    onRefreshInstalled = {},
                    onToggleAppSelection = {},
                    onClearSelection = {},
                    onExtractAndSend = {},
                    onRequestInstallReview = { apk ->
                        requestedInstallId = apk.id
                        uiState = uiState.copy(pendingInstallApk = apk)
                    },
                    onDismissInstallReview = {
                        uiState = uiState.copy(pendingInstallApk = null)
                    },
                    onConfirmInstall = {
                        confirmedInstall = true
                        uiState = uiState.copy(pendingInstallApk = null)
                    },
                    onConsumeFeedback = {},
                    iconLoader = { null }
                )
            }
        }

        // Verified APK install button enabled -> clicking opens explicit confirmation dialog
        composeRule.onNodeWithTag("received_apk_install_rec_ok")
            .assertIsEnabled()
            .performClick()
        assertEquals("rec_ok", requestedInstallId)

        // Explicit confirmation dialog shown before system installer handoff
        composeRule.onNodeWithTag("apps_install_confirm_button")
            .assertIsDisplayed()
            .performClick()
        assertTrue(confirmedInstall)

        // Scroll to tampered APK card and assert its install button is strictly disabled
        composeRule.onNodeWithTag("apps_received_list")
            .performScrollToNode(hasTestTag("received_apk_card_rec_bad"))
        composeRule.onNodeWithTag("received_apk_install_rec_bad").assertIsNotEnabled()
    }

    @Test
    fun `application sharing screen renders cleanly in Arabic RTL`() {
        val state = AppSharingUiState(
            activeTab = AppSharingTab.INSTALLED,
            isLoadingInstalled = false,
            allInstalledApps = sampleApps,
            filteredInstalledApps = sampleApps
        )

        composeRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ARABIC) {
                AppSharingContent(
                    uiState = state,
                    onNavigateBack = {},
                    onSelectTab = {},
                    onSearchQueryChange = {},
                    onToggleSystemApps = {},
                    onRefreshInstalled = {},
                    onToggleAppSelection = {},
                    onClearSelection = {},
                    onExtractAndSend = {},
                    onRequestInstallReview = {},
                    onDismissInstallReview = {},
                    onConfirmInstall = {},
                    onConsumeFeedback = {},
                    iconLoader = { null }
                )
            }
        }

        composeRule.onNodeWithTag("apps_tab_installed").assertIsDisplayed()
        composeRule.onNodeWithTag("apps_tab_received").assertIsDisplayed()
        composeRule.onNodeWithTag("apps_installed_list").assertIsDisplayed()
    }
}
