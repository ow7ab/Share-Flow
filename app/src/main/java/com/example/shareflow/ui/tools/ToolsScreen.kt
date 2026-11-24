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
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.MetricStatCard
import com.example.shareflow.core.designsystem.components.ModuleDirectoryCard
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StorageIndicator
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.StorageAnalysisReport
import com.example.shareflow.domain.model.StorageSummary
import com.example.shareflow.navigation.ShareFlowRoutes
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(
    onNavigateToRoute: (String) -> Unit,
    bottomBarClearance: androidx.compose.ui.unit.Dp = 0.dp,
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
                start = spacing.screenHorizontal,
                end = spacing.screenHorizontal,
                top = spacing.md,
                bottom = spacing.md + bottomBarClearance
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
                    icon = Icons.Outlined.Lock,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.PRIVATE_VAULT) },
                    testTag = "tools_card_private_vault"
                )
            }

            item(key = "tool_storage_analyzer") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_storage_analyzer_title),
                    description = stringResource(R.string.module_storage_analyzer_desc),
                    icon = Icons.Outlined.Storage,
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
                    icon = Icons.Outlined.Archive,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.COMPRESSION) },
                    testTag = "tools_card_compression"
                )
            }

            item(key = "tool_duplicate_finder") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_duplicate_finder_title),
                    description = stringResource(R.string.module_duplicate_finder_desc),
                    icon = Icons.Outlined.ContentCopy,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.DUPLICATE_FINDER) },
                    testTag = "tools_card_duplicate_finder"
                )
            }

            item(key = "tool_large_files") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_large_files_title),
                    description = stringResource(R.string.module_large_files_desc),
                    icon = Icons.Outlined.SdStorage,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.LARGE_FILES) },
                    testTag = "tools_card_large_files"
                )
            }

            item(key = "tool_phone_migration") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_phone_migration_title),
                    description = stringResource(R.string.module_phone_migration_desc),
                    icon = Icons.Outlined.SwapHoriz,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.PHONE_MIGRATION) },
                    testTag = "tools_card_phone_migration"
                )
            }

            item(key = "tool_recent_files") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_recent_files_title),
                    description = stringResource(R.string.module_recent_files_desc),
                    icon = Icons.Outlined.AccessTime,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.RECENT_FILES) },
                    testTag = "tools_card_recent_files"
                )
            }

            item(key = "tool_favorites") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_favorites_title),
                    description = stringResource(R.string.module_favorites_desc),
                    icon = Icons.Outlined.Star,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.FAVORITES) },
                    testTag = "tools_card_favorites"
                )
            }

            item(key = "tool_connection_diagnostics") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_connection_diagnostics_title),
                    description = stringResource(R.string.module_connection_diagnostics_desc),
                    icon = Icons.Outlined.NetworkCheck,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.CONNECTION_DIAGNOSTICS) },
                    testTag = "tools_card_connection_diagnostics"
                )
            }

            item(key = "tool_app_sharing") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.module_app_sharing_title),
                    description = stringResource(R.string.module_app_sharing_desc),
                    icon = Icons.Outlined.InstallMobile,
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
                    icon = Icons.Outlined.History,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.TRANSFER_HISTORY) },
                    testTag = "tools_card_transfer_history"
                )
            }

            item(key = "tools_bottom_spacer") {
                Spacer(
                    modifier = Modifier
                        .height(spacing.md)
                        .testTag("tools_bottom_spacer")
                )
            }
        }
    }
}

@Composable
fun StorageAnalyzerScreen(
    onNavigateBack: () -> Unit,
    onOpenLargeFiles: (() -> Unit)? = null,
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
    var analysisReport by remember { mutableStateOf<StorageAnalysisReport?>(null) }

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    fun refresh() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            when (val res = container.utilitiesRepository.analyzeStorage()) {
                is AppResult.Success -> analysisReport = res.data
                is AppResult.Failure -> Unit
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        when (val res = container.utilitiesRepository.analyzeStorage()) {
            is AppResult.Success -> analysisReport = res.data
            is AppResult.Failure -> Unit
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
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
                        val summary = analysisReport?.summary ?: result.data
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
                                    icon = Icons.Outlined.Storage,
                                    testTag = "storage_metric_total",
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatCard(
                                    label = stringResource(R.string.storage_analyzer_used),
                                    value = ShareFlowFormatters.formatBytes(summary.usedBytes, locale),
                                    icon = Icons.Outlined.DataUsage,
                                    testTag = "storage_metric_used",
                                    modifier = Modifier.weight(1f)
                                )
                                MetricStatCard(
                                    label = stringResource(R.string.storage_analyzer_available),
                                    value = ShareFlowFormatters.formatBytes(summary.availableBytes, locale),
                                    icon = Icons.Outlined.SdStorage,
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
                                leadingIcon = Icons.Outlined.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("storage_analyzer_refresh_button")
                            )
                        }

                        // Accessible Media Categories Breakdown
                        item(key = "storage_categories_card") {
                            SFGlassCard(
                                contentPadding = PaddingValues(spacing.cardPadding),
                                modifier = Modifier.testTag("storage_categories_card")
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Text(
                                        text = stringResource(R.string.storage_analyzer_categories_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val categories = summary.breakdown
                                    if (categories.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.storage_analyzer_categories_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        val maxBytes = categories.maxOf { it.bytesUsed }.coerceAtLeast(1L)
                                        categories.forEach { usage ->
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${usage.category.name} (${
                                                            stringResource(R.string.storage_analyzer_item_count_format, usage.itemCount)
                                                        })",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Text(
                                                        text = ShareFlowFormatters.formatBytes(usage.bytesUsed, locale),
                                                        style = ShareFlowMonoStyle,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                ShareFlowSignatureProgressBar(
                                                    progress = (usage.bytesUsed.toFloat() / maxBytes.toFloat()).coerceIn(0f, 1f),
                                                    modifier = Modifier.fillMaxWidth(),
                                                    height = 6.dp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Large Files Summary
                        analysisReport?.let { report ->
                            item(key = "storage_large_files_card") {
                                SFGlassCard(
                                    contentPadding = PaddingValues(spacing.cardPadding),
                                    modifier = Modifier.testTag("storage_large_files_card")
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                    ) {
                                        Text(
                                            text = stringResource(
                                                R.string.storage_analyzer_large_files_title,
                                                report.largeFiles.size
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        report.largeFiles.take(5).forEach { file ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = file.displayName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = ShareFlowFormatters.formatBytes(file.sizeBytes, locale),
                                                    style = ShareFlowMonoStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        if (onOpenLargeFiles != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            SecondaryActionButton(
                                                text = stringResource(R.string.storage_analyzer_view_all_large),
                                                onClick = onOpenLargeFiles,
                                                outlined = true,
                                                leadingIcon = Icons.Outlined.SdStorage,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("storage_open_large_files_button")
                                            )
                                        }
                                    }
                                }
                            }

                            // Storage Trends Card
                            item(key = "storage_trends_card") {
                                SFGlassCard(
                                    contentPadding = PaddingValues(spacing.cardPadding),
                                    modifier = Modifier.testTag("storage_trends_card")
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Timeline,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.storage_analyzer_trends_title),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = stringResource(
                                                R.string.storage_analyzer_trends_subtitle,
                                                report.trends.size
                                            ),
                                            style = ShareFlowMonoStyle,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        val delta = report.trendDeltaBytes
                                        val deltaText = when {
                                            delta == null -> stringResource(R.string.storage_analyzer_trend_first)
                                            delta > 0L -> stringResource(
                                                R.string.storage_analyzer_trend_delta_up,
                                                ShareFlowFormatters.formatBytes(delta, locale)
                                            )
                                            delta < 0L -> stringResource(
                                                R.string.storage_analyzer_trend_delta_down,
                                                ShareFlowFormatters.formatBytes(-delta, locale)
                                            )
                                            else -> stringResource(R.string.storage_analyzer_trend_delta_flat)
                                        }
                                        Text(
                                            text = deltaText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (report.trends.isNotEmpty()) {
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            report.trends.take(5).forEach { point ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = ShareFlowFormatters.formatTimestamp(point.capturedAtEpochMillis, locale),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = ShareFlowFormatters.formatBytes(point.usedBytes, locale),
                                                        style = ShareFlowMonoStyle,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "storage_privacy_note") {
                            SFGlassCard(
                                contentPadding = PaddingValues(spacing.md),
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.VerifiedUser,
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
