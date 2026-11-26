package com.example.shareflow.ui.update

import androidx.annotation.StringRes
import com.example.R
import com.example.shareflow.data.update.CloudUpdateErrorReason

@StringRes
fun cloudUpdateErrorReasonStringRes(reason: CloudUpdateErrorReason): Int = when (reason) {
    CloudUpdateErrorReason.INVALID_URL -> R.string.settings_update_err_invalid_url
    CloudUpdateErrorReason.NETWORK_ERROR -> R.string.settings_update_err_network
    CloudUpdateErrorReason.NO_INTERNET -> R.string.updates_err_no_internet
    CloudUpdateErrorReason.TIMEOUT -> R.string.updates_err_timeout
    CloudUpdateErrorReason.SSL_ERROR -> R.string.updates_err_ssl
    CloudUpdateErrorReason.RATE_LIMITED -> R.string.updates_err_rate_limited
    CloudUpdateErrorReason.SERVER_UNAVAILABLE -> R.string.updates_err_server_unavailable
    CloudUpdateErrorReason.RELEASE_NOT_FOUND -> R.string.updates_err_release_not_found
    CloudUpdateErrorReason.INVALID_MANIFEST -> R.string.settings_update_err_manifest
    CloudUpdateErrorReason.INCOMPATIBLE_DEVICE -> R.string.settings_update_err_incompatible
    CloudUpdateErrorReason.CHECKSUM_MISMATCH -> R.string.settings_update_err_checksum
    CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH -> R.string.settings_update_err_package_id
    CloudUpdateErrorReason.SIGNATURE_MISMATCH -> R.string.settings_update_err_signature
    CloudUpdateErrorReason.DOWNGRADE_BLOCKED -> R.string.settings_update_err_downgrade
    CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT -> R.string.settings_update_err_duplicate_version
    CloudUpdateErrorReason.VERSION_METADATA_MISMATCH -> R.string.settings_update_err_version_metadata_mismatch
    CloudUpdateErrorReason.INVALID_APK -> R.string.settings_update_err_invalid_apk
    CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA -> R.string.settings_update_err_unreadable_version
    CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED -> R.string.settings_update_err_download
    CloudUpdateErrorReason.INSTALL_PERMISSION_REQUIRED -> R.string.settings_feedback_update_install_perm
    CloudUpdateErrorReason.INSTALL_LAUNCH_FAILED -> R.string.settings_feedback_update_install_failed
}

/**
 * Returns `true` when displaying the technical diagnostic detail line (containing actual vs expected
 * `versionCode` / `versionName` values) helps users and maintainers immediately identify a version
 * or metadata conflict.
 */
fun shouldShowUpdateErrorDetail(reason: CloudUpdateErrorReason): Boolean = reason in setOf(
    CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
    CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
    CloudUpdateErrorReason.DOWNGRADE_BLOCKED,
    CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA
)
