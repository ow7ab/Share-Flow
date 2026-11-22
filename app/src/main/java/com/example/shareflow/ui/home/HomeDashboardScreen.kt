package com.example.shareflow.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.navigation.ShareFlowRoutes
import com.example.shareflow.network.DeviceNetworkStatus

@Composable
fun HomeDashboardScreen(
    onNavigateToRoute: (String) -> Unit,
    bottomBarClearance: Dp = 0.dp,
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
        bottomBarClearance = bottomBarClearance,
        modifier = modifier
    )
}

@Composable
fun HomeDashboardContent(
    uiState: HomeUiState,
    onNavigateToRoute: (String) -> Unit,
    onSelectRecentFile: (LocalFileEntry) -> Unit = {},
    activeTransferCount: Int = 0,
    bottomBarClearance: Dp = 0.dp,
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
                        start = spacing.screenHorizontal,
                        end = spacing.screenHorizontal,
                        top = spacing.sm,
                        bottom = bottomBarClearance + spacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Compact Header Row: Brand Wordmark + Subtle Network Status Badge (no top device banner card)
                    item(key = "compact_header") {
                        CompactHomeHeaderRow(
                            connectionState = uiState.connectionState,
                            networkStatus = uiState.networkStatus
                        )
                    }

                    // 2. Primary Focus: Equal-Size Send & Receive Action Tiles + Hint Directly Below
                    item(key = "primary_actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_send),
                                    subtitle = stringResource(R.string.home_action_send_sub),
                                    icon = Icons.AutoMirrored.Filled.Send,
                                    containerColor = semantic.sendAccent,
                                    gradientEndColor = Color(0xFFB01E44),
                                    contentColor = Color.White,
                                    testTag = "home_send_button",
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.SEND) },
                                    modifier = Modifier.weight(1f)
                                )
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_receive),
                                    subtitle = stringResource(R.string.home_action_receive_sub),
                                    icon = Icons.Filled.CloudDownload,
                                    containerColor = semantic.receiveAccent,
                                    gradientEndColor = Color(0xFFF05456),
                                    contentColor = Color.White,
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
                                    .fillMaxWidth()
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

                    // 4. Recent Files Section
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

                    // 5. Storage Overview Section
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

                    // 6. Quick Tools Section
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
                        Spacer(modifier = Modifier.height(spacing.sm))
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHomeHeaderRow(
    connectionState: ConnectionState,
    networkStatus: DeviceNetworkStatus,
    modifier: Modifier = Modifier
) {
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    val isConnectedOrActive = connectionState is ConnectionState.Connected ||
        connectionState is ConnectionState.Advertising ||
        networkStatus.isWifiConnected

    val statusLabel = when (connectionState) {
        is ConnectionState.Connected -> stringResource(
            R.string.home_status_connected,
            connectionState.peer.displayName
        )
        is ConnectionState.Advertising -> stringResource(
            R.string.home_status_receiving,
            ShareFlowFormatters.formatInteger(connectionState.port.toLong(), locale)
        )
        is ConnectionState.Discovering -> stringResource(R.string.home_status_discovering)
        else -> if (networkStatus.isWifiConnected) {
            stringResource(R.string.home_status_connected_short)
        } else {
            stringResource(R.string.home_status_idle)
        }
    }

    val statusDescription = when (connectionState) {
        is ConnectionState.Connected -> statusLabel
        is ConnectionState.Advertising -> statusLabel
        is ConnectionState.Discovering -> statusLabel
        else -> if (networkStatus.isWifiConnected) {
            stringResource(R.string.home_status_wifi_ready)
        } else {
            stringResource(R.string.home_status_idle)
        }
    }

    val pillContainerColor = when {
        connectionState is ConnectionState.Connected || networkStatus.isWifiConnected -> semantic.successContainer
        connectionState is ConnectionState.Advertising -> MaterialTheme.colorScheme.primaryContainer
        connectionState is ConnectionState.Discovering -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val pillContentColor = when {
        connectionState is ConnectionState.Connected || networkStatus.isWifiConnected -> semantic.success
        connectionState is ConnectionState.Advertising -> MaterialTheme.colorScheme.onPrimaryContainer
        connectionState is ConnectionState.Discovering -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .testTag("home_brand_title")
        )

        Surface(
            shape = CircleShape,
            color = pillContainerColor,
            contentColor = pillContentColor,
            modifier = Modifier
                .padding(start = 8.dp)
                .semantics { contentDescription = statusDescription }
                .testTag("home_connection_status")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (isConnectedOrActive) pillContentColor
                            else pillContentColor.copy(alpha = 0.55f)
                        )
                )
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = pillContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
private fun PrimaryActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    gradientEndColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = RoundedCornerShape(24.dp)
    val tileBrush = Brush.verticalGradient(
        colors = listOf(containerColor, gradientEndColor)
    )

    Surface(
        onClick = onClick,
        modifier = modifier
            .height(132.dp)
            .clip(tileShape)
            .testTag(testTag),
        shape = tileShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 6.dp,
        border = BorderStroke(
            width = 1.dp,
            color = ShareFlowMarpleColors.Cream.copy(alpha = 0.28f)
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = tileBrush)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentColor,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = contentColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.90f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
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
        shadowElevation = ShareFlowDesign.elevation.card,
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
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
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
