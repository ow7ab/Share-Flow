package com.example.shareflow.ui.hub

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.shareflow.core.designsystem.components.ModuleDirectoryCard
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.domain.model.ModuleCategory
import com.example.shareflow.domain.model.ModuleReadiness
import com.example.shareflow.navigation.ShareFlowModuleCatalog

@Composable
fun ModuleHubScreen(
    @StringRes titleRes: Int,
    @StringRes subtitleRes: Int,
    categories: List<ModuleCategory>,
    onNavigateToRoute: (String) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val modules = categories.flatMap { ShareFlowModuleCatalog.byCategory(it) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("${testTagPrefix}_list"),
            contentPadding = PaddingValues(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item(key = "hub_header") {
                SectionHeader(
                    title = stringResource(titleRes),
                    subtitle = stringResource(subtitleRes)
                )
            }
            items(
                items = modules,
                key = { it.descriptor.id.name }
            ) { module ->
                val isReady = module.descriptor.readiness == ModuleReadiness.READY
                ModuleDirectoryCard(
                    title = stringResource(module.titleRes),
                    description = stringResource(module.descriptionRes),
                    icon = module.icon,
                    statusLabel = stringResource(
                        if (isReady) R.string.status_ready else R.string.status_scaffolded
                    ),
                    isReady = isReady,
                    onClick = { onNavigateToRoute(module.descriptor.route) },
                    testTag = "${testTagPrefix}_card_${module.descriptor.id.name.lowercase()}"
                )
            }
        }
    }
}
