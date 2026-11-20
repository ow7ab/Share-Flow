package com.example.shareflow.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.shareflow.core.designsystem.components.ModuleDirectoryCard
import com.example.shareflow.core.designsystem.components.SectionHeader
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.domain.model.ShareFlowModuleId
import com.example.shareflow.navigation.ShareFlowRoutes

@Composable
fun MediaScreen(
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = spacing.maxContentWidth)
                .testTag("media_screen_list"),
            contentPadding = PaddingValues(
                horizontal = spacing.screenHorizontal,
                vertical = spacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            item(key = "media_header") {
                SectionHeader(
                    title = stringResource(R.string.media_title),
                    subtitle = stringResource(R.string.media_subtitle)
                )
            }

            item(key = "media_photos") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.media_photos_title),
                    description = stringResource(R.string.media_photos_desc),
                    icon = Icons.Filled.PhotoLibrary,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.IMAGE_GALLERY) },
                    testTag = "media_card_photos"
                )
            }

            item(key = "media_videos") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.media_videos_title),
                    description = stringResource(R.string.media_videos_desc),
                    icon = Icons.Filled.PlayCircle,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.VIDEO_PLAYER) },
                    testTag = "media_card_videos"
                )
            }

            item(key = "media_music") {
                ModuleDirectoryCard(
                    title = stringResource(R.string.media_music_title),
                    description = stringResource(R.string.media_music_desc),
                    icon = Icons.Filled.LibraryMusic,
                    statusLabel = stringResource(R.string.status_ready),
                    isReady = true,
                    onClick = { onNavigateToRoute(ShareFlowRoutes.MUSIC_PLAYER) },
                    testTag = "media_card_music"
                )
            }

            item(key = "media_bottom_spacer") {
                Spacer(modifier = Modifier.height(spacing.md))
            }
        }
    }
}
