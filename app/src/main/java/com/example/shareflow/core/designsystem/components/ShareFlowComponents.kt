package com.example.shareflow.core.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMonoStyle

/**
 * Centralized Marple Material 3 styling for OutlinedTextFields across all ShareFlow screens.
 * Uses Coral/Ruby interactive focus borders and clean Light/Dark surface containers.
 */
object ShareFlowInputDefaults {
    @Composable
    fun outlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        focusedBorderColor = MaterialTheme.colorScheme.secondary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
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

/**
 * Centralized Marple Material 3 styling for FilterChips and AssistChips across all ShareFlow screens.
 * - Selected FilterChips use Coral interactive highlight containers and borders.
 * - AssistChips use subtle warm surface containers with Coral secondary iconography.
 */
object ShareFlowChipDefaults {
    @Composable
    fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        selectedBorderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.78f),
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        borderWidth = 1.dp,
        selectedBorderWidth = 1.25.dp
    )

    @Composable
    fun assistChipColors(supportingPeach: Boolean = false): ChipColors = AssistChipDefaults.assistChipColors(
        containerColor = if (supportingPeach) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        labelColor = if (supportingPeach) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        leadingIconContentColor = if (supportingPeach) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.secondary
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
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.52f)
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        disabledBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        borderWidth = 1.dp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareFlowTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("top_bar_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back)
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = ShareFlowDesign.shapes.badge,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
    Surface(
        modifier = modifier.let { if (testTag != null) it.testTag(testTag) else it },
        shape = ShareFlowDesign.shapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = ShareFlowDesign.elevation.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Surface(
                shape = ShareFlowDesign.shapes.badge,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = ShareFlowDesign.shapes.card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = ShareFlowDesign.elevation.card),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (isReady) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
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
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
