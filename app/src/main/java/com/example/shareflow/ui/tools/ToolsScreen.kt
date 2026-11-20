package com.example.shareflow.ui.tools

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
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.MetricStatCard
import com.example.shareflow.core.designsystem.components.ModuleDirectoryCard
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.ShareFlowModuleId
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.navigation.ShareFlowRoutes
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("tools_screen_list"),
            contentPadding = PaddingValues(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item(key = "tools_header") {
                SectionHeader(
                    title = stringResource(R.string.tools_title),
                    subtitle = stringResource(R.string.tools_subtitle)
                )
            }

            item(key = "tool_private_vault") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_private_vault_title),
                    description = stringResource(R.string.module_private_vault_desc),
                    icon = Icons.Filled.Lock,
                    statusLabel = stringResource(R.string.status_scaffolded),
                    isReady = false,
                    onClick = {
                        onNavigateToRoute(
                            ShareFlowRoutes.moduleDetailRoute(ShareFlowModuleId.PRIVATE_VAULT)
                        )
                    },
                    testTag = "tools_card_private_vault"
                )
            }

            item(key = "tool_storage_analyzer") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_storage_analyzer_title),
                    description = stringResource(R.string.module_storage_analyzer_desc),
                    icon = Icons.Filled.Storage,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.STORAGE_ANALYZER) },
                    testTag = "tools_card_storage_analyzer"
                )
            }

            item(key = "tool_compression") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_file_compression_title),
                    description = stringResource(R.string.module_file_compression_desc),
                    icon = Icons.Filled.Archive,
                    statusLabel = stringResource(R.string.status_scaffolded),
                    isReady = false,
                    onClick = {
                        onNavigateToRoute(
                            ShareFlowRoutes.moduleDetailRoute(ShareFlowModuleId.FILE_COMPRESSION)
                        )
                    },
                    testTag = "tools_card_compression"
                )
            }

            item(key = "tool_duplicate_finder") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_duplicate_finder_title),
                    description = stringResource(R.string.module_duplicate_finder_desc),
                    icon = Icons.Filled.ContentCopy,
                    statusLabel = stringResource(R.string.status_scaffolded),
                    isReady = false,
                    onClick = {
                        onNavigateToRoute(
                            ShareFlowRoutes.moduleDetailRoute(ShareFlowModuleId.DUPLICATE_FINDER)
                        )
                    },
                    testTag = "tools_card_duplicate_finder"
                )
            }

            item(key = "tool_phone_migration") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_phone_migration_title),
                    description = stringResource(R.string.module_phone_migration_desc),
                    icon = Icons.Filled.SwapHoriz,
                    statusLabel = stringResource(R.string.status_scaffolded),
                    isReady = false,
                    onClick = {
                        onNavigateToRoute(
                            ShareFlowRoutes.moduleDetailRoute(ShareFlowModuleId.PHONE_MIGRATION)
                        )
                    },
                    testTag = "tools_card_phone_migration"
                )
            }

            item(key = "tool_app_sharing") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_app_sharing_title),
                    description = stringResource(R.string.module_app_sharing_desc),
                    icon = Icons.Filled.InstallMobile,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.APPLICATION_SHARING) },
                    testTag = "tools_card_app_sharing"
                )
            }

            item(key = "tool_transfer_history") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_transfer_history_title),
                    description = stringResource(R.string.module_transfer_history_desc),
                    icon = Icons.Filled.History,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                    testTag = "tools_card_transfer_history"
                )
            }

            item(key = "tools_bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }
}

@Composable
fun StorageAnalyzerScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val storageResult by container.storageAnalyzerRepository
        .observeStorageSummary()
        .collectAsStateWithLifecycle(
            initialValue = AppResult.Success(StorageSummary(0L, 0L, 0L, emptyList()))
        )
    val scope = rememberCoroutineScope()
    var isRefreshing by rememberSaveable { mutableStateOf(false) }

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    fun refresh() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            container.storageAnalyzerRepository.refreshStorageSummary()
            isRefreshing = false
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.storage_analyzer_title),
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
                    .testTag("storage_analyzer_screen"),
                contentPadding = PaddingValues(
                    horizontal = spacing.screenHorizontal,
                    vertical = spacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                when (val result = storageResult) {
                    is AppResult.Failure -> {
                        item(key = "storage_error") {
                            ErrorState(
                                message = result.error.message,
                                onRetry = ::refresh,
                                modifier = Modifier.testTag("storage_analyzer_error")
                            )
                        }
                    }

                    is AppResult.Success -> {
                        val summary = result.data
                        item(key = "storage_indicator") {
                            StorageIndicator(
                                usedBytes = summary.usedBytes,
                                totalBytes = summary.totalBytes,
                                modifier = Modifier.testTag("storage_analyzer_indicator")
                            )
                        }

                        item(key = "storage_metrics") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                MetricStatCard(
                                    label = stringResource(R.string.storage_analyzer_total),
                                    value = ShareFlowFormatters.formatBytes(summary.totalBytes, locale),
                                    icon = Icons.Filled.Storage,
                                    testTag = "storage_metric_total",
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatCard(
                                    label = stringResource(R.string.storage_analyzer_used),
                                    value = ShareFlowFormatters.formatBytes(summary.usedBytes, locale),
                                    icon = Icons.Filled.DataUsage,
                                    testTag = "storage_metric_used",
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatCard(
                                    label = stringResource(R.string.storage_analyzer_available),
                                    value = ShareFlowFormatters.formatBytes(summary.availableBytes, locale),
                                    icon = Icons.Filled.SdStorage,
                                    testTag = "storage_metric_available",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        item(key = "storage_refresh_action") {
                            PrimaryActionButton(
                                text = stringResource(R.string.storage_analyzer_refresh),
                                onClick = ::refresh,
                                isLoading = isRefreshing,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("storage_analyzer_refresh_button")
                            )
                        }

                        item(key = "storage_privacy_note") {
                            Surface(
                                shape = ShareFlowDesign.shapes.card,
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(spacing.md),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.VerifiedUser,
                                        contentDescription = null,
                                        tint = semantic.verifiedBadge,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.storage_analyzer_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
