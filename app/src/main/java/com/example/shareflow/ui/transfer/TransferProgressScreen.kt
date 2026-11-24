package com.example.shareflow.ui.transfer

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.ConfirmationDialog
import com.example.shareflow.core.designsystem.components.EmptyState
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.LocalShareFlowHostActivity
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.core.di.shareFlowViewModelFactory
import com.example.shareflow.data.apps.AppInstallSessionState
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ReceivedApkIntegrityStatus
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferSession
import com.example.shareflow.domain.model.TransferState
import com.example.shareflow.ui.common.AppIconBitmapCache
import com.example.shareflow.ui.common.TransferDisplayItem
import com.example.shareflow.ui.common.TransferDisplayPresentation
import com.example.shareflow.ui.common.TransferItemVisualAvatar
import com.example.shareflow.ui.files.FileSharesheetHelper
import com.example.shareflow.ui.receive.ReceivedAppsInstallerCard
import com.example.shareflow.ui.viewer.CustomFileViewerDialog
import kotlinx.coroutines.launch

@Composable
fun TransferProgressScreen(
    onNavigateBack: () -> Unit,
    onOpenSend: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVault: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val hostActivity = LocalShareFlowHostActivity.current
    val scope = rememberCoroutineScope()
    val viewModel: TransferViewModel = viewModel(
        factory = shareFlowViewModelFactory {
            TransferViewModel(
                transferManager = container.transferManager,
                connectionRepository = container.connectionRepository
            )
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val receivedApks by container.appSharingRepository.observeReceivedApks()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val installState by container.applicationInstallationManager.installState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    var previewFile by remember { mutableStateOf<LocalFileEntry?>(null) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val resolvedPackagesByKey = remember { mutableStateMapOf<String, String>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                scope.launch {
                    container.applicationInstallationManager.resumePendingInstallIfPermitted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.recentCompletedSessions, receivedApks) {
        uiState.recentCompletedSessions
            .filter { it.direction == TransferDirection.RECEIVED && it.state == TransferState.COMPLETED }
            .flatMap { TransferDisplayPresentation.groupTransferItemsForDisplay(it.transferItems) }
            .filter { it.isApp }
            .forEach { displayItem ->
                val matchedPkg = receivedApks.firstOrNull { apk ->
                    val targetFile = displayItem.iconApkPathOrUri?.let(AppIconBitmapCache::resolveLocalApkFile)
                    (targetFile != null && apk.filePath == targetFile.absolutePath) ||
                        (!displayItem.bundleFolderName.isNullOrBlank() && apk.filePath.contains(displayItem.bundleFolderName)) ||
                        apk.fileName.equals(displayItem.rawFileName, ignoreCase = true) ||
                        apk.appLabel.equals(displayItem.displayTitle, ignoreCase = true)
                }?.packageName

                val pkg = matchedPkg?.takeIf { it.isNotBlank() }
                    ?: displayItem.iconApkPathOrUri?.let {
                        AppIconBitmapCache.loadPackageNameForApkPathOrUri(context, it)
                    }
                if (!pkg.isNullOrBlank()) {
                    resolvedPackagesByKey[displayItem.key] = pkg
                }
            }
    }

    fun resolvePackageForDisplayItem(displayItem: TransferDisplayItem): String? {
        resolvedPackagesByKey[displayItem.key]?.let { return it }
        return receivedApks.firstOrNull { apk ->
            val targetFile = displayItem.iconApkPathOrUri?.let(AppIconBitmapCache::resolveLocalApkFile)
            (targetFile != null && apk.filePath == targetFile.absolutePath) ||
                (!displayItem.bundleFolderName.isNullOrBlank() && apk.filePath.contains(displayItem.bundleFolderName)) ||
                apk.fileName.equals(displayItem.rawFileName, ignoreCase = true) ||
                apk.appLabel.equals(displayItem.displayTitle, ignoreCase = true)
        }?.packageName
    }

    fun canQuickOpenDisplayItem(displayItem: TransferDisplayItem): Boolean {
        // Read resumeTick & installState so Compose re-evaluates immediately after system installer completes
        val tick = resumeTick
        val currentInstall = installState
        if (tick < 0) return false
        val pkg = resolvePackageForDisplayItem(displayItem) ?: return false
        if (currentInstall is AppInstallSessionState.Succeeded && currentInstall.packageName == pkg) {
            return true
        }
        return container.applicationInstallationManager.isAppLaunchable(pkg)
    }

    fun quickOpenTransferredApp(displayItem: TransferDisplayItem) {
        val pkg = resolvePackageForDisplayItem(displayItem) ?: return
        val launchIntent = container.applicationInstallationManager.resolveLaunchIntent(pkg) ?: return
        val launcher = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
        val safeIntent = if (launcher === hostActivity) {
            launchIntent
        } else {
            Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { launcher.startActivity(safeIntent) }
    }

    LaunchedEffect(installState) {
        val state = installState
        if (state is AppInstallSessionState.AwaitingUserConfirmation) {
            val launcher = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
            val safeIntent = if (launcher === hostActivity) {
                state.confirmationIntent
            } else {
                Intent(state.confirmationIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { launcher.startActivity(safeIntent) }
        } else if (state is AppInstallSessionState.NeedsUnknownSourcesPermission) {
            val launcher = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
            val permIntent = container.permissionManager.createManageUnknownSourcesIntent()
            runCatching { launcher.startActivity(permIntent) }
        } else if (state is AppInstallSessionState.Succeeded) {
            resumeTick++
        }
    }

    fun installTransferredApp(displayItem: TransferDisplayItem) {
        val matched = receivedApks.firstOrNull { apk ->
            val targetFile = displayItem.iconApkPathOrUri?.let(AppIconBitmapCache::resolveLocalApkFile)
            (targetFile != null && apk.filePath == targetFile.absolutePath) ||
                (!displayItem.bundleFolderName.isNullOrBlank() && apk.filePath.contains(displayItem.bundleFolderName)) ||
                apk.fileName.equals(displayItem.rawFileName, ignoreCase = true) ||
                apk.appLabel.equals(displayItem.displayTitle, ignoreCase = true)
        }

        val apkToInstall = matched ?: displayItem.iconApkPathOrUri?.let(AppIconBitmapCache::resolveLocalApkFile)?.let { file ->
            val parent = file.parentFile
            val splitApks = if (!displayItem.bundleFolderName.isNullOrBlank() && parent != null && parent.isDirectory) {
                parent.listFiles()?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
                    ?.map { it.absolutePath }
                    .orEmpty()
            } else {
                emptyList()
            }
            ReceivedApkInfo(
                id = displayItem.key,
                fileName = file.name,
                filePath = file.absolutePath,
                fileSizeBytes = displayItem.fileSizeBytes,
                packageName = resolvedPackagesByKey[displayItem.key],
                appLabel = displayItem.displayTitle,
                versionName = null,
                versionCode = null,
                receivedAtEpochMillis = System.currentTimeMillis(),
                peerDeviceName = null,
                expectedSha256 = null,
                actualSha256 = null,
                integrityStatus = ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE,
                statusDetail = "",
                isSplitBundle = splitApks.size > 1,
                splitBundleApkPaths = splitApks
            )
        }

        if (apkToInstall != null) {
            scope.launch {
                container.applicationInstallationManager.startInstallation(apkToInstall)
            }
        }
    }

    val hasCompletedReceivedSession = uiState.recentCompletedSessions.any {
        it.direction == TransferDirection.RECEIVED && it.state == TransferState.COMPLETED
    }

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
        onViewFile = { displayItem ->
            TransferDisplayPresentation.toLocalFileEntry(displayItem)?.let { entry ->
                previewFile = entry
            }
        },
        onInstallApp = ::installTransferredApp,
        onQuickOpenApp = ::quickOpenTransferredApp,
        canQuickOpenApp = ::canQuickOpenDisplayItem,
        receivedApksSection = if (hasCompletedReceivedSession && receivedApks.any { it.canLaunchSystemInstaller }) {
            {
                ReceivedAppsInstallerCard(
                    receivedApks = receivedApks,
                    installationManager = container.applicationInstallationManager,
                    permissionManager = container.permissionManager
                )
            }
        } else {
            null
        },
        modifier = modifier
    )

    previewFile?.let { fileEntry ->
        CustomFileViewerDialog(
            file = fileEntry,
            onSendWithShareFlow = {
                container.localSessionController.addSelectedFiles(listOf(fileEntry))
                previewFile = null
                onOpenSend()
            },
            onDismiss = { previewFile = null },
            onOpenVaultSetup = {
                previewFile = null
                onOpenVault()
            },
            onOpenExternal = {
                FileSharesheetHelper.openExternally(context, fileEntry)
            },
            onShareExternal = {
                FileSharesheetHelper.shareFiles(context, listOf(fileEntry), fileEntry.displayName)
            }
        )
    }
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
    onViewFile: ((TransferDisplayItem) -> Unit)? = null,
    onInstallApp: ((TransferDisplayItem) -> Unit)? = null,
    onQuickOpenApp: ((TransferDisplayItem) -> Unit)? = null,
    canQuickOpenApp: (TransferDisplayItem) -> Boolean = { false },
    receivedApksSection: (@Composable () -> Unit)? = null,
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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                        key = { "active_${it.sessionId}" },
                        contentType = { "transfer_session_card" }
                    ) { session ->
                        TransferSessionProgressCard(
                            session = session,
                            onPause = { onPauseSession(session.sessionId) },
                            onResume = { onResumeSession(session.sessionId) },
                            onRetry = { onRetrySession(session.sessionId) },
                            onRequestCancel = { pendingCancelSessionId = session.sessionId },
                            onViewFile = onViewFile,
                            onInstallApp = onInstallApp,
                            onQuickOpenApp = onQuickOpenApp,
                            canQuickOpenApp = canQuickOpenApp,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                if (uiState.recentCompletedSessions.isNotEmpty()) {
                    item(key = "recent_header") {
                        SectionHeader(title = stringResource(R.string.transfer_section_recent))
                    }
                    items(
                        items = uiState.recentCompletedSessions,
                        key = { "recent_${it.sessionId}" },
                        contentType = { "transfer_session_card" }
                    ) { session ->
                        TransferSessionProgressCard(
                            session = session,
                            onPause = { onPauseSession(session.sessionId) },
                            onResume = { onResumeSession(session.sessionId) },
                            onRetry = { onRetrySession(session.sessionId) },
                            onRequestCancel = { pendingCancelSessionId = session.sessionId },
                            onViewFile = onViewFile,
                            onInstallApp = onInstallApp,
                            onQuickOpenApp = onQuickOpenApp,
                            canQuickOpenApp = canQuickOpenApp,
                            modifier = Modifier.animateItem()
                        )
                    }
                }

                if (receivedApksSection != null) {
                    item(key = "received_apks_installer") {
                        receivedApksSection()
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
    onViewFile: ((TransferDisplayItem) -> Unit)? = null,
    onInstallApp: ((TransferDisplayItem) -> Unit)? = null,
    onQuickOpenApp: ((TransferDisplayItem) -> Unit)? = null,
    canQuickOpenApp: (TransferDisplayItem) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isSent = session.direction == TransferDirection.SENT
    val displayItems = remember(session.transferItems) {
        TransferDisplayPresentation.groupTransferItemsForDisplay(session.transferItems)
    }
    val primaryDisplayItem = remember(displayItems) {
        displayItems.firstOrNull {
            it.state == TransferState.TRANSFERRING || it.state == TransferState.VERIFYING
        } ?: displayItems.firstOrNull {
            it.state == TransferState.PAUSED || it.state == TransferState.PENDING
        } ?: displayItems.firstOrNull()
    }

    val progress = session.progressFraction
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "session_progress"
    )

    val stateLabel = transferStateLabel(session.state)
    val (stateBg, stateFg) = transferStateColors(session.state)
    val cleanCompletedCount = displayItems.count { it.state == TransferState.COMPLETED }
    val cleanTotalCount = if (displayItems.isNotEmpty()) displayItems.size else session.totalFileCount

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
            // Header: Visual App/File Avatar + Direction badge, clean title (zero technical jargon), state pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                Box(modifier = Modifier.size(58.dp)) {
                    if (primaryDisplayItem != null) {
                        TransferItemVisualAvatar(
                            item = primaryDisplayItem,
                            size = 54.dp,
                            showCircularProgressOverlay = primaryDisplayItem.isApp && primaryDisplayItem.state.isActive,
                            progressOverlayTestTag = "transfer_session_app_icon_progress_${session.sessionId}",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isSent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center)
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
                    }

                    // Subtle direction micro-badge anchored to corner
                    Surface(
                        shape = CircleShape,
                        color = if (isSent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier
                            .size(20.dp)
                            .align(Alignment.BottomEnd)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isSent) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                contentDescription = stringResource(
                                    if (isSent) R.string.history_direction_sent else R.string.history_direction_received
                                ),
                                tint = if (isSent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = primaryDisplayItem?.displayTitle ?: session.peer.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .testTag("transfer_file_name_${session.sessionId}")
                        )
                        if (primaryDisplayItem?.isApp == true) {
                            StatusPill(
                                label = stringResource(R.string.transfer_app_badge),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
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

            // Smooth animated progress bar using the Official Share Flow Signature Gradient
            ShareFlowSignatureProgressBar(
                progress = animatedProgress,
                height = 10.dp,
                modifier = Modifier.testTag("transfer_progress_bar_${session.sessionId}")
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (session.state == TransferState.COMPLETED) semantic.verifiedBadge else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("transfer_percent_${session.sessionId}")
                )
            }

            // Real-time Speed, Remaining Time (ETA), Clean Item Count
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
                        ShareFlowFormatters.formatCount(cleanCompletedCount, locale),
                        ShareFlowFormatters.formatCount(cleanTotalCount, locale)
                    ),
                    testTag = "transfer_file_count_${session.sessionId}"
                )
            }

            // Clean per-item list for multi-item transfers, or direct Install + Quick Open / View CTA for single received item
            val isReceived = session.direction == TransferDirection.RECEIVED
            if (displayItems.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    displayItems.take(8).forEach { item ->
                        TransferDisplayItemRow(
                            item = item,
                            isReceived = isReceived,
                            onViewFile = onViewFile,
                            onInstallApp = onInstallApp,
                            onQuickOpenApp = onQuickOpenApp,
                            canQuickOpen = canQuickOpenApp(item)
                        )
                    }
                }
            } else if (isReceived && displayItems.size == 1 && displayItems.first().state == TransferState.COMPLETED) {
                val singleItem = displayItems.first()
                if (singleItem.isApp && onInstallApp != null) {
                    val quickOpenEnabled = onQuickOpenApp != null && canQuickOpenApp(singleItem)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.transfer_action_install_app),
                            onClick = { onInstallApp(singleItem) },
                            leadingIcon = Icons.Filled.InstallMobile,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_item_install_${singleItem.key}")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.receive_app_quick_open_action),
                            onClick = { onQuickOpenApp?.invoke(singleItem) },
                            enabled = quickOpenEnabled,
                            outlined = !quickOpenEnabled,
                            leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transfer_item_quick_open_${singleItem.key}")
                        )
                    }
                } else if (!singleItem.isApp && onViewFile != null && !singleItem.localUri.isNullOrBlank()) {
                    PrimaryActionButton(
                        text = stringResource(R.string.transfer_action_view_file),
                        onClick = { onViewFile(singleItem) },
                        leadingIcon = Icons.Filled.Visibility,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("transfer_item_view_${singleItem.key}")
                    )
                }
            }

            AnimatedVisibility(
                visible = session.state == TransferState.COMPLETED,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
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
private fun TransferDisplayItemRow(
    item: TransferDisplayItem,
    isReceived: Boolean,
    onViewFile: ((TransferDisplayItem) -> Unit)?,
    onInstallApp: ((TransferDisplayItem) -> Unit)?,
    onQuickOpenApp: ((TransferDisplayItem) -> Unit)? = null,
    canQuickOpen: Boolean = false
) {
    val locale = ShareFlowDesign.locale
    val animatedItemProgress by animateFloatAsState(
        targetValue = item.progressFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "item_progress_${item.key}"
    )

    Surface(
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transfer_item_row_${item.key}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TransferItemVisualAvatar(
                    item = item,
                    size = 46.dp,
                    showCircularProgressOverlay = item.isApp && item.state.isActive,
                    progressOverlayTestTag = "transfer_app_icon_progress_${item.key}"
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = item.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${ShareFlowFormatters.formatBytes(item.fileSizeBytes, locale)} • ${
                            ShareFlowFormatters.formatPercentage(item.progressFraction, locale)
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Receiver completed action for non-APK files inline
                if (isReceived && item.state == TransferState.COMPLETED && !item.isApp &&
                    onViewFile != null && !item.localUri.isNullOrBlank()
                ) {
                    FilledTonalButton(
                        onClick = { onViewFile(item) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("transfer_item_view_${item.key}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.transfer_action_view_file),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }

            // Receiver completed action row for APKs: Install App + Quick Open side-by-side
            if (isReceived && item.state == TransferState.COMPLETED && item.isApp && onInstallApp != null) {
                val quickOpenEnabled = onQuickOpenApp != null && canQuickOpen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onInstallApp(item) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag("transfer_item_install_${item.key}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.InstallMobile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.transfer_action_install_app),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    if (quickOpenEnabled) {
                        FilledTonalButton(
                            onClick = { onQuickOpenApp?.invoke(item) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("transfer_item_quick_open_${item.key}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.receive_app_quick_open_action),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onQuickOpenApp?.invoke(item) },
                            enabled = false,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("transfer_item_quick_open_${item.key}")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(R.string.receive_app_quick_open_action),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }

            if (item.state != TransferState.COMPLETED) {
                ShareFlowSignatureProgressBar(
                    progress = animatedItemProgress,
                    height = 5.dp
                )
            }
        }
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
