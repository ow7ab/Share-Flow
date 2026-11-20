package com.example.shareflow.core.designsystem.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Reusable Material 3 dialog explaining why a runtime permission is needed prior to system prompt.
 * Supports least-privilege consent UX across upcoming hardware/network modules.
 */
@Composable
fun PermissionExplanationDialog(
    title: String,
    rationale: String,
    onGrantClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Security,
    grantLabel: String? = null,
    dismissLabel: String? = null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = ShareFlowDesign.shapes.dialog,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onGrantClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (confirmTestTag != null) it.testTag(confirmTestTag) else it }
            ) {
                Text(text = grantLabel ?: stringResource(R.string.ds_permission_grant))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (dismissTestTag != null) it.testTag(dismissTestTag) else it }
            ) {
                Text(text = dismissLabel ?: stringResource(R.string.ds_permission_not_now))
            }
        }
    )
}

/**
 * Reusable Material 3 confirmation dialog for preventing accidental destructive or critical actions.
 */
@Composable
fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    isDestructive: Boolean = false,
    icon: ImageVector? = if (isDestructive) Icons.Filled.WarningAmber else null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = ShareFlowDesign.shapes.dialog,
        icon = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (isDestructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (confirmTestTag != null) it.testTag(confirmTestTag) else it }
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (dismissTestTag != null) it.testTag(dismissTestTag) else it }
            ) {
                Text(text = dismissLabel ?: stringResource(R.string.action_cancel))
            }
        }
    )
}
