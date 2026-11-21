package com.example.shareflow.ui.receive

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.core.permissions.ShareFlowPermissionFeature
import com.example.shareflow.core.permissions.rememberPermissionRequestCoordinator
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard
import com.example.shareflow.ui.discovery.RealQrCodeView
import com.example.shareflow.ui.discovery.SmartConnectionErrorCard
import com.example.shareflow.ui.discovery.SmartConnectionPhasePill
import com.example.shareflow.ui.discovery.shareQrCodeImage
import com.example.shareflow.ui.discovery.smartStrategyLabelRes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onOpenTransferProgress: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val viewModel: ReceiveViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            ReceiveViewModel(
                settingsRepository = container.settingsRepository,
                sessionController = container.localSessionController,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository,
                smartCoordinator = container.smartConnectionCoordinator
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeTransferSessions by container.transferManager.activeSessions.collectAsStateWithLifecycle()
    val receivedApks by container.appSharingRepository.observeReceivedApks()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val permissionCoordinator = rememberPermissionRequestCoordinator(container.permissionManager)
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val linkCopiedToast = stringResource(R.string.qr_pairing_copied_toast)
    val codeCopiedToast = stringResource(R.string.smart_receive_code_copied_toast)
    val shareChooserTitle = stringResource(R.string.smart_receive_share_qr_chooser)
    val shareErrorToast = stringResource(R.string.smart_receive_share_qr_error)

    var missingWifiPerms by remember {
        mutableStateOf(container.networkPermissionHelper.missingNearbyWifiPermissions())
    }
    val wifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingWifiPerms = container.networkPermissionHelper.missingNearbyWifiPermissions()
        viewModel.refreshNetworkStatus()
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(uiState.smartQrPayload?.sessionId) {
        while (uiState.smartQrPayload != null) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    val effectivePort = uiState.smartQrPayload?.servicePort
        ?: uiState.discoverySnapshot.advertisedPort
        ?: uiState.settings.transferPort
    val portFormatted = ShareFlowFormatters.formatInteger(effectivePort.toLong(), locale)

    val connectionHeadline = when (val state = uiState.connectionState) {
        is ConnectionState.Advertising -> stringResource(
            R.string.receive_status_listening,
            ShareFlowFormatters.formatInteger(state.port.toLong(), locale)
        )
        is ConnectionState.Connected -> "${stringResource(R.string.peer_status_connected)}: ${state.peer.displayName}"
        is ConnectionState.AwaitingApproval -> stringResource(R.string.peer_status_awaiting_approval)
        is ConnectionState.Error -> state.reason
        else -> stringResource(R.string.receive_status_not_listening)
    }
    val discoveryHeadline = when {
        uiState.isReceiving -> stringResource(
            R.string.receive_discovery_advertising,
            uiState.settings.deviceName
        )
        else -> stringResource(R.string.receive_discovery_inactive)
    }
    val wifiDetail = when {
        !uiState.smartQrPayload?.ssid.isNullOrBlank() -> stringResource(
            R.string.discovery_hotspot_ssid_label,
            uiState.smartQrPayload?.ssid.orEmpty()
        )
        uiState.discoverySnapshot.activeHotspot != null -> stringResource(
            R.string.discovery_hotspot_ssid_label,
            uiState.discoverySnapshot.activeHotspot?.ssid.orEmpty()
        )
        uiState.networkStatus.isWifiConnected -> stringResource(
            R.string.receive_wifi_connected,
            uiState.networkStatus.localIpv4Address ?: "LAN"
        )
        else -> stringResource(R.string.receive_wifi_disconnected)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.receive_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("receive_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NetworkCheck,
                            contentDescription = stringResource(R.string.smart_receive_cd_diagnostics)
                        )
                    }
                }
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
                    .testTag("receive_screen_list"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                val connState = uiState.connectionState
                if (connState is ConnectionState.Connected) {
                    item(key = "active_connection") {
                        ActiveEncryptedConnectionCard(
                            connected = connState,
                            onDisconnect = viewModel::disconnect
                        )
                    }
                }
                if (connState is ConnectionState.Connected || uiState.isReceiving || activeTransferSessions.isNotEmpty()) {
                    item(key = "active_transfers_entry") {
                        SecondaryActionButton(
                            text = stringResource(R.string.transfer_open_progress_action),
                            onClick = onOpenTransferProgress,
                            outlined = false,
                            leadingIcon = Icons.Filled.SwapVert,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_open_transfer_progress_button")
                        )
                    }
                }

                // Live linking/authenticating progress banner when sender connects
                if (connState is ConnectionState.AwaitingApproval ||
                    uiState.smartPhase == SmartConnectionPhase.AUTHENTICATING
                ) {
                    item(key = "receive_linking_banner") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_linking_sender_banner"),
                            shape = ShareFlowDesign.shapes.featuredCard,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
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
                                    CircularProgressIndicator(
                                        strokeWidth = 2.5.dp,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.receive_linking_sender_banner),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                // Error recovery banner
                uiState.smartError?.let { smartErr ->
                    item(key = "smart_error_banner") {
                        SmartConnectionErrorCard(
                            error = smartErr,
                            onRetry = viewModel::retrySmartConnection,
                            onDismiss = viewModel::clearSmartError
                        )
                    }
                }

                // Start / Stop Smart Receiving Primary Card
                item(key = "receive_control") {
                    Surface(
                        shape = ShareFlowDesign.shapes.featuredCard,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(spacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = ShareFlowDesign.shapes.avatar,
                                color = if (uiState.isReceiving) {
                                    semantic.verifiedContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.CloudDownload,
                                        contentDescription = null,
                                        tint = if (uiState.isReceiving) {
                                            semantic.verifiedBadge
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }

                            Text(
                                text = uiState.settings.deviceName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmartConnectionPhasePill(
                                    phase = uiState.smartPhase,
                                    modifier = Modifier.testTag("receive_smart_phase_pill")
                                )
                                StatusPill(
                                    label = "TCP :$portFormatted",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (uiState.smartStrategy != null) {
                                StatusPill(
                                    label = stringResource(smartStrategyLabelRes(uiState.smartStrategy)),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            } else if (uiState.enableLocalHotspot) {
                                StatusPill(
                                    label = stringResource(R.string.shareit_direct_p2p_badge),
                                    containerColor = semantic.verifiedContainer,
                                    contentColor = semantic.verifiedBadge,
                                    modifier = Modifier.testTag("receive_direct_p2p_badge")
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
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column {
                                        Text(
                                            text = stringResource(R.string.discovery_hotspot_toggle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.smart_receive_hotspot_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Switch(
                                    checked = uiState.enableLocalHotspot,
                                    onCheckedChange = viewModel::setEnableLocalHotspot,
                                    enabled = !uiState.isReceiving,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("receive_hotspot_switch")
                                )
                            }

                            if (missingWifiPerms.isNotEmpty() && uiState.enableLocalHotspot && !uiState.isReceiving) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.smart_receive_grant_wifi_permissions),
                                    onClick = {
                                        wifiPermissionLauncher.launch(missingWifiPerms.toTypedArray())
                                    },
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Wifi,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("receive_grant_wifi_permissions_button")
                                )
                            }

                            if (uiState.isReceiving) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.smart_receive_cancel_action),
                                    onClick = viewModel::toggleReceiving,
                                    isDestructive = true,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.StopCircle,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("receive_toggle_button")
                                )
                            } else {
                                PrimaryActionButton(
                                    text = stringResource(R.string.receive_start_action),
                                    onClick = {
                                        if (uiState.enableLocalHotspot && missingWifiPerms.isNotEmpty()) {
                                            permissionCoordinator.ensureFeatureReady(
                                                feature = ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT,
                                                onGranted = {
                                                    missingWifiPerms = container.networkPermissionHelper.missingNearbyWifiPermissions()
                                                    viewModel.toggleReceiving()
                                                },
                                                onDenied = {
                                                    // Gracefully continue with fallback strategy (e.g. Existing Local Wi-Fi)
                                                    viewModel.toggleReceiving()
                                                }
                                            )
                                        } else {
                                            viewModel.toggleReceiving()
                                        }
                                    },
                                    leadingIcon = Icons.Filled.CloudDownload,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("receive_toggle_button")
                                )
                            }
                        }
                    }
                }

                // Preparation Progress Card (shown ONLY while hotspot/server is starting — QR stays hidden!)
                if (uiState.isPreparingReceiver) {
                    item(key = "smart_preparing_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_preparing_card"),
                            shape = ShareFlowDesign.shapes.card,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.cardPadding),
                                verticalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Text(
                                    text = stringResource(R.string.smart_receive_preparing_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = stringResource(R.string.smart_receive_preparing_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Live Signed Smart QR Card — strictly rendered ONLY when isSmartQrReady == true
                if (uiState.isSmartQrReady) {
                    val payload = uiState.smartQrPayload!!
                    val remainingSeconds = ((payload.expiresAtEpochMillis - nowMillis) / 1_000L).coerceAtLeast(0L)
                    val isExpired = remainingSeconds <= 0L || uiState.smartPhase == SmartConnectionPhase.EXPIRED

                    item(key = "smart_live_qr_card") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_live_smart_qr_card"),
                            shape = ShareFlowDesign.shapes.featuredCard,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.5.dp, semantic.verifiedBadge.copy(alpha = 0.6f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    StatusPill(
                                        label = stringResource(R.string.smart_receive_network_ready_badge),
                                        containerColor = semantic.verifiedContainer,
                                        contentColor = semantic.verifiedBadge
                                    )
                                    StatusPill(
                                        label = if (isExpired) {
                                            stringResource(R.string.qr_token_expired)
                                        } else {
                                            stringResource(R.string.qr_expires_in_format, remainingSeconds)
                                        },
                                        containerColor = if (isExpired) {
                                            MaterialTheme.colorScheme.errorContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = if (isExpired) {
                                            MaterialTheme.colorScheme.onErrorContainer
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        }
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.smart_receive_qr_ready_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.smart_receive_qr_ready_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )

                                if (!isExpired && uiState.smartPhase == SmartConnectionPhase.WAITING_FOR_QR) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("receive_waiting_searching_banner"),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = semantic.verifiedBadge
                                        )
                                        Text(
                                            text = stringResource(R.string.receive_waiting_searching_banner),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = semantic.verifiedBadge,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }

                                Surface(
                                    shape = ShareFlowDesign.shapes.featuredCard,
                                    color = Color.White,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .size(236.dp)
                                        .testTag("receive_smart_qr_matrix")
                                ) {
                                    RealQrCodeView(
                                        payload = uiState.smartQrUri,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp)
                                    )
                                }

                                if (uiState.smartManualCode.isNotBlank()) {
                                    Text(
                                        text = stringResource(R.string.smart_receive_manual_code_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = uiState.smartManualCode.take(36) + if (uiState.smartManualCode.length > 36) "…" else "",
                                        style = ShareFlowMonoStyle,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.smart_receive_copy_link_action),
                                        onClick = {
                                            clipboard.setText(AnnotatedString(uiState.smartQrUri))
                                            scope.launch { snackbarHostState.showSnackbar(linkCopiedToast) }
                                        },
                                        outlined = true,
                                        leadingIcon = Icons.Filled.ContentCopy,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("receive_copy_link_button")
                                    )
                                    SecondaryActionButton(
                                        text = stringResource(R.string.smart_receive_copy_code_action),
                                        onClick = {
                                            clipboard.setText(AnnotatedString(uiState.smartManualCode))
                                            scope.launch { snackbarHostState.showSnackbar(codeCopiedToast) }
                                        },
                                        outlined = true,
                                        leadingIcon = Icons.Filled.Key,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("receive_copy_code_button")
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.smart_receive_share_qr_action),
                                        onClick = {
                                            val shared = shareQrCodeImage(
                                                context = context,
                                                qrPayloadUri = uiState.smartQrUri,
                                                chooserTitle = shareChooserTitle
                                            )
                                            if (!shared) {
                                                scope.launch { snackbarHostState.showSnackbar(shareErrorToast) }
                                            }
                                        },
                                        outlined = false,
                                        leadingIcon = Icons.Filled.Share,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("receive_share_qr_button")
                                    )
                                    SecondaryActionButton(
                                        text = stringResource(R.string.smart_receive_regenerate_action),
                                        onClick = viewModel::regeneratePairingSession,
                                        outlined = true,
                                        leadingIcon = Icons.Filled.Refresh,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("receive_refresh_qr_button")
                                    )
                                }
                            }
                        }
                    }
                }

                // Received Applications Ready to Install (PackageInstaller.Session)
                if (receivedApks.any { it.canLaunchSystemInstaller }) {
                    item(key = "received_apps_installer") {
                        ReceivedAppsInstallerCard(
                            receivedApks = receivedApks,
                            installationManager = container.applicationInstallationManager,
                            permissionManager = container.permissionManager
                        )
                    }
                }

                // Connection Status Card
                item(key = "connection_status") {
                    ReceiveStatusCard(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.receive_connection_status_title),
                        headline = connectionHeadline,
                        subtitle = wifiDetail,
                        isActive = uiState.isReceiving || connState is ConnectionState.Connected,
                        testTag = "receive_connection_status_card"
                    )
                }

                // Device Discovery Status Card
                item(key = "discovery_status") {
                    ReceiveStatusCard(
                        icon = Icons.Filled.Radar,
                        title = stringResource(R.string.receive_discovery_status_title),
                        headline = discoveryHeadline,
                        subtitle = "${stringResource(R.string.smart_strategy_local_hotspot)} • ${stringResource(R.string.ds_connection_wifi_direct)} • ${stringResource(R.string.ds_connection_lan)}",
                        isActive = uiState.isReceiving,
                        testTag = "receive_discovery_status_card"
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiveStatusCard(
    icon: ImageVector,
    title: String,
    headline: String,
    subtitle: String,
    isActive: Boolean,
    testTag: String
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isActive) semantic.verifiedContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isActive) semantic.verifiedBadge else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
