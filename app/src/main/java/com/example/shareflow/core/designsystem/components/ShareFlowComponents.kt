package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle

/**
 * Centralized Share Flow 2.0 styling for OutlinedTextFields across all screens.
 */
object ShareFlowInputDefaults {
    @Composable
    fun outlinedTextFieldColors(): TextFieldColors {
        val glass = ShareFlowDesign.glass
        return OutlinedTextFieldDefaults.colors(
            focusedContainerColor = glass.cardElevatedBackground,
            unfocusedContainerColor = glass.cardBackground,
            disabledContainerColor = glass.cardSubtleBackground,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = glass.borderMiddle,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            errorBorderColor = MaterialTheme.colorScheme.error,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTrailingIconColor = MaterialTheme.colorScheme.secondary,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        )
    }
}

/**
 * Centralized Share Flow 2.0 styling for FilterChips and AssistChips across all screens.
 */
object ShareFlowChipDefaults {
    @Composable
    fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        containerColor = ShareFlowDesign.glass.cardBackground,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
        selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
        disabledContainerColor = ShareFlowDesign.semanticColors.disabled.copy(alpha = 0.5f),
        disabledLabelColor = ShareFlowDesign.semanticColors.onDisabled
    )

    @Composable
    fun filterChipBorder(
        enabled: Boolean = true,
        selected: Boolean
    ): BorderStroke = FilterChipDefaults.filterChipBorder(
        enabled = enabled,
        selected = selected,
        borderColor = ShareFlowDesign.glass.borderMiddle,
        selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        borderWidth = 1.dp,
        selectedBorderWidth = 1.25.dp
    )

    @Composable
    fun assistChipColors(supportingPeach: Boolean = false): ChipColors = AssistChipDefaults.assistChipColors(
        containerColor = if (supportingPeach) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.76f)
        } else {
            ShareFlowDesign.glass.cardBackground
        },
        labelColor = if (supportingPeach) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        leadingIconContentColor = if (supportingPeach) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        trailingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledContainerColor = ShareFlowDesign.semanticColors.disabled.copy(alpha = 0.5f),
        disabledLabelColor = ShareFlowDesign.semanticColors.onDisabled
    )

    @Composable
    fun assistChipBorder(
        enabled: Boolean = true,
        supportingPeach: Boolean = false
    ): BorderStroke = AssistChipDefaults.assistChipBorder(
        enabled = enabled,
        borderColor = if (supportingPeach) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.48f)
        } else {
            ShareFlowDesign.glass.borderMiddle
        },
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        borderWidth = 1.dp
    )
}

@Composable
fun ShareFlowTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    SFTopBar(
        title = title,
        modifier = modifier,
        onNavigateBack = onNavigateBack,
        actions = { actions() }
    )
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    SFSectionHeader(
        title = title,
        subtitle = subtitle,
        modifier = modifier
    )
}

@Composable
fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {},
        shape = ShareFlowDesign.shapes.badge,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun MetricStatCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    val spacing = ShareFlowDesign.spacing
    SFGlassCard(
        modifier = modifier.let { if (testTag != null) it.testTag(testTag) else it },
        contentPadding = PaddingValues(spacing.sm)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(ShareFlowDesign.shapes.badge)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = value,
                style = ShareFlowMonoStyle.copy(
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    lineHeight = MaterialTheme.typography.titleLarge.lineHeight
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ModuleDirectoryCard(
    title: String,
    description: String,
    icon: ImageVector,
    statusLabel: String,
    isReady: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    SFGlassCard(
        onClick = onClick,
        contentPadding = PaddingValues(spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(ShareFlowDesign.shapes.iconContainer)
                    .background(
                        if (isReady) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        }
                    )
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isReady) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.xs)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    StatusPill(
                        label = statusLabel,
                        containerColor = if (isReady) {
                            semantic.verifiedContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (isReady) {
                            semantic.verifiedBadge
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(spacing.xs))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    testTag: String? = null
) {
    EmptyState(
        icon = icon,
        title = title,
        body = body,
        modifier = modifier.let { if (testTag != null) it.testTag(testTag) else it }
    )
}
