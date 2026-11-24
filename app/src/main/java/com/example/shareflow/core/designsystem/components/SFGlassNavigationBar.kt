package com.example.shareflow.core.designsystem.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors

data class SFNavBarItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

/**
 * Share Flow 2.0 Floating Liquid Glass Bottom Navigation Bar (`SFGlassNavigationBar`).
 *
 * Guarantees identical translucency, specular rim, and floating capsule elevation across ALL
 * top-level screens (Home, Files, Media, Tools, Settings):
 * - Selective GPU RenderEffect blur (`Modifier.blur`) on API 31+ applied strictly to the backdrop layer.
 * - Multi-layer frosted translucent fallback on API 24–30 — never flat opaque.
 * - Gliding brand refraction bloom + smooth spring pill highlight under the active item.
 * - Proper `WindowInsets.navigationBars` handling for gesture and 3-button Android navigation.
 * - Full RTL/LTR mirroring and reduced-motion accessibility compliance.
 */
@Composable
fun SFGlassNavigationBar(
    items: List<SFNavBarItem>,
    currentRoute: String?,
    onSelectRoute: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val glass = ShareFlowDesign.glass
    val shapes = ShareFlowDesign.shapes
    val motion = ShareFlowDesign.motion
    val reducedMotion = ShareFlowDesign.reducedMotion
    val isDark = glass.isDark
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val supportsRealBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val selectedIndex = items.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
    val rawSelectedFraction = if (items.size > 1) {
        selectedIndex.toFloat() / (items.size - 1).toFloat()
    } else {
        0.5f
    }
    val visualTargetFraction = if (isRtl) 1f - rawSelectedFraction else rawSelectedFraction
    val animatedBloomFraction by animateFloatAsState(
        targetValue = visualTargetFraction,
        animationSpec = if (reducedMotion) tween(0) else motion.navSpring(),
        label = "sf_glass_nav_bloom"
    )

    val activeContentColor = if (isDark) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby
    val inactiveIconColor = if (isDark) {
        ShareFlowMarpleColors.DarkTextPrimary.copy(alpha = 0.72f)
    } else {
        ShareFlowMarpleColors.LightTextPrimary.copy(alpha = 0.66f)
    }
    val inactiveLabelColor = if (isDark) {
        ShareFlowMarpleColors.DarkTextSecondary.copy(alpha = 0.92f)
    } else {
        ShareFlowMarpleColors.LightTextSecondary.copy(alpha = 0.94f)
    }

    val specularBorderBrush = remember(glass) {
        Brush.verticalGradient(
            colors = listOf(
                glass.navBorderTop,
                glass.borderMiddle,
                glass.navBorderBottom
            )
        )
    }

    // Dense iOS-style frosted tint overlay ensuring underlying scrolled content never bleeds sharply
    val frostedTintAlpha = when {
        isDark && supportsRealBlur -> 0.86f
        isDark -> 0.94f
        supportsRealBlur -> 0.88f
        else -> 0.95f
    }

    val glazeBrush = remember(glass, supportsRealBlur, isDark) {
        Brush.verticalGradient(
            colors = if (isDark) {
                listOf(
                    glass.navGlazeTop,
                    glass.navBase.copy(alpha = if (supportsRealBlur) 0.56f else 0.72f),
                    glass.navGlazeBottom
                )
            } else {
                listOf(
                    glass.navGlazeTop,
                    glass.navBase.copy(alpha = if (supportsRealBlur) 0.62f else 0.76f),
                    glass.navGlazeBottom
                )
            }
        )
    }

    val bloomColors = remember(isDark) {
        if (isDark) {
            listOf(
                ShareFlowMarpleColors.Ruby.copy(alpha = 0.22f),
                ShareFlowMarpleColors.Peach.copy(alpha = 0.08f),
                Color.Transparent
            )
        } else {
            listOf(
                ShareFlowMarpleColors.Ruby.copy(alpha = 0.10f),
                ShareFlowMarpleColors.Cream.copy(alpha = 0.26f),
                Color.Transparent
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .shadow(
                    elevation = if (isDark) 16.dp else 11.dp,
                    shape = shapes.navCapsule,
                    clip = false,
                    ambientColor = glass.ambientShadow,
                    spotColor = glass.spotShadow
                )
                .clip(shapes.navCapsule)
                .border(
                    width = 0.85.dp,
                    brush = specularBorderBrush,
                    shape = shapes.navCapsule
                )
                .testTag("shareflow_navigation_bar")
        ) {
            // Layer 1: Dense frosted backdrop with real Gaussian RenderEffect blur on API 31+
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(
                        if (supportsRealBlur) {
                            Modifier.blur(
                                radiusX = ShareFlowDesign.elevation.blurRadiusNav,
                                radiusY = ShareFlowDesign.elevation.blurRadiusNav,
                                edgeTreatment = BlurredEdgeTreatment.Rectangle
                            )
                        } else {
                            Modifier
                        }
                    )
                    .drawBehind {
                        drawRect(color = glass.navBase.copy(alpha = frostedTintAlpha))
                        val bloomCenterX = (0.10f + 0.80f * animatedBloomFraction) * size.width
                        val bloomRadius = (size.height * 1.25f).coerceAtLeast(1f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = bloomColors,
                                center = Offset(bloomCenterX, size.height * 0.50f),
                                radius = bloomRadius
                            ),
                            center = Offset(bloomCenterX, size.height * 0.50f),
                            radius = bloomRadius
                        )
                    }
            )

            // Layer 2: Unblurred frosted top sheen & opaque-frosted depth seal
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(brush = glazeBrush)
            )

            // Layer 3: Crisp high-contrast navigation items
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val isSelected = currentRoute == item.route

                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) activeContentColor else inactiveIconColor,
                        animationSpec = tween(durationMillis = if (reducedMotion) 0 else motion.durationQuickMs),
                        label = "sf_nav_icon_tint_${item.route}"
                    )
                    val labelTint by animateColorAsState(
                        targetValue = if (isSelected) activeContentColor else inactiveLabelColor,
                        animationSpec = tween(durationMillis = if (reducedMotion) 0 else motion.durationQuickMs),
                        label = "sf_nav_label_tint_${item.route}"
                    )
                    val pillBg by animateColorAsState(
                        targetValue = if (isSelected) glass.navActivePill else Color.Transparent,
                        animationSpec = tween(durationMillis = if (reducedMotion) 0 else motion.durationStandardMs),
                        label = "sf_nav_pill_${item.route}"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected && !reducedMotion) motion.navSelectedIconScale else 1f,
                        animationSpec = if (reducedMotion) tween(0) else motion.navSpring(),
                        label = "sf_nav_scale_${item.route}"
                    )
                    val iconLift by animateDpAsState(
                        targetValue = if (isSelected && !reducedMotion) (-1.5).dp else 0.dp,
                        animationSpec = motion.dpSpec(reducedMotion),
                        label = "sf_nav_lift_${item.route}"
                    )
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (isSelected) 18.dp else 0.dp,
                        animationSpec = motion.dpSpec(reducedMotion),
                        label = "sf_nav_indicator_${item.route}"
                    )

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(shapes.navPill)
                            .background(pillBg)
                            .minimumInteractiveComponentSize()
                            .semantics { selected = isSelected }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = ShareFlowMarpleColors.Ruby),
                                role = Role.Tab,
                                onClick = { onSelectRoute(item.route) }
                            )
                            .testTag(item.testTag)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label,
                                tint = iconTint,
                                modifier = Modifier
                                    .size(22.dp)
                                    .offset(y = iconLift)
                                    .graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    }
                            )
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = labelTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (indicatorWidth > 1.dp) {
                                Box(
                                    modifier = Modifier
                                        .width(indicatorWidth)
                                        .height(2.5.dp)
                                        .clip(CircleShape)
                                        .background(ShareFlowBrandGradients.primaryButtonGradient())
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
