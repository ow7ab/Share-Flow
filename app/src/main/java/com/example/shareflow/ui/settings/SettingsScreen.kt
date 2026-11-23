package com.example.shareflow.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.designsystem.theme.swatchColor
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.data.update.CloudUpdateManifest
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.DiscoveryVisibility
import com.example.shareflow.domain.model.FileOverwritePolicy
import com.example.shareflow.domain.model.HistoryRetentionPolicy
import com.example.shareflow.domain.model.PreferredConnectionMode
import com.example.shareflow.domain.model.ThemeMode

@Composable
fun SettingsScreen(
    onOpenVault: (() -> Unit)? = null,
    bottomBarClearance: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val factory = remember(container) {
        shareFlowViewModelFactory {
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                keystoreCipher = container.keystoreCipher,
                deviceIdentityManager = container.deviceIdentityManager,
                transferHistoryRepository = container.transferHistoryRepository,
                trustedDeviceRepository = container.trustedDeviceRepository,
                utilitiesRepository = container.utilitiesRepository,
                cloudUpdateManager = container.cloudUpdateManager,
                clock = container.clock
            )
        }
    }
    val viewModel: SettingsViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val receiveFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.onDefaultSaveDirectorySelected(uri.toString())
        }
    }

    SettingsContent(
        uiState = uiState,
        onDeviceNameDraftChange = viewModel::onDeviceNameDraftChange,
        onSaveDeviceName = viewModel::saveDeviceName,
        onPortDraftChange = viewModel::onPortDraftChange,
        onSavePort = viewModel::saveTransferPort,
        onThemeModeSelected = viewModel::onThemeModeSelected,
        onDynamicColorChanged = viewModel::onDynamicColorChanged,
        onAccentColorSelected = viewModel::onAccentColorSelected,
        onAppLanguageSelected = viewModel::onAppLanguageSelected,
        onPreferWifiDirectChanged = viewModel::onPreferWifiDirectChanged,
        onVerifySha256Changed = viewModel::onVerifySha256Changed,
        onAutoAcceptTrustedChanged = viewModel::onAutoAcceptTrustedChanged,
        onRequireVaultAuthChanged = viewModel::onRequireVaultAuthChanged,
        onResetToDefaults = viewModel::resetToDefaults,
        onFeedbackConsumed = viewModel::consumeFeedbackEvent,
        onPickReceiveFolder = { receiveFolderPicker.launch(null) },
        onResetReceiveFolder = { viewModel.onDefaultSaveDirectorySelected(null) },
        onMaxConcurrentSelected = viewModel::onMaxConcurrentTransfersSelected,
        onOverwritePolicySelected = viewModel::onFileOverwritePolicySelected,
        onNotificationsChanged = viewModel::onTransferNotificationsChanged,
        onHistoryRetentionSelected = viewModel::onHistoryRetentionSelected,
        onPruneExpiredHistory = viewModel::pruneExpiredHistoryNow,
        onDiscoveryVisibilitySelected = viewModel::onDiscoveryVisibilitySelected,
        onConnectionModeSelected = viewModel::onPreferredConnectionModeSelected,
        onBiometricPreferredChanged = viewModel::onBiometricUnlockPreferredChanged,
        onOpenVault = onOpenVault,
        onClearCache = viewModel::clearTemporaryCache,
        onClearHistory = viewModel::clearTransferHistory,
        onClearFavorites = viewModel::clearFavorites,
        onRevokeTrustedDevices = viewModel::revokeAllTrustedDevices,
        onCloudUpdateUrlDraftChange = viewModel::onCloudUpdateUrlDraftChange,
        onSaveCloudUpdateUrl = viewModel::saveCloudUpdateUrl,
        onCheckForCloudUpdates = viewModel::checkForCloudUpdates,
        onDownloadCloudUpdate = viewModel::downloadCloudUpdate,
        onCancelCloudUpdateDownload = viewModel::cancelCloudUpdateDownload,
        onInstallVerifiedCloudUpdate = { apkPath ->
            viewModel.installVerifiedCloudUpdate(
                apkPath = apkPath,
                onNeedInstallPermission = {
                    val intent = container.permissionManager.createManageUnknownSourcesIntent()
                    runCatching { context.startActivity(intent) }
                }
            )
        },
        bottomBarClearance = bottomBarClearance,
        modifier = modifier
    )
}

@Composable
fun SettingsContent(
    uiState: SettingsUiState,
    onDeviceNameDraftChange: (String) -> Unit,
    onSaveDeviceName: () -> Unit,
    onPortDraftChange: (String) -> Unit,
    onSavePort: () -> Unit,
    onThemeModeSelected: (ThemeMode) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onAccentColorSelected: (AccentColor) -> Unit,
    onAppLanguageSelected: (AppLanguage) -> Unit,
    onPreferWifiDirectChanged: (Boolean) -> Unit,
    onVerifySha256Changed: (Boolean) -> Unit,
    onAutoAcceptTrustedChanged: (Boolean) -> Unit,
    onRequireVaultAuthChanged: (Boolean) -> Unit,
    onResetToDefaults: () -> Unit,
    onFeedbackConsumed: () -> Unit,
    onPickReceiveFolder: () -> Unit = {},
    onResetReceiveFolder: () -> Unit = {},
    onMaxConcurrentSelected: (Int) -> Unit = {},
    onOverwritePolicySelected: (FileOverwritePolicy) -> Unit = {},
    onNotificationsChanged: (Boolean) -> Unit = {},
    onHistoryRetentionSelected: (HistoryRetentionPolicy) -> Unit = {},
    onPruneExpiredHistory: () -> Unit = {},
    onDiscoveryVisibilitySelected: (DiscoveryVisibility) -> Unit = {},
    onConnectionModeSelected: (PreferredConnectionMode) -> Unit = {},
    onBiometricPreferredChanged: (Boolean) -> Unit = {},
    onOpenVault: (() -> Unit)? = null,
    onClearCache: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    onClearFavorites: () -> Unit = {},
    onRevokeTrustedDevices: () -> Unit = {},
    onCloudUpdateUrlDraftChange: (String) -> Unit = {},
    onSaveCloudUpdateUrl: () -> Unit = {},
    onCheckForCloudUpdates: () -> Unit = {},
    onDownloadCloudUpdate: (CloudUpdateManifest) -> Unit = {},
    onCancelCloudUpdateDownload: () -> Unit = {},
    onInstallVerifiedCloudUpdate: (String) -> Unit = {},
    bottomBarClearance: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val snackbarHostState = remember { SnackbarHostState() }

    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showClearFavoritesDialog by rememberSaveable { mutableStateOf(false) }
    var showRevokeTrustedDialog by rememberSaveable { mutableStateOf(false) }

    val deviceNameSavedText = stringResource(R.string.settings_device_name_saved)
    val portSavedText = stringResource(R.string.settings_port_saved)
    val resetDoneText = stringResource(R.string.settings_reset_done)
    val folderSavedText = stringResource(R.string.settings_feedback_folder_saved)
    val cacheClearedText = stringResource(R.string.settings_feedback_cache_cleared)
    val historyClearedText = stringResource(R.string.settings_feedback_history_cleared)
    val historyPrunedText = stringResource(R.string.settings_feedback_history_pruned)
    val favoritesClearedText = stringResource(R.string.settings_feedback_favorites_cleared)
    val trustedRevokedText = stringResource(R.string.settings_feedback_trusted_revoked)
    val updateUrlSavedText = stringResource(R.string.settings_feedback_update_url_saved)
    val updateInstallPermText = stringResource(R.string.settings_feedback_update_install_perm)
    val updateInstallFailedText = stringResource(R.string.settings_feedback_update_install_failed)

    val isDark = when (uiState.settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val deviceModelLabel = remember {
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }
    }
    val androidVersionLabel = remember {
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    val hasNearbyPerm = remember(context) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }
    val hasCameraPerm = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    val hasNotificationsPerm = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val hasContactsPerm = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    }

    LaunchedEffect(uiState.feedbackEvent) {
        val message = when (uiState.feedbackEvent) {
            SettingsFeedbackType.DEVICE_NAME_SAVED -> deviceNameSavedText
            SettingsFeedbackType.PORT_SAVED -> portSavedText
            SettingsFeedbackType.RESET_DONE -> resetDoneText
            SettingsFeedbackType.FOLDER_SAVED -> folderSavedText
            SettingsFeedbackType.CACHE_CLEARED -> cacheClearedText
            SettingsFeedbackType.HISTORY_CLEARED -> historyClearedText
            SettingsFeedbackType.HISTORY_PRUNED -> historyPrunedText
            SettingsFeedbackType.FAVORITES_CLEARED -> favoritesClearedText
            SettingsFeedbackType.TRUSTED_REVOKED -> trustedRevokedText
            SettingsFeedbackType.UPDATE_URL_SAVED -> updateUrlSavedText
            SettingsFeedbackType.UPDATE_INSTALL_PERMISSION_REQUIRED -> updateInstallPermText
            SettingsFeedbackType.UPDATE_INSTALL_LAUNCH_FAILED -> updateInstallFailedText
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onFeedbackConsumed()
        }
    }

    if (showResetDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_reset_dialog_title),
            body = stringResource(R.string.settings_reset_dialog_body),
            confirmLabel = stringResource(R.string.action_reset),
            onConfirm = {
                showResetDialog = false
                onResetToDefaults()
            },
            onDismiss = { showResetDialog = false },
            isDestructive = true,
            confirmTestTag = "settings_confirm_reset_button"
        )
    }

    if (showClearHistoryDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_clear_history_confirm_title),
            body = stringResource(R.string.settings_clear_history_confirm_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                showClearHistoryDialog = false
                onClearHistory()
            },
            onDismiss = { showClearHistoryDialog = false },
            isDestructive = true,
            confirmTestTag = "settings_confirm_clear_history_button"
        )
    }

    if (showClearFavoritesDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_clear_favorites_confirm_title),
            body = stringResource(R.string.settings_clear_favorites_confirm_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                showClearFavoritesDialog = false
                onClearFavorites()
            },
            onDismiss = { showClearFavoritesDialog = false },
            isDestructive = true,
            confirmTestTag = "settings_confirm_clear_favorites_button"
        )
    }

    if (showRevokeTrustedDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.settings_revoke_trusted_confirm_title),
            body = stringResource(R.string.settings_revoke_trusted_confirm_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                showRevokeTrustedDialog = false
                onRevokeTrustedDevices()
            },
            onDismiss = { showRevokeTrustedDialog = false },
            isDestructive = true,
            confirmTestTag = "settings_confirm_revoke_trusted_button"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomBarClearance)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = spacing.maxContentWidth)
                    .testTag("settings_list"),
                contentPadding = PaddingValues(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    top = spacing.md,
                    bottom = spacing.md + bottomBarClearance
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "settings_title") {
                    SectionHeader(title = stringResource(R.string.settings_title))
                }

                // 1. Appearance Section
                item(key = "section_appearance") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_appearance),
                        testTag = "settings_section_appearance"
                    ) {
                        Text(
                            text = stringResource(R.string.settings_theme_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            OptionChip(
                                label = stringResource(R.string.settings_theme_system),
                                selected = uiState.settings.themeMode == ThemeMode.SYSTEM,
                                onClick = { onThemeModeSelected(ThemeMode.SYSTEM) },
                                testTag = "settings_theme_system_chip",
                                modifier = Modifier.weight(1f)
                            )
                            OptionChip(
                                label = stringResource(R.string.settings_theme_light),
                                selected = uiState.settings.themeMode == ThemeMode.LIGHT,
                                onClick = { onThemeModeSelected(ThemeMode.LIGHT) },
                                testTag = "settings_theme_light_chip",
                                modifier = Modifier.weight(1f)
                            )
                            OptionChip(
                                label = stringResource(R.string.settings_theme_dark),
                                selected = uiState.settings.themeMode == ThemeMode.DARK,
                                onClick = { onThemeModeSelected(ThemeMode.DARK) },
                                testTag = "settings_theme_dark_chip",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_accent_color_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccentColor.entries.forEach { accent ->
                                AccentColorSwatch(
                                    accent = accent,
                                    color = accent.swatchColor(isDark),
                                    selected = uiState.settings.accentColor == accent,
                                    enabled = !uiState.settings.useDynamicColor,
                                    onClick = { onAccentColorSelected(accent) }
                                )
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_dynamic_color_title),
                                description = stringResource(R.string.settings_dynamic_color_desc),
                                checked = uiState.settings.useDynamicColor,
                                onCheckedChange = onDynamicColorChanged,
                                testTag = "settings_dynamic_color_switch"
                            )
                        }
                    }
                }

                // 2. Language Section
                item(key = "section_language") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_language),
                        testTag = "settings_section_language"
                    ) {
                        Text(
                            text = stringResource(R.string.settings_language_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            OptionChip(
                                label = stringResource(R.string.settings_language_system),
                                selected = uiState.settings.appLanguage == AppLanguage.SYSTEM,
                                onClick = { onAppLanguageSelected(AppLanguage.SYSTEM) },
                                testTag = "settings_language_system_chip",
                                modifier = Modifier.weight(1f)
                            )
                            OptionChip(
                                label = stringResource(R.string.settings_language_english),
                                selected = uiState.settings.appLanguage == AppLanguage.ENGLISH,
                                onClick = { onAppLanguageSelected(AppLanguage.ENGLISH) },
                                testTag = "settings_language_english_chip",
                                modifier = Modifier.weight(1f)
                            )
                            OptionChip(
                                label = stringResource(R.string.settings_language_arabic),
                                selected = uiState.settings.appLanguage == AppLanguage.ARABIC,
                                onClick = { onAppLanguageSelected(AppLanguage.ARABIC) },
                                testTag = "settings_language_arabic_chip",
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 3. Device Settings & Identity Section
                item(key = "section_identity") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_identity),
                        testTag = "settings_section_device_info"
                    ) {
                        OutlinedTextField(
                            value = uiState.deviceNameDraft,
                            onValueChange = onDeviceNameDraftChange,
                            label = { Text(stringResource(R.string.settings_device_name_label)) },
                            placeholder = { Text(stringResource(R.string.settings_device_name_placeholder)) },
                            shape = ShareFlowDesign.shapes.input,
                            colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                            isError = uiState.deviceNameError,
                            supportingText = if (uiState.deviceNameError) {
                                { Text(stringResource(R.string.settings_device_name_error)) }
                            } else null,
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_device_name_input")
                        )
                        PrimaryActionButton(
                            text = stringResource(R.string.settings_save_action),
                            onClick = onSaveDeviceName,
                            enabled = uiState.deviceNameDraft != uiState.settings.deviceName,
                            isLoading = uiState.isSaving,
                            leadingIcon = Icons.Filled.Save,
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("settings_save_device_name_button")
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        uiState.localIdentity?.let { identity ->
                            SettingsInfoRow(
                                label = stringResource(R.string.settings_device_id_label),
                                value = identity.shortDeviceId
                            )
                            SettingsInfoRow(
                                label = stringResource(R.string.settings_device_fingerprint_label),
                                value = identity.formattedFingerprint
                            )
                        }
                        SettingsInfoRow(
                            label = stringResource(R.string.settings_device_model_label),
                            value = deviceModelLabel
                        )
                        SettingsInfoRow(
                            label = stringResource(R.string.settings_device_android_label),
                            value = androidVersionLabel
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_connection_mode_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            PreferredConnectionMode.entries.forEach { mode ->
                                val labelRes = when (mode) {
                                    PreferredConnectionMode.AUTO -> R.string.settings_conn_auto
                                    PreferredConnectionMode.WIFI_DIRECT -> R.string.settings_conn_wifi_direct
                                    PreferredConnectionMode.LOCAL_WIFI -> R.string.settings_conn_local_wifi
                                    PreferredConnectionMode.HOTSPOT -> R.string.settings_conn_hotspot
                                }
                                OptionChip(
                                    label = stringResource(labelRes),
                                    selected = uiState.settings.preferredConnectionMode == mode,
                                    onClick = { onConnectionModeSelected(mode) },
                                    testTag = "settings_conn_${mode.name.lowercase()}"
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.settings_discovery_visibility_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            DiscoveryVisibility.entries.forEach { vis ->
                                val labelRes = when (vis) {
                                    DiscoveryVisibility.EVERYONE_NEARBY -> R.string.settings_vis_everyone
                                    DiscoveryVisibility.TRUSTED_ONLY -> R.string.settings_vis_trusted
                                    DiscoveryVisibility.HIDDEN -> R.string.settings_vis_hidden
                                }
                                OptionChip(
                                    label = stringResource(labelRes),
                                    selected = uiState.settings.discoveryVisibility == vis,
                                    onClick = { onDiscoveryVisibilitySelected(vis) },
                                    testTag = "settings_visibility_${vis.name.lowercase()}"
                                )
                            }
                        }
                    }
                }

                // 4. Transfer Settings Section
                item(key = "section_transfer") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_transfer),
                        testTag = "settings_section_transfer"
                    ) {
                        // Default receiving folder
                        Text(
                            text = stringResource(R.string.settings_receive_folder_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = uiState.settings.defaultSaveDirectoryUri
                                ?: stringResource(R.string.settings_receive_folder_default),
                            style = ShareFlowMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("settings_receive_folder_value")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            SecondaryActionButton(
                                text = stringResource(R.string.settings_receive_folder_pick),
                                onClick = onPickReceiveFolder,
                                outlined = true,
                                leadingIcon = Icons.Filled.FolderOpen,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_pick_receive_folder_button")
                            )
                            if (uiState.settings.defaultSaveDirectoryUri != null) {
                                TextButton(
                                    onClick = onResetReceiveFolder,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Text(stringResource(R.string.settings_receive_folder_reset))
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            OutlinedTextField(
                                value = uiState.portDraft,
                                onValueChange = onPortDraftChange,
                                label = { Text(stringResource(R.string.settings_port_label)) },
                                shape = ShareFlowDesign.shapes.input,
                                colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                                isError = uiState.portError,
                                supportingText = if (uiState.portError) {
                                    { Text(stringResource(R.string.settings_port_error)) }
                                } else null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_port_input")
                            )
                            PrimaryActionButton(
                                text = stringResource(R.string.settings_save_action),
                                onClick = onSavePort,
                                enabled = !uiState.isSaving &&
                                    uiState.portDraft != uiState.settings.transferPort.toString(),
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .testTag("settings_save_port_button")
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_concurrent_transfers_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            (1..4).forEach { count ->
                                OptionChip(
                                    label = count.toString(),
                                    selected = uiState.settings.maxConcurrentTransfers == count,
                                    onClick = { onMaxConcurrentSelected(count) },
                                    testTag = "settings_concurrent_$count",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_overwrite_policy_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            FileOverwritePolicy.entries.forEach { policy ->
                                val labelRes = when (policy) {
                                    FileOverwritePolicy.KEEP_BOTH_RENAME -> R.string.settings_overwrite_keep_both
                                    FileOverwritePolicy.SKIP_EXISTING -> R.string.settings_overwrite_skip
                                    FileOverwritePolicy.OVERWRITE_WITH_CONFIRMATION -> R.string.settings_overwrite_confirm
                                }
                                OptionChip(
                                    label = stringResource(labelRes),
                                    selected = uiState.settings.fileOverwritePolicy == policy,
                                    onClick = { onOverwritePolicySelected(policy) },
                                    testTag = "settings_overwrite_${policy.name.lowercase()}"
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_wifi_direct_title),
                            description = stringResource(R.string.settings_wifi_direct_desc),
                            checked = uiState.settings.preferWifiDirect,
                            onCheckedChange = onPreferWifiDirectChanged,
                            testTag = "settings_wifi_direct_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_sha256_title),
                            description = stringResource(R.string.settings_sha256_desc),
                            checked = uiState.settings.verifySha256AfterTransfer,
                            onCheckedChange = onVerifySha256Changed,
                            testTag = "settings_sha256_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_auto_accept_title),
                            description = stringResource(R.string.settings_auto_accept_desc),
                            checked = uiState.settings.autoAcceptTrustedDevices,
                            onCheckedChange = onAutoAcceptTrustedChanged,
                            testTag = "settings_auto_accept_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_notifications_title),
                            description = stringResource(R.string.settings_notifications_desc),
                            checked = uiState.settings.transferNotificationsEnabled,
                            onCheckedChange = onNotificationsChanged,
                            testTag = "settings_notifications_switch"
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            text = stringResource(R.string.settings_history_retention_label),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            HistoryRetentionPolicy.entries.forEach { policy ->
                                val labelRes = when (policy) {
                                    HistoryRetentionPolicy.DAYS_7 -> R.string.settings_retention_7d
                                    HistoryRetentionPolicy.DAYS_30 -> R.string.settings_retention_30d
                                    HistoryRetentionPolicy.DAYS_90 -> R.string.settings_retention_90d
                                    HistoryRetentionPolicy.KEEP_FOREVER -> R.string.settings_retention_forever
                                }
                                OptionChip(
                                    label = stringResource(labelRes),
                                    selected = uiState.settings.historyRetentionPolicy == policy,
                                    onClick = { onHistoryRetentionSelected(policy) },
                                    testTag = "settings_retention_${policy.name.lowercase()}"
                                )
                            }
                        }
                        if (uiState.settings.historyRetentionPolicy.days != null) {
                            SecondaryActionButton(
                                text = stringResource(R.string.settings_prune_history_action),
                                onClick = onPruneExpiredHistory,
                                outlined = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_prune_history_button")
                            )
                        }
                    }
                }

                // 5. Privacy & Vault Section
                item(key = "section_security") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_security),
                        testTag = "settings_section_privacy"
                    ) {
                        if (onOpenVault != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.settings_open_vault_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_open_vault_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SecondaryActionButton(
                                    text = stringResource(R.string.settings_open_vault_action),
                                    onClick = onOpenVault,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Lock,
                                    modifier = Modifier.testTag("settings_open_vault_button")
                                )
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }

                        SettingsToggleRow(
                            title = stringResource(R.string.settings_vault_pin_title),
                            description = stringResource(R.string.settings_vault_pin_desc),
                            checked = uiState.settings.requireVaultAuthentication,
                            onCheckedChange = onRequireVaultAuthChanged,
                            testTag = "settings_vault_auth_switch"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_biometric_title),
                            description = stringResource(R.string.settings_biometric_desc),
                            checked = uiState.settings.biometricUnlockPreferred,
                            onCheckedChange = onBiometricPreferredChanged,
                            testTag = "settings_biometric_switch"
                        )
                        if (uiState.isKeystoreAvailable) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Key,
                                    contentDescription = null,
                                    tint = semantic.verifiedBadge,
                                    modifier = Modifier.size(18.dp)
                                )
                                StatusPill(
                                    label = stringResource(R.string.settings_keystore_status),
                                    containerColor = semantic.verifiedContainer,
                                    contentColor = semantic.verifiedBadge
                                )
                            }
                        }
                    }
                }

                // 6. Permissions Status Section
                item(key = "section_permissions") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_permissions),
                        testTag = "settings_section_permissions"
                    ) {
                        Text(
                            text = stringResource(R.string.settings_permissions_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.settings_perm_nearby_label),
                            granted = hasNearbyPerm
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.settings_perm_camera_label),
                            granted = hasCameraPerm
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.settings_perm_notifications_label),
                            granted = hasNotificationsPerm
                        )
                        PermissionStatusRow(
                            label = stringResource(R.string.settings_perm_contacts_label),
                            granted = hasContactsPerm
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_permissions_system_action),
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            },
                            outlined = false,
                            leadingIcon = Icons.Filled.AdminPanelSettings,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_permissions_button")
                        )
                    }
                }

                // 7. Local Data Management Section
                item(key = "section_local_data") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_local_data),
                        testTag = "settings_section_local_data"
                    ) {
                        SecondaryActionButton(
                            text = stringResource(
                                R.string.settings_clear_cache_action,
                                ShareFlowFormatters.formatBytes(uiState.temporaryCacheBytes, locale)
                            ),
                            onClick = onClearCache,
                            outlined = true,
                            leadingIcon = Icons.Filled.CleaningServices,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_cache_button")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_clear_history_action),
                            onClick = { showClearHistoryDialog = true },
                            outlined = true,
                            leadingIcon = Icons.Filled.DeleteSweep,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_history_button")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_clear_favorites_action),
                            onClick = { showClearFavoritesDialog = true },
                            outlined = true,
                            leadingIcon = Icons.Filled.DeleteSweep,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_favorites_button")
                        )
                        SecondaryActionButton(
                            text = stringResource(
                                R.string.settings_revoke_trusted_action,
                                uiState.trustedDeviceCount
                            ),
                            onClick = { showRevokeTrustedDialog = true },
                            outlined = true,
                            isDestructive = true,
                            leadingIcon = Icons.Filled.Security,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_revoke_trusted_button")
                        )
                    }
                }

                // 8. Cloud Updates & Official ShareFlow Website Section
                item(key = "section_cloud_update") {
                    CloudUpdateSettingsCard(
                        updateState = uiState.cloudUpdateState,
                        onCheckForUpdates = onCheckForCloudUpdates,
                        onDownloadUpdate = onDownloadCloudUpdate,
                        onCancelDownload = onCancelCloudUpdateDownload,
                        onInstallVerifiedUpdate = onInstallVerifiedCloudUpdate
                    )
                }

                // 9. About Section & Reset Action
                item(key = "section_about") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_about),
                        testTag = "settings_section_about"
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                                contentDescription = stringResource(R.string.cd_app_logo),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(48.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(
                                        R.string.settings_about_version_format,
                                        BuildConfig.VERSION_NAME,
                                        BuildConfig.VERSION_CODE.toString()
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.settings_about_architecture),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_reset_action),
                            onClick = { showResetDialog = true },
                            outlined = true,
                            isDestructive = true,
                            leadingIcon = Icons.Filled.RestartAlt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_reset_button")
                        )
                    }
                }

                item(key = "settings_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean
) {
    val semantic = ShareFlowDesign.semanticColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        StatusPill(
            label = stringResource(
                if (granted) R.string.settings_perm_granted else R.string.settings_perm_not_granted
            ),
            containerColor = if (granted) semantic.verifiedContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (granted) semantic.verifiedBadge else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = ShareFlowMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AccentColorSwatch(
    accent: AccentColor,
    color: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val accentName = stringResource(
        when (accent) {
            AccentColor.COBALT -> R.string.settings_accent_cobalt
            AccentColor.CYAN -> R.string.settings_accent_cyan
            AccentColor.EMERALD -> R.string.settings_accent_emerald
            AccentColor.CORAL -> R.string.settings_accent_coral
            AccentColor.VIOLET -> R.string.settings_accent_violet
        }
    )
    val cdText = stringResource(R.string.ds_cd_accent_color_format, accentName)
    val stateText = stringResource(
        if (selected) R.string.ds_state_selected else R.string.ds_state_not_selected
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick
                )
                .semantics {
                    contentDescription = cdText
                    stateDescription = stateText
                }
                .testTag("settings_accent_${accent.name.lowercase()}")
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(if (enabled) color else color.copy(alpha = 0.35f))
                    .then(
                        if (selected) {
                            Modifier.border(2.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (selected) {
                    val isLightSwatch = color == com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.Cream ||
                        color == com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.Peach
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (isLightSwatch) {
                            com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.LightTextPrimary
                        } else {
                            Color.White
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Text(
            text = accentName,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SettingsGroupCard(
    title: String,
    testTag: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = ShareFlowDesign.shapes.chip,
        colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
        border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = selected),
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
