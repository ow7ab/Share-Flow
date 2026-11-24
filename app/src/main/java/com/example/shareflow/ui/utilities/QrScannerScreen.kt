package com.example.shareflow.ui.utilities

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.QrScannerUiState
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.ui.discovery.ActiveEncryptedConnectionCard
import com.example.shareflow.ui.discovery.AdvancedQrScannerView
import com.example.shareflow.ui.discovery.NfcSenderTapCard
import com.example.shareflow.ui.discovery.SmartConnectionErrorCard
import com.example.shareflow.ui.discovery.SmartSenderProgressCard
import com.example.shareflow.ui.discovery.decodeQrCodeFromGalleryUri
import kotlinx.coroutines.launch

/**
 * Upgraded Direct QR Scanner utility screen powered by CameraX + ML Kit Barcode Scanning
 * (with ZXing fallback), gallery QR import via zero-permission Photo Picker, manual SF3 code entry,
 * and SmartConnectionCoordinator auto-join.
 */
@Composable
fun QrScannerScreen(
    onNavigateBack: () -> Unit,
    onOpenFullQrPairing: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val spacing = ShareFlowDesign.spacing
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    val connectionState by container.connectionRepository.connectionState.collectAsStateWithLifecycle()
    val smartPhase by container.smartConnectionCoordinator.phase.collectAsStateWithLifecycle()
    val smartStrategy by container.smartConnectionCoordinator.activeStrategy.collectAsStateWithLifecycle()
    val smartError by container.smartConnectionCoordinator.lastError.collectAsStateWithLifecycle()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var manualUri by rememberSaveable { mutableStateOf("") }
    var isPairing by rememberSaveable { mutableStateOf(false) }
    var isDecodingGallery by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var scannerState by remember { mutableStateOf(QrScannerUiState.SCANNING) }

    val pairedSuccessText = stringResource(R.string.qr_scanner_paired_success)
    val galleryDecodingMsg = stringResource(R.string.smart_scanner_gallery_decoding)
    val galleryNoQrMsg = stringResource(R.string.smart_scanner_gallery_no_qr)

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    fun submitQrUri(rawInput: String) {
        val trimmed = rawInput.trim()
        if (isPairing || trimmed.isBlank()) return
        scope.launch {
            isPairing = true
            errorMessage = null
            scannerState = QrScannerUiState.LOADING
            if (SmartQrPairingProtocol.isSmartV3Payload(trimmed)) {
                val res = container.smartConnectionCoordinator.connectViaSmartQr(
                    rawQrOrManualCode = trimmed,
                    localDeviceName = settings.deviceName
                )
                isPairing = false
                when (res) {
                    is AppResult.Success -> {
                        scannerState = QrScannerUiState.SUCCESS
                        snackbarHostState.showSnackbar(pairedSuccessText)
                    }
                    is AppResult.Failure -> {
                        val code = container.smartConnectionCoordinator.lastError.value?.code
                        scannerState = when (code) {
                            SmartConnectionErrorCode.STALE_OR_EXPIRED_QR,
                            SmartConnectionErrorCode.SESSION_EXPIRED -> QrScannerUiState.EXPIRED_QR
                            SmartConnectionErrorCode.INVALID_QR,
                            SmartConnectionErrorCode.TAMPERED_QR,
                            SmartConnectionErrorCode.REPLAYED_QR,
                            SmartConnectionErrorCode.SELF_PAIRING -> QrScannerUiState.INVALID_QR
                            else -> QrScannerUiState.FAILURE
                        }
                    }
                }
            } else {
                when (
                    val res = container.connectionRepository.connectViaQrPayload(
                        rawQrUri = trimmed,
                        localDeviceName = settings.deviceName
                    )
                ) {
                    is AppResult.Success -> {
                        isPairing = false
                        scannerState = QrScannerUiState.SUCCESS
                        snackbarHostState.showSnackbar(pairedSuccessText)
                    }
                    is AppResult.Failure -> {
                        isPairing = false
                        errorMessage = res.error.message
                        scannerState = if (res.error.message.contains("expired", ignoreCase = true)) {
                            QrScannerUiState.EXPIRED_QR
                        } else {
                            QrScannerUiState.INVALID_QR
                        }
                    }
                }
            }
        }
    }

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
                    manualUri = decoded
                    submitQrUri(decoded)
                } else {
                    scannerState = QrScannerUiState.INVALID_QR
                    snackbarHostState.showSnackbar(galleryNoQrMsg)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                com.example.shareflow.core.designsystem.components.SFSnackbar(snackbarData = data)
            }
        },
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.qr_scanner_utility_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = onOpenDiagnostics,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("qr_scanner_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NetworkCheck,
                            contentDescription = stringResource(R.string.smart_receive_cd_diagnostics)
                        )
                    }
                }
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
                    .testTag("qr_scanner_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "qr_subtitle") {
                    Text(
                        text = stringResource(R.string.qr_scanner_utility_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (connectionState is ConnectionState.Connected) {
                    item(key = "qr_active_connection") {
                        ActiveEncryptedConnectionCard(
                            connected = connectionState as ConnectionState.Connected,
                            onDisconnect = {
                                scope.launch {
                                    container.smartConnectionCoordinator.cancelOrDisconnect(markCancelled = false)
                                    container.connectionRepository.disconnect()
                                    scannerState = QrScannerUiState.SCANNING
                                }
                            }
                        )
                    }
                }

                if (smartPhase.isInProgress || smartPhase == SmartConnectionPhase.CONNECTED) {
                    item(key = "qr_smart_sender_progress") {
                        SmartSenderProgressCard(
                            phase = smartPhase,
                            strategy = smartStrategy,
                            onCancel = {
                                scope.launch {
                                    container.smartConnectionCoordinator.cancelOrDisconnect(markCancelled = true)
                                    isPairing = false
                                    scannerState = QrScannerUiState.SCANNING
                                }
                            }
                        )
                    }
                }

                smartError?.let { smartErr ->
                    item(key = "qr_smart_error") {
                        SmartConnectionErrorCard(
                            error = smartErr,
                            onRetry = if (manualUri.isNotBlank()) {
                                { submitQrUri(manualUri) }
                            } else {
                                null
                            },
                            onDismiss = {
                                container.smartConnectionCoordinator.clearError()
                                scannerState = QrScannerUiState.SCANNING
                            }
                        )
                    }
                }

                if (errorMessage != null) {
                    item(key = "qr_error") {
                        ErrorState(
                            message = errorMessage.orEmpty(),
                            onRetry = {
                                errorMessage = null
                                scannerState = QrScannerUiState.SCANNING
                            }
                        )
                    }
                }

                item(key = "qr_camera_section") {
                    if (hasCameraPermission) {
                        AdvancedQrScannerView(
                            scannerState = scannerState,
                            onQrDecoded = { decoded ->
                                manualUri = decoded
                                submitQrUri(decoded)
                            },
                            onRetryScan = {
                                errorMessage = null
                                scannerState = QrScannerUiState.SCANNING
                            }
                        )
                    } else {
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
                                    text = stringResource(R.string.qr_scanner_camera_denied),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                PrimaryActionButton(
                                    text = stringResource(R.string.qr_scanner_request_camera),
                                    onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                                    leadingIcon = Icons.Filled.CameraAlt,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("qr_scanner_grant_camera_button")
                                )
                            }
                        }
                    }
                }

                item(key = "qr_nfc_tap_card") {
                    NfcSenderTapCard(
                        enabled = connectionState !is ConnectionState.Connected && !isPairing,
                        isConnecting = isPairing || smartPhase.isInProgress,
                        compact = true,
                        onPairingUriReceived = { nfcUri ->
                            manualUri = nfcUri
                            submitQrUri(nfcUri)
                        }
                    )
                }

                item(key = "qr_gallery_import") {
                    SecondaryActionButton(
                        text = stringResource(R.string.smart_scanner_import_gallery),
                        onClick = {
                            galleryPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isDecodingGallery && !isPairing,
                        outlined = true,
                        leadingIcon = Icons.Filled.PhotoLibrary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("qr_scanner_import_gallery_button")
                    )
                }

                item(key = "qr_manual_section") {
                    Surface(
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shadowElevation = ShareFlowDesign.elevation.card,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.cardPadding),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            OutlinedTextField(
                                value = manualUri,
                                onValueChange = { manualUri = it },
                                label = { Text(stringResource(R.string.smart_scanner_manual_code_label)) },
                                placeholder = { Text(stringResource(R.string.smart_scanner_manual_code_placeholder)) },
                                shape = ShareFlowDesign.shapes.input,
                                colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_scanner_manual_uri_input")
                            )
                            PrimaryActionButton(
                                text = stringResource(R.string.qr_scanner_manual_connect),
                                onClick = { submitQrUri(manualUri) },
                                enabled = manualUri.isNotBlank() && !isPairing,
                                isLoading = isPairing,
                                leadingIcon = Icons.Filled.QrCodeScanner,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_scanner_manual_connect_button")
                            )
                            SecondaryActionButton(
                                text = stringResource(R.string.qr_scanner_open_full_pairing),
                                onClick = onOpenFullQrPairing,
                                outlined = true,
                                leadingIcon = Icons.Filled.QrCode2,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("qr_scanner_show_my_qr_button")
                            )
                        }
                    }
                }

                item(key = "qr_bottom_spacer") { Spacer(modifier = Modifier.height(spacing.md)) }
            }
        }
    }
}
