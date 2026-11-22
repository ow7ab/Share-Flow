package com.example.shareflow.ui.discovery

import android.util.Size as AndroidSize
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.domain.model.ConnectionState
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.IncomingConnectionRequest
import com.example.shareflow.network.qr.QrCodeCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit user approval modal displayed when a cryptographically verified peer requests connection.
 * Displays peer name, short ID, full public key fingerprint prefix, and 6-digit SAS verification code.
 */
@Composable
fun IncomingConnectionApprovalDialog(
    request: IncomingConnectionRequest,
    onApprove: (rememberTrusted: Boolean) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rememberTrusted by remember(request.requestId) {
        mutableStateOf(request.isPreviouslyTrusted)
    }
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors

    AlertDialog(
        onDismissRequest = onReject,
        modifier = modifier.testTag("incoming_approval_dialog"),
        shape = ShareFlowDesign.shapes.dialog,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = stringResource(R.string.approval_dialog_title),
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.approval_dialog_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (request.hasValidQrToken) {
                    StatusPill(
                        label = stringResource(R.string.approval_qr_verified_badge),
                        containerColor = semantic.verifiedContainer,
                        contentColor = semantic.verifiedBadge
                    )
                }

                Surface(
                    shape = ShareFlowDesign.shapes.card,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ApprovalDetailRow(
                            label = stringResource(R.string.approval_peer_name_label),
                            value = request.peerDisplayName
                        )
                        ApprovalDetailRow(
                            label = stringResource(R.string.approval_peer_id_label),
                            value = request.shortPeerId,
                            mono = true
                        )
                        ApprovalDetailRow(
                            label = stringResource(R.string.approval_fingerprint_label),
                            value = request.formattedFingerprint,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.approval_sas_code_label),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = request.sasVerificationCode,
                                style = ShareFlowMonoStyle.copy(
                                    fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                    lineHeight = MaterialTheme.typography.headlineMedium.lineHeight
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("approval_sas_code_value")
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = rememberTrusted,
                        onCheckedChange = { rememberTrusted = it },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("approval_remember_trusted_checkbox")
                    )
                    Text(
                        text = stringResource(R.string.approval_remember_trusted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = {
            PrimaryActionButton(
                text = stringResource(R.string.approval_approve_action),
                onClick = { onApprove(rememberTrusted) },
                modifier = Modifier.testTag("approval_confirm_button")
            )
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("approval_reject_button")
            ) {
                Text(text = stringResource(R.string.approval_reject_action))
            }
        }
    )
}

@Composable
private fun ApprovalDetailRow(
    label: String,
    value: String,
    mono: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = if (mono) ShareFlowMonoStyle else MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

/**
 * Displays an active, mutually authenticated AES-256-GCM encrypted P2P session with SAS code.
 */
@Composable
fun ActiveEncryptedConnectionCard(
    connected: ConnectionState.Connected,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val transportLabel = when (connected.transport) {
        ConnectionType.WIFI_DIRECT -> stringResource(R.string.ds_connection_wifi_direct)
        ConnectionType.LOCAL_LAN_NSD -> stringResource(R.string.ds_connection_lan)
        ConnectionType.HOTSPOT -> stringResource(R.string.ds_connection_hotspot)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_encrypted_connection_card"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = semantic.verifiedContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.5.dp, semantic.verifiedBadge)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = semantic.verifiedBadge,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.connection_active_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                StatusPill(
                    label = stringResource(R.string.connection_active_encrypted_badge),
                    containerColor = semantic.verifiedContainer,
                    contentColor = semantic.verifiedBadge
                )
            }

            Text(
                text = "${connected.peer.displayName} • $transportLabel",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.discovery_device_id_format, connected.peer.shortDeviceId),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (connected.sasVerificationCode.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.connection_sas_label, connected.sasVerificationCode),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (connected.peer.shortFingerprint.isNotBlank()) {
                Text(
                    text = stringResource(R.string.discovery_fingerprint_format, connected.peer.shortFingerprint),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = stringResource(R.string.connection_session_id_label, connected.sessionId.take(12).uppercase()),
                style = ShareFlowMonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SecondaryActionButton(
                text = stringResource(R.string.connection_disconnect_action),
                onClick = onDisconnect,
                isDestructive = true,
                outlined = true,
                leadingIcon = Icons.Filled.Close,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_connection_disconnect_button")
            )
        }
    }
}

/**
 * Renders a real ISO/IEC 18004 scannable QR code on Jetpack Compose Canvas using ZXing Core.
 */
@Composable
fun RealQrCodeView(
    payload: String,
    modifier: Modifier = Modifier
) {
    val matrix = remember(payload) { QrCodeCodec.encodeQrMatrix(payload) }

    if (matrix == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.QrCode2,
                contentDescription = stringResource(R.string.qr_pairing_title),
                tint = com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.DarkBackground,
                modifier = Modifier.size(64.dp)
            )
        }
        return
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val cellW = size.width / matrix.width.toFloat()
        val cellH = size.height / matrix.height.toFloat()
        val moduleColor = com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.DarkBackground
        drawRect(color = Color.White, size = size)

        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        color = moduleColor,
                        topLeft = Offset(x * cellW, y * cellH),
                        size = Size(cellW + 0.5f, cellH + 0.5f)
                    )
                }
            }
        }
    }
}

/**
 * Live offline CameraX + ZXing QR code scanner.
 * Must only be composed after runtime CAMERA permission has been granted and camera hardware exists.
 */
@Composable
fun QrCameraScannerView(
    onQrDecoded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val decodedOnce = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analysisExecutor.shutdownNow() }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(ShareFlowDesign.shapes.featuredCard)
            .testTag("qr_camera_preview_box"),
        contentAlignment = Alignment.BottomCenter
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener(
                    {
                        runCatching {
                            val cameraProvider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            @Suppress("DEPRECATION")
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(AndroidSize(640, 480))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                try {
                                    if (!decodedOnce.get()) {
                                        val plane = imageProxy.planes.firstOrNull()
                                        if (plane != null) {
                                            val buffer = plane.buffer
                                            val data = ByteArray(buffer.remaining())
                                            buffer.get(data)
                                            val text = QrCodeCodec.decodeFromYuvLuminance(
                                                yPlaneData = data,
                                                width = imageProxy.width,
                                                height = imageProxy.height
                                            )
                                            if (!text.isNullOrBlank() &&
                                                text.startsWith("shareflow://pair") &&
                                                decodedOnce.compareAndSet(false, true)
                                            ) {
                                                ContextCompat.getMainExecutor(ctx).execute {
                                                    onQrDecoded(text)
                                                }
                                            }
                                        }
                                    }
                                } finally {
                                    imageProxy.close()
                                }
                            }

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                        }
                    },
                    ContextCompat.getMainExecutor(ctx)
                )
                previewView
            }
        )

        Surface(
            color = com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.DarkBackground.copy(alpha = 0.78f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.Cream,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.qr_scanner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors.DarkTextPrimary
                )
            }
        }
    }
}
