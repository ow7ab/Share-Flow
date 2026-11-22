package com.example.shareflow.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.CategoryCard
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.DeviceCard
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.FileItem
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PermissionExplanationDialog
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureTabIndicator
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.components.TransferCard
import com.example.shareflow.core.designsystem.components.TransferProgressCard
import com.example.shareflow.core.designsystem.theme.DarkColorScheme
import com.example.shareflow.core.designsystem.theme.LightColorScheme
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFlowDesignSystemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders all core Material 3 design system components and handles interactions`() {
        var primaryClicks = 0
        var secondaryClicks = 0
        var deviceClicks = 0
        var categoryClicks = 0

        val sampleRecord = TransferRecord(
            id = "rec-1",
            sessionId = "sess-1",
            fileName = "quarterly_report.pdf",
            mimeType = "application/pdf",
            fileType = TransferFileType.DOCUMENT,
            fileSizeBytes = 2_097_152L,
            bytesTransferred = 2_097_152L,
            direction = TransferDirection.SENT,
            status = TransferStatus.COMPLETED_VERIFIED,
            peerDeviceId = "peer-1",
            peerDeviceName = "Pixel 9",
            localUri = null,
            sha256Checksum = "a".repeat(64),
            isChecksumVerified = true,
            startedAtEpochMillis = 1_700_000_000_000L,
            completedAtEpochMillis = 1_700_000_002_000L
        )
        val sampleDevice = PeerDevice(
            deviceId = "peer-1",
            displayName = "Pixel 9",
            deviceType = DeviceType.PHONE,
            connectionType = ConnectionType.WIFI_DIRECT,
            isTrusted = true
        )
        val sampleFile = LocalFileEntry(
            uriString = "content://docs/1",
            displayName = "slides.pdf",
            mimeType = "application/pdf",
            sizeBytes = 512_000L,
            lastModifiedEpochMillis = 1_700_000_000_000L,
            category = FileCategory.DOCUMENTS,
            isDirectory = false
        )

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.LIGHT,
                accentColor = AccentColor.COBALT,
                appLanguage = AppLanguage.ENGLISH
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PrimaryActionButton(
                        text = "Send Now",
                        onClick = { primaryClicks++ },
                        modifier = Modifier.testTag("ds_primary_btn")
                    )
                    SecondaryActionButton(
                        text = "Cancel",
                        onClick = { secondaryClicks++ },
                        modifier = Modifier.testTag("ds_secondary_btn")
                    )
                    TransferCard(
                        record = sampleRecord,
                        modifier = Modifier.testTag("ds_transfer_card")
                    )
                    DeviceCard(
                        device = sampleDevice,
                        onClick = { deviceClicks++ },
                        modifier = Modifier.testTag("ds_device_card")
                    )
                    FileItem(
                        file = sampleFile,
                        onClick = {},
                        selectable = true,
                        selected = true,
                        modifier = Modifier.testTag("ds_file_item")
                    )
                    CategoryCard(
                        title = "Photos",
                        icon = Icons.Filled.PhotoLibrary,
                        itemCount = 24,
                        totalBytes = 10_485_760L,
                        onClick = { categoryClicks++ },
                        modifier = Modifier.testTag("ds_category_card")
                    )
                    StorageIndicator(
                        usedBytes = 32L * 1024 * 1024 * 1024,
                        totalBytes = 128L * 1024 * 1024 * 1024,
                        modifier = Modifier.testTag("ds_storage_indicator")
                    )
                    TransferProgressCard(
                        fileName = "backup.zip",
                        peerName = "Pixel Tablet",
                        direction = TransferDirection.RECEIVED,
                        bytesTransferred = 5_242_880L,
                        totalBytes = 10_485_760L,
                        bytesPerSecond = 2_097_152L,
                        modifier = Modifier.testTag("ds_transfer_progress")
                    )
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title = "No files",
                        body = "Select a folder to begin.",
                        modifier = Modifier.testTag("ds_empty_state")
                    )
                    ErrorState(
                        message = "Storage unavailable",
                        onRetry = {},
                        modifier = Modifier.testTag("ds_error_state")
                    )
                    LoadingState(modifier = Modifier.testTag("ds_loading_state"))
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ds_primary_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("ds_secondary_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("ds_device_card").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("ds_category_card").performScrollTo().performClick()

        assertEquals(1, primaryClicks)
        assertEquals(1, secondaryClicks)
        assertEquals(1, deviceClicks)
        assertEquals(1, categoryClicks)
        composeTestRule.onNodeWithTag("ds_transfer_card").assertExists()
        composeTestRule.onNodeWithTag("ds_file_item").assertExists()
        composeTestRule.onNodeWithTag("ds_storage_indicator").assertExists()
        composeTestRule.onNodeWithTag("ds_transfer_progress").assertExists()
        composeTestRule.onNodeWithTag("ds_empty_state").assertExists()
        composeTestRule.onNodeWithTag("ds_error_state").assertExists()
        composeTestRule.onNodeWithTag("ds_loading_state").assertExists()
    }

    @Test
    fun `renders PermissionExplanationDialog and ConfirmationDialog cleanly`() {
        var granted = false
        var confirmed = false
        val showPermission = mutableStateOf(true)
        val showConfirm = mutableStateOf(false)

        composeTestRule.setContent {
            ShareFlowTheme(themeMode = ThemeMode.DARK, appLanguage = AppLanguage.ENGLISH) {
                if (showPermission.value) {
                    PermissionExplanationDialog(
                        title = "Nearby Devices",
                        rationale = "Required to discover nearby Android devices over Wi-Fi Direct.",
                        onGrantClick = {
                            granted = true
                            showPermission.value = false
                            showConfirm.value = true
                        },
                        onDismiss = { showPermission.value = false },
                        confirmTestTag = "perm_grant_btn"
                    )
                }
                if (showConfirm.value) {
                    ConfirmationDialog(
                        title = "Delete Item?",
                        body = "This removes the encrypted entry permanently.",
                        confirmLabel = "Delete",
                        onConfirm = {
                            confirmed = true
                            showConfirm.value = false
                        },
                        onDismiss = { showConfirm.value = false },
                        isDestructive = true,
                        confirmTestTag = "confirm_action_btn"
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("perm_grant_btn").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertTrue(granted)

        composeTestRule.onNodeWithTag("confirm_action_btn").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertTrue(confirmed)
    }

    @Test
    fun `applies Arabic RTL layout direction localized strings and official Share Flow brand palette`() {
        var capturedDirection: LayoutDirection? = null
        var capturedPrimary: Color? = null
        var capturedBackground: Color? = null
        var capturedOnBackground: Color? = null
        var capturedSecondary: Color? = null
        var capturedTertiary: Color? = null

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.LIGHT,
                useDynamicColor = false,
                accentColor = AccentColor.EMERALD,
                appLanguage = AppLanguage.ARABIC
            ) {
                capturedDirection = LocalLayoutDirection.current
                capturedPrimary = MaterialTheme.colorScheme.primary
                capturedBackground = MaterialTheme.colorScheme.background
                capturedOnBackground = MaterialTheme.colorScheme.onBackground
                capturedSecondary = MaterialTheme.colorScheme.secondary
                capturedTertiary = MaterialTheme.colorScheme.tertiary
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.testTag("rtl_settings_title")
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Rtl, capturedDirection)
        assertEquals(Color(0xFFFEF3B3), capturedBackground)
        assertEquals(Color(0xFF0A1821), capturedOnBackground)
        assertEquals(Color(0xFF1D59BB), capturedPrimary)
        assertEquals(Color(0xFF76B9FF), capturedSecondary)
        assertEquals(Color(0xFFFEBD19), capturedTertiary)
        assertEquals(Color(0xFFFEF3B3), ShareFlowBrandColors.SoftCream)
        assertEquals(
            listOf(Color(0xFF1D59BB), Color(0xFF76B9FF), Color(0xFFFEF3B3), Color(0xFFFEBD19)),
            ShareFlowBrandGradients.SignatureStops
        )
        assertEquals(LightColorScheme.primary, DarkColorScheme.primary)
        composeTestRule.onNodeWithTag("rtl_settings_title").assertTextEquals("الإعدادات")

        val arabicSize = ShareFlowFormatters.formatBytes(2048L, Locale.forLanguageTag("ar"))
        assertTrue("Expected Arabic KB unit, got: $arabicSize", arabicSize.contains("ك.ب"))
    }

    @Test
    fun `applies English LTR layout direction and strings`() {
        var capturedDirection: LayoutDirection? = null

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.LIGHT,
                appLanguage = AppLanguage.ENGLISH
            ) {
                capturedDirection = LocalLayoutDirection.current
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.testTag("ltr_settings_title")
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, capturedDirection)
        assertEquals(Color(0xEB0A1821), ShareFlowBrandColors.GlassNavBackground)
        assertEquals(Color(0x6676B9FF), ShareFlowBrandColors.GlassNavBorder)
        composeTestRule.onNodeWithTag("ltr_settings_title").assertTextEquals("Settings")
    }

    @Test
    fun `renders signature gradient determinate indeterminate progress bars and tab indicator`() {
        composeTestRule.setContent {
            ShareFlowTheme(appLanguage = AppLanguage.ENGLISH) {
                Column {
                    ShareFlowSignatureProgressBar(
                        progress = 0.65f,
                        modifier = Modifier.testTag("signature_progress_determinate")
                    )
                    ShareFlowSignatureProgressBar(
                        modifier = Modifier.testTag("signature_progress_indeterminate")
                    )
                    ShareFlowSignatureTabIndicator(
                        modifier = Modifier.testTag("signature_tab_indicator")
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("signature_progress_determinate").assertExists()
        composeTestRule.onNodeWithTag("signature_progress_indeterminate").assertExists()
        composeTestRule.onNodeWithTag("signature_tab_indicator").assertExists()
    }
}
