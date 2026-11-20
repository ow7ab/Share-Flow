package com.example.shareflow.ui.send

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.SafDocumentInspector
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.DeviceCard
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.FileItem
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.FileCategory

@Composable
fun SendScreen(
    onNavigateBack: () -> Unit,
    onContinueToConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val viewModel: SendViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            SendViewModel(
                sessionController = container.localSessionController,
                trustedDeviceRepository = container.trustedDeviceRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val safPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val inspected = SafDocumentInspector.inspectUris(context, uris)
            viewModel.addSelectedFiles(inspected)
        }
    }

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.send_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState.selectedCount > 0) {
                        TextButton(
                            onClick = viewModel::clearSelection,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("send_clear_selection_button")
                        ) {
                            Text(text = stringResource(R.string.send_clear_selection))
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = ShareFlowDesign.elevation.raised,
                shadowElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${stringResource(R.string.send_selected_count_label)}: ${
                                ShareFlowFormatters.formatCount(uiState.selectedCount, locale)
                            }",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("send_selected_counter")
                        )
                        Text(
                            text = "${stringResource(R.string.send_total_size_label)}: ${
                                ShareFlowFormatters.formatBytes(uiState.totalSelectedBytes, locale)
                            }",
                            style = ShareFlowMonoStyle,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("send_total_size")
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.send_continue_action),
                        onClick = onContinueToConfirm,
                        enabled = uiState.selectedCount > 0,
                        leadingIcon = Icons.AutoMirrored.Filled.ArrowForward,
                        modifier = Modifier.testTag("send_continue_button")
                    )
                }
            }
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
                    .testTag("send_screen_list"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // File Category Selector
                item(key = "category_selector") {
                    val categories: List<Pair<FileCategory?, Int>> = listOf(
                        null to R.string.send_category_all,
                        FileCategory.IMAGES to R.string.send_category_images,
                        FileCategory.VIDEOS to R.string.send_category_videos,
                        FileCategory.AUDIO to R.string.send_category_audio,
                        FileCategory.DOCUMENTS to R.string.send_category_documents,
                        FileCategory.APKS to R.string.send_category_apks,
                        FileCategory.ARCHIVES to R.string.send_category_archives,
                        FileCategory.OTHER to R.string.send_category_other
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_category_selector")
                    ) {
                        items(categories) { (category, labelRes) ->
                            val selected = uiState.activeCategory == category
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectCategory(category) },
                                label = { Text(text = stringResource(labelRes)) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("send_cat_${category?.name?.lowercase() ?: "all"}")
                            )
                        }
                    }
                }

                // Real Zero-Permission SAF Picker Trigger
                item(key = "pick_files_cta") {
                    SecondaryActionButton(
                        text = stringResource(R.string.send_add_files_action),
                        onClick = {
                            safPickerLauncher.launch(
                                SafDocumentInspector.mimeTypesForCategory(uiState.activeCategory)
                            )
                        },
                        outlined = false,
                        leadingIcon = Icons.Filled.Add,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_pick_files_button")
                    )
                }

                if (uiState.filteredFiles.isEmpty()) {
                    item(key = "empty_selection") {
                        EmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = stringResource(R.string.send_empty_title),
                            body = stringResource(R.string.send_empty_body),
                            modifier = Modifier.testTag("send_empty_state")
                        )
                    }
                } else {
                    items(
                        items = uiState.filteredFiles,
                        key = { it.uriString }
                    ) { file ->
                        FileItem(
                            file = file,
                            onClick = {},
                            trailingContent = {
                                IconButton(
                                    onClick = { viewModel.removeSelectedFile(file.uriString) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.send_clear_selection),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            modifier = Modifier.testTag("send_selected_file_${file.displayName}")
                        )
                    }
                }

                item(key = "send_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
fun SendConfirmScreen(
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: SendViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            SendViewModel(
                sessionController = container.localSessionController,
                trustedDeviceRepository = container.trustedDeviceRepository,
                settingsRepository = container.settingsRepository,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors
    val peers = (uiState.discoveredDevices + uiState.trustedDevices).distinctBy { it.deviceId }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.send_confirm_title),
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
                    .testTag("send_confirm_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                val connState = uiState.connectionState
                if (connState is com.example.shareflow.domain.model.ConnectionState.Connected) {
                    item(key = "active_connection") {
                        com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard(
                            connected = connState,
                            onDisconnect = viewModel::disconnect
                        )
                    }
                }

                item(key = "package_summary") {
                    Surface(
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
                                text = stringResource(R.string.send_confirm_summary_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.send_selected_count_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = ShareFlowFormatters.formatCount(uiState.selectedCount, locale),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.send_total_size_label),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = ShareFlowFormatters.formatBytes(uiState.totalSelectedBytes, locale),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = semantic.verifiedBadge,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.send_confirm_sha256_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                item(key = "peers_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = stringResource(R.string.send_confirm_peers_title))
                        TextButton(
                            onClick = viewModel::startPeerDiscovery,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("send_confirm_scan_button")
                        ) {
                            Text(text = stringResource(R.string.discovery_start_action))
                        }
                    }
                }

                if (peers.isEmpty()) {
                    item(key = "no_peers") {
                        EmptyState(
                            icon = Icons.Filled.Devices,
                            title = stringResource(R.string.send_confirm_no_peers_title),
                            body = stringResource(R.string.send_confirm_no_peers_body),
                            actionLabel = stringResource(R.string.send_open_qr_action),
                            onAction = onOpenQrPairing,
                            modifier = Modifier.testTag("send_confirm_empty_peers")
                        )
                    }
                } else {
                    items(items = peers, key = { it.deviceId }) { peer ->
                        DeviceCard(
                            device = peer,
                            onClick = { viewModel.connectToPeer(peer) }
                        )
                    }
                }

                item(key = "qr_shortcut") {
                    SecondaryActionButton(
                        text = stringResource(R.string.send_open_qr_action),
                        onClick = onOpenQrPairing,
                        leadingIcon = Icons.Filled.QrCode2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_confirm_qr_button")
                    )
                }
            }
        }
    }
}
