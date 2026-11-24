package com.example.shareflow.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.components.SFGlassCard
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.data.update.UpdateDetails

/**
 * Non-intrusive floating top Liquid Glass banner shown when [com.example.shareflow.data.update.UpdateManager]
 * automatically detects an optional newer release on app launch.
 */
@Composable
fun UpdateAvailableBanner(
    update: UpdateDetails?,
    onOpenDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = ShareFlowDesign.spacing
    val glass = ShareFlowDesign.glass
    val reducedMotion = ShareFlowDesign.reducedMotion
    val accentGradient = Brush.linearGradient(
        colors = listOf(
            ShareFlowMarpleColors.Ruby,
            ShareFlowMarpleColors.Coral,
            ShareFlowMarpleColors.Peach
        )
    )

    AnimatedVisibility(
        visible = update != null,
        enter = if (reducedMotion) {
            fadeIn(animationSpec = tween(durationMillis = 120))
        } else {
            slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) + fadeIn(animationSpec = tween(durationMillis = 200))
        },
        exit = if (reducedMotion) {
            fadeOut(animationSpec = tween(durationMillis = 100))
        } else {
            slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(durationMillis = 160)
            ) + fadeOut(animationSpec = tween(durationMillis = 140))
        },
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        if (update != null) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                SFGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 560.dp)
                        .testTag("update_available_banner"),
                    shape = RoundedCornerShape(22.dp),
                    containerColor = glass.overlayBackground,
                    selected = true,
                    shadowElevation = ShareFlowDesign.elevation.modal,
                    enableBackdropBlur = true
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = spacing.sm,
                                end = 4.dp,
                                top = 10.dp,
                                bottom = 10.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    role = Role.Button,
                                    onClick = onOpenDetails
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(accentGradient),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.SystemUpdate,
                                    contentDescription = null,
                                    tint = ShareFlowMarpleColors.Cream,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.update_banner_title),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.update_banner_subtitle_format,
                                        update.versionName
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("update_banner_version_subtitle")
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(
                                    role = Role.Button,
                                    onClick = onOpenDetails
                                )
                                .testTag("update_banner_view_action"),
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = stringResource(R.string.update_banner_view_action),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("update_banner_dismiss_action")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.update_banner_dismiss_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
