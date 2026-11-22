package com.example.shareflow.ui.send

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.DeviceCard
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.core.permissions.ShareFlowPermissionFeature
import com.example.shareflow.core.permissions.rememberPermissionRequestCoordinator
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.DiscoveryStatus
import com.example.shareflow.domain.model.QrScannerUiState
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard
import com.example.shareflow.ui.discovery.AdvancedQrScannerView
import com.example.shareflow.ui.discovery.KeepScreenOnEffect
import com.example.shareflow.ui.discovery.QuickPairingGuideCard
import com.example.shareflow.ui.discovery.SmartConnectionErrorCard
import com.example.shareflow.ui.discovery.SmartSenderProgressCard
import com.example.shareflow.ui.discovery.decodeQrCodeFromGalleryUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SendConnectMode {
    SCAN_QR,
    MANUAL_CODE,
    NEARBY
}

/**
 * Unified Sender Connection & Confirmation screen integrated directly inside the main Send workflow.
 * Embeds the Smart QR Scanner (CameraX + ML Kit + ZXing fallback), Manual SF3- code, Gallery QR
 * import, Wi-Fi auto-join progress, Nearby peers, Smart Permission UX, and Encrypted Transfer trigger.
 */
@Suppress("DEPRECATION")
@Composable
fun SendConfirmScreen(
    onNavigateBack: () -> Unit,
    onOpenQrPairing: () -> Unit,
    onOpenTransferProgress: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val viewModel: SendViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            SendViewModel(
                sessionController = container.localSessionController,
                trustedDeviceRepository = container.trustedDeviceRepository,
                settingsRepository = container.settingsRepository,
                discoveryRepository = container.deviceDiscoveryRepository,
                connectionRepository = container.connectionRepository,
                transferManager = container.transferManager,
                mediaCenterRepository = container.mediaCenterRepository,
                appSharingRepository = container.appSharingRepository,
                fileManagerRepository = container.fileManagerRepository,
                unifiedConnectionManager = container.unifiedConnectionManager
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val permissionCoordinator = rememberPermissionRequestCoordinator(container.permissionManager)

    KeepScreenOnEffect(enabled = uiState.connectionState !is ConnectionState.Connected)

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors
    val peers = (uiState.discoveredDevices + uiState.trustedDevices).distinctBy { it.deviceId }
    val liveNearbyReceivers = uiState.discoveredDevices
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var connectMode by rememberSaveable { mutableStateOf(SendConnectMode.SCAN_QR) }
    var manualCodeInput by rememberSaveable { mutableStateOf("") }
    var awaitingFreshLinkAutoSend by rememberSaveable { mutableStateOf(false) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val galleryNoQrMsg = stringResource(R.string.smart_scanner_gallery_no_qr)
    val galleryErrorMsg = stringResource(R.string.smart_scanner_gallery_error)
    val clipboardEmptyMsg = stringResource(R.string.send_clipboard_empty_toast)

    val galleryQrLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = withContext(Dispatchers.IO) {
                    runCatching { decodeQrCodeFromGalleryUri(context, uri) }.getOrNull()
                }
                if (!decoded.isNullOrBlank()) {
                    awaitingFreshLinkAutoSend = true
                    viewModel.connectViaQrOrCode(decoded)
                } else {
                    viewModel.showFeedback(galleryNoQrMsg)
                }
            }
        }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeFeedback()
        }
    }

    var autoStartedPeerSession by rememberSaveable { mutableStateOf<String?>(null) }
    val autoSendStartingMsg = stringResource(R.string.shareit_auto_send_starting)

    // SHAREit-style zero-intermediary workflow: once a fresh QR, manual code, or 1-tap Nearby
    // link succeeds with AES-256-GCM auth, immediately start sending without an extra confirmation tap.
    LaunchedEffect(
        uiState.connectionState,
        uiState.scannerState,
        awaitingFreshLinkAutoSend,
        uiState.selectedCount
    ) {
        val connected = uiState.connectionState as? ConnectionState.Connected
        val readyForAutoSend = uiState.scannerState == QrScannerUiState.SUCCESS || awaitingFreshLinkAutoSend
        if (connected != null &&
            readyForAutoSend &&
            uiState.selectedCount > 0 &&
            !uiState.isStartingTransfer
        ) {
            val sessionKey = "${connected.peer.deviceId}:${connected.sasVerificationCode}"
            if (autoStartedPeerSession != sessionKey) {
                autoStartedPeerSession = sessionKey
                awaitingFreshLinkAutoSend = false
                viewModel.showFeedback(autoSendStartingMsg)
                viewModel.startEncryptedTransfer(onStarted = onOpenTransferProgress)
            }
        }
    }

    // Proactively discover nearby receivers on the same local network as soon as SendConfirm opens
    // so 1-tap Quick Connect pills pop up even while the QR scanner is open.
    LaunchedEffect(Unit) {
        if (uiState.connectionState !is ConnectionState.Connected &&
            uiState.discoveryStatus !is DiscoveryStatus.Discovering
        ) {
            viewModel.startPeerDiscovery()
        }
    }

    // Re-trigger discovery if user explicitly switches to Nearby tab while idle
    LaunchedEffect(connectMode) {
        if (connectMode == SendConnectMode.NEARBY &&
            uiState.connectionState !is ConnectionState.Connected &&
            uiState.discoveryStatus !is DiscoveryStatus.Discovering
        ) {
            viewModel.startPeerDiscovery()
        }
    }

    val isSmartJoining = uiState.smartPhase in setOf(
        SmartConnectionPhase.QR_SCANNED,
        SmartConnectionPhase.REQUESTING_WIFI,
        SmartConnectionPhase.WAITING_FOR_APPROVAL,
        SmartConnectionPhase.JOINING_NETWORK,
        SmartConnectionPhase.DISCOVERING_RECEIVER,
        SmartConnectionPhase.AUTHENTICATING
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.send_confirm_title),
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
                    .testTag("send_confirm_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // 1. Active Authenticated Connection + Immediate Send Trigger
                val connState = uiState.connectionState
                if (connState is ConnectionState.Connected) {
                    item(key = "active_connection") {
                        ActiveEncryptedConnectionCard(
                            connected = connState,
                            onDisconnect = viewModel::disconnect
                        )
                    }
                    if (uiState.selectedCount > 0) {
                        item(key = "start_encrypted_transfer") {
                            PrimaryActionButton(
                                text = stringResource(R.string.transfer_start_encrypted_action),
                                onClick = {
                                    viewModel.startEncryptedTransfer(onStarted = onOpenTransferProgress)
                                },
                                enabled = !uiState.isStartingTransfer,
                                leadingIcon = Icons.AutoMirrored.Filled.Send,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("send_confirm_start_transfer_button")
                            )
                        }
                    }
                }

                // 2. Live Smart Wi-Fi Auto-Join / Discovery / Auth / Searching Progress
                val isDirectConnecting = !isSmartJoining && (
                    uiState.scannerState == QrScannerUiState.LOADING ||
                        connState is ConnectionState.Connecting ||
                        connState is ConnectionState.AwaitingApproval
                    )
                val isSearchingNearby = !isSmartJoining &&
                    !isDirectConnecting &&
                    connState !is ConnectionState.Connected &&
                    connectMode == SendConnectMode.NEARBY &&
                    uiState.discoveryStatus is DiscoveryStatus.Discovering

                if (isSmartJoining) {
                    item(key = "smart_sender_progress") {
                        SmartSenderProgressCard(
                            phase = uiState.smartPhase,
                            strategy = uiState.smartStrategy,
                            onCancel = viewModel::cancelSmartConnect
                        )
                    }
                } else if (isDirectConnecting) {
                    item(key = "direct_connecting_progress") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_live_connection_progress_card"),
                            shape = ShareFlowDesign.shapes.featuredCard,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
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
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.send_connecting_peer_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.send_connecting_peer_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                                SecondaryActionButton(
                                    text = stringResource(R.string.smart_sender_cancel_action),
                                    onClick = viewModel::cancelSmartConnect,
                                    isDestructive = true,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Close,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("send_live_cancel_button")
                                )
                            }
                        }
                    }
                } else if (isSearchingNearby) {
                    item(key = "nearby_searching_progress") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_live_searching_progress_card"),
                            shape = ShareFlowDesign.shapes.featuredCard,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
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
                                        color = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.send_searching_nearby_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.send_searching_nearby_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                // 3. Actionable Error Recovery Banner
                uiState.smartError?.let { smartErr ->
                    item(key = "smart_sender_error") {
                        SmartConnectionErrorCard(
                            error = smartErr,
                            onRetry = viewModel::retrySmartConnect,
                            onDismiss = viewModel::clearSmartError
                        )
                    }
                }

                // 4. Compact 1-Line Package Summary Pill Bar (keeps QR Scanner immediately above the fold)
                item(key = "package_summary") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("send_confirm_summary_bar"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderZip,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(
                                    R.string.send_summary_compact_format,
                                    ShareFlowFormatters.formatCount(uiState.selectedCount, locale),
                                    ShareFlowFormatters.formatBytes(uiState.totalSelectedBytes, locale)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = ShareFlowDesign.shapes.chip,
                                color = semantic.verifiedContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = null,
                                        tint = semantic.verifiedBadge,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.send_summary_sha256_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = semantic.verifiedBadge
                                    )
                                }
                            }
                        }
                    }
                }

                // 4b. Instant 1-Tap Quick Connect Nearby Receivers Strip (pops up even while on Scan QR tab!)
                if (connState !is ConnectionState.Connected &&
                    !isSmartJoining &&
                    !isDirectConnecting &&
                    connectMode != SendConnectMode.NEARBY &&
                    liveNearbyReceivers.isNotEmpty()
                ) {
                    item(key = "quick_nearby_strip") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_quick_nearby_strip"),
                            shape = ShareFlowDesign.shapes.card,
                            color = semantic.verifiedContainer.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, semantic.verifiedBadge.copy(alpha = 0.55f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.sm),
                                verticalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Radar,
                                        contentDescription = null,
                                        tint = semantic.verifiedBadge,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.send_quick_nearby_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.send_quick_nearby_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    items(items = liveNearbyReceivers, key = { it.deviceId }) { peer ->
                                        AssistChip(
                                            onClick = {
                                                awaitingFreshLinkAutoSend = true
                                                viewModel.connectToPeer(peer)
                                            },
                                            label = {
                                                Text(
                                                    text = "${peer.displayName} • ${stringResource(R.string.send_quick_nearby_connect_action)}",
                                                    maxLines = 1
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Filled.Devices,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            shape = ShareFlowDesign.shapes.chip,
                                            colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.assistChipColors(),
                                            border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.assistChipBorder(),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("send_quick_nearby_peer_${peer.deviceId}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Active transfers quick shortcut if running
                if (uiState.activeTransferCount > 0) {
                    item(key = "open_active_transfers") {
                        SecondaryActionButton(
                            text = stringResource(R.string.transfer_open_progress_action),
                            onClick = onOpenTransferProgress,
                            outlined = false,
                            leadingIcon = Icons.Filled.SwapVert,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_confirm_open_progress_button")
                        )
                    }
                }

                // 6. Unified In-Send Connection Selector (Scan QR | Manual Code | Nearby)
                if (connState !is ConnectionState.Connected) {
                    item(key = "connect_mode_selector") {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                            Text(
                                text = stringResource(R.string.send_connect_header_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("send_connect_mode_switcher")
                            ) {
                                val modes = listOf(
                                    SendConnectMode.SCAN_QR to R.string.send_connect_scan_qr_tab,
                                    SendConnectMode.MANUAL_CODE to R.string.send_connect_manual_code_tab,
                                    SendConnectMode.NEARBY to R.string.send_connect_nearby_tab
                                )
                                modes.forEachIndexed { idx, (mode, labelRes) ->
                                    SegmentedButton(
                                        selected = connectMode == mode,
                                        onClick = { connectMode = mode },
                                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = modes.size),
                                        colors = SegmentedButtonDefaults.colors(
                                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            activeBorderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.72f),
                                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant
                                        ),
                                        modifier = Modifier.testTag("send_connect_mode_${mode.name.lowercase()}")
                                    ) {
                                        Text(text = stringResource(labelRes), maxLines = 1)
                                    }
                                }
                            }
                        }
                    }

                    when (connectMode) {
                        SendConnectMode.SCAN_QR -> {
                            item(key = "inline_qr_scanner") {
                                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                    StatusPill(
                                        label = stringResource(R.string.shareit_direct_p2p_badge),
                                        containerColor = semantic.verifiedContainer,
                                        contentColor = semantic.verifiedBadge,
                                        modifier = Modifier.testTag("send_direct_p2p_badge")
                                    )
                                    if (hasCameraPermission && container.networkPermissionHelper.isCameraHardwareSupported()) {
                                        Surface(
                                            shape = ShareFlowDesign.shapes.featuredCard,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(300.dp)
                                                .testTag("send_confirm_inline_qr_scanner")
                                        ) {
                                            AdvancedQrScannerView(
                                                scannerState = uiState.scannerState,
                                                onQrDecoded = { raw ->
                                                    if (uiState.scannerState == QrScannerUiState.SCANNING) {
                                                        awaitingFreshLinkAutoSend = true
                                                        viewModel.connectViaQrOrCode(raw)
                                                    }
                                                },
                                                onRetryScan = {
                                                    viewModel.clearSmartError()
                                                    viewModel.resetScannerState()
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    } else {
                                        Surface(
                                            shape = ShareFlowDesign.shapes.card,
                                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(spacing.cardPadding),
                                                verticalArrangement = Arrangement.spacedBy(spacing.sm),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.QrCodeScanner,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(36.dp)
                                                )
                                                Text(
                                                    text = stringResource(R.string.perm_camera_qr_body),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                PrimaryActionButton(
                                                    text = stringResource(R.string.send_connect_camera_grant_action),
                                                    onClick = {
                                                        permissionCoordinator.ensureFeatureReady(
                                                            feature = ShareFlowPermissionFeature.QR_CAMERA,
                                                            onGranted = { hasCameraPermission = true }
                                                        )
                                                    },
                                                    leadingIcon = Icons.Filled.CameraAlt,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("send_confirm_enable_camera_button")
                                                )
                                            }
                                        }
                                    }

                                    SecondaryActionButton(
                                        text = stringResource(R.string.smart_scanner_import_gallery),
                                        onClick = {
                                            runCatching {
                                                galleryQrLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            }.onFailure {
                                                viewModel.showFeedback(galleryErrorMsg)
                                            }
                                        },
                                        outlined = true,
                                        leadingIcon = Icons.Filled.Image,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("send_confirm_gallery_qr_button")
                                    )
                                }
                            }
                        }

                        SendConnectMode.MANUAL_CODE -> {
                            item(key = "inline_manual_code") {
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
                                        OutlinedTextField(
                                            value = manualCodeInput,
                                            onValueChange = { manualCodeInput = it },
                                            label = { Text(stringResource(R.string.smart_scanner_manual_code_label)) },
                                            placeholder = { Text(stringResource(R.string.smart_scanner_manual_code_placeholder)) },
                                            shape = ShareFlowDesign.shapes.input,
                                            colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                                            singleLine = false,
                                            maxLines = 3,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("send_confirm_manual_code_input")
                                        )
                                        SecondaryActionButton(
                                            text = stringResource(R.string.send_paste_and_connect_action),
                                            onClick = {
                                                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                                if (pasted.isNotBlank()) {
                                                    manualCodeInput = pasted
                                                    awaitingFreshLinkAutoSend = true
                                                    viewModel.connectViaQrOrCode(pasted)
                                                } else {
                                                    viewModel.showFeedback(clipboardEmptyMsg)
                                                }
                                            },
                                            outlined = true,
                                            enabled = !isSmartJoining,
                                            leadingIcon = Icons.Filled.ContentPasteGo,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("send_confirm_paste_connect_button")
                                        )
                                        PrimaryActionButton(
                                            text = stringResource(R.string.send_connect_manual_submit_action),
                                            onClick = {
                                                if (manualCodeInput.isNotBlank()) {
                                                    awaitingFreshLinkAutoSend = true
                                                    viewModel.connectViaQrOrCode(manualCodeInput)
                                                }
                                            },
                                            enabled = manualCodeInput.isNotBlank() && !isSmartJoining,
                                            leadingIcon = Icons.Filled.Key,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("send_confirm_manual_connect_button")
                                        )
                                    }
                                }
                            }
                        }

                        SendConnectMode.NEARBY -> {
                            item(key = "peers_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SectionHeader(title = stringResource(R.string.send_confirm_peers_title))
                                    TextButton(
                                        onClick = {
                                            permissionCoordinator.ensureFeatureReady(
                                                feature = ShareFlowPermissionFeature.SEND_WIFI_NEARBY,
                                                onGranted = { viewModel.startPeerDiscovery() },
                                                onDenied = { viewModel.startPeerDiscovery() }
                                            )
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("send_confirm_scan_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Radar,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.discovery_start_action),
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                            }

                            if (peers.isEmpty()) {
                                item(key = "no_peers") {
                                    EmptyState(
                                        icon = Icons.Filled.Devices,
                                        title = stringResource(R.string.send_confirm_no_peers_title),
                                        body = stringResource(R.string.send_confirm_no_peers_body),
                                        actionLabel = stringResource(R.string.send_connect_scan_qr_tab),
                                        onAction = { connectMode = SendConnectMode.SCAN_QR },
                                        modifier = Modifier.testTag("send_confirm_empty_peers")
                                    )
                                }
                            } else {
                                items(items = peers, key = { it.deviceId }) { peer ->
                                    DeviceCard(
                                        device = peer,
                                        onClick = {
                                            awaitingFreshLinkAutoSend = true
                                            viewModel.connectToPeer(peer)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // 7. Compact 3-Step Quick Pairing Guide
                    item(key = "send_quick_guide") {
                        QuickPairingGuideCard(
                            title = stringResource(R.string.send_guide_title),
                            steps = listOf(
                                stringResource(R.string.send_guide_step_1),
                                stringResource(R.string.send_guide_step_2),
                                stringResource(R.string.send_guide_step_3)
                            ),
                            testTag = "send_quick_guide_card"
                        )
                    }
                }
            }
        }
    }
}
