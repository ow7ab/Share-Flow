package com.example.shareflow.ui.update

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.BuildConfig
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.components.SFSnackbar
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.data.update.CloudUpdateManager
import com.example.shareflow.data.update.CloudUpdateManifest
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.CloudUpdateValidator
import com.example.shareflow.data.update.FcmSubscriptionState
import com.example.shareflow.data.update.ReleaseHistoryItem
import com.example.shareflow.data.update.UpdateDiagnosticsSnapshot
import com.example.shareflow.data.update.UpdateInstallLaunchResult
import com.example.shareflow.data.update.UpdateServerConnectionStatus
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Dedicated Share Flow Updates Center page accessible from Settings, update dialogs,
 * and instant FCM release notifications.
 */
@Composable
fun UpdatesScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val updateState by container.cloudUpdateManager.state.collectAsStateWithLifecycle()
    val releaseHistory by container.cloudUpdateManager.releaseHistory.collectAsStateWithLifecycle()
    val diagnostics by container.cloudUpdateManager.diagnosticsFlow.collectAsStateWithLifecycle(
        initialValue = UpdateDiagnosticsSnapshot()
    )
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = container.settingsRepository.peekInitialSettings()
    )

    var activeJob by remember { mutableStateOf<Job?>(null) }
    var hasNotificationsPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationsPermission = granted
        if (granted) {
            scope.launch {
                runCatching { container.fcmUpdateTopicCoordinator.ensureSubscribed(forceResubscribe = true) }
            }
        }
    }

    val installPermMsg = stringResource(R.string.settings_feedback_update_install_perm)
    val installFailMsg = stringResource(R.string.settings_feedback_update_install_failed)

    // Automatically check once when landing on Updates screen in Idle state
    LaunchedEffect(Unit) {
        if (container.cloudUpdateManager.state.value is CloudUpdateState.Idle) {
            activeJob = scope.launch {
                runCatching { container.cloudUpdateManager.checkForUpdates("") }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
    }

    UpdatesScreenContent(
        updateState = updateState,
        diagnostics = diagnostics,
        releaseHistory = releaseHistory,
        autoCheckEnabled = settings.autoCheckUpdatesOnLaunch,
        hasNotificationsPermission = hasNotificationsPermission,
        snackbarHostState = snackbarHostState,
        onNavigateBack = onNavigateBack,
        onCheckForUpdates = {
            activeJob?.cancel()
            activeJob = scope.launch {
                runCatching { container.cloudUpdateManager.checkForUpdates("") }
                    .onFailure { if (it is CancellationException) throw it }
            }
        },
        onDownloadOrResume = { manifest ->
            activeJob?.cancel()
            activeJob = scope.launch {
                runCatching { container.cloudUpdateManager.downloadAndVerifyUpdate(manifest) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        },
        onPauseDownload = {
            container.cloudUpdateManager.markDownloadPaused()
            activeJob?.cancel()
            activeJob = null
        },
        onCancelDownload = {
            activeJob?.cancel()
            activeJob = null
            container.cloudUpdateManager.cancelActiveDownload()
        },
        onInstallVerifiedUpdate = { apkPath ->
            when (container.cloudUpdateManager.launchVerifiedUpdateInstaller(apkPath)) {
                UpdateInstallLaunchResult.LAUNCHED -> Unit
                UpdateInstallLaunchResult.PERMISSION_REQUIRED -> {
                    scope.launch { snackbarHostState.showSnackbar(installPermMsg) }
                    val intent = container.permissionManager.createManageUnknownSourcesIntent()
                    runCatching { context.startActivity(intent) }
                }
                UpdateInstallLaunchResult.FAILED -> {
                    scope.launch { snackbarHostState.showSnackbar(installFailMsg) }
                }
            }
        },
        onAutoCheckChanged = { enabled ->
            scope.launch {
                runCatching { container.settingsRepository.updateAutoCheckUpdatesOnLaunch(enabled) }
            }
        },
        onRequestNotificationPermission = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
        onOpenExternalReleaseUrl = { url ->
            if (CloudUpdateValidator.isValidPublicHttpsUrl(url)) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            }
        },
        modifier = modifier
    )
}

@Composable
fun UpdatesScreenContent(
    updateState: CloudUpdateState,
    diagnostics: UpdateDiagnosticsSnapshot,
    releaseHistory: List<ReleaseHistoryItem>,
    autoCheckEnabled: Boolean,
    hasNotificationsPermission: Boolean,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onNavigateBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadOrResume: (CloudUpdateManifest) -> Unit,
    onPauseDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallVerifiedUpdate: (String) -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExternalReleaseUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isArabicLocale = locale.language.equals("ar", ignoreCase = true)

    val brandGradient = remember {
        Brush.linearGradient(
            colors = listOf(
                ShareFlowMarpleColors.Ruby,
                ShareFlowMarpleColors.Coral,
                ShareFlowMarpleColors.Peach
            )
        )
    }

    val lastCheckedText = remember(diagnostics.lastCheckEpochMillis, locale) {
        if (diagnostics.lastCheckEpochMillis <= 0L) {
            null
        } else {
            runCatching {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
                    .format(Date(diagnostics.lastCheckEpochMillis))
            }.getOrNull()
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("updates_screen"),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
                snackbar = { data -> SFSnackbar(snackbarData = data) }
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
                    .testTag("updates_list"),
                contentPadding = PaddingValues(
                    start = spacing.screenHorizontal,
                    end = spacing.screenHorizontal,
                    top = spacing.sm,
                    bottom = spacing.xl
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // Top Bar with transparent status bar support
                item(key = "updates_top_bar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("updates_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_navigate_back),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.updates_screen_title),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 1. Share Flow Brand Identity & Installed Version Hero Card
                item(key = "updates_hero_card") {
                    SFGlassCard(
                        contentPadding = PaddingValues(spacing.cardPadding),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("updates_hero_card")
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(spacing.md)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.md)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(brandGradient)
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                                        contentDescription = stringResource(R.string.cd_app_logo),
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.app_name),
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.settings_about_version_format,
                                            BuildConfig.VERSION_NAME,
                                            BuildConfig.VERSION_CODE.toString()
                                        ),
                                        style = ShareFlowMonoStyle,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("updates_installed_version")
                                    )
                                    UpdateStatusBadge(updateState = updateState)
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            // Diagnostics: Server Status, Last Checked, Push Topic Status
                            DiagnosticInfoRow(
                                label = stringResource(R.string.updates_server_label),
                                value = serverStatusLabel(diagnostics.serverStatus, updateState),
                                isPositive = diagnostics.serverStatus == UpdateServerConnectionStatus.CONNECTED,
                                isNegative = diagnostics.serverStatus == UpdateServerConnectionStatus.OFFLINE ||
                                    diagnostics.serverStatus == UpdateServerConnectionStatus.ERROR
                            )
                            DiagnosticInfoRow(
                                label = stringResource(R.string.updates_last_checked_label),
                                value = lastCheckedText ?: stringResource(R.string.updates_last_checked_never)
                            )
                            DiagnosticInfoRow(
                                label = stringResource(R.string.updates_push_status_label),
                                value = fcmStatusLabel(diagnostics.fcmSubscriptionState),
                                isPositive = diagnostics.fcmSubscriptionState == FcmSubscriptionState.SUBSCRIBED
                            )

                            if (!hasNotificationsPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.updates_allow_notifications_action),
                                    onClick = onRequestNotificationPermission,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.NotificationsActive,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("updates_allow_notifications_button")
                                )
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_auto_check_updates_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_auto_check_updates_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = autoCheckEnabled,
                                    onCheckedChange = onAutoCheckChanged,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("updates_auto_check_switch")
                                )
                            }

                            PrimaryActionButton(
                                text = stringResource(R.string.settings_update_check_action),
                                onClick = onCheckForUpdates,
                                enabled = updateState !is CloudUpdateState.Checking &&
                                    updateState !is CloudUpdateState.Downloading &&
                                    updateState !is CloudUpdateState.Verifying,
                                isLoading = updateState is CloudUpdateState.Checking,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("updates_check_now_button")
                            )
                        }
                    }
                }

                // 2. Active Update / Up-To-Date / Progress / Error Card
                item(key = "updates_active_state") {
                    ActiveUpdateStateCard(
                        updateState = updateState,
                        isArabicLocale = isArabicLocale,
                        onCheckForUpdates = onCheckForUpdates,
                        onDownloadOrResume = onDownloadOrResume,
                        onPauseDownload = onPauseDownload,
                        onCancelDownload = onCancelDownload,
                        onInstallVerifiedUpdate = onInstallVerifiedUpdate
                    )
                }

                // 3. Previous Releases History Header
                item(key = "updates_history_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.updates_history_section_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (releaseHistory.isEmpty()) {
                    item(key = "updates_history_empty") {
                        SFGlassCard(
                            contentPadding = PaddingValues(spacing.cardPadding),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("updates_history_empty_card")
                        ) {
                            Text(
                                text = stringResource(R.string.updates_history_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(
                        items = releaseHistory,
                        key = { "release_${it.tagName}" }
                    ) { item ->
                        ReleaseHistoryCard(
                            item = item,
                            onOpenRelease = { onOpenExternalReleaseUrl(item.htmlUrl) }
                        )
                    }
                }

                item(key = "updates_bottom_inset") {
                    Spacer(
                        modifier = Modifier
                            .navigationBarsPadding()
                            .height(spacing.md)
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveUpdateStateCard(
    updateState: CloudUpdateState,
    isArabicLocale: Boolean,
    onCheckForUpdates: () -> Unit,
    onDownloadOrResume: (CloudUpdateManifest) -> Unit,
    onPauseDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstallVerifiedUpdate: (String) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    when (updateState) {
        CloudUpdateState.Idle -> Unit

        CloudUpdateState.Checking -> {
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_checking")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_update_checking_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        is CloudUpdateState.UpToDate -> {
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_up_to_date")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(semantic.verifiedContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = semantic.verifiedBadge,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.settings_update_up_to_date_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_update_up_to_date_body,
                                updateState.currentVersionName,
                                updateState.currentVersionCode.toString()
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        is CloudUpdateState.UpdateAvailable -> {
            val manifest = updateState.manifest
            val notes = if (isArabicLocale) {
                manifest.releaseNotesAr.ifBlank { manifest.releaseNotesEn }
            } else {
                manifest.releaseNotesEn.ifBlank { manifest.releaseNotesAr }
            }
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                selected = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_available")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_available_title,
                            manifest.versionName,
                            manifest.versionCode.toString()
                        ),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(
                            R.string.updates_version_transition_format,
                            BuildConfig.VERSION_NAME,
                            manifest.versionName
                        ),
                        style = ShareFlowMonoStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.md)
                    ) {
                        if (manifest.publishedAt.isNotBlank()) {
                            Text(
                                text = stringResource(
                                    R.string.updates_published_date_format,
                                    manifest.publishedAt.take(10)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (manifest.fileSizeBytes > 0L) {
                            Text(
                                text = stringResource(
                                    R.string.updates_apk_size_format,
                                    ShareFlowFormatters.formatBytes(manifest.fileSizeBytes, locale)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    StatusPill(
                        label = stringResource(R.string.updates_sha256_verified_badge),
                        containerColor = semantic.verifiedContainer,
                        contentColor = semantic.verifiedBadge
                    )
                    if (notes.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.settings_update_release_notes_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PrimaryActionButton(
                        text = stringResource(R.string.updates_dialog_update_now_action),
                        onClick = { onDownloadOrResume(manifest) },
                        leadingIcon = Icons.Filled.CloudDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("updates_start_download_button")
                    )
                }
            }
        }

        is CloudUpdateState.Downloading -> {
            val percent = (updateState.progress * 100f).roundToInt().coerceIn(0, 100)
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_downloading")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    val label = if (updateState.totalBytes > 0L) {
                        stringResource(
                            R.string.updates_downloading_total_format,
                            percent,
                            ShareFlowFormatters.formatBytes(updateState.downloadedBytes, locale),
                            ShareFlowFormatters.formatBytes(updateState.totalBytes, locale)
                        )
                    } else {
                        stringResource(
                            R.string.settings_update_downloading_format,
                            percent,
                            ShareFlowFormatters.formatBytes(updateState.downloadedBytes, locale)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (updateState.totalBytes > 0L) {
                        ShareFlowSignatureProgressBar(
                            progress = updateState.progress,
                            modifier = Modifier.fillMaxWidth(),
                            height = 10.dp
                        )
                    } else {
                        ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        SecondaryActionButton(
                            text = stringResource(R.string.updates_pause_download_action),
                            onClick = onPauseDownload,
                            outlined = true,
                            leadingIcon = Icons.Filled.Pause,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("updates_pause_download_button")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_update_cancel_download_action),
                            onClick = onCancelDownload,
                            outlined = true,
                            isDestructive = true,
                            leadingIcon = Icons.Filled.Close,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("updates_cancel_download_button")
                        )
                    }
                }
            }
        }

        is CloudUpdateState.Paused -> {
            val percent = (updateState.progress * 100f).roundToInt().coerceIn(0, 100)
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_paused")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Text(
                        text = stringResource(
                            R.string.updates_paused_progress_format,
                            percent,
                            ShareFlowFormatters.formatBytes(updateState.downloadedBytes, locale),
                            ShareFlowFormatters.formatBytes(
                                updateState.totalBytes.coerceAtLeast(updateState.downloadedBytes),
                                locale
                            )
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    ShareFlowSignatureProgressBar(
                        progress = updateState.progress,
                        modifier = Modifier.fillMaxWidth(),
                        height = 8.dp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        PrimaryActionButton(
                            text = stringResource(R.string.updates_resume_download_action),
                            onClick = { onDownloadOrResume(updateState.manifest) },
                            leadingIcon = Icons.Filled.PlayArrow,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("updates_resume_download_button")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.settings_update_cancel_download_action),
                            onClick = onCancelDownload,
                            outlined = true,
                            isDestructive = true,
                            leadingIcon = Icons.Filled.Close,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("updates_cancel_paused_button")
                        )
                    }
                }
            }
        }

        is CloudUpdateState.Verifying -> {
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_verifying")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = stringResource(R.string.settings_update_verifying_status),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        is CloudUpdateState.ReadyToInstall -> {
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                selected = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_ready")
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = semantic.verifiedBadge,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_update_ready_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_update_ready_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PrimaryActionButton(
                        text = stringResource(R.string.settings_update_install_action),
                        onClick = { onInstallVerifiedUpdate(updateState.verifiedApkPath) },
                        leadingIcon = Icons.Filled.InstallMobile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("updates_install_verified_button")
                    )
                }
            }
        }

        CloudUpdateState.PortalNotConfigured -> {
            SFGlassCard(
                contentPadding = PaddingValues(spacing.cardPadding),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_not_configured")
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.settings_update_not_configured_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.settings_update_not_configured_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        is CloudUpdateState.Error -> {
            val localizedReason = stringResource(cloudUpdateErrorReasonStringRes(updateState.reason))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("updates_state_error"),
                shape = ShareFlowDesign.shapes.card,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f))
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
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_update_error_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        text = localizedReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    val retryableDownloadErrors = setOf(
                        com.example.shareflow.data.update.CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED,
                        com.example.shareflow.data.update.CloudUpdateErrorReason.TIMEOUT,
                        com.example.shareflow.data.update.CloudUpdateErrorReason.NO_INTERNET,
                        com.example.shareflow.data.update.CloudUpdateErrorReason.SERVER_UNAVAILABLE,
                        com.example.shareflow.data.update.CloudUpdateErrorReason.NETWORK_ERROR
                    )
                    val retryAction = if (updateState.manifest != null &&
                        updateState.reason in retryableDownloadErrors
                    ) {
                        { onDownloadOrResume(updateState.manifest) }
                    } else {
                        onCheckForUpdates
                    }
                    SecondaryActionButton(
                        text = stringResource(R.string.settings_update_retry_action),
                        onClick = retryAction,
                        outlined = true,
                        leadingIcon = Icons.Filled.Refresh,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("updates_retry_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseHistoryCard(
    item: ReleaseHistoryItem,
    onOpenRelease: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale

    SFGlassCard(
        contentPadding = PaddingValues(spacing.cardPadding),
        onClick = onOpenRelease,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("updates_history_item_${item.tagName}")
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.title.ifBlank { item.tagName },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    label = stringResource(
                        if (item.isPrerelease) R.string.updates_history_prerelease_badge
                        else R.string.updates_history_stable_badge
                    ),
                    containerColor = if (item.isPrerelease) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        semantic.verifiedContainer
                    },
                    contentColor = if (item.isPrerelease) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        semantic.verifiedBadge
                    }
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.tagName,
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.primary
                )
                if (item.publishedAt.isNotBlank()) {
                    Text(
                        text = item.publishedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (item.fileSizeBytes > 0L) {
                    Text(
                        text = ShareFlowFormatters.formatBytes(item.fileSizeBytes, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = item.releaseNotes.ifBlank { stringResource(R.string.updates_history_no_notes) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun UpdateStatusBadge(updateState: CloudUpdateState) {
    val semantic = ShareFlowDesign.semanticColors
    val (labelRes, bg, fg) = when (updateState) {
        CloudUpdateState.Idle -> Triple(
            R.string.updates_status_up_to_date,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
        CloudUpdateState.Checking -> Triple(
            R.string.updates_status_checking,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is CloudUpdateState.UpToDate -> Triple(
            R.string.updates_status_up_to_date,
            semantic.verifiedContainer,
            semantic.verifiedBadge
        )
        is CloudUpdateState.UpdateAvailable -> Triple(
            R.string.updates_status_available,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is CloudUpdateState.Downloading -> Triple(
            R.string.updates_status_downloading,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is CloudUpdateState.Paused -> Triple(
            R.string.updates_status_paused,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        is CloudUpdateState.Verifying -> Triple(
            R.string.updates_status_verifying,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        is CloudUpdateState.ReadyToInstall -> Triple(
            R.string.updates_status_ready,
            semantic.verifiedContainer,
            semantic.verifiedBadge
        )
        CloudUpdateState.PortalNotConfigured,
        is CloudUpdateState.Error -> Triple(
            R.string.updates_status_error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
    }
    StatusPill(
        label = stringResource(labelRes),
        containerColor = bg,
        contentColor = fg,
        modifier = Modifier.testTag("updates_status_badge")
    )
}

@Composable
private fun DiagnosticInfoRow(
    label: String,
    value: String,
    isPositive: Boolean = false,
    isNegative: Boolean = false
) {
    val semantic = ShareFlowDesign.semanticColors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isPositive) {
                Icon(
                    imageVector = Icons.Filled.CloudDone,
                    contentDescription = null,
                    tint = semantic.verifiedBadge,
                    modifier = Modifier.size(15.dp)
                )
            } else if (isNegative) {
                Icon(
                    imageVector = Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp)
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = when {
                    isPositive -> semantic.verifiedBadge
                    isNegative -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun serverStatusLabel(
    status: UpdateServerConnectionStatus,
    state: CloudUpdateState
): String {
    if (state is CloudUpdateState.Checking) {
        return stringResource(R.string.updates_server_checking)
    }
    return stringResource(
        when (status) {
            UpdateServerConnectionStatus.CONNECTED -> R.string.updates_server_connected
            UpdateServerConnectionStatus.OFFLINE -> R.string.updates_server_offline
            UpdateServerConnectionStatus.RATE_LIMITED -> R.string.updates_server_rate_limited
            UpdateServerConnectionStatus.CHECKING -> R.string.updates_server_checking
            UpdateServerConnectionStatus.ERROR -> R.string.updates_server_error
            UpdateServerConnectionStatus.UNKNOWN -> R.string.updates_server_unknown
        }
    )
}

@Composable
private fun fcmStatusLabel(state: FcmSubscriptionState): String = stringResource(
    when (state) {
        FcmSubscriptionState.SUBSCRIBED -> R.string.updates_push_status_subscribed
        FcmSubscriptionState.NOT_CONFIGURED -> R.string.updates_push_status_not_configured
        FcmSubscriptionState.FAILED -> R.string.updates_push_status_failed
        FcmSubscriptionState.UNKNOWN -> R.string.updates_push_status_unknown
    }
)
