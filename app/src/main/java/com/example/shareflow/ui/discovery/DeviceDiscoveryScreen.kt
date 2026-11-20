package com.example.shareflow.ui.discovery

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BroadcastOnPersonal
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.designsystem.components.DeviceCard
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.StrategyAvailability
import com.example.shareflow.domain.model.StrategyStatus

@Composable
fun DeviceDiscoveryScreen(
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    modifier: Modifier = Modifier,
    showApprovalDialog: Boolean = true
) {
    val container = LocalAppContainer.current
    val viewModel: DiscoveryViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            DiscoveryViewModel(
                settingsRepository = container.settingsRepository,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DeviceDiscoveryContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenQrPairing = onOpenQrPairing,
        onStartDiscovery = { viewModel.startDiscovery() },
        onStopDiscovery = viewModel::stopDiscovery,
        onToggleAdvertising = viewModel::toggleAdvertising,
        onToggleHotspotPreference = viewModel::setEnableHotspotWhenAdvertising,
        onConnectToPeer = viewModel::connectToPeer,
        onApproveIncoming = viewModel::approveIncoming,
        onRejectIncoming = viewModel::rejectIncoming,
        onDisconnect = viewModel::disconnect,
        onRefreshCapabilities = viewModel::refreshCapabilities,
        onConsumeFeedback = viewModel::consumeFeedback,
        showApprovalDialog = showApprovalDialog,
        modifier = modifier
    )
}

@Composable
fun DeviceDiscoveryContent(
    uiState: DiscoveryUiState,
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onToggleAdvertising: () -> Unit,
    onToggleHotspotPreference: (Boolean) -> Unit,
    onConnectToPeer: (com.example.shareflow.domain.model.PeerDevice) -> Unit,
    onApproveIncoming: (String, Boolean) -> Unit,
    onRejectIncoming: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefreshCapabilities: () -> Unit,
    onConsumeFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    showApprovalDialog: Boolean = true
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshCapabilities()
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val message = uiState.feedbackMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onConsumeFeedback()
        }
    }

    if (showApprovalDialog) {
        uiState.pendingIncomingRequest?.let { request ->
            IncomingConnectionApprovalDialog(
                request = request,
                onApprove = { rememberTrusted -> onApproveIncoming(request.requestId, rememberTrusted) },
                onReject = { onRejectIncoming(request.requestId) }
            )
        }
    }

    val snapshot = uiState.discoverySnapshot
    val isDiscovering = snapshot.status is DiscoveryStatus.Discovering
    val missingPermissions = snapshot.strategyStatuses
        .mapNotNull { (it.availability as? StrategyAvailability.PermissionRequired)?.missingPermissions }
        .flatten()
        .distinct()
    val wifiDisabled = snapshot.strategyStatuses.any { it.availability is StrategyAvailability.WifiDisabled }
    val locationDisabled = snapshot.strategyStatuses.any {
        it.availability is StrategyAvailability.LocationServicesDisabled
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.discovery_title),
                onNavigateBack = onNavigateBack
            )
        },
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
                    .testTag("discovery_screen_list"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // 1. Active Encrypted Connection Card (when connected)
                val connState = uiState.connectionState
                if (connState is ConnectionState.Connected) {
                    item(key = "active_connection") {
                        ActiveEncryptedConnectionCard(
                            connected = connState,
                            onDisconnect = onDisconnect
                        )
                    }
                }

                // 2. Local Cryptographic Identity & Discovery Controls
                item(key = "local_identity_controls") {
                    Surface(
                        shape = ShareFlowDesign.shapes.featuredCard,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("discovery_identity_card")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Fingerprint,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = uiState.settings.deviceName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    uiState.localIdentity?.let { identity ->
                                        Text(
                                            text = stringResource(
                                                R.string.discovery_device_id_format,
                                                identity.shortDeviceId
                                            ),
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.testTag("discovery_local_device_id")
                                        )
                                        Text(
                                            text = stringResource(
                                                R.string.discovery_fingerprint_format,
                                                identity.formattedFingerprint
                                            ),
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (isDiscovering) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("discovery_progress_bar")
                                )
                                Text(
                                    text = stringResource(R.string.discovery_scanning_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                if (isDiscovering) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.discovery_stop_action),
                                        onClick = onStopDiscovery,
                                        isDestructive = true,
                                        outlined = true,
                                        leadingIcon = Icons.Filled.StopCircle,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("discovery_scan_toggle_button")
                                    )
                                } else {
                                    PrimaryActionButton(
                                        text = stringResource(R.string.discovery_start_action),
                                        onClick = onStartDiscovery,
                                        leadingIcon = Icons.Filled.Radar,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("discovery_scan_toggle_button")
                                    )
                                }

                                SecondaryActionButton(
                                    text = stringResource(
                                        if (snapshot.isAdvertising) {
                                            R.string.discovery_advertise_stop
                                        } else {
                                            R.string.discovery_advertise_start
                                        }
                                    ),
                                    onClick = onToggleAdvertising,
                                    outlined = snapshot.isAdvertising,
                                    leadingIcon = Icons.Filled.BroadcastOnPersonal,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("discovery_advertise_toggle_button")
                                )
                            }

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
                                    Icon(
                                        imageVector = Icons.Filled.WifiTethering,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.discovery_hotspot_toggle),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Switch(
                                    checked = uiState.enableHotspotWhenAdvertising,
                                    onCheckedChange = onToggleHotspotPreference,
                                    enabled = !snapshot.isAdvertising,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("discovery_hotspot_switch")
                                )
                            }

                            snapshot.activeHotspot?.let { hotspot ->
                                StatusPill(
                                    label = stringResource(R.string.discovery_hotspot_ssid_label, hotspot.ssid),
                                    containerColor = semantic.verifiedContainer,
                                    contentColor = semantic.verifiedBadge
                                )
                            }

                            SecondaryActionButton(
                                text = stringResource(R.string.receive_qr_open_action),
                                onClick = onOpenQrPairing,
                                outlined = false,
                                leadingIcon = Icons.Filled.QrCode2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("discovery_open_qr_button")
                            )
                        }
                    }
                }

                // 3. Version-Aware Runtime Permissions / Hardware Guidance Banners
                if (missingPermissions.isNotEmpty()) {
                    item(key = "permission_banner") {
                        GuidanceBannerCard(
                            message = stringResource(R.string.discovery_permission_required),
                            actionLabel = stringResource(R.string.discovery_permission_grant_action),
                            onAction = { permissionLauncher.launch(missingPermissions.toTypedArray()) },
                            testTag = "discovery_permission_banner"
                        )
                    }
                }
                if (wifiDisabled) {
                    item(key = "wifi_disabled_banner") {
                        GuidanceBannerCard(
                            message = stringResource(R.string.discovery_wifi_disabled),
                            actionLabel = stringResource(R.string.discovery_open_settings_action),
                            onAction = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            testTag = "discovery_wifi_disabled_banner"
                        )
                    }
                }
                if (locationDisabled) {
                    item(key = "location_disabled_banner") {
                        GuidanceBannerCard(
                            message = stringResource(R.string.discovery_location_disabled),
                            actionLabel = stringResource(R.string.discovery_open_settings_action),
                            onAction = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            },
                            testTag = "discovery_location_disabled_banner"
                        )
                    }
                }

                // 4. Connection Methods Readiness Cards
                item(key = "transports_header") {
                    SectionHeader(title = stringResource(R.string.discovery_transports_title))
                }
                items(
                    items = snapshot.strategyStatuses,
                    key = { "strategy_${it.connectionType.name}" }
                ) { strategy ->
                    StrategyStatusRow(strategy = strategy)
                }

                // 5. Discovery Timeout / Failure State
                when (val status = snapshot.status) {
                    is DiscoveryStatus.TimedOut -> {
                        item(key = "discovery_timeout") {
                            EmptyState(
                                icon = Icons.Filled.Refresh,
                                title = stringResource(R.string.discovery_timeout_title),
                                body = stringResource(R.string.discovery_timeout_body),
                                actionLabel = stringResource(R.string.action_retry),
                                onAction = onStartDiscovery,
                                modifier = Modifier.testTag("discovery_timeout_card")
                            )
                        }
                    }

                    is DiscoveryStatus.Failed -> {
                        item(key = "discovery_failed") {
                            ErrorState(
                                title = stringResource(R.string.discovery_title),
                                message = status.reason,
                                retryLabel = stringResource(R.string.action_retry),
                                onRetry = onStartDiscovery,
                                modifier = Modifier.testTag("discovery_error_card")
                            )
                        }
                    }

                    else -> Unit
                }

                // 6. Nearby ShareFlow Devices List
                item(key = "peers_header") {
                    SectionHeader(title = stringResource(R.string.discovery_peers_section_title))
                }

                if (snapshot.peers.isEmpty()) {
                    item(key = "peers_empty") {
                        EmptyState(
                            icon = Icons.Filled.Radar,
                            title = stringResource(R.string.discovery_empty_title),
                            body = stringResource(R.string.discovery_empty_body),
                            actionLabel = stringResource(R.string.receive_qr_open_action),
                            onAction = onOpenQrPairing,
                            modifier = Modifier.testTag("discovery_empty_peers_card")
                        )
                    }
                } else {
                    items(
                        items = snapshot.peers,
                        key = { "peer_${it.deviceId}" }
                    ) { peer ->
                        DeviceCard(
                            device = peer,
                            onClick = { onConnectToPeer(peer) },
                            modifier = Modifier.testTag("discovery_peer_${peer.deviceId}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceBannerCard(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.cardPadding, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            TextButton(
                onClick = onAction,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
private fun StrategyStatusRow(strategy: StrategyStatus) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val label = when (strategy.connectionType) {
        ConnectionType.WIFI_DIRECT -> stringResource(R.string.ds_connection_wifi_direct)
        ConnectionType.LOCAL_LAN_NSD -> stringResource(R.string.ds_connection_lan)
        ConnectionType.HOTSPOT -> stringResource(R.string.ds_connection_hotspot)
    }
    val (pillText, pillBg, pillFg) = when {
        strategy.isDiscovering || strategy.isAdvertising -> Triple(
            stringResource(R.string.discovery_strategy_active),
            semantic.verifiedContainer,
            semantic.verifiedBadge
        )
        strategy.availability is StrategyAvailability.Available -> Triple(
            stringResource(R.string.discovery_strategy_ready),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        strategy.availability is StrategyAvailability.PermissionRequired -> Triple(
            stringResource(R.string.discovery_strategy_permission),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        strategy.availability is StrategyAvailability.WifiDisabled -> Triple(
            stringResource(R.string.discovery_strategy_wifi_off),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        strategy.availability is StrategyAvailability.LocationServicesDisabled -> Triple(
            stringResource(R.string.discovery_strategy_location_off),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        else -> Triple(
            stringResource(R.string.discovery_strategy_unsupported),
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("strategy_status_${strategy.connectionType.name.lowercase()}"),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.cardPadding, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (strategy.connectionType == ConnectionType.HOTSPOT) {
                        Icons.Filled.WifiTethering
                    } else {
                        Icons.Filled.Wifi
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    strategy.statusDetail?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            StatusPill(
                label = pillText,
                containerColor = pillBg,
                contentColor = pillFg
            )
        }
    }
}
