package com.example.shareflow.ui.utilities

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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.FavoriteFileItem
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.utilities.UtilitiesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RecentFilesUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val allFiles: List<LocalFileEntry> = emptyList(),
    val favoriteUris: Set<String> = emptySet()
) {
    val filteredFiles: List<LocalFileEntry>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return allFiles
            return allFiles.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.category.name.contains(q, ignoreCase = true)
            }
        }
}

class RecentFilesViewModel(
    private val utilitiesRepository: UtilitiesRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(RecentFilesUiState())
    val uiState: StateFlow<RecentFilesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            utilitiesRepository.observeFavoriteUris().collectLatest { favs ->
                _uiState.update { it.copy(favoriteUris = favs) }
            }
        }
        refresh()
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val loaded = utilitiesRepository.loadRecentFiles()
            _uiState.update { it.copy(isLoading = false, allFiles = loaded) }
        }
    }

    fun toggleFavorite(entry: LocalFileEntry) {
        viewModelScope.launch {
            utilitiesRepository.toggleFavorite(entry)
        }
    }
}

@Composable
fun RecentFilesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: RecentFilesViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            RecentFilesViewModel(container.utilitiesRepository)
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.recent_files_title),
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
                    .testTag("recent_files_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                item(key = "recent_search") {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = viewModel::onQueryChanged,
                        placeholder = { Text(stringResource(R.string.recent_files_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = ShareFlowDesign.shapes.input,
                        colors = ShareFlowInputDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("recent_files_search_input")
                    )
                }

                when {
                    uiState.isLoading -> {
                        item(key = "recent_loading") { LoadingState() }
                    }
                    uiState.filteredFiles.isEmpty() -> {
                        item(key = "recent_empty") {
                            EmptyState(
                                icon = Icons.Filled.History,
                                title = stringResource(R.string.recent_files_empty_title),
                                body = stringResource(R.string.recent_files_empty_body),
                                modifier = Modifier.testTag("recent_files_empty_state")
                            )
                        }
                    }
                    else -> {
                        items(items = uiState.filteredFiles, key = { it.uriString }) { entry ->
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
                                            text = "${ShareFlowFormatters.formatBytes(entry.sizeBytes, locale)} • ${
                                                ShareFlowFormatters.formatTimestamp(entry.lastModifiedEpochMillis, locale)
                                            }",
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                                }
                            }
                        }
                    }
                }

                item(key = "recent_bottom_spacer") { Spacer(modifier = Modifier.height(spacing.md)) }
            }
        }
    }
}

data class FavoritesUiState(
    val isLoading: Boolean = true,
    val items: List<FavoriteFileItem> = emptyList(),
    val errorMessage: String? = null
)

class FavoritesViewModel(
    private val utilitiesRepository: UtilitiesRepository
) : ViewModel() {
    private val errorState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FavoritesUiState> = combine(
        utilitiesRepository.observeFavorites(),
        errorState
    ) { items, err ->
        FavoritesUiState(
            isLoading = false,
            items = items,
            errorMessage = err
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = FavoritesUiState(isLoading = true)
    )

    fun addFavorite(uriString: String) {
        viewModelScope.launch {
            when (val res = utilitiesRepository.addFavoriteByUri(uriString)) {
                is AppResult.Success -> errorState.value = null
                is AppResult.Failure -> errorState.value = res.error.message
            }
        }
    }

    fun removeFavorite(uriString: String) {
        viewModelScope.launch {
            when (val res = utilitiesRepository.removeFavorite(uriString)) {
                is AppResult.Success -> errorState.value = null
                is AppResult.Failure -> errorState.value = res.error.message
            }
        }
    }

    fun dismissError() {
        errorState.value = null
    }
}

@Composable
fun FavoritesScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: FavoritesViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            FavoritesViewModel(container.utilitiesRepository)
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    val addFavoriteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.addFavorite(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.favorites_title),
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
                    .testTag("favorites_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                item(key = "fav_safety_note") {
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
                                text = stringResource(R.string.favorites_safety_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                item(key = "fav_add_action") {
                    PrimaryActionButton(
                        text = stringResource(R.string.favorites_add_action),
                        onClick = { addFavoriteLauncher.launch(arrayOf("*/*")) },
                        leadingIcon = Icons.Filled.Add,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("favorites_add_button")
                    )
                }

                if (uiState.errorMessage != null) {
                    item(key = "fav_error") {
                        ErrorState(
                            message = uiState.errorMessage.orEmpty(),
                            onRetry = viewModel::dismissError
                        )
                    }
                }

                when {
                    uiState.isLoading -> {
                        item(key = "fav_loading") { LoadingState() }
                    }
                    uiState.items.isEmpty() -> {
                        item(key = "fav_empty") {
                            EmptyState(
                                icon = Icons.Filled.Star,
                                title = stringResource(R.string.favorites_empty_title),
                                body = stringResource(R.string.favorites_empty_body),
                                modifier = Modifier.testTag("favorites_empty_state")
                            )
                        }
                    }
                    else -> {
                        items(items = uiState.items, key = { it.uriString }) { fav ->
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
                                            text = fav.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = ShareFlowFormatters.formatBytes(fav.sizeBytes, locale),
                                                style = ShareFlowMonoStyle,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (!fav.isAccessible) {
                                                StatusPill(
                                                    label = stringResource(R.string.favorites_inaccessible_badge),
                                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                    if (fav.isAccessible) {
                                        IconButton(
                                            onClick = {
                                                val entry = LocalFileEntry(
                                                    uriString = fav.uriString,
                                                    displayName = fav.displayName,
                                                    mimeType = fav.mimeType,
                                                    sizeBytes = fav.sizeBytes,
                                                    lastModifiedEpochMillis = fav.addedAtEpochMillis,
                                                    category = fav.category,
                                                    isDirectory = false
                                                )
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
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeFavorite(fav.uriString) },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("favorite_remove_${fav.displayName}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.favorites_remove_cd),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "fav_bottom_spacer") { Spacer(modifier = Modifier.height(spacing.md)) }
            }
        }
    }
}
