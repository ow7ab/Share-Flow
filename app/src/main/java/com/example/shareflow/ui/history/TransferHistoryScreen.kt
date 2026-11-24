package com.example.shareflow.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.TransferCard
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.ui.files.FileSharesheetHelper
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import com.example.shareflow.ui.viewer.toViewerFileEntry
import kotlinx.coroutines.launch

@Composable
fun TransferHistoryScreen(
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToSend: () -> Unit = {},
    onOpenVault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val shareChooserTitle = stringResource(R.string.files_share_chooser_title)
    var previewRecord by remember { mutableStateOf<TransferRecord?>(null) }

    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = AppSettings()
    )
    val viewModel: HistoryViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            HistoryViewModel(
                repository = container.transferHistoryRepository,
                localDeviceNameProvider = { settings.deviceName }
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    previewRecord?.let { record ->
        val entry = remember(record) { record.toViewerFileEntry() }
        if (entry != null) {
            val isApk = entry.category == FileCategory.APKS || entry.displayName.endsWith(".apk", ignoreCase = true)
            CustomFileViewerDialog(
                file = entry,
                onSendWithShareFlow = {
                    previewRecord = null
                    container.localSessionController.addSelectedFiles(listOf(entry))
                    onNavigateToSend()
                },
                onShareExternal = { FileSharesheetHelper.shareFiles(context, listOf(entry), shareChooserTitle) },
                onOpenExternal = { FileSharesheetHelper.openExternally(context, entry) },
                onMoveToVaultSuccess = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
                onOpenVaultSetup = {
                    previewRecord = null
                    onOpenVault()
                },
                onInstallApk = if (isApk) {
                    {
                        val parsed = runCatching { android.net.Uri.parse(entry.uriString) }.getOrNull()
                        val directFile = if (parsed?.scheme.equals("file", ignoreCase = true)) {
                            parsed?.path?.let { java.io.File(it) }
                        } else {
                            val root = com.example.shareflow.network.transfer.ChunkProcessor.defaultReceiveRoot(context)
                            java.io.File(root, entry.displayName).takeIf { it.exists() && it.isFile }
                        }
                        if (directFile != null && directFile.exists()) {
                            scope.launch {
                                container.applicationInstallationManager.startInstallationFromFile(
                                    apkFile = directFile,
                                    expectedSha256 = record.sha256Checksum
                                )
                            }
                        } else {
                            FileSharesheetHelper.openExternally(context, entry)
                        }
                    }
                } else {
                    null
                },
                onDismiss = { previewRecord = null }
            )
        }
    }

    TransferHistoryContent(
        uiState = uiState,
        onClearHistory = viewModel::clearHistory,
        onDeleteRecord = viewModel::deleteRecord,
        onFilterSelected = viewModel::selectFilter,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onDismissError = viewModel::dismissError,
        onPreviewRecord = { record ->
            if (!record.localUri.isNullOrBlank()) {
                previewRecord = record
            }
        },
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    )
}

@Composable
fun TransferHistoryContent(
    uiState: TransferHistoryUiState,
    onClearHistory: () -> Unit,
    onDeleteRecord: (String) -> Unit,
    onFilterSelected: (HistoryFilter) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onDismissError: () -> Unit = {},
    onPreviewRecord: ((TransferRecord) -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    if (showClearDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.history_clear_dialog_title),
            body = stringResource(R.string.history_clear_files_safe_note),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = {
                showClearDialog = false
                onClearHistory()
            },
            onDismiss = { showClearDialog = false },
            isDestructive = true,
            confirmTestTag = "history_confirm_clear_button"
        )
    }

    val hasAnyRecords = uiState.allRecords.isNotEmpty() || uiState.records.isNotEmpty()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                com.example.shareflow.core.designsystem.components.SFSnackbar(snackbarData = data)
            }
        },
        topBar = {
            if (onNavigateBack != null) {
                ShareFlowTopBar(
                    title = stringResource(R.string.history_title),
                    onNavigateBack = onNavigateBack,
                    actions = {
                        if (hasAnyRecords) {
                            TextButton(
                                onClick = { showClearDialog = true },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("history_clear_button")
                            ) {
                                Text(text = stringResource(R.string.history_clear_action))
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingState(modifier = Modifier.testTag("history_loading"))
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = spacing.maxContentWidth)
                            .testTag("history_list"),
                        contentPadding = PaddingValues(
                            horizontal = spacing.screenHorizontal,
                            vertical = spacing.md
                        ),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        if (onNavigateBack == null) {
                            item(key = "history_header") {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    SectionHeader(
                                        title = stringResource(R.string.history_title),
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (hasAnyRecords) {
                                        TextButton(
                                            onClick = { showClearDialog = true },
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("history_clear_button")
                                        ) {
                                            Text(text = stringResource(R.string.history_clear_action))
                                        }
                                    }
                                }
                            }
                        }

                        if (hasAnyRecords) {
                            item(key = "history_search") {
                                OutlinedTextField(
                                    value = uiState.searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    placeholder = { Text(stringResource(R.string.history_search_hint)) },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    shape = ShareFlowDesign.shapes.input,
                                    colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("history_search_input")
                                )
                            }

                            item(key = "history_filters") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    HistoryFilter.entries.forEach { filter ->
                                        val labelRes = when (filter) {
                                            HistoryFilter.ALL -> R.string.history_filter_all
                                            HistoryFilter.SENT -> R.string.history_filter_sent
                                            HistoryFilter.RECEIVED -> R.string.history_filter_received
                                            HistoryFilter.FAILED -> R.string.history_filter_failed
                                            HistoryFilter.CANCELLED -> R.string.history_filter_cancelled
                                        }
                                        val filterSelected = uiState.selectedFilter == filter
                                        FilterChip(
                                            selected = filterSelected,
                                            onClick = { onFilterSelected(filter) },
                                            label = { Text(stringResource(labelRes)) },
                                            shape = ShareFlowDesign.shapes.chip,
                                            colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                                            border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = filterSelected),
                                            modifier = Modifier
                                                .minimumInteractiveComponentSize()
                                                .testTag("history_filter_${filter.name.lowercase()}")
                                        )
                                    }
                                }
                            }

                            item(key = "history_summary") {
                                Text(
                                    text = stringResource(
                                        R.string.history_summary_format,
                                        uiState.records.size,
                                        uiState.distinctSessionCount,
                                        ShareFlowFormatters.formatBytes(uiState.totalFilteredBytes, locale)
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("history_summary_text")
                                )
                            }
                        }

                        if (uiState.errorMessage != null) {
                            item(key = "history_error") {
                                ErrorState(
                                    message = uiState.errorMessage,
                                    onRetry = onDismissError,
                                    modifier = Modifier.testTag("history_error_state")
                                )
                            }
                        }

                        if (uiState.records.isEmpty()) {
                            item(key = "history_empty") {
                                EmptyState(
                                    icon = Icons.Filled.History,
                                    title = stringResource(R.string.history_empty_title),
                                    body = stringResource(R.string.history_empty_body),
                                    modifier = Modifier.testTag("history_empty_state")
                                )
                            }
                        } else {
                            items(
                                items = uiState.records,
                                key = { it.id }
                            ) { record ->
                                val sessionCount = uiState.sessionFileCounts[record.sessionId] ?: 1
                                val routeText = if (record.direction == TransferDirection.SENT) {
                                    stringResource(
                                        R.string.history_route_sent_format,
                                        uiState.localDeviceName,
                                        record.peerDeviceName
                                    )
                                } else {
                                    stringResource(
                                        R.string.history_route_received_format,
                                        record.peerDeviceName,
                                        uiState.localDeviceName
                                    )
                                }
                                val canPreview = onPreviewRecord != null &&
                                    record.status == TransferStatus.COMPLETED_VERIFIED &&
                                    !record.localUri.isNullOrBlank()
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TransferCard(
                                        record = record,
                                        onClick = if (canPreview) ({ onPreviewRecord?.invoke(record) }) else null,
                                        onDelete = { onDeleteRecord(record.id) },
                                        modifier = Modifier.testTag("history_record_${record.id}")
                                    )
                                    Surface(
                                        shape = ShareFlowDesign.shapes.card,
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = spacing.sm, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = routeText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = stringResource(
                                                    R.string.history_session_meta_format,
                                                    record.sessionId.take(8).uppercase(),
                                                    sessionCount
                                                ),
                                                style = ShareFlowMonoStyle,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
