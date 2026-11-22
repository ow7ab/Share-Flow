package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Reusable Material 3 dialog explaining why a runtime permission is needed prior to system prompt.
 * Supports least-privilege consent UX across hardware and network modules.
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
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
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(
                onClick = onGrantClick,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (confirmTestTag != null) it.testTag(confirmTestTag) else it }
            ) {
                Text(
                    text = grantLabel ?: stringResource(R.string.ds_permission_grant),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (dismissTestTag != null) it.testTag(dismissTestTag) else it }
            ) {
                Text(
                    text = dismissLabel ?: stringResource(R.string.ds_permission_not_now),
                    style = MaterialTheme.typography.labelLarge
                )
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = ShareFlowDesign.elevation.modal,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        icon = icon?.let {
            {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (isDestructive) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = it,
                            contentDescription = null,
                            tint = if (isDestructive) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
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
                Text(text = confirmLabel, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .let { if (dismissTestTag != null) it.testTag(dismissTestTag) else it }
            ) {
                Text(
                    text = dismissLabel ?: stringResource(R.string.action_cancel),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    )
}

/**
 * Reusable Marple Material 3 modal bottom sheet container with consistent rounded top corners,
 * warm surface container, Marple drag handle, and accessible scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFlowModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = ShareFlowDesign.shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = ShareFlowDesign.semanticColors.scrim,
        tonalElevation = ShareFlowDesign.elevation.modal,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant
            )
        },
        content = content
    )
}
