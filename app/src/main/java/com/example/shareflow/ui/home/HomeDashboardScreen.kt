package com.example.shareflow.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.components.SFSectionHeader
import com.example.shareflow.core.designsystem.components.SFTransferCard
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
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
    activeTransferCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val factory = remember(container) {
        shareFlowViewModelFactory {
            HomeViewModel(
                settingsRepository = container.settingsRepository,
                transferHistoryRepository = container.transferHistoryRepository,
                trustedDeviceRepository = container.trustedDeviceRepository,
                storageAnalyzerRepository = container.storageAnalyzerRepository,
                sessionController = container.localSessionController,
                discoveryRepository = null,
                connectionRepository = null
            )
        }
    }
    val viewModel: HomeViewModel = viewModel(factory = factory)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current

    HomeDashboardContent(
        uiState = uiState,
        onNavigateToRoute = onNavigateToRoute,
        onSelectRecentFile = { file ->
            viewModel.selectFileForSend(file)
            onNavigateToRoute(ShareFlowRoutes.SEND)
        },
        onOpenWifiPanel = {
            val panelIntent = container.permissionManager.createWifiPanelIntent()
            runCatching { context.startActivity(panelIntent) }
                .onFailure {
                    runCatching { context.startActivity(container.permissionManager.createWifiSettingsIntent()) }
                }
        },
        activeTransferCount = activeTransferCount,
        bottomBarClearance = bottomBarClearance,
        modifier = modifier
    )
}

@Composable
fun HomeDashboardContent(
    uiState: HomeUiState,
    onNavigateToRoute: (String) -> Unit,
    onSelectRecentFile: (LocalFileEntry) -> Unit = {},
    onOpenWifiPanel: () -> Unit = {},
    activeTransferCount: Int = 0,
    bottomBarClearance: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

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
                var wifiPromptDismissed by remember { mutableStateOf(false) }
                val wifiIsAvailable = uiState.networkStatus.isWifiEnabled || uiState.networkStatus.isWifiConnected
                LaunchedEffect(wifiIsAvailable) {
                    if (wifiIsAvailable) {
                        wifiPromptDismissed = false
                    }
                }
                val showWifiOffPrompt = uiState.networkStatus.hasCheckedInitialState &&
                    !wifiIsAvailable &&
                    !wifiPromptDismissed

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
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Minimal One UI + Liquid Glass Brand Header + Live Status Pill
                    item(key = "compact_header") {
                        CompactHomeHeaderRow(
                            connectionState = uiState.connectionState,
                            networkStatus = uiState.networkStatus
                        )
                    }

                    // 1b. Smart Wi-Fi Off Prompt (Android 10+ official Settings Panel / API < 29 fallback)
                    if (showWifiOffPrompt) {
                        item(key = "wifi_off_prompt_banner") {
                            WifiOffStartupPromptCard(
                                onEnableWifi = onOpenWifiPanel,
                                onDismiss = { wifiPromptDismissed = true }
                            )
                        }
                    }

                    // 2. Signature Liquid Glass Hero Action Tiles: Send (Ruby) & Receive (Coral)
                    item(key = "primary_actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_send),
                                    subtitle = stringResource(R.string.home_action_send_sub),
                                    icon = Icons.AutoMirrored.Outlined.Send,
                                    gradientStops = ShareFlowBrandGradients.SendHeroStops,
                                    spotShadowColor = ShareFlowMarpleColors.Ruby.copy(alpha = 0.34f),
                                    contentColor = Color.White,
                                    testTag = "home_send_button",
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.SEND) },
                                    modifier = Modifier.weight(1f)
                                )
                                PrimaryActionTile(
                                    title = stringResource(R.string.home_action_receive),
                                    subtitle = stringResource(R.string.home_action_receive_sub),
                                    icon = Icons.Outlined.CloudDownload,
                                    gradientStops = ShareFlowBrandGradients.ReceiveHeroStops,
                                    spotShadowColor = ShareFlowMarpleColors.Coral.copy(alpha = 0.30f),
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

                    // 3. Active Transfers Live Glass Banner (real active transfer count only)
                    if (activeTransferCount > 0) {
                        item(key = "active_transfers_banner") {
                            SFGlassCard(
                                onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_PROGRESS) },
                                selected = true,
                                elevated = true,
                                contentPadding = PaddingValues(spacing.cardPadding),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("home_active_transfers_banner")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(ShareFlowDesign.shapes.iconContainer)
                                            .background(ShareFlowBrandGradients.primaryButtonGradient())
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.SwapVert,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.transfer_progress_title),
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.transfer_open_progress_action),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

                    // 4. Recent Transfers Section
                    item(key = "recent_transfers_header") {
                        SFSectionHeader(
                            title = stringResource(R.string.home_section_recent_transfers),
                            actionLabel = stringResource(R.string.home_view_all),
                            onActionClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                            actionTestTag = "home_recent_transfers_view_all",
                            modifier = Modifier.testTag("home_section_recent_transfers")
                        )
                    }

                    if (uiState.recentTransfers.isEmpty()) {
                        item(key = "recent_transfers_empty") {
                            EmptyState(
                                icon = Icons.Outlined.History,
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
                            SFTransferCard(
                                record = record,
                                onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                                modifier = Modifier.testTag("home_recent_transfer_${record.id}")
                            )
                        }
                    }

                    // 5. Recent Files Section
                    item(key = "recent_files_header") {
                        SFSectionHeader(
                            title = stringResource(R.string.home_section_recent_files),
                            actionLabel = stringResource(R.string.home_view_all),
                            onActionClick = { onNavigateToRoute(ShareFlowRoutes.FILES_HUB) },
                            actionTestTag = "home_recent_files_view_all",
                            modifier = Modifier.testTag("home_section_recent_files")
                        )
                    }

                    if (uiState.recentFiles.isEmpty()) {
                        item(key = "recent_files_empty") {
                            EmptyState(
                                icon = Icons.Outlined.FolderOpen,
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
                            SFSectionHeader(
                                title = stringResource(R.string.home_section_storage),
                                actionLabel = stringResource(R.string.home_view_all),
                                onActionClick = { onNavigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                                actionTestTag = "home_storage_view_all",
                                modifier = Modifier.testTag("home_storage_header")
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
                            SFSectionHeader(
                                title = stringResource(R.string.home_section_quick_tools),
                                actionLabel = stringResource(R.string.home_view_all),
                                onActionClick = { onNavigateToRoute(ShareFlowRoutes.TOOLS_HUB) },
                                actionTestTag = "home_quick_tools_view_all",
                                modifier = Modifier.testTag("home_quick_tools_header")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                QuickToolTile(
                                    title = stringResource(R.string.module_private_vault_title),
                                    icon = Icons.Outlined.Lock,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                                    testTag = "home_quick_tool_vault",
                                    modifier = Modifier.weight(1f)
                                )
                                QuickToolTile(
                                    title = stringResource(R.string.module_storage_analyzer_title),
                                    icon = Icons.Outlined.Storage,
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
                                    icon = Icons.Outlined.Archive,
                                    onClick = { onNavigateToRoute(ShareFlowRoutes.COMPRESSION) },
                                    testTag = "home_quick_tool_compression",
                                    modifier = Modifier.weight(1f)
                                )
                                QuickToolTile(
                                    title = stringResource(R.string.module_transfer_history_title),
                                    icon = Icons.Outlined.History,
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
    val glass = ShareFlowDesign.glass
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

    val pillBgColor = when {
        connectionState is ConnectionState.Connected || networkStatus.isWifiConnected ->
            semantic.successContainer.copy(alpha = if (glass.isDark) 0.78f else 0.90f)
        connectionState is ConnectionState.Advertising ->
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        connectionState is ConnectionState.Discovering ->
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
        else -> glass.cardBackground
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
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f, fill = false)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(glass.cardElevatedBackground)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                    contentDescription = stringResource(R.string.cd_app_logo),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("home_brand_logo")
                )
            }
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("home_brand_title")
            )
        }

        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(CircleShape)
                .background(pillBgColor)
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(listOf(glass.borderTop, glass.borderBottom)),
                    shape = CircleShape
                )
                .semantics { contentDescription = statusDescription }
                .testTag("home_connection_status")
                .padding(horizontal = 12.dp, vertical = 7.dp)
        ) {
            Row(
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
private fun PrimaryActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradientStops: List<Color>,
    spotShadowColor: Color,
    contentColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tileShape = RoundedCornerShape(28.dp)
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val glass = ShareFlowDesign.glass

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && !reducedMotion) motion.pressScale else 1f,
        animationSpec = motion.buttonSpring(),
        label = "home_hero_tile_scale_$testTag"
    )

    val tileBrush = remember(gradientStops) {
        Brush.linearGradient(colors = gradientStops)
    }

    Box(
        modifier = modifier
            .height(144.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 10.dp,
                shape = tileShape,
                clip = false,
                ambientColor = glass.ambientShadow,
                spotColor = spotShadowColor
            )
            .clip(tileShape)
            .background(brush = tileBrush)
            .border(
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.45f),
                            ShareFlowMarpleColors.Cream.copy(alpha = 0.16f)
                        )
                    )
                ),
                shape = tileShape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                role = Role.Button,
                onClick = onClick
            )
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = contentColor,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
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
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.88f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
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
    SFGlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = spacing.sm),
        modifier = modifier
            .height(74.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.82f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun WifiOffStartupPromptCard(
    onEnableWifi: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    SFGlassCard(
        elevated = true,
        contentPadding = PaddingValues(spacing.cardPadding),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_wifi_off_banner")
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(ShareFlowDesign.shapes.iconContainer)
                        .background(semantic.warningContainer)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WifiOff,
                        contentDescription = null,
                        tint = semantic.warningAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wifi_prompt_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.wifi_prompt_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("home_wifi_dismiss_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.wifi_prompt_dismiss_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            com.example.shareflow.core.designsystem.components.SFPrimaryButton(
                text = stringResource(R.string.wifi_prompt_enable_action),
                onClick = onEnableWifi,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_wifi_enable_button")
            )
        }
    }
}
