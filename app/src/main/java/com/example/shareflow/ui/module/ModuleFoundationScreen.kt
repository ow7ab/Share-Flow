package com.example.shareflow.ui.module

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.components.ShareFlowTopBar
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.domain.model.ShareFlowModuleId
import com.example.shareflow.navigation.ShareFlowModuleCatalog

/**
 * Honest architectural status screen for scaffolded ShareFlow modules prior to feature implementation.
 * Strictly avoids simulated transfers, fake devices, or non-functional action controls.
 */
@Composable
fun ModuleFoundationScreen(
    moduleId: ShareFlowModuleId,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entry = ShareFlowModuleCatalog.findById(moduleId)
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors
    val title = entry?.let { stringResource(it.titleRes) } ?: moduleId.name

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            ShareFlowTopBar(
                title = title,
                onNavigateBack = onNavigateBack
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = spacing.maxContentWidth)
                    .verticalScroll(rememberScrollState())
                    .padding(spacing.screenHorizontal)
                    .testTag("module_foundation_screen_${moduleId.name.lowercase()}"),
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = entry?.icon ?: Icons.Filled.Construction,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                StatusPill(
                                    label = stringResource(R.string.status_foundation),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        if (entry != null) {
                            Text(
                                text = stringResource(entry.descriptionRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.module_detail_architecture_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        ReadinessChecklistItem(
                            text = stringResource(R.string.module_detail_layer_domain),
                            tint = semantic.verifiedBadge
                        )
                        ReadinessChecklistItem(
                            text = stringResource(R.string.module_detail_layer_data),
                            tint = semantic.verifiedBadge
                        )
                        ReadinessChecklistItem(
                            text = stringResource(R.string.module_detail_layer_security),
                            tint = semantic.verifiedBadge
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(spacing.md),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs)
                    ) {
                        Text(
                            text = stringResource(R.string.module_detail_notice_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.module_detail_notice_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }
}

@Composable
private fun ReadinessChecklistItem(
    text: String,
    tint: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
