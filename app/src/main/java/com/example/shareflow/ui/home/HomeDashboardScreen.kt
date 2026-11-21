package com.example.shareflow.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.FileItem
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.components.TransferCard
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.navigation.ShareFlowRoutes
import com.example.shareflow.network.DeviceNetworkStatus

@Composable
fun HomeDashboardScreen(
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: HomeViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            HomeViewModel(
                settingsRepository = container.settingsRepository,
                transferHistoryRepository = container.transferHistoryRepository,
                trustedDeviceRepository = container.trustedDeviceRepository,
                storageAnalyzerRepository = container.storageAnalyzerRepository,
                sessionController = container.localSessionController,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTransfers by container.transferManager.activeSessions.collectAsStateWithLifecycle()

    HomeDashboardContent(
        uiState = uiState,
        onNavigateToRoute = onNavigateToRoute,
        onSelectRecentFile = { file ->
            viewModel.selectFileForSend(file)
            onNavigateToRoute(ShareFlowRoutes.SEND)
        },
        activeTransferCount = activeTransfers.size,
        modifier = modifier
    )
}

@Composable
fun HomeDashboardContent(
    uiState: HomeUiState,
    onNavigateToRoute: (String) -> Unit,
    onSelectRecentFile: (LocalFileEntry) -> Unit = {},
    activeTransferCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        when (uiState) {
            HomeUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingState(modifier = Modifier.testTag("home_loading_indicator"))
                }
            }

            is HomeUiState.Ready -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = spacing.maxContentWidth)
                        .testTag("home_dashboard_list"),
                    contentPadding = PaddingValues(
                        horizontal = spacing.screenHorizontal,
                        vertical = spacing.md
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.md)
                ) {
                    // 1. Header: Logo, Device Name, Connection Status, Settings Shortcut
                    item(key = "hero_header") {
                        HeroIdentityCard(
                            deviceName = uiState.settings.deviceName,
                            port = uiState.settings.transferPort,
                            connectionState = uiState.connectionState,
                            networkStatus = uiState.networkStatus,
                            onSettingsClick = { onNavigateToRoute(ShareFlowRoutes.SETTINGS) }
                        )
                    }

                    // 2. Main Actions: Dedicated Send & Receive Only
                    item(key = "primary_actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_send),
                                    subtitle = stringResource(R.string.home_action_send_sub),
                                    icon = Icons.AutoMirrored.Filled.Send,
                                    containerColor = semantic.sendAccent,
                                    contentColor = semantic.onSendAccent,
                                    useSignatureBorder = true,
                                    testTag = "home_send_button",
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.SEND) },
                                    modifier = Modifier.weight(1f)
                                )
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_receive),
                                    subtitle = stringResource(R.string.home_action_receive_sub),
                                    icon = Icons.Filled.CloudDownload,
                                    containerColor = semantic.receiveAccent,
                                    contentColor = semantic.onReceiveAccent,
                                    useSignatureBorder = false,
                                    testTag = "home_receive_button",
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.RECEIVE) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                text = stringResource(R.string.home_unified_transfer_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .testTag("home_unified_transfer_hint")
                            )
                        }
                    }

                    if (activeTransferCount > 0) {
                        item(key = "active_transfers_banner") {
                            Surface(
                                onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) },
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("home_active_transfers_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.cardPadding),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SwapVert,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.transfer_progress_title),
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = stringResource(R.string.transfer_open_progress_action),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    StatusPill(
                                        label = ShareFlowFormatters.formatCount(activeTransferCount, ShareFlowDesign.locale),
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }

                    // 3. Recent Transfers Section
                    item(key = "recent_transfers_header") {
                        HomeSectionRow(
                            title = stringResource(R.string.home_section_recent_transfers),
                            actionLabel = stringResource(R.string.home_view_all),
                            onActionClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                            sectionTestTag = "home_section_recent_transfers",
                            actionTestTag = "home_recent_transfers_view_all"
                        )
                    }

                    if (uiState.recentTransfers.isEmpty()) {
                        item(key = "recent_transfers_empty") {
                            EmptyState(
                                icon = Icons.Filled.History,
                                title = stringResource(R.string.history_empty_title),
                                body = stringResource(R.string.history_empty_body),
                                modifier = Modifier.testTag("home_recent_transfers_empty")
                            )
                        }
                    } else {
                        items(
                            items = uiState.recentTransfers,
                            key = { "transfer_${it.id}" }
                        ) { record ->
                            TransferCard(
                                record = record,
                                onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                                modifier = Modifier.testTag("home_recent_transfer_${record.id}")
                            )
                        }
                    }

                    // 5. Recent Files Section
                    item(key = "recent_files_header") {
                        HomeSectionRow(
                            title = stringResource(R.string.home_section_recent_files),
                            actionLabel = stringResource(R.string.home_view_all),
                            onActionClick = { onNavigateToRoute(ShareFlowRoutes.FILES_HUB) },
                            sectionTestTag = "home_section_recent_files",
                            actionTestTag = "home_recent_files_view_all"
                        )
                    }

                    if (uiState.recentFiles.isEmpty()) {
                        item(key = "recent_files_empty") {
                            EmptyState(
                                icon = Icons.Filled.FolderOpen,
                                title = stringResource(R.string.home_section_recent_files_empty_title),
                                body = stringResource(R.string.home_section_recent_files_empty_body),
                                actionLabel = stringResource(R.string.home_section_recent_files_action),
                                onAction = { onNavigateToRoute(ShareFlowRoutes.FILES_HUB) },
                                modifier = Modifier.testTag("home_recent_files_empty")
                            )
                        }
                    } else {
                        items(
                            items = uiState.recentFiles,
                            key = { "recent_file_${it.uriString}" }
                        ) { file ->
                            FileItem(
                                file = file,
                                onClick = { onSelectRecentFile(file) },
                                modifier = Modifier.testTag("home_recent_file_${file.displayName}")
                            )
                        }
                    }

                    // 6. Storage Overview Section
                    item(key = "storage_overview") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(spacing.xs),
                            modifier = Modifier.testTag("home_section_storage")
                        ) {
                            HomeSectionRow(
                                title = stringResource(R.string.home_section_storage),
                                actionLabel = stringResource(R.string.home_view_all),
                                onActionClick = { onNavigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                                sectionTestTag = "home_storage_header",
                                actionTestTag = "home_storage_view_all"
                            )
                            val summary = uiState.storageSummary
                            StorageIndicator(
                                usedBytes = summary?.usedBytes ?: 0L,
                                totalBytes = summary?.totalBytes ?: 0L,
                                title = stringResource(R.string.module_storage_analyzer_title),
                                modifier = Modifier
                                    .clip(ShareFlowDesign.shapes.card)
                                    .clickable { onNavigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) }
                                    .testTag("home_storage_card")
                            )
                        }
                    }

                    // 7. Quick Tools Section
                    item(key = "quick_tools") {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                            modifier = Modifier.testTag("home_section_quick_tools")
                        ) {
                            HomeSectionRow(
                                title = stringResource(R.string.home_section_quick_tools),
                                actionLabel = stringResource(R.string.home_view_all),
                                onActionClick = { onNavigateToRoute(ShareFlowRoutes.TOOLS_HUB) },
                                sectionTestTag = "home_quick_tools_header",
                                actionTestTag = "home_quick_tools_view_all"
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                QuickToolTile(
                                    title = stringResource(R.string.module_private_vault_title),
                                    icon = Icons.Filled.Lock,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                                    testTag = "home_quick_tool_vault",
                                    modifier = Modifier.weight(1f)
                                )
                                QuickToolTile(
                                    title = stringResource(R.string.module_storage_analyzer_title),
                                    icon = Icons.Filled.Storage,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                                    testTag = "home_quick_tool_storage",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                QuickToolTile(
                                    title = stringResource(R.string.module_file_compression_title),
                                    icon = Icons.Filled.Archive,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.COMPRESSION) },
                                    testTag = "home_quick_tool_compression",
                                    modifier = Modifier.weight(1f)
                                )
                                QuickToolTile(
                                    title = stringResource(R.string.module_transfer_history_title),
                                    icon = Icons.Filled.History,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                                    testTag = "home_quick_tool_history",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(spacing.md))
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionRow(
    title: String,
    actionLabel: String,
    onActionClick: () -> Unit,
    sectionTestTag: String,
    actionTestTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(sectionTestTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        SectionHeader(
            title = title,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onActionClick,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(actionTestTag)
        ) {
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun HeroIdentityCard(
    deviceName: String,
    port: Int,
    connectionState: ConnectionState,
    networkStatus: DeviceNetworkStatus,
    onSettingsClick: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scrimColors = if (isRtl) {
        listOf(Color.Transparent, semantic.heroScrimEnd, semantic.heroScrimStart)
    } else {
        listOf(semantic.heroScrimStart, semantic.heroScrimEnd, Color.Transparent)
    }

    val statusText = when (connectionState) {
        is ConnectionState.Advertising -> stringResource(
            R.string.home_status_receiving,
            ShareFlowFormatters.formatInteger(connectionState.port.toLong(), locale)
        )
        is ConnectionState.Discovering -> stringResource(R.string.home_status_discovering)
        is ConnectionState.Connected -> stringResource(
            R.string.home_status_connected,
            connectionState.peer.displayName
        )
        else -> if (networkStatus.isWifiConnected) {
            stringResource(R.string.home_status_wifi_ready)
        } else {
            stringResource(R.string.home_status_idle)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(184.dp)
            .testTag("home_hero_card"),
        shape = ShareFlowDesign.shapes.featuredCard,
        border = BorderStroke(1.dp, ShareFlowBrandGradients.signatureBorder(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = ShareFlowDesign.elevation.raised)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_shareflow),
                contentDescription = stringResource(R.string.cd_hero_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.horizontalGradient(colors = scrimColors))
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                            contentDescription = stringResource(R.string.cd_app_logo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .testTag("home_app_logo")
                        )
                        StatusPill(
                            label = statusText,
                            containerColor = ShareFlowBrandColors.DeepNavy.copy(alpha = 0.78f),
                            contentColor = ShareFlowBrandColors.SoftCream,
                            modifier = Modifier.testTag("home_connection_status")
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ShareFlowBrandColors.DeepNavy.copy(alpha = 0.62f),
                        border = BorderStroke(1.dp, ShareFlowBrandColors.SkyBlue.copy(alpha = 0.38f))
                    ) {
                        IconButton(
                            onClick = onSettingsClick,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_settings_shortcut")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.cd_open_settings),
                                tint = ShareFlowBrandColors.SoftCream
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineLarge,
                            color = ShareFlowBrandColors.InkOnDeepNavy
                        )
                        Text(
                            text = "TCP :${ShareFlowFormatters.formatInteger(port.toLong(), locale)}",
                            style = ShareFlowMonoStyle,
                            color = ShareFlowBrandColors.GoldenYellow
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_device_identity_format, deviceName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShareFlowBrandColors.SoftCream.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("home_device_name_text")
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    useSignatureBorder: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(92.dp)
            .clip(MaterialTheme.shapes.large)
            .testTag(testTag),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
        border = if (useSignatureBorder) {
            BorderStroke(1.4.dp, ShareFlowBrandGradients.signatureBorder(alpha = 0.9f))
        } else {
            BorderStroke(1.dp, ShareFlowBrandColors.SoftCream.copy(alpha = 0.45f))
        },
        tonalElevation = ShareFlowDesign.elevation.raised
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = contentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = contentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun QuickToolTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(72.dp)
            .testTag(testTag),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
