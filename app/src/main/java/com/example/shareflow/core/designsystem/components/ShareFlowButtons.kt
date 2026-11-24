package com.example.shareflow.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Primary action button delegating directly to Share Flow 2.0 [SFPrimaryButton].
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null,
    useSignatureBorder: Boolean = true
) {
    SFPrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        isLoading = isLoading,
        leadingIcon = leadingIcon
    )
}

/**
 * Secondary action button delegating directly to Share Flow 2.0 [SFSecondaryButton].
 */
@Composable
fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = true,
    isDestructive: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    SFSecondaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        outlined = outlined,
        isDestructive = isDestructive,
        leadingIcon = leadingIcon
    )
}
