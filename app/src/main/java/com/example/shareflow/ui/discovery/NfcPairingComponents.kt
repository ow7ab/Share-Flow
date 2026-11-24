package com.example.shareflow.ui.discovery

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.network.nfc.NfcHardwareStatus
import com.example.shareflow.network.nfc.NfcPairingManager
import com.example.shareflow.network.nfc.NfcPairingSessionStore
import com.example.shareflow.network.nfc.NfcReadError

/**
 * Observes real Android NFC hardware status and automatically re-checks on ON_RESUME
 * when the user returns from system NFC Settings.
 */
@Composable
fun rememberNfcHardwareStatus(
    overrideStatus: NfcHardwareStatus? = null
): NfcHardwareStatus {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember(overrideStatus) {
        mutableStateOf(overrideStatus ?: NfcPairingManager.inspectHardwareStatus(context))
    }

    DisposableEffect(lifecycleOwner, context, overrideStatus) {
        if (overrideStatus != null) {
            status = overrideStatus
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    status = NfcPairingManager.inspectHardwareStatus(context)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
    return status
}

/**
 * Receiver-side NFC Tap-to-Pair card.
 *
 * Arms [NfcPairingSessionStore] strictly while this composable is visible, NFC is enabled,
 * and the receiver's signed pairing session is active & unexpired, allowing an unlocked phone
 * to broadcast the exact ECDSA-signed pairing URI over Android HCE (`ShareFlowNfcHceService`).
 */
@Composable
fun NfcReceiverBroadcastCard(
    pairingUri: String,
    expiresAtEpochMillis: Long,
    isSessionExpired: Boolean,
    modifier: Modifier = Modifier,
    hardwareStatusOverride: NfcHardwareStatus? = null
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val hardwareStatus = rememberNfcHardwareStatus(overrideStatus = hardwareStatusOverride)
    val lastTapMillis by NfcPairingSessionStore.lastPeerTapEpochMillis.collectAsStateWithLifecycle()
    val armedAtMillis = remember(pairingUri) { System.currentTimeMillis() }

    DisposableEffect(pairingUri, expiresAtEpochMillis, isSessionExpired, hardwareStatus) {
        if (hardwareStatus == NfcHardwareStatus.ENABLED &&
            !isSessionExpired &&
            pairingUri.isNotBlank()
        ) {
            NfcPairingSessionStore.arm(
                pairingUri = pairingUri,
                expiresAtEpochMillis = expiresAtEpochMillis
            )
        } else {
            NfcPairingSessionStore.clear()
        }
        onDispose {
            NfcPairingSessionStore.clear()
        }
    }

    when (hardwareStatus) {
        NfcHardwareStatus.ENABLED -> {
            if (isSessionExpired || pairingUri.isBlank()) return
            val pulseScale = rememberNfcPulseScale(active = true)
            val recentlyTapped = lastTapMillis >= armedAtMillis && lastTapMillis > 0L

            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_receiver_ready_card"),
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .scale(pulseScale)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    shape = CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Nfc,
                                contentDescription = stringResource(R.string.nfc_icon_cd),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusPill(
                            label = stringResource(R.string.nfc_badge_active),
                            containerColor = semantic.verifiedContainer,
                            contentColor = semantic.verifiedBadge
                        )
                        Text(
                            text = if (recentlyTapped) {
                                stringResource(R.string.nfc_receiver_tap_detected)
                            } else {
                                stringResource(R.string.nfc_receiver_ready_title)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.nfc_receiver_ready_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        NfcHardwareStatus.DISABLED -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_receiver_disabled_card"),
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
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
                            imageVector = Icons.Filled.Nfc,
                            contentDescription = stringResource(R.string.nfc_icon_cd),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.nfc_receiver_disabled_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        StatusPill(
                            label = stringResource(R.string.nfc_badge_disabled),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = stringResource(R.string.nfc_receiver_disabled_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.nfc_open_settings_action),
                        onClick = { NfcPairingManager.openNfcSettings(context) },
                        outlined = true,
                        leadingIcon = Icons.Filled.Settings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nfc_receiver_open_settings_button")
                    )
                }
            }
        }

        NfcHardwareStatus.UNSUPPORTED -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_receiver_unsupported_card"),
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = stringResource(R.string.nfc_icon_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.nfc_receiver_unsupported_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Sender-side NFC Tap-to-Pair card (compact strip or full hero mode).
 *
 * Activates real Android NFC Reader Mode (`NfcAdapter.enableReaderMode`) while [enabled] is true
 * and NFC is turned on, releasing Reader Mode cleanly on `ON_PAUSE` and disposal.
 */
@Composable
fun NfcSenderTapCard(
    enabled: Boolean,
    isConnecting: Boolean,
    onPairingUriReceived: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    hardwareStatusOverride: NfcHardwareStatus? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val hardwareStatus = rememberNfcHardwareStatus(overrideStatus = hardwareStatusOverride)
    val latestCallback by rememberUpdatedState(onPairingUriReceived)

    var isReadingTag by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<NfcReadError?>(null) }

    DisposableEffect(enabled, isConnecting, hardwareStatus, context, lifecycleOwner) {
        val activity = NfcPairingManager.findHostActivity(context)
        val shouldActivate = enabled &&
            !isConnecting &&
            hardwareStatus == NfcHardwareStatus.ENABLED &&
            hardwareStatusOverride == null &&
            activity != null

        fun startReader() {
            if (shouldActivate) {
                NfcPairingManager.enableReaderMode(
                    activity = activity,
                    onReadingStarted = {
                        isReadingTag = true
                        lastError = null
                    },
                    onPairingUriRead = { uri ->
                        isReadingTag = false
                        lastError = null
                        latestCallback(uri)
                    },
                    onError = { err ->
                        isReadingTag = false
                        lastError = err
                    }
                )
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (activity == null) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> startReader()
                Lifecycle.Event.ON_PAUSE -> NfcPairingManager.disableReaderMode(activity)
                else -> Unit
            }
        }
        if (shouldActivate) {
            startReader()
            lifecycleOwner.lifecycle.addObserver(observer)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity != null && hardwareStatusOverride == null) {
                NfcPairingManager.disableReaderMode(activity)
            }
            isReadingTag = false
        }
    }

    when (hardwareStatus) {
        NfcHardwareStatus.ENABLED -> {
            val pulseScale = rememberNfcPulseScale(active = enabled && !isConnecting)
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_sender_ready_card"),
                shape = if (compact) ShareFlowDesign.shapes.card else ShareFlowDesign.shapes.featuredCard,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (compact) 0.32f else 0.42f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            ) {
                if (compact) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .scale(pulseScale)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            CircleShape
                                        )
                                )
                                Icon(
                                    imageVector = Icons.Filled.Nfc,
                                    contentDescription = stringResource(R.string.nfc_icon_cd),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isReadingTag) {
                                        stringResource(R.string.nfc_sender_reading)
                                    } else {
                                        stringResource(R.string.nfc_sender_ready_title)
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.nfc_sender_compact_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (isReadingTag || isConnecting) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                StatusPill(
                                    label = stringResource(R.string.nfc_badge_ready),
                                    containerColor = semantic.verifiedContainer,
                                    contentColor = semantic.verifiedBadge
                                )
                            }
                        }
                        lastError?.let { err ->
                            Text(
                                text = nfcReadErrorMessage(err),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("nfc_sender_error_text")
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        StatusPill(
                            label = stringResource(R.string.nfc_badge_ready),
                            containerColor = semantic.verifiedContainer,
                            contentColor = semantic.verifiedBadge
                        )
                        Box(
                            modifier = Modifier.size(84.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .scale(pulseScale)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                        shape = CircleShape
                                    )
                            )
                            Box(
                                modifier = Modifier
                                    .size(60.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isReadingTag || isConnecting) {
                                    CircularProgressIndicator(
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Nfc,
                                        contentDescription = stringResource(R.string.nfc_icon_cd),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isReadingTag) {
                                stringResource(R.string.nfc_sender_reading)
                            } else {
                                stringResource(R.string.nfc_sender_ready_title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.nfc_sender_ready_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        lastError?.let { err ->
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = nfcReadErrorMessage(err),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .padding(spacing.sm)
                                        .testTag("nfc_sender_error_text")
                                )
                            }
                        }
                    }
                }
            }
        }

        NfcHardwareStatus.DISABLED -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_sender_disabled_card"),
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
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = stringResource(R.string.nfc_icon_cd),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.nfc_sender_disabled_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.nfc_sender_disabled_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    SecondaryActionButton(
                        text = stringResource(R.string.nfc_open_settings_action),
                        onClick = { NfcPairingManager.openNfcSettings(context) },
                        outlined = true,
                        leadingIcon = Icons.Filled.Settings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nfc_sender_open_settings_button")
                    )
                }
            }
        }

        NfcHardwareStatus.UNSUPPORTED -> {
            if (compact) return
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .testTag("nfc_sender_unsupported_card"),
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.cardPadding),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Nfc,
                        contentDescription = stringResource(R.string.nfc_icon_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = stringResource(R.string.nfc_sender_unsupported_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.nfc_sender_unsupported_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberNfcPulseScale(active: Boolean): Float {
    if (!active) return 1f
    val transition = rememberInfiniteTransition(label = "nfc_pulse")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nfc_pulse_scale"
    )
    return scale
}

@Composable
private fun nfcReadErrorMessage(error: NfcReadError): String = when (error) {
    NfcReadError.RECEIVER_NOT_READY -> stringResource(R.string.nfc_err_receiver_not_ready)
    NfcReadError.INVALID_TAG -> stringResource(R.string.nfc_err_invalid_tag)
    NfcReadError.CONNECTION_INTERRUPTED -> stringResource(R.string.nfc_err_interrupted)
}
