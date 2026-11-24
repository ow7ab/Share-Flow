package com.example.shareflow.ui.update

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
 * Material 3 dialog triggered when [com.example.shareflow.data.update.UpdateManager] detects a newer
 * ShareFlow release. Displays `versionName`, `releaseNotes`, and a primary "Download Now" button
 * that starts the verified APK download task.
 *
 * Strictly non-dismissible (back press & outside taps disabled, "Later" hidden) when
 * [UpdateDetails.mandatory] is `true`.
 */
@Composable
fun UpdateAvailableDialog(
    update: UpdateDetails,
    downloadState: CloudUpdateState = CloudUpdateState.Idle,
    onDownloadNow: (UpdateDetails) -> Unit,
    onInstallVerifiedApk: (String) -> Unit = {},
    onCancelDownload: () -> Unit = {},
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
            usePlatformDefaultWidth = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
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
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                // Header with new ShareFlow logo + title + versionName badge
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
                            .heightIn(max = 180.dp),
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

                // Live Download / Verification / Ready / Error Controls
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
                            if (!update.mandatory) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.settings_update_cancel_download_action),
                                    onClick = onCancelDownload,
                                    outlined = true,
                                    isDestructive = true,
                                    leadingIcon = Icons.Filled.Close,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("update_dialog_cancel_download_button")
                                )
                            }
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
                                    text = downloadState.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            PrimaryActionButton(
                                text = stringResource(R.string.update_dialog_download_now),
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
                            text = stringResource(R.string.update_dialog_download_now),
                            onClick = { onDownloadNow(update) },
                            leadingIcon = Icons.Filled.CloudDownload,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("update_dialog_download_button")
                        )
                    }
                }

                // Dismiss button is ONLY shown when mandatory == false
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
