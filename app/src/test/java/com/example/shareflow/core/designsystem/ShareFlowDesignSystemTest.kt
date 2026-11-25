package com.example.shareflow.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
    fun `applies Arabic RTL layout direction localized strings and Marple Light and Dark color palettes`() {
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
        // Pearl White Light Mode Official Palette
        assertEquals(Color(0xFFF8F7F4), capturedBackground)
        assertEquals(Color(0xFF252525), capturedOnBackground)
        assertEquals(Color(0xFFFFFFFF), LightColorScheme.surface)
        assertEquals(Color(0xFFF1F0EC), LightColorScheme.surfaceContainer)
        assertEquals(Color(0xFF777777), LightColorScheme.onSurfaceVariant)
        assertEquals(Color(0xFFEAE8E3), LightColorScheme.outlineVariant)
        assertEquals(Color(0xFFC9C7C2), LightColorScheme.outline)
        // Immutable Official Brand Palette
        assertEquals(Color(0xFFCA2851), capturedPrimary)
        assertEquals(Color(0xFFFF6766), capturedSecondary)
        assertEquals(Color(0xFFFFB173), capturedTertiary)
        assertEquals(Color(0xFFFFE3B3), ShareFlowBrandColors.SoftCream)
        assertEquals(
            listOf(Color(0xFFCA2851), Color(0xFFFF6766), Color(0xFFFFB173), Color(0xFFFFE3B3)),
            ShareFlowBrandGradients.SignatureStops
        )
        assertEquals(Color(0xFFCA2851), LightColorScheme.primary)
        // Deep Charcoal Dark Mode Official Palette
        assertEquals(Color(0xFFFF6D8C), DarkColorScheme.primary)
        assertEquals(Color(0xFF171717), DarkColorScheme.background)
        assertEquals(Color(0xFF222222), DarkColorScheme.surface)
        assertEquals(Color(0xFF292929), DarkColorScheme.surfaceContainer)
        assertEquals(Color(0xFF303030), DarkColorScheme.surfaceContainerHigh)
        assertEquals(Color(0xFFFAFAFA), DarkColorScheme.onBackground)
        assertEquals(Color(0xFFAAAAAA), DarkColorScheme.onSurfaceVariant)
        assertEquals(Color(0xFF383838), DarkColorScheme.outlineVariant)
        assertEquals(Color(0xFF666666), DarkColorScheme.outline)
        composeTestRule.onNodeWithTag("rtl_settings_title").assertTextEquals("الإعدادات")

        val arabicSize = ShareFlowFormatters.formatBytes(2048L, Locale.forLanguageTag("ar"))
        assertTrue("Expected Arabic KB unit, got: $arabicSize", arabicSize.contains("ك.ب"))
    }

    @Test
    fun `applies English LTR layout direction and Marple Dark theme tokens`() {
        var capturedDirection: LayoutDirection? = null
        var capturedDarkBackground: Color? = null
        var capturedDarkPrimary: Color? = null
        var capturedDarkSecondary: Color? = null
        var capturedDarkTertiary: Color? = null
        var capturedDarkOnBackground: Color? = null

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = ThemeMode.DARK,
                appLanguage = AppLanguage.ENGLISH
            ) {
                capturedDirection = LocalLayoutDirection.current
                capturedDarkBackground = MaterialTheme.colorScheme.background
                capturedDarkPrimary = MaterialTheme.colorScheme.primary
                capturedDarkSecondary = MaterialTheme.colorScheme.secondary
                capturedDarkTertiary = MaterialTheme.colorScheme.tertiary
                capturedDarkOnBackground = MaterialTheme.colorScheme.onBackground
                Text(
                    text = stringResource(R.string.settings_title),
                    modifier = Modifier.testTag("ltr_settings_title")
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(LayoutDirection.Ltr, capturedDirection)
        assertEquals(Color(0xFF171717), capturedDarkBackground)
        assertNotEquals(Color.Black, capturedDarkBackground)
        assertEquals(Color(0xFFFF6D8C), capturedDarkPrimary)
        assertEquals(Color(0xFFFF8281), capturedDarkSecondary)
        assertEquals(Color(0xFFFFB173), capturedDarkTertiary)
        assertEquals(Color(0xFFFAFAFA), capturedDarkOnBackground)
        assertEquals(Color(0xEBFFFFFF), ShareFlowBrandColors.GlassNavBackground)
        assertEquals(Color(0xCCEAE8E3), ShareFlowBrandColors.GlassNavBorder)
        assertEquals(Color(0xFFEAE8E3), ShareFlowBrandColors.GlassCardBorder)
        assertEquals(Color.Transparent, com.example.shareflow.core.designsystem.theme.LightSFGlassTokens.ambientGlowPrimary)
        assertEquals(Color.Transparent, com.example.shareflow.core.designsystem.theme.DarkSFGlassTokens.ambientGlowPrimary)
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

    @Test
    fun `renders floating glass bottom navigation bar in light and dark modes and switches destinations`() {
        val currentRoute = mutableStateOf(com.example.shareflow.navigation.ShareFlowRoutes.HOME)
        val themeMode = mutableStateOf(ThemeMode.LIGHT)

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = themeMode.value,
                appLanguage = AppLanguage.ENGLISH
            ) {
                com.example.shareflow.navigation.ShareFlowFloatingGlassNavBar(
                    currentRoute = currentRoute.value,
                    onNavigateTopLevel = { route -> currentRoute.value = route }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("shareflow_navigation_bar").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_item_home").assertIsDisplayed()
        composeTestRule.onNodeWithTag("nav_item_files").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.shareflow.navigation.ShareFlowRoutes.FILES_HUB, currentRoute.value)

        // Switch to Dark Mode and verify glass bar renders cleanly and navigates
        themeMode.value = ThemeMode.DARK
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("nav_item_settings").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(com.example.shareflow.navigation.ShareFlowRoutes.SETTINGS, currentRoute.value)
    }

    @Test
    fun `renders compact home layout and glass navigation bar in Arabic RTL light and dark modes`() {
        val themeMode = mutableStateOf(ThemeMode.LIGHT)
        val readyState = com.example.shareflow.ui.home.HomeUiState.Ready(
            settings = com.example.shareflow.domain.model.AppSettings(
                appLanguage = AppLanguage.ARABIC
            ),
            totalTransferCount = 0,
            trustedDeviceCount = 0,
            recentTransfers = emptyList(),
            nearbyDevices = emptyList(),
            recentFiles = emptyList(),
            storageSummary = null,
            connectionState = com.example.shareflow.domain.model.ConnectionState.Idle,
            networkStatus = com.example.shareflow.network.DeviceNetworkStatus(
                isWifiConnected = true,
                localIpv4Address = "192.168.1.20"
            )
        )

        composeTestRule.setContent {
            ShareFlowTheme(
                themeMode = themeMode.value,
                appLanguage = AppLanguage.ARABIC
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize()
                ) {
                    com.example.shareflow.ui.home.HomeDashboardContent(
                        uiState = readyState,
                        onNavigateToRoute = {},
                        bottomBarClearance = 88.dp
                    )
                    com.example.shareflow.navigation.ShareFlowFloatingGlassNavBar(
                        currentRoute = com.example.shareflow.navigation.ShareFlowRoutes.HOME,
                        onNavigateTopLevel = {},
                        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithTag("home_hero_card", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        )
        composeTestRule.onNodeWithTag("home_brand_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_connection_status").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_receive_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_unified_transfer_hint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shareflow_navigation_bar").assertIsDisplayed()

        // Verify Dark Mode Arabic RTL rendering
        themeMode.value = ThemeMode.DARK
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("home_send_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("home_receive_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shareflow_navigation_bar").assertIsDisplayed()
    }

    @Test
    fun `renders all Share Flow 2_0 SF components in Light and Deep Navy Dark modes and preserves official brand colors`() {
        val themeMode = mutableStateOf(ThemeMode.LIGHT)
        var capturedPrimaryLight: Color? = null
        var capturedPrimaryDark: Color? = null
        var primaryButtonClicks = 0
        var secondaryButtonClicks = 0
        var iconButtonClicks = 0
        var settingsItemClicks = 0
        var dialogConfirmed = false
        val showDialog = mutableStateOf(false)

        composeTestRule.setContent {
            // Even when useDynamicColor = true, Share Flow 2.0 preserves the official brand palette
            ShareFlowTheme(
                themeMode = themeMode.value,
                useDynamicColor = true,
                appLanguage = AppLanguage.ENGLISH
            ) {
                if (themeMode.value == ThemeMode.LIGHT) {
                    capturedPrimaryLight = MaterialTheme.colorScheme.primary
                } else {
                    capturedPrimaryDark = MaterialTheme.colorScheme.primary
                }
                com.example.shareflow.core.designsystem.components.SFBackground(
                    modifier = Modifier.testTag("sf2_background")
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        com.example.shareflow.core.designsystem.components.SFTopBar(
                            title = "ShareFlow 2.0",
                            onNavigateBack = {},
                            modifier = Modifier.testTag("sf2_top_bar")
                        )
                        com.example.shareflow.core.designsystem.components.SFSectionHeader(
                            title = "Transfers",
                            actionLabel = "All",
                            onActionClick = {},
                            modifier = Modifier.testTag("sf2_section_header")
                        )
                        com.example.shareflow.core.designsystem.components.SFGlassCard(
                            modifier = Modifier.testTag("sf2_glass_card")
                        ) {
                            Text("Liquid Glass Card")
                        }
                        com.example.shareflow.core.designsystem.components.SFPrimaryButton(
                            text = "Open Dialog",
                            onClick = {
                                primaryButtonClicks++
                                showDialog.value = true
                            },
                            modifier = Modifier.testTag("sf2_primary_btn")
                        )
                        com.example.shareflow.core.designsystem.components.SFSecondaryButton(
                            text = "Secondary",
                            onClick = { secondaryButtonClicks++ },
                            modifier = Modifier.testTag("sf2_secondary_btn")
                        )
                        com.example.shareflow.core.designsystem.components.SFIconButton(
                            icon = Icons.Filled.Folder,
                            contentDescription = "Folder",
                            onClick = { iconButtonClicks++ },
                            modifier = Modifier.testTag("sf2_icon_btn")
                        )
                        com.example.shareflow.core.designsystem.components.SFProgressIndicator(
                            progress = 0.72f,
                            modifier = Modifier.testTag("sf2_progress")
                        )
                        com.example.shareflow.core.designsystem.components.SFLoadingIndicator(
                            message = "Syncing",
                            modifier = Modifier.testTag("sf2_loading")
                        )
                        com.example.shareflow.core.designsystem.components.SFSettingsItem(
                            title = "Appearance",
                            subtitle = "Liquid Glass",
                            leadingIcon = Icons.Filled.Folder,
                            onClick = { settingsItemClicks++ },
                            modifier = Modifier.testTag("sf2_settings_item")
                        )
                        com.example.shareflow.core.designsystem.components.SFSnackbarSurface(
                            message = "Transfer verified",
                            modifier = Modifier.testTag("sf2_snackbar")
                        )
                    }
                    if (showDialog.value) {
                        com.example.shareflow.core.designsystem.components.SFDialog(
                            onDismissRequest = { showDialog.value = false },
                            title = "Confirm",
                            body = "Proceed with ShareFlow 2.0 action?",
                            confirmLabel = "Confirm",
                            onConfirm = {
                                dialogConfirmed = true
                                showDialog.value = false
                            },
                            onDismiss = { showDialog.value = false },
                            confirmTestTag = "sf2_dialog_confirm"
                        )
                    }
                }
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(Color(0xFFCA2851), capturedPrimaryLight)
        composeTestRule.onNodeWithTag("sf2_background").assertExists()
        composeTestRule.onNodeWithTag("sf2_top_bar").assertExists()
        composeTestRule.onNodeWithTag("sf2_section_header").assertExists()
        composeTestRule.onNodeWithTag("sf2_glass_card").assertExists()
        composeTestRule.onNodeWithTag("sf2_progress").assertExists()
        composeTestRule.onNodeWithTag("sf2_loading").assertExists()
        composeTestRule.onNodeWithTag("sf2_snackbar").assertExists()

        composeTestRule.onNodeWithTag("sf2_secondary_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sf2_icon_btn").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sf2_settings_item").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("sf2_primary_btn").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("sf2_dialog_confirm").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, primaryButtonClicks)
        assertEquals(1, secondaryButtonClicks)
        assertEquals(1, iconButtonClicks)
        assertEquals(1, settingsItemClicks)
        assertTrue(dialogConfirmed)

        // Switch to Deep Navy Dark Mode
        themeMode.value = ThemeMode.DARK
        composeTestRule.waitForIdle()
        assertEquals(Color(0xFFFF6D8C), capturedPrimaryDark)
        composeTestRule.onNodeWithTag("sf2_glass_card").assertExists()
    }
}
