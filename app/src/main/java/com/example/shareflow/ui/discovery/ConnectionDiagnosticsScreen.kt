package com.example.shareflow.ui.discovery

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import kotlinx.coroutines.launch

/**
 * Phase 8 Connection Diagnostics Screen.
 * Shows live wireless transport, hotspot, network binding, discovery, and authentication telemetry
 * while strictly omitting passwords, pairing tokens, and private keys.
 */
@Suppress("DEPRECATION")
@Composable
fun ConnectionDiagnosticsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val coordinator = container.smartConnectionCoordinator
    val diagnostics by coordinator.diagnostics.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedToast = stringResource(R.string.diag_report_copied)

    LaunchedEffect(Unit) {
        coordinator.refreshDiagnosticsSnapshot()
    }

    val naValue = stringResource(R.string.diag_value_na)
    val noneValue = stringResource(R.string.diag_value_none)
    val yesValue = stringResource(R.string.diag_value_yes)
    val noValue = stringResource(R.string.diag_value_no)
    val notRunValue = stringResource(R.string.diag_value_not_run)

    val ssidStatusText = if (diagnostics.ssidAvailable && !diagnostics.ssidLabel.isNullOrBlank()) {
        stringResource(R.string.diag_value_ssid_available, diagnostics.ssidLabel.orEmpty())
    } else if (diagnostics.ssidAvailable) {
        yesValue
    } else {
        stringResource(R.string.diag_value_ssid_unavailable)
    }

    val qrExpiryText = diagnostics.qrSessionExpiresAtEpochMillis?.let { exp ->
        ShareFlowFormatters.formatTimestamp(exp, locale)
    } ?: naValue

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.diag_title),
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
                    .testTag("connection_diagnostics_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "diag_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        SmartConnectionPhasePill(
                            phase = diagnostics.phase,
                            modifier = Modifier.testTag("diagnostics_phase_pill")
                        )
                        Text(
                            text = stringResource(R.string.diag_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Actions: Test Connection, Refresh, Copy Redacted Report
                item(key = "diag_actions") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        PrimaryActionButton(
                            text = if (diagnostics.isTestingConnection) {
                                stringResource(R.string.diag_action_testing)
                            } else {
                                stringResource(R.string.diag_action_test)
                            },
                            onClick = {
                                scope.launch { coordinator.runDiagnosticsConnectionTest() }
                            },
                            isLoading = diagnostics.isTestingConnection,
                            leadingIcon = Icons.Filled.NetworkCheck,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostics_test_connection_button")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            SecondaryActionButton(
                                text = stringResource(R.string.diag_action_refresh),
                                onClick = { coordinator.refreshDiagnosticsSnapshot() },
                                outlined = true,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostics_refresh_button")
                            )
                            SecondaryActionButton(
                                text = stringResource(R.string.diag_action_copy_report),
                                onClick = {
                                    clipboard.setText(AnnotatedString(diagnostics.toSafeReportString()))
                                    scope.launch { snackbarHostState.showSnackbar(copiedToast) }
                                },
                                outlined = false,
                                leadingIcon = Icons.Filled.ContentCopy,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostics_copy_report_button")
                            )
                        }
                    }
                }

                // Section 1: Device & Runtime
                item(key = "diag_device_card") {
                    DiagnosticsCard(title = stringResource(R.string.diag_section_device)) {
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_android_version),
                            value = diagnostics.androidVersionLabel,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_device_model),
                            value = diagnostics.deviceModel
                        )
                    }
                }

                // Section 2: Transport & Network Binding
                item(key = "diag_transport_card") {
                    DiagnosticsCard(title = stringResource(R.string.diag_section_transport)) {
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_strategy),
                            value = stringResource(smartStrategyLabelRes(diagnostics.activeStrategy))
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_phase),
                            value = stringResource(smartConnectionPhaseLabelRes(diagnostics.phase))
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_hotspot_state),
                            value = diagnostics.hotspotState,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_ssid_status),
                            value = ssidStatusText,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_network_id),
                            value = diagnostics.activeNetworkId ?: naValue,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_interface),
                            value = diagnostics.activeNetworkInterface ?: naValue,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_local_ip),
                            value = diagnostics.localIp ?: naValue,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_remote_ip),
                            value = diagnostics.remoteIp ?: naValue,
                            mono = true
                        )
                    }
                }

                // Section 3: Server, Discovery & Security
                item(key = "diag_session_card") {
                    DiagnosticsCard(title = stringResource(R.string.diag_section_session)) {
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_server_port),
                            value = diagnostics.serverPort?.toString() ?: naValue,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_server_listening),
                            value = if (diagnostics.isServerListening) yesValue else noValue
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_discovery_status),
                            value = diagnostics.nsdDiscoveryStatus,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_qr_expiry),
                            value = qrExpiryText,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_auth_status),
                            value = diagnostics.authenticationStatus,
                            mono = true
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_last_error),
                            value = diagnostics.lastError?.let {
                                "${it.code.name}: ${it.detail}"
                            } ?: noneValue
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        DiagnosticRow(
                            label = stringResource(R.string.diag_label_test_result),
                            value = diagnostics.lastTestConnectionResult ?: notRunValue,
                            modifier = Modifier.testTag("diagnostics_test_result_row")
                        )
                    }
                }

                // Privacy Assurance Banner
                item(key = "diag_privacy_card") {
                    Surface(
                        shape = ShareFlowDesign.shapes.card,
                        color = semantic.verifiedContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, semantic.verifiedBadge.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.cardPadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = semantic.verifiedBadge,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = stringResource(R.string.diag_privacy_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item(key = "diag_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(
    title: String,
    content: @Composable () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
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
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    mono: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.44f)
        )
        Text(
            text = value,
            style = if (mono) ShareFlowMonoStyle else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.56f)
        )
    }
}
