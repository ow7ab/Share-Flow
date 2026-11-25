package com.example.shareflow.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.UpdateDetails
import kotlin.math.roundToInt

/**
 * Share Flow Update Details Dialog shown automatically on foreground FCM release pushes,
 * mandatory releases, or tapping the top update banner.
 *
 * Displays Share Flow logo, current -> new version, publish date, APK size, release notes,
 * "Update Now" (with live progress + Pause/Resume), "View Update Details", and "Later" (snooze).
 */
@Composable
fun UpdateAvailableDialog(
    update: UpdateDetails,
    downloadState: CloudUpdateState = CloudUpdateState.Idle,
    onDownloadNow: (UpdateDetails) -> Unit,
    onPauseDownload: () -> Unit = {},
    onInstallVerifiedApk: (String) -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onViewDetails: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val glass = ShareFlowDesign.glass
    val locale = ShareFlowDesign.locale
    val isArabicLocale = locale.language.equals("ar", ignoreCase = true)
    val releaseNotesText = update.localizedReleaseNotes(isArabicLocale)
        .ifBlank { stringResource(R.string.update_dialog_default_notes) }

    val isDownloadingOrVerifying = downloadState is CloudUpdateState.Downloading ||
        downloadState is CloudUpdateState.Verifying
    val canDismiss = !update.mandatory && !isDownloadingOrVerifying

    Dialog(
        onDismissRequest = {
            if (canDismiss) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss,
            usePlatformDefaultWidth = true,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .testTag("update_available_dialog"),
            shape = ShareFlowDesign.shapes.dialog,
            color = glass.overlayBackground,
            border = BorderStroke(1.dp, glass.borderTop),
            shadowElevation = ShareFlowDesign.elevation.modal,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.sm)
            ) {
                // Header with Share Flow logo + title + version transition + metadata
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                        contentDescription = stringResource(R.string.cd_app_logo),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(52.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_dialog_title),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            StatusPill(
                                label = "v${update.versionName}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.testTag("update_dialog_version_name")
                            )
                            if (update.fileSizeBytes > 0L) {
                                Text(
                                    text = ShareFlowFormatters.formatBytes(update.fileSizeBytes, locale),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Current -> New version + Published Date
                Text(
                    text = stringResource(
                        R.string.updates_version_transition_format,
                        BuildConfig.VERSION_NAME,
                        update.versionName
                    ),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("update_dialog_version_transition")
                )
                if (update.publishedAt.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.updates_published_date_format,
                            update.publishedAt.take(10)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("update_dialog_published_date")
                    )
                }

                if (update.mandatory) {
                    StatusPill(
                        label = stringResource(R.string.update_dialog_mandatory_badge),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.testTag("update_dialog_mandatory_badge")
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Release Notes container
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.settings_update_release_notes_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 170.dp),
                        shape = ShareFlowDesign.shapes.input,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Text(
                            text = releaseNotesText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(spacing.sm)
                                .testTag("update_dialog_release_notes")
                        )
                    }
                }

                // Live Download / Paused / Verification / Ready / Error Controls
                when (downloadState) {
                    is CloudUpdateState.Downloading -> {
                        val percent = (downloadState.progress * 100f).roundToInt().coerceIn(0, 100)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_downloading_section"),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_update_downloading_format,
                                    percent,
                                    ShareFlowFormatters.formatBytes(downloadState.downloadedBytes, locale)
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (downloadState.totalBytes > 0L) {
                                ShareFlowSignatureProgressBar(
                                    progress = downloadState.progress,
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 8.dp
                                )
                            } else {
                                ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.updates_pause_download_action),
                                    onClick = onPauseDownload,
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Pause,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("update_dialog_pause_download_button")
                                )
                                if (!update.mandatory) {
                                    SecondaryActionButton(
                                        text = stringResource(R.string.settings_update_cancel_download_action),
                                        onClick = onCancelDownload,
                                        outlined = true,
                                        isDestructive = true,
                                        leadingIcon = Icons.Filled.Close,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("update_dialog_cancel_download_button")
                                    )
                                }
                            }
                        }
                    }

                    is CloudUpdateState.Paused -> {
                        val percent = (downloadState.progress * 100f).roundToInt().coerceIn(0, 100)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_paused_section"),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.updates_paused_progress_format,
                                    percent,
                                    ShareFlowFormatters.formatBytes(downloadState.downloadedBytes, locale),
                                    ShareFlowFormatters.formatBytes(
                                        downloadState.totalBytes.coerceAtLeast(downloadState.downloadedBytes),
                                        locale
                                    )
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ShareFlowSignatureProgressBar(
                                progress = downloadState.progress,
                                modifier = Modifier.fillMaxWidth(),
                                height = 8.dp
                            )
                            PrimaryActionButton(
                                text = stringResource(R.string.updates_resume_download_action),
                                onClick = { onDownloadNow(update) },
                                leadingIcon = Icons.Filled.PlayArrow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_dialog_resume_button")
                            )
                        }
                    }

                    is CloudUpdateState.Verifying -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_verifying_row"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.settings_update_verifying_status),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    is CloudUpdateState.ReadyToInstall -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_ready_section"),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.VerifiedUser,
                                    contentDescription = null,
                                    tint = semantic.verifiedBadge,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.settings_update_ready_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = semantic.verifiedBadge
                                )
                            }
                            PrimaryActionButton(
                                text = stringResource(R.string.settings_update_install_action),
                                onClick = { onInstallVerifiedApk(downloadState.verifiedApkPath) },
                                leadingIcon = Icons.Filled.InstallMobile,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_dialog_install_button")
                            )
                        }
                    }

                    is CloudUpdateState.Error -> {
                        val localizedErr = stringResource(cloudUpdateErrorReasonStringRes(downloadState.reason))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_error_section"),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
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
                                    text = localizedErr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            PrimaryActionButton(
                                text = stringResource(R.string.updates_dialog_update_now_action),
                                onClick = { onDownloadNow(update) },
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_dialog_download_button")
                            )
                        }
                    }

                    else -> {
                        PrimaryActionButton(
                            text = stringResource(R.string.updates_dialog_update_now_action),
                            onClick = { onDownloadNow(update) },
                            leadingIcon = Icons.Filled.CloudDownload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_download_button")
                        )
                    }
                }

                // "View Update Details" button opens the dedicated Updates Center page
                if (onViewDetails != null && !isDownloadingOrVerifying) {
                    SecondaryActionButton(
                        text = stringResource(R.string.updates_dialog_view_details_action),
                        onClick = onViewDetails,
                        outlined = true,
                        leadingIcon = Icons.Filled.Info,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update_dialog_view_details_button")
                    )
                }

                // Dismiss ("Later") button is ONLY shown when mandatory == false
                if (!update.mandatory && !isDownloadingOrVerifying) {
                    SecondaryActionButton(
                        text = stringResource(R.string.update_dialog_later_action),
                        onClick = onDismiss,
                        outlined = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("update_dialog_later_button")
                    )
                }
            }
        }
    }
}
