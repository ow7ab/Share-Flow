package com.example.shareflow.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.shareflow.core.common.ShareFlowFormatters
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.components.SecondaryActionButton
import com.example.shareflow.core.designsystem.components.ShareFlowSignatureProgressBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle
import com.example.shareflow.data.update.CloudUpdateErrorReason
import com.example.shareflow.data.update.CloudUpdateManager
import com.example.shareflow.data.update.CloudUpdateManifest
import com.example.shareflow.data.update.CloudUpdateState
import com.example.shareflow.data.update.CloudUpdateValidator
import kotlin.math.roundToInt

/**
 * Dedicated Settings section connected directly to the official ShareFlow HTTPS update website
 * for one-tap version checking, SHA-256 verification, and Android system installer handoff.
 */
@Composable
fun CloudUpdateSettingsCard(
    updateState: CloudUpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (CloudUpdateManifest) -> Unit,
    onCancelDownload: () -> Unit,
    onInstallVerifiedUpdate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val locale = ShareFlowDesign.locale
    val isArabicLocale = locale.language.equals("ar", ignoreCase = true)
    val officialPortalUrl = BuildConfig.CLOUD_UPDATE_PORTAL_URL
        .ifBlank { CloudUpdateManager.OFFICIAL_PORTAL_URL }

    fun openSafeHttpsWebsite(url: String?) {
        val candidate = url?.trim().orEmpty()
        if (CloudUpdateValidator.isValidPublicHttpsUrl(candidate)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(candidate))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_section_cloud_update"),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_section_cloud_update),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }

            StatusPill(
                label = stringResource(R.string.settings_update_sudan_portal_badge),
                containerColor = semantic.verifiedContainer,
                contentColor = semantic.verifiedBadge,
                modifier = Modifier.testTag("settings_update_sudan_badge")
            )

            Text(
                text = stringResource(R.string.settings_update_sudan_portal_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_update_current_version_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.settings_about_version_format,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE.toString()
                    ),
                    style = ShareFlowMonoStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("settings_update_current_version")
                )
            }

            // Direct Official ShareFlow Update Website source indicator (no manual URL entry required)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_update_official_source"),
                shape = ShareFlowDesign.shapes.input,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.sm, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = semantic.verifiedBadge,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_update_official_source_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = CloudUpdateManager.OFFICIAL_PORTAL_HOST,
                            style = ShareFlowMonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
                    .testTag("settings_check_updates_button")
            )

            SecondaryActionButton(
                text = stringResource(R.string.settings_update_open_portal_action),
                onClick = { openSafeHttpsWebsite(officialPortalUrl) },
                outlined = true,
                leadingIcon = Icons.Filled.Language,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_open_official_website_button")
            )

            // Dynamic Cloud Update Status Panel
            when (updateState) {
                CloudUpdateState.Idle -> Unit

                CloudUpdateState.Checking -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_checking_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_update_checking_status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                CloudUpdateState.PortalNotConfigured -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_not_configured_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
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

                is CloudUpdateState.UpToDate -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_up_to_date_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = semantic.verifiedContainer.copy(alpha = 0.55f),
                        border = BorderStroke(1.dp, semantic.verifiedBadge.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Row(
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
                                    text = stringResource(R.string.settings_update_up_to_date_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(
                                    R.string.settings_update_up_to_date_body,
                                    updateState.currentVersionName,
                                    updateState.currentVersionCode.toString()
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!updateState.portalUrl.isNullOrBlank()) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.settings_update_open_portal_action),
                                    onClick = { openSafeHttpsWebsite(updateState.portalUrl) },
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Language,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_open_portal_button")
                                )
                            }
                        }
                    }
                }

                is CloudUpdateState.UpdateAvailable -> {
                    val manifest = updateState.manifest
                    val releaseNotes = if (isArabicLocale) {
                        manifest.releaseNotesAr.ifBlank { manifest.releaseNotesEn }
                    } else {
                        manifest.releaseNotesEn.ifBlank { manifest.releaseNotesAr }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_available_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_update_available_title,
                                    manifest.versionName,
                                    manifest.versionCode.toString()
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (manifest.fileSizeBytes > 0L) {
                                Text(
                                    text = ShareFlowFormatters.formatBytes(manifest.fileSizeBytes, locale),
                                    style = ShareFlowMonoStyle,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (releaseNotes.isNotBlank()) {
                                Text(
                                    text = stringResource(R.string.settings_update_release_notes_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = releaseNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            PrimaryActionButton(
                                text = stringResource(R.string.settings_update_download_action),
                                onClick = { onDownloadUpdate(manifest) },
                                leadingIcon = Icons.Filled.CloudDownload,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_download_update_button")
                            )
                            if (!manifest.portalUrl.isNullOrBlank()) {
                                SecondaryActionButton(
                                    text = stringResource(R.string.settings_update_open_portal_action),
                                    onClick = { openSafeHttpsWebsite(manifest.portalUrl) },
                                    outlined = true,
                                    leadingIcon = Icons.Filled.Language,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("settings_open_portal_button")
                                )
                            }
                        }
                    }
                }

                is CloudUpdateState.Downloading -> {
                    val percent = (updateState.progress * 100f).roundToInt().coerceIn(0, 100)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_downloading_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.xs)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.settings_update_downloading_format,
                                    percent,
                                    ShareFlowFormatters.formatBytes(updateState.downloadedBytes, locale)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (updateState.totalBytes > 0L) {
                                ShareFlowSignatureProgressBar(
                                    progress = updateState.progress,
                                    modifier = Modifier.fillMaxWidth(),
                                    height = 8.dp
                                )
                            } else {
                                ShareFlowSignatureProgressBar(modifier = Modifier.fillMaxWidth())
                            }
                            SecondaryActionButton(
                                text = stringResource(R.string.settings_update_cancel_download_action),
                                onClick = onCancelDownload,
                                outlined = true,
                                isDestructive = true,
                                leadingIcon = Icons.Filled.Close,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_cancel_update_download_button")
                            )
                        }
                    }
                }

                is CloudUpdateState.Verifying -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_verifying_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_update_verifying_status),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is CloudUpdateState.ReadyToInstall -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_ready_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = semantic.verifiedContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.5.dp, semantic.verifiedBadge.copy(alpha = 0.65f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
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
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(R.string.settings_update_ready_title),
                                    style = MaterialTheme.typography.titleSmall,
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
                                    .testTag("settings_install_update_button")
                            )
                        }
                    }
                }

                is CloudUpdateState.Error -> {
                    val localizedMsg = stringResource(cloudUpdateErrorStringRes(updateState.reason))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_update_error_card"),
                        shape = ShareFlowDesign.shapes.card,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.sm),
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
                                    text = stringResource(R.string.settings_update_error_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            Text(
                                text = localizedMsg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            SecondaryActionButton(
                                text = stringResource(R.string.settings_update_retry_action),
                                onClick = onCheckForUpdates,
                                outlined = true,
                                leadingIcon = Icons.Filled.Refresh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_update_retry_button")
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun cloudUpdateErrorStringRes(reason: CloudUpdateErrorReason): Int = when (reason) {
    CloudUpdateErrorReason.INVALID_URL -> R.string.settings_update_err_invalid_url
    CloudUpdateErrorReason.NETWORK_ERROR -> R.string.settings_update_err_network
    CloudUpdateErrorReason.INVALID_MANIFEST -> R.string.settings_update_err_manifest
    CloudUpdateErrorReason.INCOMPATIBLE_DEVICE -> R.string.settings_update_err_incompatible
    CloudUpdateErrorReason.CHECKSUM_MISMATCH -> R.string.settings_update_err_checksum
    CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH -> R.string.settings_update_err_package_id
    CloudUpdateErrorReason.SIGNATURE_MISMATCH -> R.string.settings_update_err_signature
    CloudUpdateErrorReason.DOWNGRADE_BLOCKED -> R.string.settings_update_err_downgrade
    CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED -> R.string.settings_update_err_download
    CloudUpdateErrorReason.INSTALL_PERMISSION_REQUIRED -> R.string.settings_feedback_update_install_perm
    CloudUpdateErrorReason.INSTALL_LAUNCH_FAILED -> R.string.settings_feedback_update_install_failed
}
