package com.example.shareflow.ui.utilities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.repository.FileManagerRepository
import com.example.shareflow.utilities.UtilitiesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LargeFileThreshold(val bytes: Long) {
    ALL(0L),
    MB_10(10L * 1024L * 1024L),
    MB_50(50L * 1024L * 1024L),
    MB_100(100L * 1024L * 1024L)
}

data class LargeFilesUiState(
    val isLoading: Boolean = true,
    val threshold: LargeFileThreshold = LargeFileThreshold.MB_10,
    val categoryFilter: FileCategory? = null,
    val files: List<LocalFileEntry> = emptyList(),
    val favoriteUris: Set<String> = emptySet(),
    val errorMessage: String? = null
)

class LargeFilesViewModel(
    private val utilitiesRepository: UtilitiesRepository,
    private val fileManagerRepository: FileManagerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LargeFilesUiState())
    val uiState: StateFlow<LargeFilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            utilitiesRepository.observeFavoriteUris().collectLatest { uris ->
                _uiState.update { it.copy(favoriteUris = uris) }
            }
        }
        refresh()
    }

    fun setThreshold(threshold: LargeFileThreshold) {
        _uiState.update { it.copy(threshold = threshold) }
        refresh()
    }

    fun setCategory(category: FileCategory?) {
        _uiState.update { it.copy(categoryFilter = category) }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val state = _uiState.value
            val loaded = utilitiesRepository.loadLargeFiles(
                minSizeBytes = state.threshold.bytes,
                categoryFilter = state.categoryFilter
            )
            _uiState.update { it.copy(isLoading = false, files = loaded) }
        }
    }

    fun toggleFavorite(entry: LocalFileEntry) {
        viewModelScope.launch {
            utilitiesRepository.toggleFavorite(entry)
        }
    }

    fun deleteFileConfirmed(entry: LocalFileEntry) {
        viewModelScope.launch {
            when (val res = fileManagerRepository.deleteFiles(listOf(entry.uriString))) {
                is AppResult.Success -> refresh()
                is AppResult.Failure -> _uiState.update { it.copy(errorMessage = res.error.message) }
            }
        }
    }
}

@Composable
fun LargeFilesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: LargeFilesViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            LargeFilesViewModel(
                utilitiesRepository = container.utilitiesRepository,
                fileManagerRepository = container.fileManagerRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    var pendingDelete by remember { mutableStateOf<LocalFileEntry?>(null) }

    pendingDelete?.let { target ->
        ConfirmationDialog(
            title = stringResource(R.string.large_files_delete_confirm_title),
            body = stringResource(
                R.string.large_files_delete_confirm_body,
                target.displayName,
                ShareFlowFormatters.formatBytes(target.sizeBytes, locale)
            ),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                pendingDelete = null
                viewModel.deleteFileConfirmed(target)
            },
            onDismiss = { pendingDelete = null },
            isDestructive = true,
            confirmTestTag = "large_files_confirm_delete_button"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.large_files_title),
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
                    .testTag("large_files_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                item(key = "large_thresholds") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        LargeFileThreshold.entries.forEach { threshold ->
                            val labelRes = when (threshold) {
                                LargeFileThreshold.ALL -> R.string.large_files_threshold_all
                                LargeFileThreshold.MB_10 -> R.string.large_files_threshold_10mb
                                LargeFileThreshold.MB_50 -> R.string.large_files_threshold_50mb
                                LargeFileThreshold.MB_100 -> R.string.large_files_threshold_100mb
                            }
                            FilterChip(
                                selected = uiState.threshold == threshold,
                                onClick = { viewModel.setThreshold(threshold) },
                                label = { Text(stringResource(labelRes)) },
                                shape = ShareFlowDesign.shapes.chip,
                                colors = ShareFlowChipDefaults.filterChipColors(),
                                border = ShareFlowChipDefaults.filterChipBorder(
                                    selected = uiState.threshold == threshold
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("large_threshold_${threshold.name.lowercase()}")
                            )
                        }
                    }
                }

                when {
                    uiState.isLoading -> {
                        item(key = "large_loading") { LoadingState() }
                    }
                    uiState.files.isEmpty() -> {
                        item(key = "large_empty") {
                            EmptyState(
                                icon = Icons.Filled.SdStorage,
                                title = stringResource(R.string.large_files_empty_title),
                                body = stringResource(R.string.large_files_empty_body),
                                modifier = Modifier.testTag("large_files_empty_state")
                            )
                        }
                    }
                    else -> {
                        items(items = uiState.files, key = { it.uriString }) { entry ->
                            val isFav = entry.uriString in uiState.favoriteUris
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                shadowElevation = ShareFlowDesign.elevation.card,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${ShareFlowFormatters.formatBytes(entry.sizeBytes, locale)} • ${entry.category.name}",
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.toggleFavorite(entry) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                            contentDescription = stringResource(R.string.favorites_toggle_cd),
                                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            container.localSessionController.addSelectedFiles(listOf(entry))
                                            onNavigateToSend()
                                        },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.Send,
                                            contentDescription = stringResource(R.string.large_files_send_action),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = { pendingDelete = entry },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.action_delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "large_bottom_spacer") {
                    Spacer(modifier = Modifier.height(spacing.md))
                }
            }
        }
    }
}
