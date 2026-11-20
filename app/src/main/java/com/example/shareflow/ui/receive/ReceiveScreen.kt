package com.example.shareflow.ui.receive

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
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
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard
import com.example.shareflow.ui.discovery.QrCameraScannerView
import com.example.shareflow.ui.discovery.RealQrCodeView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: ReceiveViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            ReceiveViewModel(
                settingsRepository = container.settingsRepository,
                sessionController = container.localSessionController,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    val effectivePort = uiState.discoverySnapshot.advertisedPort ?: uiState.settings.transferPort
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
    val wifiDetail = if (uiState.networkStatus.isWifiConnected) {
        stringResource(
            R.string.receive_wifi_connected,
            uiState.networkStatus.localIpv4Address ?: "LAN"
        )
    } else {
        stringResource(R.string.receive_wifi_disconnected)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.receive_title),
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

                // Start / Stop Receiving Primary Action
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
                                StatusPill(
                                    label = "TCP :$portFormatted",
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                uiState.localIdentity?.let { identity ->
                                    StatusPill(
                                        label = stringResource(R.string.discovery_device_id_format, identity.shortDeviceId),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
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
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
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

                            if (uiState.isReceiving) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.receive_stop_action),
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
                                    onClick = viewModel::toggleReceiving,
                                    leadingIcon = Icons.Filled.CloudDownload,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("receive_toggle_button")
                                )
                            }
                        }
                    }
                }

                // Connection Status Card
                item(key = "connection_status") {
                    ReceiveStatusCard(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.receive_connection_status_title),
                        headline = connectionHeadline,
                        subtitle = uiState.discoverySnapshot.activeHotspot?.let {
                            stringResource(R.string.discovery_hotspot_ssid_label, it.ssid)
                        } ?: wifiDetail,
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
                        subtitle = "${stringResource(R.string.ds_connection_wifi_direct)} • ${stringResource(R.string.ds_connection_lan)}",
                        isActive = uiState.isReceiving,
                        testTag = "receive_discovery_status_card"
                    )
                }

                // QR Pairing Entry Point
                item(key = "qr_pairing_entry") {
                    Surface(
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.testTag("receive_qr_card")
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
                                    imageVector = Icons.Filled.QrCode2,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.receive_qr_card_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.receive_qr_card_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            SecondaryActionButton(
                                text = stringResource(R.string.receive_qr_open_action),
                                onClick = onOpenQrPairing,
                                outlined = false,
                                leadingIcon = Icons.Filled.QrCode2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("receive_open_qr_button")
                            )
                        }
                    }
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

@Suppress("DEPRECATION")
@Composable
fun QrPairingScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: ReceiveViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            ReceiveViewModel(
                settingsRepository = container.settingsRepository,
                sessionController = container.localSessionController,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pairing = uiState.pairingPayload
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.qr_pairing_copied_toast)

    var scanMode by rememberSaveable { mutableStateOf(false) }
    var manualUriInput by rememberSaveable { mutableStateOf("") }
    var hasCameraPermission by remember {
        mutableStateOf(container.networkPermissionHelper.hasCameraPermission())
    }
    val hasCameraHardware = remember {
        container.networkPermissionHelper.isCameraHardwareSupported()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairing?.sessionId) {
        while (true) {
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.qr_pairing_title),
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
                    .testTag("qr_pairing_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mode Selector: Show My QR vs Scan Peer QR
                item(key = "qr_mode_tabs") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        FilterChip(
                            selected = !scanMode,
                            onClick = { scanMode = false },
                            label = { Text(stringResource(R.string.qr_tab_show)) },
                            leadingIcon = {
                                Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("qr_tab_show_button")
                        )
                        FilterChip(
                            selected = scanMode,
                            onClick = { scanMode = true },
                            label = { Text(stringResource(R.string.qr_tab_scan)) },
                            leadingIcon = {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("qr_tab_scan_button")
                        )
                    }
                }

                val connState = uiState.connectionState
                if (connState is ConnectionState.Connected) {
                    item(key = "qr_active_conn") {
                        ActiveEncryptedConnectionCard(
                            connected = connState,
                            onDisconnect = viewModel::disconnect
                        )
                    }
                }

                if (!scanMode && pairing != null) {
                    val remainingSeconds = ((pairing.expiresAtEpochMillis - nowMillis) / 1_000L).coerceAtLeast(0L)
                    val isExpired = remainingSeconds <= 0L

                    item(key = "qr_visual") {
                        Surface(
                            shape = ShareFlowDesign.shapes.featuredCard,
                            color = Color.White,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .size(240.dp)
                                .testTag("qr_pairing_matrix")
                        ) {
                            RealQrCodeView(
                                payload = uiState.pairingUri,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                            )
                        }
                    }

                    item(key = "pin_card") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = ShareFlowDesign.shapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.cardPadding),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                StatusPill(
                                    label = if (isExpired) {
                                        stringResource(R.string.qr_token_expired)
                                    } else {
                                        stringResource(R.string.qr_expires_in_format, remainingSeconds)
                                    },
                                    containerColor = if (isExpired) {
                                        MaterialTheme.colorScheme.errorContainer
                                    } else {
                                        semantic.verifiedContainer
                                    },
                                    contentColor = if (isExpired) {
                                        MaterialTheme.colorScheme.onErrorContainer
                                    } else {
                                        semantic.verifiedBadge
                                    }
                                )

                                if (!pairing.hotspotSsid.isNullOrBlank()) {
                                    StatusPill(
                                        label = stringResource(R.string.qr_hotspot_included_badge),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Text(
                                    text = stringResource(R.string.qr_pairing_pin_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = pairing.sessionId.take(8).uppercase(),
                                    style = ShareFlowMonoStyle.copy(
                                        fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                        lineHeight = MaterialTheme.typography.headlineMedium.lineHeight
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("qr_pairing_pin_value")
                                )
                                Text(
                                    text = stringResource(
                                        R.string.discovery_fingerprint_format,
                                        pairing.publicKeyFingerprint.take(16).chunked(4).joinToString(":").uppercase()
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.qr_pairing_uri_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = uiState.pairingUri,
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    item(key = "qr_actions") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.qr_pairing_copy_action),
                                onClick = {
                                    clipboard.setText(AnnotatedString(uiState.pairingUri))
                                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                                },
                                leadingIcon = Icons.Filled.ContentCopy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_copy_uri_button")
                            )
                            SecondaryActionButton(
                                text = stringResource(R.string.qr_pairing_regenerate_action),
                                onClick = viewModel::regeneratePairingSession,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_regenerate_pin_button")
                            )
                        }
                    }
                } else if (scanMode) {
                    item(key = "qr_scanner_panel") {
                        if (!hasCameraHardware) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = stringResource(R.string.qr_scanner_no_camera),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(spacing.cardPadding)
                                )
                            }
                        } else if (!hasCameraPermission) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_camera_permission_card"),
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.cardPadding),
                                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.CameraAlt,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.qr_scanner_permission_title),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.qr_scanner_permission_body),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    PrimaryActionButton(
                                        text = stringResource(R.string.qr_scanner_grant_camera),
                                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                        leadingIcon = Icons.Filled.CameraAlt,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("qr_grant_camera_button")
                                    )
                                }
                            }
                        } else {
                            QrCameraScannerView(
                                onQrDecoded = { decodedUri ->
                                    manualUriInput = decodedUri
                                    viewModel.connectViaQrUri(decodedUri)
                                }
                            )
                        }
                    }

                    item(key = "qr_manual_entry") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
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
                                OutlinedTextField(
                                    value = manualUriInput,
                                    onValueChange = { manualUriInput = it },
                                    label = { Text(stringResource(R.string.qr_manual_uri_label)) },
                                    singleLine = false,
                                    maxLines = 3,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_manual_uri_input")
                                )
                                PrimaryActionButton(
                                    text = stringResource(R.string.qr_manual_connect_action),
                                    onClick = { viewModel.connectViaQrUri(manualUriInput) },
                                    enabled = manualUriInput.isNotBlank(),
                                    leadingIcon = Icons.Filled.Link,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_manual_connect_button")
                                )
                            }
                        }
                    }
                }

                item(key = "qr_security_note") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.qr_pairing_security_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
