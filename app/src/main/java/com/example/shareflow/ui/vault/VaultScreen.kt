package com.example.shareflow.ui.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults
import com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.LocalShareFlowHostActivity
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.findFragmentActivity
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.DecryptedVaultItem
import com.example.shareflow.domain.model.VaultAutoLockTimeout
import com.example.shareflow.domain.model.VaultCategory
import com.example.shareflow.domain.model.VaultKeystoreHealth
import com.example.shareflow.domain.model.VaultLockState
import com.example.shareflow.domain.model.VaultStatusSnapshot
import com.example.shareflow.vault.BiometricAuthOutcome
import com.example.shareflow.vault.BiometricCapability
import com.example.shareflow.vault.VaultBiometricAuthenticator

@Composable
fun VaultScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    providedViewModel: VaultViewModel? = null
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel: VaultViewModel = providedViewModel
        ?: androidx.lifecycle.viewmodel.compose.viewModel(
            factory = shareFlowViewModelFactory {
                VaultViewModel(
                    context = context,
                    vaultManager = container.vaultManager
                )
            }
        )
    val activity = (LocalShareFlowHostActivity.current as? FragmentActivity)
        ?: context.findFragmentActivity()
    val status by viewModel.vaultStatus.collectAsStateWithLifecycle()
    val items by viewModel.decryptedItems.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Scoped Play-compliant pickers for importing Photos, Videos, and Documents
    val importPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 25)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris, VaultCategory.PHOTOS)
    }

    val importVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris, VaultCategory.VIDEOS)
    }

    val importDocsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importUris(uris, VaultCategory.DOCUMENTS)
    }

    var exportItemHolder by remember { mutableStateOf<DecryptedVaultItem?>(null) }
    val exportCreateDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destinationUri ->
        val item = exportItemHolder
        exportItemHolder = null
        if (destinationUri != null && item != null) {
            viewModel.exportToDestinationUri(item, destinationUri)
        }
    }

    LaunchedEffect(uiState.itemReadyForExportPicker) {
        val target = uiState.itemReadyForExportPicker ?: return@LaunchedEffect
        exportItemHolder = target
        viewModel.consumeExportPickerRequest()
        exportCreateDocLauncher.launch(target.displayName)
    }

    LaunchedEffect(uiState.statusMessage) {
        val msg = uiState.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessages()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("private_vault_screen"),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                com.example.shareflow.core.designsystem.components.SFSnackbar(snackbarData = data)
            }
        },
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.module_private_vault_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (status.lockState == VaultLockState.UNLOCKED) {
                        IconButton(
                            onClick = { viewModel.setShowSecuritySettingsDialog(true) },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("vault_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.vault_security_settings_title)
                            )
                        }
                        IconButton(
                            onClick = viewModel::lockNow,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("vault_lock_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Lock,
                                contentDescription = stringResource(R.string.vault_action_lock_now)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (uiState.isBusy) {
                ShareFlowSignatureProgressBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_progress_indicator")
                )
            }

            when (status.lockState) {
                VaultLockState.NOT_INITIALIZED -> {
                    VaultSetupContent(
                        biometricCapability = uiState.biometricCapability,
                        isBusy = uiState.isBusy,
                        errorMessage = uiState.errorMessage,
                        onCreateVault = viewModel::createVault
                    )
                }

                VaultLockState.LOCKED -> {
                    VaultLockedContent(
                        status = status,
                        biometricCapability = uiState.biometricCapability,
                        isBusy = uiState.isBusy,
                        errorMessage = uiState.errorMessage,
                        onUnlockWithPin = viewModel::unlockWithPin,
                        onTriggerBiometric = {
                            if (activity != null) {
                                VaultBiometricAuthenticator.authenticate(
                                    activity = activity,
                                    title = context.getString(R.string.vault_biometric_prompt_title),
                                    subtitle = context.getString(R.string.vault_biometric_prompt_subtitle),
                                    negativeButtonText = context.getString(R.string.vault_use_pin_instead),
                                    onOutcome = viewModel::onBiometricOutcome
                                )
                            } else {
                                viewModel.onBiometricOutcome(
                                    BiometricAuthOutcome.Failed(
                                        errorCode = -1,
                                        message = "Biometric prompt requires an active FragmentActivity."
                                    )
                                )
                            }
                        },
                        onOpenRecovery = { viewModel.setShowRecoveryDialog(true) }
                    )
                }

                VaultLockState.UNLOCKED -> {
                    VaultUnlockedDashboard(
                        status = status,
                        items = items,
                        selectedCategory = uiState.selectedCategory,
                        errorMessage = uiState.errorMessage,
                        onSelectCategory = viewModel::selectCategory,
                        onImportPhotos = {
                            importPhotoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onImportVideos = {
                            importVideoLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        onImportDocuments = {
                            importDocsLauncher.launch(arrayOf("*/*"))
                        },
                        onOpenItemDetails = viewModel::openItemDetails
                    )
                }
            }
        }
    }

    // Dialogs
    uiState.oneTimeRecoveryKey?.let { key ->
        VaultRecoveryKeyReceiptDialog(
            recoveryKey = key,
            onConfirmSaved = viewModel::dismissRecoveryKeyReceipt
        )
    }

    if (uiState.showRecoveryDialog) {
        VaultRecoverPinDialog(
            onSubmit = viewModel::recoverWithKey,
            onDismiss = { viewModel.setShowRecoveryDialog(false) }
        )
    }

    uiState.selectedItemForDetails?.let { item ->
        VaultItemDetailsDialog(
            item = item,
            onPreviewInMemory = { viewModel.openVerifiedPreview(item) },
            onRequestExport = { viewModel.requestExportConfirmation(item) },
            onRequestSecureDelete = { viewModel.requestSecureDelete(item) },
            onDismiss = viewModel::closeItemDetails
        )
    }

    uiState.previewContent?.let { preview ->
        VaultInMemoryPreviewDialog(
            preview = preview,
            onClose = viewModel::closePreview
        )
    }

    uiState.pendingExportConfirmation?.let { item ->
        ConfirmationDialog(
            title = stringResource(R.string.vault_export_confirm_title),
            body = stringResource(R.string.vault_export_confirm_body, item.displayName),
            confirmLabel = stringResource(R.string.vault_export_confirm_cta),
            onConfirm = viewModel::confirmExportLaunchPicker,
            onDismiss = viewModel::dismissExportConfirmation,
            confirmTestTag = "vault_confirm_export_button",
            dismissTestTag = "vault_cancel_export_button"
        )
    }

    uiState.pendingDeleteConfirmation?.let { item ->
        ConfirmationDialog(
            title = stringResource(R.string.vault_delete_confirm_title),
            body = stringResource(R.string.vault_delete_confirm_body, item.displayName),
            confirmLabel = stringResource(R.string.vault_action_secure_delete),
            onConfirm = viewModel::confirmSecureDelete,
            onDismiss = viewModel::dismissSecureDelete,
            isDestructive = true,
            confirmTestTag = "vault_confirm_delete_button",
            dismissTestTag = "vault_cancel_delete_button"
        )
    }

    if (uiState.showSecuritySettingsDialog) {
        VaultSecuritySettingsDialog(
            status = status,
            biometricCapability = uiState.biometricCapability,
            onToggleBiometric = viewModel::setBiometricEnabled,
            onSelectTimeout = viewModel::setAutoLockTimeout,
            onOpenChangePin = {
                viewModel.setShowSecuritySettingsDialog(false)
                viewModel.setShowChangePinDialog(true)
            },
            onDismiss = { viewModel.setShowSecuritySettingsDialog(false) }
        )
    }

    if (uiState.showChangePinDialog) {
        VaultChangePinDialog(
            onSubmit = viewModel::changePin,
            onDismiss = { viewModel.setShowChangePinDialog(false) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VaultSetupContent(
    biometricCapability: BiometricCapability,
    isBusy: Boolean,
    errorMessage: String?,
    onCreateVault: (String, String, Boolean, VaultAutoLockTimeout) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    var pin by rememberSaveable { mutableStateOf("") }
    var confirmPin by rememberSaveable { mutableStateOf("") }
    var enableBiometric by rememberSaveable {
        mutableStateOf(biometricCapability == BiometricCapability.AVAILABLE)
    }
    var timeout by rememberSaveable { mutableStateOf(VaultAutoLockTimeout.MINUTES_1) }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.md)
                .testTag("vault_setup_view"),
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Surface(
                shape = ShareFlowDesign.shapes.featuredCard,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Icon(
                        imageVector = Icons.Filled.EnhancedEncryption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.vault_setup_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.vault_setup_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            errorMessage?.let { err ->
                VaultWarningBanner(text = err, isError = true, testTag = "vault_setup_error")
            }

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.vault_create_pin_label)) },
                supportingText = { Text(stringResource(R.string.vault_pin_requirements)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = ShareFlowDesign.shapes.input,
                colors = ShareFlowInputDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_setup_pin_input")
            )

            OutlinedTextField(
                value = confirmPin,
                onValueChange = { confirmPin = it.filter { ch -> ch.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.vault_confirm_pin_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = ShareFlowDesign.shapes.input,
                colors = ShareFlowInputDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_setup_confirm_pin_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vault_biometric_unlock_label),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(
                            if (biometricCapability == BiometricCapability.AVAILABLE) {
                                R.string.vault_biometric_available_desc
                            } else {
                                R.string.vault_biometric_unavailable_desc
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableBiometric,
                    onCheckedChange = { enableBiometric = it },
                    enabled = biometricCapability == BiometricCapability.AVAILABLE,
                    modifier = Modifier.testTag("vault_setup_biometric_switch")
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.vault_autolock_title),
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VaultAutoLockTimeout.entries.forEach { option ->
                        FilterChip(
                            selected = timeout == option,
                            onClick = { timeout = option },
                            label = { Text(autoLockLabel(option)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(selected = timeout == option)
                        )
                    }
                }
            }

            PrimaryActionButton(
                text = stringResource(R.string.vault_create_cta),
                onClick = {
                    val p = pin
                    val c = confirmPin
                    pin = ""
                    confirmPin = ""
                    onCreateVault(p, c, enableBiometric, timeout)
                },
                enabled = pin.length >= 4 && confirmPin.length >= 4,
                isLoading = isBusy,
                leadingIcon = Icons.Filled.Shield,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_create_submit_button")
            )

            Text(
                text = stringResource(R.string.vault_recovery_zero_backdoor_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VaultLockedContent(
    status: VaultStatusSnapshot,
    biometricCapability: BiometricCapability,
    isBusy: Boolean,
    errorMessage: String?,
    onUnlockWithPin: (String) -> Unit,
    onTriggerBiometric: () -> Unit,
    onOpenRecovery: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    var pin by rememberSaveable { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.screenHorizontal, vertical = spacing.lg)
                .testTag("vault_locked_view"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.md)
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.vault_locked_title),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.vault_locked_subtitle, status.encryptedItemCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (status.keystoreHealth == VaultKeystoreHealth.BIOMETRIC_KEY_INVALIDATED) {
                VaultWarningBanner(
                    text = stringResource(R.string.vault_biometric_key_invalidated_warning),
                    isError = false,
                    testTag = "vault_key_invalidated_banner"
                )
            }

            errorMessage?.let { err ->
                VaultWarningBanner(text = err, isError = true, testTag = "vault_unlock_error")
            }

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(12) },
                label = { Text(stringResource(R.string.vault_enter_pin_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = ShareFlowDesign.shapes.input,
                colors = ShareFlowInputDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_pin_input")
            )

            PrimaryActionButton(
                text = stringResource(R.string.vault_unlock_cta),
                onClick = {
                    val attempt = pin
                    pin = ""
                    onUnlockWithPin(attempt)
                },
                enabled = pin.length >= 4,
                isLoading = isBusy,
                leadingIcon = Icons.Filled.LockOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_unlock_button")
            )

            if (status.isBiometricEnabled && biometricCapability == BiometricCapability.AVAILABLE) {
                SecondaryActionButton(
                    text = stringResource(R.string.vault_unlock_biometric_cta),
                    onClick = onTriggerBiometric,
                    outlined = true,
                    leadingIcon = Icons.Filled.Fingerprint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("vault_biometric_button")
                )
            }

            TextButton(
                onClick = onOpenRecovery,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_forgot_pin_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.vault_forgot_pin_cta))
            }
        }
    }
}

@Composable
private fun VaultUnlockedDashboard(
    status: VaultStatusSnapshot,
    items: List<DecryptedVaultItem>,
    selectedCategory: VaultCategory?,
    errorMessage: String?,
    onSelectCategory: (VaultCategory?) -> Unit,
    onImportPhotos: () -> Unit,
    onImportVideos: () -> Unit,
    onImportDocuments: () -> Unit,
    onOpenItemDetails: (DecryptedVaultItem) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors
    val filteredItems = remember(items, selectedCategory) {
        if (selectedCategory == null) items else items.filter { it.category == selectedCategory }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("vault_unlocked_dashboard"),
            contentPadding = PaddingValues(horizontal = spacing.screenHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item(key = "vault_security_card") {
                Surface(
                    shape = ShareFlowDesign.shapes.featuredCard,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    shadowElevation = ShareFlowDesign.elevation.card,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.EnhancedEncryption,
                                    contentDescription = null,
                                    tint = semantic.verifiedBadge
                                )
                                Text(
                                    text = stringResource(R.string.vault_dashboard_protected_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            StatusPill(
                                label = "AES-256-GCM",
                                containerColor = semantic.verifiedContainer,
                                contentColor = semantic.verifiedBadge
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.vault_dashboard_summary,
                                items.size,
                                autoLockLabel(status.autoLockTimeout)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            errorMessage?.let { err ->
                item(key = "vault_dashboard_error") {
                    VaultWarningBanner(text = err, isError = true, testTag = "vault_dashboard_error_banner")
                }
            }

            item(key = "vault_import_actions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    SecondaryActionButton(
                        text = stringResource(R.string.vault_category_photos),
                        onClick = onImportPhotos,
                        outlined = false,
                        leadingIcon = Icons.Filled.AddPhotoAlternate,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_import_photos_button")
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.vault_category_videos),
                        onClick = onImportVideos,
                        outlined = false,
                        leadingIcon = Icons.Filled.VideoFile,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_import_videos_button")
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.vault_category_documents),
                        onClick = onImportDocuments,
                        outlined = false,
                        leadingIcon = Icons.Filled.UploadFile,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("vault_import_docs_button")
                    )
                }
            }

            item(key = "vault_category_filters") {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    modifier = Modifier.testTag("vault_category_chips")
                ) {
                    item(key = "cat_all") {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { onSelectCategory(null) },
                            label = { Text(stringResource(R.string.vault_category_all, items.size)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(selected = selectedCategory == null),
                            modifier = Modifier.testTag("vault_chip_all")
                        )
                    }
                    item(key = "cat_photos") {
                        FilterChip(
                            selected = selectedCategory == VaultCategory.PHOTOS,
                            onClick = { onSelectCategory(VaultCategory.PHOTOS) },
                            label = { Text(stringResource(R.string.vault_category_photos)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(
                                selected = selectedCategory == VaultCategory.PHOTOS
                            ),
                            modifier = Modifier.testTag("vault_chip_photos")
                        )
                    }
                    item(key = "cat_videos") {
                        FilterChip(
                            selected = selectedCategory == VaultCategory.VIDEOS,
                            onClick = { onSelectCategory(VaultCategory.VIDEOS) },
                            label = { Text(stringResource(R.string.vault_category_videos)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(
                                selected = selectedCategory == VaultCategory.VIDEOS
                            ),
                            modifier = Modifier.testTag("vault_chip_videos")
                        )
                    }
                    item(key = "cat_documents") {
                        FilterChip(
                            selected = selectedCategory == VaultCategory.DOCUMENTS,
                            onClick = { onSelectCategory(VaultCategory.DOCUMENTS) },
                            label = { Text(stringResource(R.string.vault_category_documents)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(
                                selected = selectedCategory == VaultCategory.DOCUMENTS
                            ),
                            modifier = Modifier.testTag("vault_chip_documents")
                        )
                    }
                }
            }

            if (filteredItems.isEmpty()) {
                item(key = "vault_empty") {
                    EmptyState(
                        icon = Icons.Filled.Shield,
                        title = stringResource(R.string.vault_empty_title),
                        body = stringResource(R.string.vault_empty_body),
                        modifier = Modifier.testTag("vault_empty_state")
                    )
                }
            } else {
                items(filteredItems, key = { it.id }) { item ->
                    VaultEncryptedFileCard(
                        item = item,
                        formattedSize = ShareFlowFormatters.formatBytes(item.originalSizeBytes, locale),
                        onClick = { onOpenItemDetails(item) },
                        modifier = Modifier.testTag("vault_item_${item.id}")
                    )
                }
            }
        }
    }
}

@Composable
private fun VaultEncryptedFileCard(
    item: DecryptedVaultItem,
    formattedSize: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (item.category) {
        VaultCategory.PHOTOS -> Icons.Filled.Photo
        VaultCategory.VIDEOS -> Icons.Filled.VideoFile
        VaultCategory.DOCUMENTS -> Icons.Filled.Description
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = ShareFlowDesign.shapes.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = ShareFlowDesign.elevation.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$formattedSize • ${item.originalMimeType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.EnhancedEncryption,
                contentDescription = stringResource(R.string.vault_encrypted_badge_cd),
                tint = ShareFlowDesign.semanticColors.verifiedBadge,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun VaultWarningBanner(
    text: String,
    isError: Boolean,
    testTag: String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.tertiaryContainer
        }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                }
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                }
            )
        }
    }
}
