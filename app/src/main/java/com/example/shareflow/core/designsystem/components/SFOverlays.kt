package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign

/**
 * Share Flow 2.0 Frosted Liquid Glass Dialog (`SFDialog`).
 * Replaces default flat Material 3 AlertDialog styling with a layered translucent glass modal,
 * specular rim border, icon badge, and crisp high-contrast hierarchy in Light & Deep Navy Dark modes.
 */
@Composable
fun SFDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isDestructive: Boolean = false,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    body: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val glass = ShareFlowDesign.glass
    val spacing = ShareFlowDesign.spacing
    val shapes = ShareFlowDesign.shapes

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside
        )
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .shadow(
                    elevation = ShareFlowDesign.elevation.modal,
                    shape = shapes.dialog,
                    clip = false,
                    ambientColor = glass.ambientShadow,
                    spotColor = glass.spotShadow
                )
                .clip(shapes.dialog)
                .background(glass.overlayBackground)
                .border(
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(glass.borderTop, glass.borderMiddle, glass.borderBottom)
                        )
                    ),
                    shape = shapes.dialog
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                if (icon != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(shapes.iconContainer)
                            .background(
                                if (isDestructive) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            )
                    ) {
                        Icon(
                            imageVector = icon,
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

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (content != null) {
                    content()
                }

                if (confirmLabel != null || dismissLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (dismissLabel != null && onDismiss != null) {
                            TextButton(
                                onClick = onDismiss,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .let { if (dismissTestTag != null) it.testTag(dismissTestTag) else it }
                            ) {
                                Text(
                                    text = dismissLabel,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            TextButton(
                                onClick = onConfirm,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isDestructive) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                ),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .let { if (confirmTestTag != null) it.testTag(confirmTestTag) else it }
                            ) {
                                Text(
                                    text = confirmLabel,
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Share Flow 2.0 Frosted Liquid Glass Modal Bottom Sheet (`SFBottomSheet`).
 * Refined pill drag handle, smooth 30dp top corners, and consistent Light / Deep Navy Dark treatment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SFBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = ShareFlowDesign.glass
    val shapes = ShareFlowDesign.shapes

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = glass.overlayBackground,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = ShareFlowDesign.semanticColors.scrim,
        tonalElevation = ShareFlowDesign.elevation.flat,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(42.dp)
                    .size(width = 42.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.50f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            content = content
        )
    }
}

/**
 * Share Flow 2.0 Floating Liquid Glass Snackbar (`SFSnackbar`).
 * High-contrast dark frosted pill with subtle brand accent indicator.
 */
@Composable
fun SFSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier
) {
    SFSnackbarSurface(
        message = snackbarData.visuals.message,
        actionLabel = snackbarData.visuals.actionLabel,
        onAction = { snackbarData.performAction() },
        modifier = modifier
    )
}

@Composable
fun SFSnackbarSurface(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val glass = ShareFlowDesign.glass
    val shapes = ShareFlowDesign.shapes

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = shapes.snackbar,
        color = glass.snackbarBackground,
        contentColor = glass.snackbarContent,
        shadowElevation = 10.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    glass.borderTop.copy(alpha = 0.40f),
                    glass.borderBottom.copy(alpha = 0.20f)
                )
            )
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 22.dp)
                    .clip(CircleShape)
                    .background(ShareFlowBrandGradients.primaryButtonGradient())
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = glass.snackbarContent,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = ShareFlowDesign.semanticColors.cream
                    ),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}
