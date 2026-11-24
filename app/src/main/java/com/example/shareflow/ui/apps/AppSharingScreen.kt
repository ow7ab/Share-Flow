package com.example.shareflow.ui.apps

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import android.content.Intent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.shareflow.core.designsystem.theme.LocalShareFlowHostActivity
import com.example.shareflow.data.apps.AppInstallSessionState
import kotlinx.coroutines.launch
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.ErrorState
import com.example.shareflow.core.designsystem.components.LoadingState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureTabIndicator
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ReceivedApkIntegrityStatus
import com.example.shareflow.domain.model.ShareableAppInfo

@Composable
fun AppSharingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSendConfirm: () -> Unit,
    onNavigateToTransferProgress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val viewModel: AppSharingViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            AppSharingViewModel(
                appSharingRepository = container.appSharingRepository,
                localSessionController = container.localSessionController,
                connectionRepository = container.connectionRepository,
                transferManager = container.transferManager
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hostActivity = LocalShareFlowHostActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val installState by container.applicationInstallationManager.installState.collectAsStateWithLifecycle()

    fun launchSafely(intent: Intent) {
        val launcher = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
        val safeIntent = if (launcher === hostActivity) intent else Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { launcher.startActivity(safeIntent) }
            .onFailure { err ->
                viewModel.reportInstallLaunchFailure(
                    context.getString(
                        R.string.apps_install_launch_failed,
                        err.localizedMessage ?: err.javaClass.simpleName
                    )
                )
            }
    }

    LaunchedEffect(installState) {
        when (val state = installState) {
            is AppInstallSessionState.AwaitingUserConfirmation -> {
                launchSafely(state.confirmationIntent)
            }
            is AppInstallSessionState.NeedsUnknownSourcesPermission -> {
                launchSafely(container.permissionManager.createManageUnknownSourcesIntent())
            }
            is AppInstallSessionState.Failed -> {
                val fallback = state.fallbackApkFile?.let {
                    container.applicationInstallationManager.createSystemInstallerFallbackIntent(it)
                }
                if (fallback != null && !state.wasCancelledByUser) {
                    launchSafely(fallback)
                } else {
                    viewModel.reportInstallLaunchFailure(state.reason)
                }
            }
            else -> Unit
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    container.applicationInstallationManager.resumePendingInstallIfPermitted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AppSharingContent(
        uiState = uiState,
        onNavigateBack = onNavigateBack,
        onSelectTab = viewModel::selectTab,
        onSearchQueryChange = viewModel::updateSearchQuery,
        onToggleSystemApps = viewModel::toggleIncludeSystemApps,
        onRefreshInstalled = viewModel::refreshInstalledApps,
        onToggleAppSelection = viewModel::toggleAppSelection,
        onClearSelection = viewModel::clearSelection,
        onExtractAndSend = {
            viewModel.extractAndSendSelected(
                onQueuedForConfirm = onNavigateToSendConfirm,
                onStartedLiveTransfer = onNavigateToTransferProgress
            )
        },
        onRequestInstallReview = viewModel::requestInstallReview,
        onDismissInstallReview = viewModel::dismissInstallReview,
        onConfirmInstall = {
            val targetApk = uiState.pendingInstallApk
            if (targetApk != null) {
                viewModel.dismissInstallReview()
                scope.launch {
                    container.applicationInstallationManager.startInstallation(targetApk)
                }
            }
        },
        onConsumeFeedback = viewModel::consumeFeedback,
        iconLoader = viewModel::loadAppIcon,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSharingContent(
    uiState: AppSharingUiState,
    onNavigateBack: () -> Unit,
    onSelectTab: (AppSharingTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onToggleSystemApps: () -> Unit,
    onRefreshInstalled: () -> Unit,
    onToggleAppSelection: (ShareableAppInfo) -> Unit,
    onClearSelection: () -> Unit,
    onExtractAndSend: () -> Unit,
    onRequestInstallReview: (ReceivedApkInfo) -> Unit,
    onDismissInstallReview: () -> Unit,
    onConfirmInstall: () -> Unit,
    onConsumeFeedback: () -> Unit,
    iconLoader: (String) -> Drawable?,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.feedbackMessage) {
        val message = uiState.feedbackMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onConsumeFeedback()
        }
    }

    uiState.pendingInstallApk?.let { apk ->
        ConfirmationDialog(
            title = stringResource(R.string.apps_install_confirm_title),
            body = stringResource(
                R.string.apps_install_confirm_body,
                apk.appLabel ?: apk.fileName,
                apk.packageName ?: apk.fileName,
                apk.versionName ?: "—"
            ),
            confirmLabel = stringResource(R.string.apps_install_confirm_cta),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = onConfirmInstall,
            onDismiss = onDismissInstallReview,
            confirmTestTag = "apps_install_confirm_button",
            dismissTestTag = "apps_install_cancel_button"
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        topBar = {
            ShareFlowTopBar(
                title = stringResource(R.string.apps_screen_title),
                onNavigateBack = onNavigateBack,
                actions = {
                    if (uiState.activeTab == AppSharingTab.INSTALLED) {
                        IconButton(
                            onClick = onRefreshInstalled,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("apps_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.apps_refresh_action)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (uiState.activeTab == AppSharingTab.INSTALLED && uiState.selectedCount > 0) {
                Surface(
                    tonalElevation = ShareFlowDesign.elevation.raised,
                    shadowElevation = 4.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.screenHorizontal, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.apps_selected_summary,
                                    ShareFlowFormatters.formatCount(uiState.selectedCount, locale),
                                    ShareFlowFormatters.formatBytes(uiState.selectedTotalBytes, locale)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("apps_selection_summary")
                            )
                            TextButton(
                                onClick = onClearSelection,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.testTag("apps_clear_selection_button")
                            ) {
                                Text(text = stringResource(R.string.apps_clear_selection_action))
                            }
                        }

                        PrimaryActionButton(
                            text = if (uiState.isExtracting) {
                                stringResource(R.string.apps_extracting_label)
                            } else {
                                stringResource(R.string.apps_extract_and_send_action)
                            },
                            onClick = onExtractAndSend,
                            enabled = !uiState.isExtracting && uiState.selectedCount > 0,
                            leadingIcon = Icons.AutoMirrored.Filled.Send,
                            modifier = Modifier.testTag("apps_extract_and_send_button")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val selectedAppTab = if (uiState.activeTab == AppSharingTab.INSTALLED) 0 else 1
            PrimaryTabRow(
                selectedTabIndex = selectedAppTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = {
                    ShareFlowSignatureTabIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedAppTab, matchContentSize = false)
                    )
                },
                modifier = Modifier.widthIn(max = spacing.maxContentWidth)
            ) {
                Tab(
                    selected = uiState.activeTab == AppSharingTab.INSTALLED,
                    onClick = { onSelectTab(AppSharingTab.INSTALLED) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = { Text(text = stringResource(R.string.apps_tab_installed)) },
                    modifier = Modifier.testTag("apps_tab_installed")
                )
                Tab(
                    selected = uiState.activeTab == AppSharingTab.RECEIVED,
                    onClick = { onSelectTab(AppSharingTab.RECEIVED) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    text = {
                        val suffix = if (uiState.receivedApks.isNotEmpty()) {
                            " (${ShareFlowFormatters.formatCount(uiState.receivedApks.size, locale)})"
                        } else {
                            ""
                        }
                        Text(text = stringResource(R.string.apps_tab_received) + suffix)
                    },
                    modifier = Modifier.testTag("apps_tab_received")
                )
            }

            when (uiState.activeTab) {
                AppSharingTab.INSTALLED -> InstalledAppsTabContent(
                    uiState = uiState,
                    onSearchQueryChange = onSearchQueryChange,
                    onToggleSystemApps = onToggleSystemApps,
                    onRefreshInstalled = onRefreshInstalled,
                    onToggleAppSelection = onToggleAppSelection,
                    iconLoader = iconLoader,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
                AppSharingTab.RECEIVED -> ReceivedApksTabContent(
                    receivedApks = uiState.receivedApks,
                    onRequestInstallReview = onRequestInstallReview,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun InstalledAppsTabContent(
    uiState: AppSharingUiState,
    onSearchQueryChange: (String) -> Unit,
    onToggleSystemApps: () -> Unit,
    onRefreshInstalled: () -> Unit,
    onToggleAppSelection: (ShareableAppInfo) -> Unit,
    iconLoader: (String) -> Drawable?,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("apps_installed_list"),
            contentPadding = PaddingValues(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item(key = "visibility_notice") {
                Surface(
                    shape = ShareFlowDesign.shapes.card,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.md),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.apps_visibility_notice_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.apps_visibility_notice_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "search_and_filter") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        shape = ShareFlowDesign.shapes.input,
                        colors = com.example.shareflow.core.designsystem.components.ShareFlowInputDefaults.outlinedTextFieldColors(),
                        placeholder = { Text(text = stringResource(R.string.apps_search_placeholder)) },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.apps_clear_selection_action)
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apps_search_field")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        FilterChip(
                            selected = uiState.includeSystemApps,
                            onClick = onToggleSystemApps,
                            label = { Text(text = stringResource(R.string.apps_filter_show_system)) },
                            shape = ShareFlowDesign.shapes.chip,
                            colors = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipColors(),
                            border = com.example.shareflow.core.designsystem.components.ShareFlowChipDefaults.filterChipBorder(selected = uiState.includeSystemApps),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("apps_filter_system_chip")
                        )
                    }
                }
            }

            when {
                uiState.isLoadingInstalled -> {
                    item(key = "loading") {
                        LoadingState(
                            message = stringResource(R.string.apps_screen_title),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = spacing.lg)
                        )
                    }
                }
                uiState.errorMessage != null -> {
                    item(key = "error") {
                        ErrorState(
                            message = uiState.errorMessage,
                            title = stringResource(R.string.apps_screen_title),
                            retryLabel = stringResource(R.string.apps_refresh_action),
                            onRetry = onRefreshInstalled
                        )
                    }
                }
                uiState.filteredInstalledApps.isEmpty() -> {
                    item(key = "empty") {
                        EmptyState(
                            icon = Icons.Filled.Android,
                            title = stringResource(R.string.apps_empty_title),
                            body = stringResource(R.string.apps_empty_body),
                            modifier = Modifier.testTag("apps_installed_empty")
                        )
                    }
                }
                else -> {
                    items(
                        items = uiState.filteredInstalledApps,
                        key = { it.packageName }
                    ) { app ->
                        InstalledAppCard(
                            app = app,
                            selected = app.packageName in uiState.selectedPackages,
                            onToggleSelection = { onToggleAppSelection(app) },
                            iconLoader = iconLoader
                        )
                    }
                }
            }

            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }
}

@Composable
private fun InstalledAppCard(
    app: ShareableAppInfo,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    iconLoader: (String) -> Drawable?,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val appIconBitmap = com.example.shareflow.ui.common.rememberAppIconBitmap(packageName = app.packageName)

    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = ShareFlowDesign.shapes.card,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelection)
            .testTag("app_item_${app.packageName}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Surface(
                    shape = ShareFlowDesign.shapes.chip,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (appIconBitmap != null) {
                            Image(
                                bitmap = appIconBitmap,
                                contentDescription = stringResource(R.string.apps_icon_content_desc, app.appName),
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(ShareFlowDesign.shapes.chip)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Android,
                                contentDescription = stringResource(R.string.apps_icon_content_desc, app.appName),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeText = app.totalApkSizeBytes?.let { bytes ->
                        ShareFlowFormatters.formatBytes(bytes, locale)
                    } ?: stringResource(R.string.apps_size_unavailable)
                    val cleanMeta = listOfNotNull(
                        app.versionName.takeIf { it.isNotBlank() }?.let { "v$it" },
                        sizeText
                    ).joinToString(" · ")
                    Text(
                        text = cleanMeta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    enabled = app.isExtractable,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("app_checkbox_${app.packageName}")
                )
            }

            if (app.isSplitApk && app.isExtractable) {
                StatusPillRow(
                    icon = Icons.Filled.Info,
                    label = stringResource(R.string.apps_split_apk_badge, app.apkPartCount),
                    detail = stringResource(R.string.apps_split_apk_note),
                    tint = MaterialTheme.colorScheme.tertiary,
                    detailTestTag = "app_split_note_${app.packageName}"
                )
            }

            if (!app.isExtractable) {
                StatusPillRow(
                    icon = Icons.Filled.WarningAmber,
                    label = stringResource(R.string.apps_unavailable_badge),
                    detail = app.unavailableReason ?: stringResource(R.string.apps_unavailable_badge),
                    tint = MaterialTheme.colorScheme.error,
                    detailTestTag = "app_unavailable_reason_${app.packageName}"
                )
            }
        }
    }
}

@Composable
private fun ReceivedApksTabContent(
    receivedApks: List<ReceivedApkInfo>,
    onRequestInstallReview: (ReceivedApkInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("apps_received_list"),
            contentPadding = PaddingValues(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            if (receivedApks.isEmpty()) {
                item(key = "received_empty") {
                    EmptyState(
                        icon = Icons.Filled.InstallMobile,
                        title = stringResource(R.string.apps_received_empty_title),
                        body = stringResource(R.string.apps_received_empty_body),
                        modifier = Modifier.testTag("apps_received_empty")
                    )
                }
            } else {
                items(
                    items = receivedApks,
                    key = { it.id }
                ) { apk ->
                    ReceivedApkCard(
                        apk = apk,
                        onRequestInstall = { onRequestInstallReview(apk) }
                    )
                }
            }

            item(key = "received_bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }
}

@Composable
private fun ReceivedApkCard(
    apk: ReceivedApkInfo,
    onRequestInstall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    val (badgeIcon, badgeLabel, badgeTint) = when (apk.integrityStatus) {
        ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE -> Triple(
            Icons.Filled.VerifiedUser,
            stringResource(R.string.apps_received_verified_badge),
            semantic.verifiedBadge
        )
        ReceivedApkIntegrityStatus.CHECKSUM_MISMATCH -> Triple(
            Icons.Filled.ErrorOutline,
            stringResource(R.string.apps_received_mismatch_badge),
            MaterialTheme.colorScheme.error
        )
        ReceivedApkIntegrityStatus.INVALID_APK_ARCHIVE -> Triple(
            Icons.Filled.ErrorOutline,
            stringResource(R.string.apps_received_invalid_badge),
            MaterialTheme.colorScheme.error
        )
        ReceivedApkIntegrityStatus.FILE_MISSING -> Triple(
            Icons.Filled.ErrorOutline,
            stringResource(R.string.apps_received_missing_badge),
            MaterialTheme.colorScheme.error
        )
        ReceivedApkIntegrityStatus.UNVERIFIED_EXTERNAL -> Triple(
            Icons.Filled.WarningAmber,
            stringResource(R.string.apps_received_unverified_badge),
            MaterialTheme.colorScheme.tertiary
        )
    }

    Surface(
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .testTag("received_apk_card_${apk.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                val receivedIcon = com.example.shareflow.ui.common.rememberAppIconBitmap(apkPathOrUri = apk.filePath)
                Surface(
                    shape = ShareFlowDesign.shapes.chip,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (receivedIcon != null) {
                            Image(
                                bitmap = receivedIcon,
                                contentDescription = apk.appLabel ?: apk.fileName,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(ShareFlowDesign.shapes.chip)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.InstallMobile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = apk.appLabel?.takeIf { it.isNotBlank() }
                            ?: com.example.shareflow.ui.common.TransferDisplayPresentation.cleanAppDisplayName(apk.fileName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val metaParts = buildList {
                        if (!apk.versionName.isNullOrBlank()) {
                            add("v${apk.versionName}")
                        }
                        if (apk.fileSizeBytes > 0L) {
                            add(ShareFlowFormatters.formatBytes(apk.fileSizeBytes, locale))
                        }
                        if (!apk.peerDeviceName.isNullOrBlank()) {
                            add(stringResource(R.string.apps_received_from_peer, apk.peerDeviceName))
                        }
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(
                            text = metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            StatusPillRow(
                icon = badgeIcon,
                label = badgeLabel,
                detail = apk.statusDetail,
                tint = badgeTint,
                detailTestTag = "received_apk_status_${apk.id}"
            )

            PrimaryActionButton(
                text = stringResource(R.string.apps_install_action),
                onClick = onRequestInstall,
                enabled = apk.canLaunchSystemInstaller,
                leadingIcon = Icons.Filled.InstallMobile,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("received_apk_install_${apk.id}")
            )
        }
    }
}

@Composable
private fun StatusPillRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    tint: Color,
    detailTestTag: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint
            )
        }
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(detailTestTag)
        )
    }
}
