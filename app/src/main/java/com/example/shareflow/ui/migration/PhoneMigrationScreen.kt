package com.example.shareflow.ui.migration

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.MigrationCategory
import com.example.shareflow.domain.model.MigrationCategorySummary
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.domain.model.MigrationRole
import com.example.shareflow.domain.model.MigrationStep

@Composable
fun PhoneMigrationScreen(
    onNavigateBack: () -> Unit,
    onOpenDiscovery: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onOpenReceive: () -> Unit,
    onOpenTransferProgress: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: PhoneMigrationViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            PhoneMigrationViewModel(
                connectionRepository = container.connectionRepository,
                transferManager = container.transferManager,
                fileManagerRepository = container.fileManagerRepository,
                settingsRepository = container.settingsRepository,
                contactsExporter = container.contactsMigrationExporter,
                clock = container.clock
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onContactsPermissionResult(granted)
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.addUserSelectedFolder(uri)
        }
    }

    PhoneMigrationContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectRole = viewModel::selectRole,
        onGoToStep = viewModel::goToStep,
        onRefreshCategories = viewModel::scanCategories,
        onToggleCategory = { summary, checked ->
            if (summary.category == MigrationCategory.CONTACTS && checked && !summary.permissionGranted) {
                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            } else {
                viewModel.toggleCategory(summary.category, checked)
            }
        },
        onAddFolder = { folderPickerLauncher.launch(null) },
        onOpenDiscovery = onOpenDiscovery,
        onOpenQrPairing = onOpenQrPairing,
        onOpenReceive = onOpenReceive,
        onStartTransfer = viewModel::startMigrationTransfer,
        onCancelTransfer = viewModel::cancelActiveMigration,
        onRetryFailed = viewModel::retryFailedItems,
        onResetWorkflow = viewModel::resetWorkflow,
        onOpenTransferProgress = onOpenTransferProgress,
        onOpenHistory = onOpenHistory,
        onDismissError = viewModel::dismissError,
        modifier = modifier
    )
}

@Composable
fun PhoneMigrationContent(
    uiState: PhoneMigrationUiState,
    onNavigateBack: () -> Unit,
    onSelectRole: (MigrationRole) -> Unit,
    onGoToStep: (MigrationStep) -> Unit,
    onRefreshCategories: () -> Unit,
    onToggleCategory: (MigrationCategorySummary, Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onOpenDiscovery: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onOpenReceive: () -> Unit,
    onStartTransfer: () -> Unit,
    onCancelTransfer: () -> Unit,
    onRetryFailed: () -> Unit,
    onResetWorkflow: () -> Unit,
    onOpenTransferProgress: () -> Unit,
    onOpenHistory: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.migration_title),
                onNavigateBack = onNavigateBack
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
                    .testTag("phone_migration_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // Role selector (Old Phone Sender vs New Phone Receiver)
                item(key = "migration_roles") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        FilterChip(
                            selected = uiState.role == MigrationRole.SENDER_OLD_PHONE,
                            onClick = { onSelectRole(MigrationRole.SENDER_OLD_PHONE) },
                            label = { Text(stringResource(R.string.migration_role_sender)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(
                                selected = uiState.role == MigrationRole.SENDER_OLD_PHONE
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("migration_role_sender_chip")
                        )
                        FilterChip(
                            selected = uiState.role == MigrationRole.RECEIVER_NEW_PHONE,
                            onClick = { onSelectRole(MigrationRole.RECEIVER_NEW_PHONE) },
                            label = { Text(stringResource(R.string.migration_role_receiver)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = ShareFlowChipDefaults.filterChipColors(),
                            border = ShareFlowChipDefaults.filterChipBorder(
                                selected = uiState.role == MigrationRole.RECEIVER_NEW_PHONE
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("migration_role_receiver_chip")
                        )
                    }
                }

                // Stepper indicator for Sender flow
                if (uiState.role == MigrationRole.SENDER_OLD_PHONE) {
                    item(key = "migration_stepper") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("migration_stepper_row"),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            MigrationStep.entries.forEach { step ->
                                val labelRes = when (step) {
                                    MigrationStep.PAIRING -> R.string.migration_step_pairing
                                    MigrationStep.CATEGORIES -> R.string.migration_step_categories
                                    MigrationStep.PREVIEW -> R.string.migration_step_preview
                                    MigrationStep.TRANSFERRING -> R.string.migration_step_transfer
                                    MigrationStep.REPORT -> R.string.migration_step_report
                                }
                                StatusPill(
                                    label = stringResource(labelRes),
                                    containerColor = if (uiState.step == step) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                    contentColor = if (uiState.step == step) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // Honest scope & privacy disclosure card
                item(key = "migration_scope_disclosure") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("migration_scope_disclosure"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.migration_scope_disclosure_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.migration_scope_disclosure_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    item(key = "migration_error") {
                        ErrorState(
                            message = uiState.errorMessage,
                            onRetry = onDismissError,
                            modifier = Modifier.testTag("migration_error_state")
                        )
                    }
                }

                if (uiState.role == MigrationRole.RECEIVER_NEW_PHONE) {
                    item(key = "migration_receiver_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("migration_receiver_card"),
                            shape = ShareFlowDesign.shapes.featuredCard,
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
                                    text = stringResource(R.string.migration_receiver_ready_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.migration_receiver_ready_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                PrimaryActionButton(
                                    text = stringResource(R.string.migration_open_qr_action),
                                    onClick = onOpenQrPairing,
                                    leadingIcon = Icons.Filled.QrCode2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_receiver_qr_button")
                                )
                                SecondaryActionButton(
                                    text = stringResource(R.string.migration_open_receive_action),
                                    onClick = onOpenReceive,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.SwapHoriz,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_receiver_open_button")
                                )
                            }
                        }
                    }
                } else {
                    when (uiState.step) {
                        MigrationStep.PAIRING -> {
                            item(key = "step_pairing") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_pairing_card"),
                                    shape = ShareFlowDesign.shapes.featuredCard,
                                    color = if (uiState.isConnected) {
                                        semantic.verifiedContainer.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerLow
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (uiState.isConnected) semantic.verifiedBadge else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(spacing.cardPadding),
                                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            Icon(
                                                imageVector = if (uiState.isConnected) Icons.Filled.VerifiedUser else Icons.Filled.Radar,
                                                contentDescription = null,
                                                tint = if (uiState.isConnected) semantic.verifiedBadge else MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = stringResource(
                                                    if (uiState.isConnected) {
                                                        R.string.migration_pair_connected_title
                                                    } else {
                                                        R.string.migration_pair_not_connected_title
                                                    }
                                                ),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = if (uiState.isConnected) {
                                                stringResource(R.string.migration_preview_target, uiState.connectedPeerName)
                                            } else {
                                                stringResource(R.string.migration_pair_not_connected_body)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            SecondaryActionButton(
                                                text = stringResource(R.string.migration_open_discovery_action),
                                                onClick = onOpenDiscovery,
                                                outlined = true,
                                                leadingIcon = Icons.Filled.Radar,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("migration_discovery_button")
                                            )
                                            SecondaryActionButton(
                                                text = stringResource(R.string.migration_open_qr_action),
                                                onClick = onOpenQrPairing,
                                                outlined = true,
                                                leadingIcon = Icons.Filled.QrCode2,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("migration_qr_button")
                                            )
                                        }
                                        PrimaryActionButton(
                                            text = stringResource(R.string.migration_continue_action),
                                            onClick = { onGoToStep(MigrationStep.CATEGORIES) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("migration_continue_categories_button")
                                        )
                                    }
                                }
                            }
                        }

                        MigrationStep.CATEGORIES -> {
                            item(key = "categories_actions") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.migration_add_folder_action),
                                        onClick = onAddFolder,
                                        outlined = true,
                                        leadingIcon = Icons.Filled.CreateNewFolder,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("migration_add_folder_button")
                                    )
                                    SecondaryActionButton(
                                        text = stringResource(R.string.migration_scan_categories_action),
                                        onClick = onRefreshCategories,
                                        outlined = false,
                                        leadingIcon = Icons.Filled.Refresh,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("migration_refresh_categories_button")
                                    )
                                }
                            }

                            items(
                                items = uiState.categories,
                                key = { it.category.name }
                            ) { summary ->
                                MigrationCategoryRow(
                                    summary = summary,
                                    onToggle = { checked -> onToggleCategory(summary, checked) }
                                )
                            }

                            item(key = "categories_nav") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.migration_back_action),
                                        onClick = { onGoToStep(MigrationStep.PAIRING) },
                                        outlined = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    PrimaryActionButton(
                                        text = stringResource(R.string.migration_continue_action),
                                        onClick = { onGoToStep(MigrationStep.PREVIEW) },
                                        enabled = uiState.totalSelectedItems > 0,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("migration_continue_preview_button")
                                    )
                                }
                            }
                        }

                        MigrationStep.PREVIEW -> {
                            item(key = "preview_card") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_preview_card"),
                                    shape = ShareFlowDesign.shapes.featuredCard,
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
                                            text = stringResource(R.string.migration_preview_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.migration_preview_summary,
                                                uiState.totalSelectedItems,
                                                ShareFlowFormatters.formatBytes(uiState.totalSelectedBytes, locale)
                                            ),
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        if (uiState.isConnected) {
                                            Text(
                                                text = stringResource(R.string.migration_preview_target, uiState.connectedPeerName),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                        uiState.selectedCategories.forEach { cat ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = stringResource(cat.category.labelRes()),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = "${cat.itemCount} • ${ShareFlowFormatters.formatBytes(cat.totalBytes, locale)}",
                                                    style = ShareFlowMonoStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            SecondaryActionButton(
                                                text = stringResource(R.string.migration_back_action),
                                                onClick = { onGoToStep(MigrationStep.CATEGORIES) },
                                                outlined = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            PrimaryActionButton(
                                                text = stringResource(R.string.migration_start_transfer_action),
                                                onClick = onStartTransfer,
                                                enabled = uiState.isConnected && uiState.totalSelectedItems > 0,
                                                isLoading = uiState.isStartingTransfer,
                                                leadingIcon = Icons.Filled.SwapHoriz,
                                                modifier = Modifier
                                                    .weight(1.4f)
                                                    .testTag("migration_start_transfer_button")
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        MigrationStep.TRANSFERRING -> {
                            item(key = "transferring_card") {
                                val session = uiState.activeSession
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_transferring_card"),
                                    shape = ShareFlowDesign.shapes.featuredCard,
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
                                            text = stringResource(R.string.migration_transferring_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        ShareFlowSignatureProgressBar(
                                            progress = session?.progressFraction ?: 0f,
                                            modifier = Modifier.fillMaxWidth(),
                                            height = 8.dp
                                        )
                                        if (session != null) {
                                            Text(
                                                text = stringResource(
                                                    R.string.migration_transferring_progress,
                                                    session.completedFileCount,
                                                    session.transferItems.size,
                                                    ShareFlowFormatters.formatBytes(session.transferredBytes, locale),
                                                    ShareFlowFormatters.formatBytes(session.totalBytes, locale)
                                                ),
                                                style = ShareFlowMonoStyle,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            SecondaryActionButton(
                                                text = stringResource(R.string.migration_cancel_action),
                                                onClick = onCancelTransfer,
                                                outlined = true,
                                                isDestructive = true,
                                                leadingIcon = Icons.Filled.Cancel,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("migration_cancel_button")
                                            )
                                            PrimaryActionButton(
                                                text = stringResource(R.string.migration_open_full_transfer),
                                                onClick = onOpenTransferProgress,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        MigrationStep.REPORT -> {
                            val report = uiState.completionReport
                            item(key = "report_card") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("migration_report_card"),
                                    shape = ShareFlowDesign.shapes.featuredCard,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(spacing.cardPadding),
                                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            Icon(
                                                imageVector = if (report?.isFullSuccess == true) {
                                                    Icons.Filled.CheckCircle
                                                } else {
                                                    Icons.Filled.ErrorOutline
                                                },
                                                contentDescription = null,
                                                tint = if (report?.isFullSuccess == true) {
                                                    semantic.verifiedBadge
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                }
                                            )
                                            Text(
                                                text = stringResource(R.string.migration_report_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (report != null) {
                                            StatusPill(
                                                label = stringResource(
                                                    if (report.isFullSuccess) {
                                                        R.string.migration_report_success_badge
                                                    } else {
                                                        R.string.migration_report_partial_badge
                                                    }
                                                ),
                                                containerColor = if (report.isFullSuccess) {
                                                    semantic.verifiedContainer
                                                } else {
                                                    MaterialTheme.colorScheme.errorContainer
                                                },
                                                contentColor = if (report.isFullSuccess) {
                                                    semantic.verifiedBadge
                                                } else {
                                                    MaterialTheme.colorScheme.onErrorContainer
                                                }
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.migration_report_summary,
                                                    report.completedCount,
                                                    report.failedItems.size,
                                                    ShareFlowFormatters.formatBytes(report.totalBytesTransferred, locale)
                                                ),
                                                style = ShareFlowMonoStyle,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (report.failedItems.isNotEmpty()) {
                                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                                Text(
                                                    text = stringResource(R.string.migration_failed_items_title, report.failedItems.size),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                                report.failedItems.forEach { failed ->
                                                    Text(
                                                        text = "• ${failed.displayName}: ${failed.reason}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                PrimaryActionButton(
                                                    text = stringResource(R.string.migration_retry_failed_action),
                                                    onClick = onRetryFailed,
                                                    leadingIcon = Icons.Filled.Refresh,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("migration_retry_failed_button")
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            SecondaryActionButton(
                                                text = stringResource(R.string.migration_view_history_action),
                                                onClick = onOpenHistory,
                                                outlined = true,
                                                leadingIcon = Icons.Filled.History,
                                                modifier = Modifier.weight(1f)
                                            )
                                            PrimaryActionButton(
                                                text = stringResource(R.string.migration_start_new_action),
                                                onClick = onResetWorkflow,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "migration_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun MigrationCategoryRow(
    summary: MigrationCategorySummary,
    onToggle: (Boolean) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("migration_category_${summary.category.name.lowercase()}"),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(
            width = if (summary.selected) 1.25.dp else 1.dp,
            color = if (summary.selected) {
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Icon(
                imageVector = summary.category.icon(),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(summary.category.labelRes()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (summary.permissionRequired && !summary.permissionGranted) {
                    Text(
                        text = stringResource(R.string.migration_contacts_permission_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "${summary.itemCount} • ${ShareFlowFormatters.formatBytes(summary.totalBytes, locale)}",
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Checkbox(
                checked = summary.selected,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("migration_checkbox_${summary.category.name.lowercase()}")
            )
        }
    }
}

private fun MigrationCategory.labelRes(): Int = when (this) {
    MigrationCategory.PHOTOS -> R.string.migration_cat_photos
    MigrationCategory.VIDEOS -> R.string.migration_cat_videos
    MigrationCategory.AUDIO -> R.string.migration_cat_audio
    MigrationCategory.DOCUMENTS -> R.string.migration_cat_documents
    MigrationCategory.USER_FOLDERS -> R.string.migration_cat_user_folders
    MigrationCategory.CONTACTS -> R.string.migration_cat_contacts
    MigrationCategory.OTHER_FILES -> R.string.migration_cat_other_files
}

private fun MigrationCategory.icon(): ImageVector = when (this) {
    MigrationCategory.PHOTOS -> Icons.Filled.PhotoLibrary
    MigrationCategory.VIDEOS -> Icons.Filled.VideoLibrary
    MigrationCategory.AUDIO -> Icons.Filled.AudioFile
    MigrationCategory.DOCUMENTS -> Icons.Filled.Description
    MigrationCategory.USER_FOLDERS -> Icons.Filled.Folder
    MigrationCategory.CONTACTS -> Icons.Filled.Contacts
    MigrationCategory.OTHER_FILES -> Icons.AutoMirrored.Filled.InsertDriveFile
}
