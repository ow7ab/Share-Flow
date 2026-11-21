package com.example.shareflow.ui.receive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.components.StatusPill
import android.content.Intent
import com.example.shareflow.core.designsystem.theme.LocalShareFlowHostActivity
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.data.apps.AppInstallSessionState
import com.example.shareflow.data.apps.ApplicationInstallationManager
import com.example.shareflow.domain.model.IncomingTransferOffer
import com.example.shareflow.domain.model.ReceivedApkInfo
import kotlinx.coroutines.launch

/**
 * Phase 1/11 Incoming Transfer Offer Approval dialog shown to the receiving user before any file
 * bytes are accepted from a connected, authenticated sender.
 */
@Composable
fun IncomingTransferOfferDialog(
    offer: IncomingTransferOffer,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val locale = ShareFlowDesign.locale
    val spacing = ShareFlowDesign.spacing
    val formattedSize = ShareFlowFormatters.formatBytes(offer.totalBytes, locale)

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.receive_offer_title),
                modifier = Modifier.testTag("incoming_transfer_offer_title")
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                Text(
                    text = stringResource(R.string.receive_offer_from_format, offer.peer.displayName),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(
                        R.string.receive_offer_summary_format,
                        offer.items.size,
                        formattedSize
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                val displayItems = androidx.compose.runtime.remember(offer.items) {
                    com.example.shareflow.ui.common.TransferDisplayPresentation.groupTransferItemsForDisplay(offer.items)
                }
                displayItems.take(5).forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.example.shareflow.ui.common.TransferItemVisualAvatar(
                            item = item,
                            size = 32.dp
                        )
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = ShareFlowFormatters.formatBytes(item.fileSizeBytes, locale),
                            style = ShareFlowMonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (displayItems.size > 5) {
                    Text(
                        text = stringResource(R.string.receive_offer_more_items_format, displayItems.size - 5),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                modifier = Modifier.testTag("receive_offer_accept_button")
            ) {
                Text(text = stringResource(R.string.receive_offer_accept_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier.testTag("receive_offer_reject_button")
            ) {
                Text(text = stringResource(R.string.receive_offer_reject_action))
            }
        }
    )
}

/**
 * Phase 6 Receiver-side verified APK & Split APK bundle installer section powered by
 * [ApplicationInstallationManager] (`PackageInstaller.Session`).
 */
@Composable
fun ReceivedAppsInstallerCard(
    receivedApks: List<ReceivedApkInfo>,
    installationManager: ApplicationInstallationManager,
    permissionManager: PermissionManager,
    modifier: Modifier = Modifier
) {
    val installable = receivedApks.filter { it.canLaunchSystemInstaller }
    if (installable.isEmpty()) return

    val context = LocalContext.current
    val hostActivity = LocalShareFlowHostActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val installState by installationManager.installState.collectAsStateWithLifecycle()
    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale
    val semantic = ShareFlowDesign.semanticColors

    fun launchSafely(intent: Intent) {
        val launcher = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed } ?: context
        val safeIntent = if (launcher === hostActivity) intent else Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { launcher.startActivity(safeIntent) }
    }

    // Automatically launch Android's system installation confirmation Intent when PackageInstaller signals STATUS_PENDING_USER_ACTION
    LaunchedEffect(installState) {
        val state = installState
        if (state is AppInstallSessionState.AwaitingUserConfirmation) {
            launchSafely(state.confirmationIntent)
        }
    }

    var resumeTick by remember { mutableIntStateOf(0) }

    // Automatically resume pending install & refresh Quick Open state when user returns from Android installer
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
                scope.launch {
                    installationManager.resumePendingInstallIfPermitted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("receive_apps_installer_section"),
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
            SectionHeader(title = stringResource(R.string.receive_apps_ready_title))

            // Active installation session feedback banner
            when (val state = installState) {
                AppInstallSessionState.Idle -> Unit
                is AppInstallSessionState.Validating -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.receive_app_install_validating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is AppInstallSessionState.Staging -> {
                    LinearProgressIndicator(
                        progress = { state.progressFraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = stringResource(
                            R.string.receive_app_install_staging,
                            (state.progressFraction * 100f).toInt().coerceIn(0, 100)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                is AppInstallSessionState.NeedsUnknownSourcesPermission -> {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Text(
                            text = stringResource(R.string.perm_install_unknown_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        PrimaryActionButton(
                            text = stringResource(R.string.perm_open_settings_action),
                            onClick = {
                                launchSafely(permissionManager.createManageUnknownSourcesIntent())
                            },
                            leadingIcon = Icons.Filled.InstallMobile,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_install_unknown_sources_button")
                        )
                    }
                }

                is AppInstallSessionState.AwaitingUserConfirmation -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.receive_app_install_confirm_prompt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { launchSafely(state.confirmationIntent) }
                        ) {
                            Text(stringResource(R.string.receive_app_install_confirm_action))
                        }
                    }
                }

                is AppInstallSessionState.Succeeded -> {
                    val launchIntent = remember(state.packageName, resumeTick) {
                        installationManager.resolveLaunchIntent(state.packageName)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = semantic.verifiedBadge,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.receive_app_install_success, state.appName),
                            style = MaterialTheme.typography.bodySmall,
                            color = semantic.verifiedBadge,
                            modifier = Modifier.weight(1f)
                        )
                        if (launchIntent != null) {
                            TextButton(
                                onClick = { launchSafely(launchIntent) },
                                modifier = Modifier.testTag("receive_install_success_quick_open")
                            ) {
                                Text(text = stringResource(R.string.receive_app_quick_open_action))
                            }
                        }
                    }
                }

                is AppInstallSessionState.Failed -> {
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.receive_app_install_failed, state.reason),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        state.fallbackApkFile?.let { fallbackApk ->
                            SecondaryActionButton(
                                text = stringResource(R.string.receive_app_install_fallback_action),
                                onClick = {
                                    installationManager.createSystemInstallerFallbackIntent(fallbackApk)
                                        ?.let(::launchSafely)
                                },
                                outlined = true,
                                leadingIcon = Icons.Filled.InstallMobile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("receive_install_fallback_button")
                            )
                        }
                    }
                }
            }

            installable.take(5).forEach { apk ->
                val apkIcon = com.example.shareflow.ui.common.rememberAppIconBitmap(apkPathOrUri = apk.filePath)
                val resolvedPackageName by produceState<String?>(
                    initialValue = apk.packageName?.takeIf { it.isNotBlank() },
                    key1 = apk.packageName,
                    key2 = apk.filePath
                ) {
                    if (value.isNullOrBlank()) {
                        value = com.example.shareflow.ui.common.AppIconBitmapCache
                            .loadPackageNameForApkPathOrUri(context, apk.filePath)
                    }
                }
                val launchIntent = remember(resolvedPackageName, installState, resumeTick) {
                    installationManager.resolveLaunchIntent(resolvedPackageName)
                }
                val isJustInstalled = installState is AppInstallSessionState.Succeeded &&
                    !resolvedPackageName.isNullOrBlank() &&
                    (installState as AppInstallSessionState.Succeeded).packageName == resolvedPackageName
                val canQuickOpen = launchIntent != null || isJustInstalled

                val cleanLabel = apk.appLabel?.takeIf { it.isNotBlank() }
                    ?: com.example.shareflow.ui.common.TransferDisplayPresentation.cleanAppDisplayName(apk.fileName)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                if (apkIcon != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = apkIcon,
                                        contentDescription = cleanLabel,
                                        modifier = Modifier.size(44.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Filled.Android,
                                        contentDescription = cleanLabel,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cleanLabel,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = listOfNotNull(
                                apk.versionName?.let { "v$it" },
                                ShareFlowFormatters.formatBytes(apk.fileSizeBytes, locale)
                            ).joinToString(" • ")
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        SecondaryActionButton(
                            text = stringResource(R.string.receive_app_install_action),
                            onClick = {
                                scope.launch {
                                    installationManager.startInstallation(apk)
                                }
                            },
                            outlined = canQuickOpen,
                            leadingIcon = Icons.Filled.InstallMobile,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("receive_install_apk_${apk.id}")
                        )
                        SecondaryActionButton(
                            text = stringResource(R.string.receive_app_quick_open_action),
                            onClick = {
                                val resolvedIntent = installationManager.resolveLaunchIntent(resolvedPackageName)
                                if (resolvedIntent != null) {
                                    launchSafely(resolvedIntent)
                                }
                            },
                            enabled = canQuickOpen,
                            outlined = !canQuickOpen,
                            leadingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("receive_quick_open_apk_${apk.id}")
                        )
                    }
                }
            }
        }
    }
}

/**
 * Receiver-side quick access card showing recently received non-APK files with a direct 1-tap
 * "View File" button that opens the built-in Custom File Viewer.
 */
@Composable
fun ReceivedFilesQuickActionCard(
    receivedFiles: List<com.example.shareflow.ui.common.TransferDisplayItem>,
    onViewFile: (com.example.shareflow.ui.common.TransferDisplayItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val viewable = receivedFiles.filter { !it.isApp && !it.localUri.isNullOrBlank() }
    if (viewable.isEmpty()) return

    val spacing = ShareFlowDesign.spacing
    val locale = ShareFlowDesign.locale

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("receive_completed_files_section"),
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
            SectionHeader(title = stringResource(R.string.receive_completed_files_title))

            viewable.take(5).forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    com.example.shareflow.ui.common.TransferItemVisualAvatar(
                        item = item,
                        size = 42.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = ShareFlowFormatters.formatBytes(item.fileSizeBytes, locale),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SecondaryActionButton(
                        text = stringResource(R.string.transfer_action_view_file),
                        onClick = { onViewFile(item) },
                        outlined = false,
                        leadingIcon = Icons.Filled.Visibility,
                        modifier = Modifier.testTag("receive_view_file_${item.key}")
                    )
                }
            }
        }
    }
}
