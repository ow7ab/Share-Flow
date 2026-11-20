package com.example.shareflow.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.R
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
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ThemeMode

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: SettingsViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            SettingsViewModel(
                settingsRepository = container.settingsRepository,
                keystoreCipher = container.keystoreCipher
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }

    val deviceNameSavedText = stringResource(R.string.settings_device_name_saved)
    val portSavedText = stringResource(R.string.settings_port_saved)
    val resetDoneText = stringResource(R.string.settings_reset_done)
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

    LaunchedEffect(uiState.feedbackEvent) {
        val message = when (uiState.feedbackEvent) {
            SettingsFeedbackType.DEVICE_NAME_SAVED -> deviceNameSavedText
            SettingsFeedbackType.PORT_SAVED -> portSavedText
            SettingsFeedbackType.RESET_DONE -> resetDoneText
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
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

                // 3. Device Information Section
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
                        SettingsInfoRow(
                            label = stringResource(R.string.settings_device_model_label),
                            value = deviceModelLabel
                        )
                        SettingsInfoRow(
                            label = stringResource(R.string.settings_device_android_label),
                            value = androidVersionLabel
                        )
                    }
                }

                // 4. Transfer Preferences Section
                item(key = "section_transfer") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_transfer),
                        testTag = "settings_section_transfer"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            OutlinedTextField(
                                value = uiState.portDraft,
                                onValueChange = onPortDraftChange,
                                label = { Text(stringResource(R.string.settings_port_label)) },
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
                    }
                }

                // 5. Privacy Section
                item(key = "section_security") {
                    SettingsGroupCard(
                        title = stringResource(R.string.settings_section_security),
                        testTag = "settings_section_privacy"
                    ) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_vault_pin_title),
                            description = stringResource(R.string.settings_vault_pin_desc),
                            checked = uiState.settings.requireVaultAuthentication,
                            onCheckedChange = onRequireVaultAuthChanged,
                            testTag = "settings_vault_auth_switch"
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

                // 6. Permissions Section
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

                // 7. About Section & Reset Action
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
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
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
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
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
