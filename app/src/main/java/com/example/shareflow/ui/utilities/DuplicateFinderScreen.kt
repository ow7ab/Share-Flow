package com.example.shareflow.ui.utilities

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.DuplicateFileGroup
import com.example.shareflow.domain.model.DuplicateScanReport
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.utilities.DuplicateFileFinderEngine
import com.example.shareflow.utilities.UtilitiesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DuplicateFinderUiState(
    val isScanning: Boolean = false,
    val hashedCount: Int = 0,
    val totalCandidates: Int = 0,
    val report: DuplicateScanReport? = null,
    val selectedUris: Set<String> = emptySet(),
    val isDeleting: Boolean = false,
    val deletedCountFeedback: Int? = null,
    val errorMessage: String? = null
) {
    val selectedBytes: Long
        get() {
            val groups = report?.groups.orEmpty()
            return groups.flatMap { it.files }
                .filter { it.uriString in selectedUris }
                .sumOf { it.sizeBytes }
        }

    val hasAnyGroupFullySelected: Boolean
        get() = report?.groups.orEmpty().any { group ->
            group.files.isNotEmpty() && group.files.all { it.uriString in selectedUris }
        }
}

class DuplicateFinderViewModel(
    private val utilitiesRepository: UtilitiesRepository,
    private val duplicateEngine: DuplicateFileFinderEngine,
    private val fileManagerRepository: FileManagerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicateFinderUiState())
    val uiState: StateFlow<DuplicateFinderUiState> = _uiState.asStateFlow()

    init {
        scanDuplicates()
    }

    fun scanDuplicates() {
        if (_uiState.value.isScanning) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isScanning = true,
                    hashedCount = 0,
                    totalCandidates = 0,
                    selectedUris = emptySet(),
                    errorMessage = null
                )
            }
            runCatching {
                val entries = utilitiesRepository.collectAccessibleFiles()
                duplicateEngine.findDuplicates(entries) { hashed, total ->
                    _uiState.update { it.copy(hashedCount = hashed, totalCandidates = total) }
                }
            }.fold(
                onSuccess = { report ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            report = report,
                            errorMessage = null
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isScanning = false,
                            errorMessage = err.message ?: "Duplicate scan failed."
                        )
                    }
                }
            )
        }
    }

    fun toggleSelection(uriString: String) {
        _uiState.update { current ->
            val updated = current.selectedUris.toMutableSet()
            if (!updated.add(uriString)) {
                updated.remove(uriString)
            }
            current.copy(selectedUris = updated)
        }
    }

    fun deleteSelectedConfirmed() {
        val uris = _uiState.value.selectedUris.toList()
        if (uris.isEmpty() || _uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
            when (val result = fileManagerRepository.deleteFiles(uris)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            selectedUris = emptySet(),
                            deletedCountFeedback = result.data.succeededCount
                        )
                    }
                    scanDuplicates()
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            errorMessage = result.error.message
                        )
                    }
                }
            }
        }
    }

    fun consumeFeedback() {
        _uiState.update { it.copy(deletedCountFeedback = null) }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

@Composable
fun DuplicateFinderScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: DuplicateFinderViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            DuplicateFinderViewModel(
                utilitiesRepository = container.utilitiesRepository,
                duplicateEngine = container.duplicateFileFinderEngine,
                fileManagerRepository = container.fileManagerRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DuplicateFinderContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onScan = viewModel::scanDuplicates,
        onToggleSelection = viewModel::toggleSelection,
        onDeleteConfirmed = viewModel::deleteSelectedConfirmed,
        onConsumeFeedback = viewModel::consumeFeedback,
        onDismissError = viewModel::dismissError,
        modifier = modifier
    )
}

@Composable
fun DuplicateFinderContent(
    uiState: DuplicateFinderUiState,
    onNavigateBack: () -> Unit,
    onScan: () -> Unit,
    onToggleSelection: (String) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onConsumeFeedback: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    val deletedFeedback = uiState.deletedCountFeedback?.let {
        stringResource(R.string.duplicates_deleted_feedback, it)
    }
    LaunchedEffect(uiState.deletedCountFeedback) {
        if (deletedFeedback != null) {
            snackbarHostState.showSnackbar(deletedFeedback)
            onConsumeFeedback()
        }
    }

    if (showDeleteDialog) {
        ConfirmationDialog(
            title = stringResource(R.string.duplicates_delete_confirm_title),
            body = stringResource(
                R.string.duplicates_delete_confirm_body,
                uiState.selectedUris.size,
                ShareFlowFormatters.formatBytes(uiState.selectedBytes, locale)
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                showDeleteDialog = false
                onDeleteConfirmed()
            },
            onDismiss = { showDeleteDialog = false },
            isDestructive = true,
            confirmTestTag = "duplicates_confirm_delete_button"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.duplicates_title),
                onNavigateBack = onNavigateBack
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
                    .testTag("duplicate_finder_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "dup_safety_banner") {
                    Surface(
                        shape = ShareFlowDesign.shapes.card,
                        color = semantic.verifiedContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, semantic.verifiedBadge)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.md),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = semantic.verifiedBadge,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.duplicates_never_auto_delete_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item(key = "dup_controls") {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = stringResource(R.string.duplicates_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            PrimaryActionButton(
                                text = stringResource(R.string.duplicates_scan_action),
                                onClick = onScan,
                                isLoading = uiState.isScanning,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("duplicates_scan_button")
                            )
                            if (uiState.selectedUris.isNotEmpty()) {
                                SecondaryActionButton(
                                    text = stringResource(
                                        R.string.duplicates_delete_selected_action,
                                        uiState.selectedUris.size
                                    ),
                                    onClick = { showDeleteDialog = true },
                                    outlined = true,
                                    isDestructive = true,
                                    leadingIcon = Icons.Filled.Delete,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("duplicates_delete_button")
                                )
                            }
                        }
                    }
                }

                if (uiState.isScanning) {
                    item(key = "dup_progress") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                            if (uiState.totalCandidates > 0) {
                                Text(
                                    text = stringResource(
                                        R.string.duplicates_scanning_status,
                                        uiState.hashedCount,
                                        uiState.totalCandidates
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                if (uiState.hasAnyGroupFullySelected) {
                    item(key = "dup_all_selected_warn") {
                        Surface(
                            shape = ShareFlowDesign.shapes.card,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.testTag("duplicates_all_copies_warning")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.sm),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = stringResource(R.string.duplicates_all_copies_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    item(key = "dup_error") {
                        ErrorState(
                            message = uiState.errorMessage,
                            onRetry = onDismissError
                        )
                    }
                }

                val report = uiState.report
                if (report != null && !uiState.isScanning) {
                    item(key = "dup_summary") {
                        Surface(
                            shape = ShareFlowDesign.shapes.card,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(spacing.md),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.duplicates_summary_groups, report.groups.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    StatusPill(
                                        label = stringResource(
                                            R.string.duplicates_summary_reclaimable,
                                            ShareFlowFormatters.formatBytes(report.totalReclaimableBytes, locale)
                                        ),
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = stringResource(
                                        R.string.duplicates_summary_inspected,
                                        report.totalFilesInspected,
                                        report.candidatesHashed
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (report.groups.isEmpty()) {
                        item(key = "dup_empty") {
                            EmptyState(
                                icon = Icons.Filled.ContentCopy,
                                title = stringResource(R.string.duplicates_empty_title),
                                body = stringResource(R.string.duplicates_empty_body),
                                modifier = Modifier.testTag("duplicates_empty_state")
                            )
                        }
                    } else {
                        items(
                            items = report.groups,
                            key = { it.sha256Hex }
                        ) { group ->
                            DuplicateGroupCard(
                                group = group,
                                selectedUris = uiState.selectedUris,
                                onToggleSelection = onToggleSelection
                            )
                        }
                    }
                }

                item(key = "dup_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateFileGroup,
    selectedUris: Set<String>,
    onToggleSelection: (String) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("duplicate_group_${group.sha256Hex.take(8)}"),
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
                text = stringResource(
                    R.string.duplicates_group_header,
                    group.files.size,
                    ShareFlowFormatters.formatBytes(group.sizeBytes, locale)
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(
                    R.string.duplicates_hash_prefix,
                    group.sha256Hex.take(16).uppercase()
                ),
                style = ShareFlowMonoStyle,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            group.files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Checkbox(
                        checked = file.uriString in selectedUris,
                        onCheckedChange = { onToggleSelection(file.uriString) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("duplicate_checkbox_${file.displayName}")
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${file.category.name} • ${file.mimeType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
