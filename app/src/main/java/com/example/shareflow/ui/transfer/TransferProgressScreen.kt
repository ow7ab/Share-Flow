package com.example.shareflow.ui.transfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState

@Composable
fun TransferProgressScreen(
    onNavigateBack: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: TransferViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            TransferViewModel(
                transferManager = container.transferManager,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TransferProgressContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onOpenSend = onOpenSend,
        onOpenHistory = onOpenHistory,
        onPauseSession = viewModel::pauseSession,
        onResumeSession = viewModel::resumeSession,
        onRetrySession = viewModel::retrySession,
        onCancelSession = viewModel::cancelSession,
        onConsumeFeedback = viewModel::consumeFeedback,
        modifier = modifier
    )
}

@Composable
fun TransferProgressContent(
    uiState: TransferProgressUiState,
    onNavigateBack: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenHistory: () -> Unit,
    onPauseSession: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onRetrySession: (String) -> Unit,
    onCancelSession: (String) -> Unit,
    onConsumeFeedback: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingCancelSessionId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.feedbackMessage) {
        val msg = uiState.feedbackMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            onConsumeFeedback()
        }
    }

    pendingCancelSessionId?.let { cancelId ->
        ConfirmationDialog(
            title = stringResource(R.string.transfer_cancel_confirm_title),
            body = stringResource(R.string.transfer_cancel_confirm_body),
            confirmLabel = stringResource(R.string.transfer_action_cancel),
            dismissLabel = stringResource(R.string.action_cancel),
            isDestructive = true,
            confirmTestTag = "confirmation_dialog_confirm",
            dismissTestTag = "confirmation_dialog_dismiss",
            modifier = Modifier.testTag("confirmation_dialog"),
            onConfirm = {
                onCancelSession(cancelId)
                pendingCancelSessionId = null
            },
            onDismiss = { pendingCancelSessionId = null }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.transfer_progress_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("transfer_open_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = stringResource(R.string.module_transfer_history_title)
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
                    .testTag("transfer_progress_list"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                if (uiState.activeSessions.isEmpty() && uiState.recentCompletedSessions.isEmpty()) {
                    item(key = "transfer_empty") {
                        EmptyState(
                            icon = Icons.Filled.SwapVert,
                            title = stringResource(R.string.transfer_empty_title),
                            body = stringResource(R.string.transfer_empty_body),
                            actionLabel = stringResource(R.string.home_action_send),
                            onAction = onOpenSend,
                            modifier = Modifier.testTag("transfer_empty_state")
                        )
                    }
                }

                if (uiState.activeSessions.isNotEmpty()) {
                    item(key = "active_header") {
                        SectionHeader(title = stringResource(R.string.transfer_section_active))
                    }
                    items(
                        items = uiState.activeSessions,
                        key = { "active_${it.sessionId}" }
                    ) { session ->
                        TransferSessionProgressCard(
                            session = session,
                            onPause = { onPauseSession(session.sessionId) },
                            onResume = { onResumeSession(session.sessionId) },
                            onRetry = { onRetrySession(session.sessionId) },
                            onRequestCancel = { pendingCancelSessionId = session.sessionId }
                        )
                    }
                }

                if (uiState.recentCompletedSessions.isNotEmpty()) {
                    item(key = "recent_header") {
                        SectionHeader(title = stringResource(R.string.transfer_section_recent))
                    }
                    items(
                        items = uiState.recentCompletedSessions,
                        key = { "recent_${it.sessionId}" }
                    ) { session ->
                        TransferSessionProgressCard(
                            session = session,
                            onPause = { onPauseSession(session.sessionId) },
                            onResume = { onResumeSession(session.sessionId) },
                            onRetry = { onRetrySession(session.sessionId) },
                            onRequestCancel = { pendingCancelSessionId = session.sessionId }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TransferSessionProgressCard(
    session: TransferSession,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRequestCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isSent = session.direction == TransferDirection.SENT
    val activeFile = session.activeItem
    val progress = session.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 180),
        label = "session_progress"
    )

    val stateLabel = transferStateLabel(session.state)
    val (stateBg, stateFg) = transferStateColors(session.state)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = false) {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            }
            .testTag("transfer_session_card_${session.sessionId}"),
        shape = ShareFlowDesign.shapes.featuredCard,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            // Header: Direction icon, active file name, state pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isSent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isSent) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = stringResource(
                                if (isSent) R.string.history_direction_sent else R.string.history_direction_received
                            ),
                            tint = if (isSent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = activeFile?.displayPath ?: session.sessionId,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("transfer_file_name_${session.sessionId}")
                    )
                    Text(
                        text = "${stringResource(R.string.transfer_metric_device)}: ${session.peer.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("transfer_device_name_${session.sessionId}")
                    )
                }

                StatusPill(
                    label = stateLabel,
                    containerColor = stateBg,
                    contentColor = stateFg,
                    modifier = Modifier.testTag("transfer_state_pill_${session.sessionId}")
                )
            }

            // Progress bar + real bytes & percentage
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(ShareFlowDesign.shapes.progressTrack)
                    .testTag("transfer_progress_bar_${session.sessionId}"),
                color = if (session.state == TransferState.COMPLETED) semantic.verifiedBadge else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeCap = StrokeCap.Round
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.ds_transfer_progress_format,
                        ShareFlowFormatters.formatBytes(session.transferredBytes, locale),
                        ShareFlowFormatters.formatBytes(session.totalBytes, locale)
                    ),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("transfer_file_size_${session.sessionId}")
                )
                Text(
                    text = ShareFlowFormatters.formatPercentage(progress, locale),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("transfer_percent_${session.sessionId}")
                )
            }

            // Real-time Speed, Remaining Time (ETA), File Count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricColumn(
                    label = stringResource(R.string.transfer_metric_speed),
                    value = ShareFlowFormatters.formatTransferSpeed(session.currentSpeedBytesPerSec, locale),
                    testTag = "transfer_speed_${session.sessionId}"
                )
                MetricColumn(
                    label = stringResource(R.string.transfer_metric_remaining),
                    value = ShareFlowFormatters.formatRemainingTime(session.estimatedRemainingSeconds, locale),
                    testTag = "transfer_eta_${session.sessionId}"
                )
                MetricColumn(
                    label = stringResource(R.string.nav_files),
                    value = stringResource(
                        R.string.transfer_metric_files_format,
                        ShareFlowFormatters.formatCount(session.completedFileCount, locale),
                        ShareFlowFormatters.formatCount(session.totalFileCount, locale)
                    ),
                    testTag = "transfer_file_count_${session.sessionId}"
                )
            }

            // Per-file rows when transferring multiple files or folders
            if (session.transferItems.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    session.transferItems.take(5).forEach { item ->
                        TransferSubItemRow(item = item)
                    }
                }
            }

            if (session.state == TransferState.COMPLETED) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Verified,
                        contentDescription = null,
                        tint = semantic.verifiedBadge,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.transfer_sha256_verified_badge),
                        style = MaterialTheme.typography.labelMedium,
                        color = semantic.verifiedBadge
                    )
                }
            }

            session.errorMessage?.takeIf { it.isNotBlank() && session.state != TransferState.COMPLETED }?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("transfer_error_text_${session.sessionId}")
                )
            }

            // Control actions: Pause / Resume / Retry / Cancel
            val canPause = session.state == TransferState.TRANSFERRING
            val canResume = session.state == TransferState.PAUSED && session.isResumable
            val canRetry = session.state == TransferState.FAILED && session.isResumable
            val canCancel = session.state.isActive

            if (canPause || canResume || canRetry || canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    if (canPause) {
                        SecondaryActionButton(
                            text = stringResource(R.string.transfer_action_pause),
                            onClick = onPause,
                            leadingIcon = Icons.Filled.Pause,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_pause_button_${session.sessionId}")
                        )
                    }
                    if (canResume) {
                        PrimaryActionButton(
                            text = stringResource(R.string.transfer_action_resume),
                            onClick = onResume,
                            leadingIcon = Icons.Filled.PlayArrow,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_resume_button_${session.sessionId}")
                        )
                    }
                    if (canRetry) {
                        PrimaryActionButton(
                            text = stringResource(R.string.transfer_action_retry),
                            onClick = onRetry,
                            leadingIcon = Icons.Filled.Refresh,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_retry_button_${session.sessionId}")
                        )
                    }
                    if (canCancel) {
                        SecondaryActionButton(
                            text = stringResource(R.string.transfer_action_cancel),
                            onClick = onRequestCancel,
                            isDestructive = true,
                            outlined = true,
                            leadingIcon = Icons.Filled.Close,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_cancel_button_${session.sessionId}")
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    testTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = ShareFlowMonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun TransferSubItemRow(item: TransferItem) {
    val locale = ShareFlowDesign.locale
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.displayPath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${ShareFlowFormatters.formatPercentage(item.progressFraction, locale)} • ${transferStateLabel(item.state)}",
            style = ShareFlowMonoStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun transferStateLabel(state: TransferState): String = when (state) {
    TransferState.PENDING -> stringResource(R.string.transfer_state_pending)
    TransferState.CONNECTING -> stringResource(R.string.transfer_state_connecting)
    TransferState.WAITING_APPROVAL -> stringResource(R.string.transfer_state_waiting_approval)
    TransferState.TRANSFERRING -> stringResource(R.string.transfer_state_transferring)
    TransferState.PAUSED -> stringResource(R.string.transfer_state_paused)
    TransferState.VERIFYING -> stringResource(R.string.transfer_state_verifying)
    TransferState.COMPLETED -> stringResource(R.string.transfer_state_completed)
    TransferState.FAILED -> stringResource(R.string.transfer_state_failed)
    TransferState.CANCELLED -> stringResource(R.string.transfer_state_cancelled)
}

@Composable
private fun transferStateColors(state: TransferState) = when (state) {
    TransferState.COMPLETED ->
        ShareFlowDesign.semanticColors.verifiedContainer to ShareFlowDesign.semanticColors.verifiedBadge
    TransferState.FAILED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    TransferState.CANCELLED ->
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
    TransferState.PAUSED ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    else ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
}
