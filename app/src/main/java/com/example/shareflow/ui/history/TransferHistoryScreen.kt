package com.example.shareflow.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.TransferCard
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar

@Composable
fun TransferHistoryScreen(
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: HistoryViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            HistoryViewModel(container.transferHistoryRepository)
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    TransferHistoryContent(
        uiState = uiState,
        onClearHistory = viewModel::clearHistory,
        onDeleteRecord = viewModel::deleteRecord,
        onDismissError = viewModel::dismissError,
        onNavigateBack = onNavigateBack,
        modifier = modifier
    )
}

@Composable
fun TransferHistoryContent(
    uiState: TransferHistoryUiState,
    onClearHistory: () -> Unit,
    onDeleteRecord: (String) -> Unit,
    onDismissError: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    if (showClearDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.history_clear_dialog_title),
            body = stringResource(R.string.history_clear_dialog_body),
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (onNavigateBack != null) {
                ShareFlowTopBar(
                    title = stringResource(R.string.history_title),
                    onNavigateBack = onNavigateBack,
                    actions = {
                        if (uiState.records.isNotEmpty()) {
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
                                    if (uiState.records.isNotEmpty()) {
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
                                TransferCard(
                                    record = record,
                                    onDelete = { onDeleteRecord(record.id) },
                                    modifier = Modifier.testTag("history_record_${record.id}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
