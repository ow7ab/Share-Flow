package com.example.shareflow.ui.utilities

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ArchiveCreateResult
import com.example.shareflow.domain.model.ArchiveEntryInfo
import com.example.shareflow.domain.model.ArchiveExtractResult
import com.example.shareflow.utilities.ZipArchiveEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CompressionTab {
    COMPRESS,
    EXTRACT
}

data class CompressionUiState(
    val activeTab: CompressionTab = CompressionTab.COMPRESS,
    val selectedSourceUris: List<Uri> = emptyList(),
    val archiveNameDraft: String = "shareflow_archive",
    val preserveOriginals: Boolean = true,
    val isWorking: Boolean = false,
    val createResult: ArchiveCreateResult? = null,
    val selectedArchiveUri: Uri? = null,
    val inspectedEntries: List<ArchiveEntryInfo> = emptyList(),
    val extractResult: ArchiveExtractResult? = null,
    val errorMessage: String? = null
)

class CompressionViewModel(
    private val zipArchiveEngine: ZipArchiveEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompressionUiState())
    val uiState: StateFlow<CompressionUiState> = _uiState.asStateFlow()

    fun selectTab(tab: CompressionTab) {
        _uiState.update { it.copy(activeTab = tab, errorMessage = null) }
    }

    fun onArchiveNameChanged(value: String) {
        _uiState.update { it.copy(archiveNameDraft = value, errorMessage = null) }
    }

    fun setPreserveOriginals(preserve: Boolean) {
        _uiState.update { it.copy(preserveOriginals = preserve) }
    }

    fun addSourceUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _uiState.update { current ->
            current.copy(
                selectedSourceUris = (current.selectedSourceUris + uris).distinct(),
                createResult = null,
                errorMessage = null
            )
        }
    }

    fun clearSourceUris() {
        _uiState.update { it.copy(selectedSourceUris = emptyList(), createResult = null) }
    }

    fun createZipArchive() {
        val state = _uiState.value
        if (state.isWorking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, errorMessage = null, createResult = null) }
            when (
                val res = zipArchiveEngine.compressToZip(
                    sourceUris = state.selectedSourceUris,
                    archiveBaseName = state.archiveNameDraft
                )
            ) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        createResult = res.data,
                        errorMessage = null
                    )
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        errorMessage = res.error.message
                    )
                }
            }
        }
    }

    fun selectArchiveToInspect(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    selectedArchiveUri = uri,
                    inspectedEntries = emptyList(),
                    extractResult = null,
                    errorMessage = null
                )
            }
            when (val res = zipArchiveEngine.inspectZip(uri)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        inspectedEntries = res.data,
                        errorMessage = null
                    )
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        errorMessage = res.error.message
                    )
                }
            }
        }
    }

    fun extractSelectedArchive() {
        val uri = _uiState.value.selectedArchiveUri ?: return
        if (_uiState.value.isWorking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, errorMessage = null, extractResult = null) }
            when (val res = zipArchiveEngine.extractZip(uri)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        extractResult = res.data,
                        errorMessage = null
                    )
                }
                is AppResult.Failure -> _uiState.update {
                    it.copy(
                        isWorking = false,
                        errorMessage = res.error.message
                    )
                }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

@Composable
fun CompressionScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: CompressionViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            CompressionViewModel(container.zipArchiveEngine)
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val multiFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        viewModel.addSourceUris(uris)
    }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.selectArchiveToInspect(uri)
        }
    }

    CompressionContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectTab = viewModel::selectTab,
        onPickFilesToCompress = { multiFilePicker.launch(arrayOf("*/*")) },
        onClearSelectedFiles = viewModel::clearSourceUris,
        onArchiveNameChanged = viewModel::onArchiveNameChanged,
        onPreserveOriginalsChanged = viewModel::setPreserveOriginals,
        onCreateZip = viewModel::createZipArchive,
        onPickZipToExtract = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
        onExtractZip = viewModel::extractSelectedArchive,
        onDismissError = viewModel::dismissError,
        modifier = modifier
    )
}

@Composable
fun CompressionContent(
    uiState: CompressionUiState,
    onNavigateBack: () -> Unit,
    onSelectTab: (CompressionTab) -> Unit,
    onPickFilesToCompress: () -> Unit,
    onClearSelectedFiles: () -> Unit,
    onArchiveNameChanged: (String) -> Unit,
    onPreserveOriginalsChanged: (Boolean) -> Unit,
    onCreateZip: () -> Unit,
    onPickZipToExtract: () -> Unit,
    onExtractZip: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.compression_title),
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
                    .testTag("compression_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                item(key = "comp_format_pill") {
                    StatusPill(
                        label = stringResource(R.string.compression_format_badge),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                item(key = "comp_tabs") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        FilterChip(
                            selected = uiState.activeTab == CompressionTab.COMPRESS,
                            onClick = { onSelectTab(CompressionTab.COMPRESS) },
                            label = { Text(stringResource(R.string.compression_tab_compress)) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("compression_tab_compress")
                        )
                        FilterChip(
                            selected = uiState.activeTab == CompressionTab.EXTRACT,
                            onClick = { onSelectTab(CompressionTab.EXTRACT) },
                            label = { Text(stringResource(R.string.compression_tab_extract)) },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("compression_tab_extract")
                        )
                    }
                }

                if (uiState.errorMessage != null) {
                    item(key = "comp_error") {
                        ErrorState(
                            message = uiState.errorMessage,
                            onRetry = onDismissError,
                            modifier = Modifier.testTag("compression_error_state")
                        )
                    }
                }

                if (uiState.activeTab == CompressionTab.COMPRESS) {
                    item(key = "comp_form") {
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.compression_select_files_action),
                                        onClick = onPickFilesToCompress,
                                        outlined = true,
                                        leadingIcon = Icons.Filled.Add,
                                        modifier = Modifier.testTag("compression_pick_files_button")
                                    )
                                    if (uiState.selectedSourceUris.isNotEmpty()) {
                                        TextButton(
                                            onClick = onClearSelectedFiles,
                                            modifier = Modifier.minimumInteractiveComponentSize()
                                        ) {
                                            Text(stringResource(R.string.compression_clear_selection))
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(
                                        R.string.compression_selected_count,
                                        uiState.selectedSourceUris.size
                                    ),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                OutlinedTextField(
                                    value = uiState.archiveNameDraft,
                                    onValueChange = onArchiveNameChanged,
                                    label = { Text(stringResource(R.string.compression_archive_name_label)) },
                                    placeholder = { Text(stringResource(R.string.compression_archive_name_placeholder)) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("compression_archive_name_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.compression_keep_originals_title),
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = stringResource(R.string.compression_keep_originals_desc),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = uiState.preserveOriginals,
                                        onCheckedChange = onPreserveOriginalsChanged,
                                        modifier = Modifier.testTag("compression_preserve_originals_switch")
                                    )
                                }

                                PrimaryActionButton(
                                    text = stringResource(R.string.compression_create_action),
                                    onClick = onCreateZip,
                                    enabled = uiState.selectedSourceUris.isNotEmpty() && uiState.archiveNameDraft.isNotBlank(),
                                    isLoading = uiState.isWorking,
                                    leadingIcon = Icons.Filled.Archive,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("compression_create_button")
                                )
                            }
                        }
                    }

                    uiState.createResult?.let { result ->
                        item(key = "comp_create_result") {
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = semantic.verifiedContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, semantic.verifiedBadge),
                                modifier = Modifier.testTag("compression_create_result_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.cardPadding),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = semantic.verifiedBadge
                                        )
                                        Text(
                                            text = stringResource(R.string.compression_result_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.compression_result_summary,
                                            result.archiveFileName,
                                            result.includedFileCount,
                                            ShareFlowFormatters.formatBytes(result.archiveSizeBytes, locale),
                                            ShareFlowFormatters.formatBytes(result.totalInputBytes, locale)
                                        ),
                                        style = ShareFlowMonoStyle,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.duplicates_hash_prefix,
                                            result.sha256Hex.take(16).uppercase()
                                        ),
                                        style = ShareFlowMonoStyle,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = stringResource(R.string.compression_result_originals_preserved),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item(key = "extract_controls") {
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Security,
                                        contentDescription = null,
                                        tint = semantic.verifiedBadge,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.compression_extract_security_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                SecondaryActionButton(
                                    text = stringResource(R.string.compression_extract_pick_action),
                                    onClick = onPickZipToExtract,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.FolderZip,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("compression_pick_zip_button")
                                )
                                if (uiState.inspectedEntries.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                    Text(
                                        text = stringResource(
                                            R.string.compression_extract_inspect_title,
                                            uiState.inspectedEntries.size
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    PrimaryActionButton(
                                        text = stringResource(R.string.compression_extract_action),
                                        onClick = onExtractZip,
                                        isLoading = uiState.isWorking,
                                        leadingIcon = Icons.Filled.Archive,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("compression_extract_button")
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.inspectedEntries.isNotEmpty()) {
                        items(
                            items = uiState.inspectedEntries.take(50),
                            key = { it.entryPath }
                        ) { entry ->
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = spacing.md, vertical = spacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = entry.entryPath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = ShareFlowFormatters.formatBytes(entry.uncompressedSizeBytes, locale),
                                        style = ShareFlowMonoStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    uiState.extractResult?.let { result ->
                        item(key = "extract_result") {
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = semantic.verifiedContainer.copy(alpha = 0.45f),
                                border = BorderStroke(1.dp, semantic.verifiedBadge),
                                modifier = Modifier.testTag("compression_extract_result_card")
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.cardPadding),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.compression_extract_result_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.compression_extract_result_summary,
                                            result.extractedFiles.size,
                                            ShareFlowFormatters.formatBytes(result.totalExtractedBytes, locale),
                                            result.destinationDirectoryPath
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "comp_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}
