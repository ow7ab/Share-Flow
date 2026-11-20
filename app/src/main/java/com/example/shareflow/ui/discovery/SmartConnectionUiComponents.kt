package com.example.shareflow.ui.discovery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.R
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.domain.model.SmartConnectionError
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartConnectionPhase
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.network.qr.QrCodeCodec
import java.io.File
import java.io.FileOutputStream

@StringRes
fun smartConnectionPhaseLabelRes(phase: SmartConnectionPhase): Int = when (phase) {
    SmartConnectionPhase.IDLE -> R.string.smart_phase_idle
    SmartConnectionPhase.STARTING_HOTSPOT -> R.string.smart_phase_starting_hotspot
    SmartConnectionPhase.HOTSPOT_READY -> R.string.smart_phase_hotspot_ready
    SmartConnectionPhase.STARTING_SERVER -> R.string.smart_phase_starting_server
    SmartConnectionPhase.SERVER_READY -> R.string.smart_phase_server_ready
    SmartConnectionPhase.WAITING_FOR_QR -> R.string.smart_phase_waiting_for_qr
    SmartConnectionPhase.QR_SCANNED -> R.string.smart_phase_qr_scanned
    SmartConnectionPhase.REQUESTING_WIFI -> R.string.smart_phase_requesting_wifi
    SmartConnectionPhase.WAITING_FOR_APPROVAL -> R.string.smart_phase_waiting_for_approval
    SmartConnectionPhase.JOINING_NETWORK -> R.string.smart_phase_joining_network
    SmartConnectionPhase.DISCOVERING_RECEIVER -> R.string.smart_phase_discovering_receiver
    SmartConnectionPhase.AUTHENTICATING -> R.string.smart_phase_authenticating
    SmartConnectionPhase.CONNECTED -> R.string.smart_phase_connected
    SmartConnectionPhase.TRANSFERRING -> R.string.smart_phase_transferring
    SmartConnectionPhase.DISCONNECTED -> R.string.smart_phase_disconnected
    SmartConnectionPhase.CANCELLED -> R.string.smart_phase_cancelled
    SmartConnectionPhase.EXPIRED -> R.string.smart_phase_expired
    SmartConnectionPhase.FAILED -> R.string.smart_phase_failed
}

@StringRes
fun smartStrategyLabelRes(strategy: SmartStrategyType?): Int = when (strategy) {
    SmartStrategyType.LOCAL_ONLY_HOTSPOT -> R.string.smart_strategy_local_hotspot
    SmartStrategyType.WIFI_NETWORK_SPECIFIER -> R.string.smart_strategy_wifi_specifier
    SmartStrategyType.WIFI_DIRECT -> R.string.smart_strategy_wifi_direct
    SmartStrategyType.EXISTING_LOCAL_WIFI -> R.string.smart_strategy_existing_wifi
    null -> R.string.smart_strategy_none
}

@StringRes
fun smartErrorMessageRes(code: SmartConnectionErrorCode): Int = when (code) {
    SmartConnectionErrorCode.PERMISSION_DENIED -> R.string.smart_err_permission_denied
    SmartConnectionErrorCode.WIFI_DISABLED -> R.string.smart_err_wifi_disabled
    SmartConnectionErrorCode.HOTSPOT_CREATION_FAILED -> R.string.smart_err_hotspot_creation_failed
    SmartConnectionErrorCode.HOTSPOT_STOPPED_UNEXPECTEDLY -> R.string.smart_err_hotspot_stopped
    SmartConnectionErrorCode.USER_REJECTED_NETWORK -> R.string.smart_err_user_rejected_network
    SmartConnectionErrorCode.WIFI_JOIN_TIMEOUT -> R.string.smart_err_wifi_join_timeout
    SmartConnectionErrorCode.WRONG_NETWORK -> R.string.smart_err_wrong_network
    SmartConnectionErrorCode.SERVER_UNAVAILABLE -> R.string.smart_err_server_unavailable
    SmartConnectionErrorCode.PORT_CONFLICT -> R.string.smart_err_port_conflict
    SmartConnectionErrorCode.STALE_OR_EXPIRED_QR -> R.string.smart_err_expired_qr
    SmartConnectionErrorCode.INVALID_QR -> R.string.smart_err_invalid_qr
    SmartConnectionErrorCode.TAMPERED_QR -> R.string.smart_err_tampered_qr
    SmartConnectionErrorCode.REPLAYED_QR -> R.string.smart_err_replayed_qr
    SmartConnectionErrorCode.SELF_PAIRING -> R.string.smart_err_self_pairing
    SmartConnectionErrorCode.AUTHENTICATION_FAILED -> R.string.smart_err_auth_failed
    SmartConnectionErrorCode.RECEIVER_DISCONNECTED -> R.string.smart_err_receiver_disconnected
    SmartConnectionErrorCode.APP_BACKGROUNDED -> R.string.smart_err_app_backgrounded
    SmartConnectionErrorCode.UNSUPPORTED_HARDWARE -> R.string.smart_err_unsupported_hardware
    SmartConnectionErrorCode.SESSION_EXPIRED -> R.string.smart_err_session_expired
    SmartConnectionErrorCode.UNKNOWN -> R.string.smart_err_unknown
}

@Composable
fun SmartConnectionPhasePill(
    phase: SmartConnectionPhase,
    modifier: Modifier = Modifier
) {
    val semantic = ShareFlowDesign.semanticColors
    val (container, content) = when (phase) {
        SmartConnectionPhase.CONNECTED,
        SmartConnectionPhase.TRANSFERRING,
        SmartConnectionPhase.WAITING_FOR_QR,
        SmartConnectionPhase.SERVER_READY -> semantic.verifiedContainer to semantic.verifiedBadge

        SmartConnectionPhase.FAILED,
        SmartConnectionPhase.EXPIRED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer

        SmartConnectionPhase.STARTING_HOTSPOT,
        SmartConnectionPhase.HOTSPOT_READY,
        SmartConnectionPhase.STARTING_SERVER,
        SmartConnectionPhase.QR_SCANNED,
        SmartConnectionPhase.REQUESTING_WIFI,
        SmartConnectionPhase.WAITING_FOR_APPROVAL,
        SmartConnectionPhase.JOINING_NETWORK,
        SmartConnectionPhase.DISCOVERING_RECEIVER,
        SmartConnectionPhase.AUTHENTICATING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer

        SmartConnectionPhase.IDLE,
        SmartConnectionPhase.DISCONNECTED,
        SmartConnectionPhase.CANCELLED -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }

    StatusPill(
        label = stringResource(smartConnectionPhaseLabelRes(phase)),
        containerColor = container,
        contentColor = content,
        modifier = modifier
    )
}

/**
 * Step-by-step sender auto-connection progress indicator.
 * Never shows CONNECTED before mutual cryptographic authentication succeeds.
 */
@Composable
fun SmartSenderProgressCard(
    phase: SmartConnectionPhase,
    strategy: SmartStrategyType?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors

    val currentStepIndex = when (phase) {
        SmartConnectionPhase.QR_SCANNED -> 0
        SmartConnectionPhase.REQUESTING_WIFI,
        SmartConnectionPhase.WAITING_FOR_APPROVAL,
        SmartConnectionPhase.JOINING_NETWORK -> 1
        SmartConnectionPhase.DISCOVERING_RECEIVER -> 2
        SmartConnectionPhase.AUTHENTICATING -> 3
        SmartConnectionPhase.CONNECTED,
        SmartConnectionPhase.TRANSFERRING -> 4
        else -> -1
    }
    if (currentStepIndex < 0) return

    val steps = listOf(
        R.string.smart_sender_step_qr,
        R.string.smart_sender_step_wifi,
        R.string.smart_sender_step_discover,
        R.string.smart_sender_step_auth
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart_sender_progress_card"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.smart_sender_progress_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                SmartConnectionPhasePill(phase = phase)
            }

            if (strategy != null) {
                Text(
                    text = stringResource(smartStrategyLabelRes(strategy)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (phase.isInProgress) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                steps.forEachIndexed { idx, labelRes ->
                    val completed = idx < currentStepIndex
                    val active = idx == currentStepIndex && phase.isInProgress
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        when {
                            completed -> Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = semantic.verifiedBadge,
                                modifier = Modifier.size(18.dp)
                            )
                            active -> CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            else -> Icon(
                                imageVector = Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = stringResource(labelRes),
                            style = if (active) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                            color = when {
                                completed -> semantic.verifiedBadge
                                active -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            if (phase.isInProgress) {
                SecondaryActionButton(
                    text = stringResource(R.string.smart_sender_cancel_action),
                    onClick = onCancel,
                    isDestructive = true,
                    outlined = true,
                    leadingIcon = Icons.Filled.Close,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("smart_sender_cancel_button")
                )
            }
        }
    }
}

@Composable
fun SmartConnectionErrorCard(
    error: SmartConnectionError,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("smart_connection_error_card"),
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.smart_receive_error_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = stringResource(smartErrorMessageRes(error.code)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("smart_error_dismiss_button")
                ) {
                    Text(stringResource(R.string.smart_receive_dismiss_action))
                }
                if (onRetry != null && error.recoverable) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("smart_error_retry_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = " ${stringResource(R.string.smart_receive_retry_action)}"
                        )
                    }
                }
            }
        }
    }
}

/**
 * Generates a crisp PNG image of the signed ShareFlow QR code in app cache and shares it via FileProvider.
 */
fun shareQrCodeImage(
    context: Context,
    qrPayloadUri: String,
    chooserTitle: String
): Boolean = runCatching {
    val bitmap = QrCodeCodec.renderQrBitmap(payload = qrPayloadUri, sizePx = 960) ?: return false
    val shareDir = File(context.cacheDir, "shareflow_qr_shares").apply { mkdirs() }
    val outFile = File(shareDir, "shareflow_qr_session.png")
    FileOutputStream(outFile).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }
    bitmap.recycle()

    val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, contentUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
    true
}.getOrDefault(false)
