package com.example.shareflow.ui.receive

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard
import com.example.shareflow.ui.discovery.AdvancedQrScannerView
import com.example.shareflow.ui.discovery.RealQrCodeView
import com.example.shareflow.ui.discovery.SmartConnectionErrorCard
import com.example.shareflow.ui.discovery.SmartSenderProgressCard
import com.example.shareflow.ui.discovery.decodeQrCodeFromGalleryUri
import com.example.shareflow.ui.discovery.shareQrCodeImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@Composable
fun QrPairingScreen(
    onNavigateBack: () -> Unit,
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
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val copiedMessage = stringResource(R.string.qr_pairing_copied_toast)
    val codeCopiedMessage = stringResource(R.string.smart_receive_code_copied_toast)
    val shareChooserTitle = stringResource(R.string.smart_receive_share_qr_chooser)
    val shareErrorText = stringResource(R.string.smart_receive_share_qr_error)
    val galleryDecodingMsg = stringResource(R.string.smart_scanner_gallery_decoding)
    val galleryNoQrMsg = stringResource(R.string.smart_scanner_gallery_no_qr)

    var scanMode by rememberSaveable { mutableStateOf(false) }
    var manualUriInput by rememberSaveable { mutableStateOf("") }
    var isDecodingGallery by remember { mutableStateOf(false) }

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

    // Zero-permission Android Photo Picker for gallery QR import (Google Play compliant)
    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isDecodingGallery = true
                snackbarHostState.showSnackbar(galleryDecodingMsg)
                val decoded = decodeQrCodeFromGalleryUri(context, uri)
                isDecodingGallery = false
                if (!decoded.isNullOrBlank()) {
                    manualUriInput = decoded
                    viewModel.connectViaQrUri(decoded)
                } else {
                    snackbarHostState.showSnackbar(galleryNoQrMsg)
                }
            }
        }
    }

    val activeSmartPayload = if (uiState.isSmartQrReady) uiState.smartQrPayload else null
    val legacyPayload = uiState.pairingPayload
    val activeSessionId = activeSmartPayload?.sessionId ?: legacyPayload?.sessionId
    val activeExpiresAt = activeSmartPayload?.expiresAtEpochMillis ?: legacyPayload?.expiresAtEpochMillis ?: 0L
    val activeFingerprint = activeSmartPayload?.publicKeyFingerprint ?: legacyPayload?.publicKeyFingerprint ?: ""
    val activeUri = if (activeSmartPayload != null) uiState.smartQrUri else uiState.pairingUri
    val activeHotspotSsid = activeSmartPayload?.ssid ?: legacyPayload?.hotspotSsid

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activeSessionId) {
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
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("qr_pairing_diagnostics_button")
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
                            onClick = {
                                scanMode = true
                                viewModel.resetScannerState()
                            },
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

                // Step-by-step sender auto-connection progress (never shows Connected before auth)
                if (scanMode && (uiState.smartPhase.isInProgress || uiState.smartPhase == SmartConnectionPhase.CONNECTED)) {
                    item(key = "qr_sender_progress") {
                        SmartSenderProgressCard(
                            phase = uiState.smartPhase,
                            strategy = uiState.smartStrategy,
                            onCancel = viewModel::cancelSmartConnection
                        )
                    }
                }

                uiState.smartError?.let { smartErr ->
                    item(key = "qr_smart_error") {
                        SmartConnectionErrorCard(
                            error = smartErr,
                            onRetry = viewModel::retrySmartConnection,
                            onDismiss = viewModel::clearSmartError
                        )
                    }
                }

                if (!scanMode && activeSessionId != null) {
                    val remainingSeconds = ((activeExpiresAt - nowMillis) / 1_000L).coerceAtLeast(0L)
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
                                payload = activeUri,
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

                                if (!activeHotspotSsid.isNullOrBlank()) {
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
                                    text = activeSessionId.take(8).uppercase(),
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
                                        activeFingerprint.take(16).chunked(4).joinToString(":").uppercase()
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
                                    text = activeUri,
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
                                    clipboard.setText(AnnotatedString(activeUri))
                                    scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                                },
                                leadingIcon = Icons.Filled.ContentCopy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_copy_uri_button")
                            )
                            if (activeSmartPayload != null && uiState.smartManualCode.isNotBlank()) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.smart_receive_copy_code_action),
                                    onClick = {
                                        clipboard.setText(AnnotatedString(uiState.smartManualCode))
                                        scope.launch { snackbarHostState.showSnackbar(codeCopiedMessage) }
                                    },
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Key,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_copy_manual_code_button")
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
                                            qrPayloadUri = activeUri,
                                            chooserTitle = shareChooserTitle
                                        )
                                        if (!shared) {
                                            scope.launch { snackbarHostState.showSnackbar(shareErrorText) }
                                        }
                                    },
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Share,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("qr_share_image_button")
                                )
                                SecondaryActionButton(
                                    text = stringResource(R.string.qr_pairing_regenerate_action),
                                    onClick = viewModel::regeneratePairingSession,
                                    leadingIcon = Icons.Filled.Refresh,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("qr_regenerate_pin_button")
                                )
                            }
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
                            AdvancedQrScannerView(
                                scannerState = uiState.scannerState,
                                onQrDecoded = { decodedUri ->
                                    manualUriInput = decodedUri
                                    viewModel.connectViaQrUri(decodedUri)
                                },
                                onRetryScan = {
                                    viewModel.clearSmartError()
                                    viewModel.resetScannerState()
                                }
                            )
                        }
                    }

                    // Import QR from Gallery via zero-permission Android Photo Picker
                    item(key = "qr_gallery_import") {
                        SecondaryActionButton(
                            text = stringResource(R.string.smart_scanner_import_gallery),
                            onClick = {
                                galleryPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !isDecodingGallery && !uiState.smartPhase.isInProgress,
                            outlined = true,
                            leadingIcon = Icons.Filled.PhotoLibrary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("qr_import_gallery_button")
                        )
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
                                    label = { Text(stringResource(R.string.smart_scanner_manual_code_label)) },
                                    placeholder = { Text(stringResource(R.string.smart_scanner_manual_code_placeholder)) },
                                    singleLine = false,
                                    maxLines = 3,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_manual_uri_input")
                                )
                                PrimaryActionButton(
                                    text = stringResource(R.string.qr_manual_connect_action),
                                    onClick = { viewModel.connectViaQrUri(manualUriInput) },
                                    enabled = manualUriInput.isNotBlank() && !uiState.smartPhase.isInProgress,
                                    isLoading = uiState.smartPhase.isInProgress,
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
