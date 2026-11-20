package com.example.shareflow.core.permissions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

internal data class PendingPermissionRequest(
    val feature: ShareFlowPermissionFeature,
    val status: FeaturePermissionStatus,
    val onGranted: () -> Unit,
    val onDenied: () -> Unit
)

/**
 * In-flow Compose coordinator for Smart Permission UX (Phase 9).
 *
 * - Never requests permissions on app launch.
 * - Explains clearly why the permission is needed for the user's current action.
 * - Requests only the exact permission required for the tapped workflow.
 * - Automatically resumes the pending action (`onGranted`) upon approval or return from Settings.
 * - Gracefully guides to App Settings when denied multiple times without trapping the user.
 */
class PermissionRequestCoordinator internal constructor(
    private val permissionManager: PermissionManager,
    private val launchRuntimePermissions: (Array<String>) -> Unit,
    private val onShowRationale: (PendingPermissionRequest) -> Unit,
    private val onShowSettingsFallback: (PendingPermissionRequest) -> Unit,
    private val onRegisterPendingResume: (PendingPermissionRequest) -> Unit
) {
    /**
     * Checks permission readiness for [feature]. If ready, runs [onGranted] immediately;
     * otherwise launches the guided rationale + permission request flow and automatically
     * resumes [onGranted] when approved.
     */
    fun ensureFeatureReady(
        feature: ShareFlowPermissionFeature,
        requireStrictRuntimeOnly: Boolean = true,
        onDenied: () -> Unit = {},
        onGranted: () -> Unit
    ) {
        val status = permissionManager.evaluateFeature(feature)
        val ready = if (requireStrictRuntimeOnly) {
            status.missingRuntimePermissions.isEmpty() && !status.requiresUnknownSourcesSetting
        } else {
            status.isReady
        }
        if (ready) {
            onGranted()
            return
        }

        val pending = PendingPermissionRequest(
            feature = feature,
            status = status,
            onGranted = onGranted,
            onDenied = onDenied
        )
        onRegisterPendingResume(pending)

        if (status.requiresUnknownSourcesSetting ||
            (status.missingRuntimePermissions.isNotEmpty() && !status.canAskRuntimePermissionAgain)
        ) {
            onShowSettingsFallback(pending)
        } else {
            onShowRationale(pending)
        }
    }

    internal fun launchDirectRuntimeRequest(request: PendingPermissionRequest) {
        if (request.status.missingRuntimePermissions.isNotEmpty()) {
            launchRuntimePermissions(request.status.missingRuntimePermissions.toTypedArray())
        } else {
            onShowSettingsFallback(request)
        }
    }
}

@Composable
fun rememberPermissionRequestCoordinator(
    permissionManager: PermissionManager
): PermissionRequestCoordinator {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var rationaleDialogRequest by remember { mutableStateOf<PendingPermissionRequest?>(null) }
    var settingsDialogRequest by remember { mutableStateOf<PendingPermissionRequest?>(null) }
    var activePendingRequest by remember { mutableStateOf<PendingPermissionRequest?>(null) }
    var waitingForSettingsReturn by remember { mutableStateOf(false) }

    val runtimeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val pending = activePendingRequest ?: return@rememberLauncherForActivityResult
        val allGranted = grants.isNotEmpty() && grants.values.all { it }
        permissionManager.recordPermissionResult(pending.feature, allGranted)
        if (allGranted || permissionManager.missingRuntimePermissions(pending.feature).isEmpty()) {
            activePendingRequest = null
            pending.onGranted()
        } else {
            val updatedStatus = permissionManager.evaluateFeature(pending.feature)
            if (!updatedStatus.canAskRuntimePermissionAgain) {
                settingsDialogRequest = pending.copy(status = updatedStatus)
            } else {
                activePendingRequest = null
                pending.onDenied()
            }
        }
    }

    // Resume automatically when the user returns from Android Settings after enabling permission
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForSettingsReturn) {
                waitingForSettingsReturn = false
                val pending = activePendingRequest ?: return@LifecycleEventObserver
                val refreshed = permissionManager.evaluateFeature(pending.feature)
                if (refreshed.missingRuntimePermissions.isEmpty() && !refreshed.requiresUnknownSourcesSetting) {
                    permissionManager.recordPermissionResult(pending.feature, true)
                    activePendingRequest = null
                    pending.onGranted()
                } else {
                    activePendingRequest = null
                    pending.onDenied()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val coordinator = remember(permissionManager) {
        PermissionRequestCoordinator(
            permissionManager = permissionManager,
            launchRuntimePermissions = { perms -> runtimeLauncher.launch(perms) },
            onShowRationale = { req -> rationaleDialogRequest = req },
            onShowSettingsFallback = { req -> settingsDialogRequest = req },
            onRegisterPendingResume = { req -> activePendingRequest = req }
        )
    }

    rationaleDialogRequest?.let { req ->
        val (icon, titleRes, bodyRes) = permissionCopyFor(req.feature)
        AlertDialog(
            onDismissRequest = {
                rationaleDialogRequest = null
                activePendingRequest = null
                req.onDenied()
            },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            title = { Text(text = stringResource(titleRes)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ShareFlowDesign.spacing.xs)
                ) {
                    Text(
                        text = stringResource(bodyRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        rationaleDialogRequest = null
                        coordinator.launchDirectRuntimeRequest(req)
                    },
                    modifier = Modifier.testTag("permission_rationale_allow_button")
                ) {
                    Text(text = stringResource(R.string.perm_allow_continue_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        rationaleDialogRequest = null
                        activePendingRequest = null
                        req.onDenied()
                    },
                    modifier = Modifier.testTag("permission_rationale_cancel_button")
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    settingsDialogRequest?.let { req ->
        val (icon, titleRes, bodyRes) = permissionCopyFor(req.feature)
        AlertDialog(
            onDismissRequest = {
                settingsDialogRequest = null
                activePendingRequest = null
                req.onDenied()
            },
            icon = { Icon(imageVector = icon, contentDescription = null) },
            title = { Text(text = stringResource(titleRes)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(ShareFlowDesign.spacing.xs)
                ) {
                    Text(
                        text = stringResource(bodyRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.perm_settings_resume_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        settingsDialogRequest = null
                        waitingForSettingsReturn = true
                        val intent = if (req.status.requiresUnknownSourcesSetting) {
                            permissionManager.createManageUnknownSourcesIntent()
                        } else {
                            permissionManager.createAppSettingsIntent()
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                waitingForSettingsReturn = false
                                activePendingRequest = null
                                req.onDenied()
                            }
                    },
                    modifier = Modifier.testTag("permission_open_settings_button")
                ) {
                    Text(text = stringResource(R.string.perm_open_settings_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        settingsDialogRequest = null
                        activePendingRequest = null
                        req.onDenied()
                    },
                    modifier = Modifier.testTag("permission_settings_cancel_button")
                ) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        )
    }

    return coordinator
}

private fun permissionCopyFor(feature: ShareFlowPermissionFeature): Triple<ImageVector, Int, Int> =
    when (feature) {
        ShareFlowPermissionFeature.RECEIVE_WIFI_HOTSPOT -> Triple(
            Icons.Filled.Wifi,
            R.string.perm_wifi_receive_title,
            R.string.perm_wifi_receive_body
        )

        ShareFlowPermissionFeature.SEND_WIFI_NEARBY -> Triple(
            Icons.Filled.Wifi,
            R.string.perm_wifi_send_title,
            R.string.perm_wifi_send_body
        )

        ShareFlowPermissionFeature.QR_CAMERA -> Triple(
            Icons.Filled.CameraAlt,
            R.string.perm_camera_qr_title,
            R.string.perm_camera_qr_body
        )

        ShareFlowPermissionFeature.APP_INSTALL -> Triple(
            Icons.Filled.InstallMobile,
            R.string.perm_install_unknown_title,
            R.string.perm_install_unknown_body
        )

        ShareFlowPermissionFeature.NOTIFICATIONS -> Triple(
            Icons.Filled.Notifications,
            R.string.perm_notifications_title,
            R.string.perm_notifications_body
        )

        ShareFlowPermissionFeature.PHOTOS_MEDIA,
        ShareFlowPermissionFeature.VIDEOS_MEDIA,
        ShareFlowPermissionFeature.APP_DISCOVERY -> Triple(
            Icons.Filled.Security,
            R.string.perm_wifi_receive_title,
            R.string.perm_wifi_receive_body
        )
    }
