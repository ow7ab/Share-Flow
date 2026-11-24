package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.example.R

/**
 * Permission explanation dialog delegating to Share Flow 2.0 [SFDialog].
 */
@Composable
fun PermissionExplanationDialog(
    title: String,
    rationale: String,
    onGrantClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Security,
    grantLabel: String? = null,
    dismissLabel: String? = null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null
) {
    SFDialog(
        onDismissRequest = onDismiss,
        title = title,
        body = rationale,
        icon = icon,
        confirmLabel = grantLabel ?: stringResource(R.string.ds_permission_grant),
        onConfirm = onGrantClick,
        dismissLabel = dismissLabel ?: stringResource(R.string.ds_permission_not_now),
        onDismiss = onDismiss,
        confirmTestTag = confirmTestTag,
        dismissTestTag = dismissTestTag,
        modifier = modifier
    )
}

/**
 * Confirmation dialog delegating to Share Flow 2.0 [SFDialog].
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
    icon: ImageVector? = if (isDestructive) Icons.Outlined.WarningAmber else null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null
) {
    SFDialog(
        onDismissRequest = onDismiss,
        title = title,
        body = body,
        icon = icon,
        isDestructive = isDestructive,
        confirmLabel = confirmLabel,
        onConfirm = onConfirm,
        dismissLabel = dismissLabel ?: stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
        confirmTestTag = confirmTestTag,
        dismissTestTag = dismissTestTag,
        modifier = modifier
    )
}

/**
 * Modal bottom sheet delegating to Share Flow 2.0 [SFBottomSheet].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFlowModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    SFBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        content = content
    )
}
