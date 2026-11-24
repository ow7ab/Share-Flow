package com.example.shareflow.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.shareflow.core.designsystem.components.PrimaryActionButton
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.core.designsystem.theme.ShareFlowMarpleColors
import com.example.shareflow.domain.model.AppLanguage
import kotlinx.coroutines.launch

private const val ONBOARDING_PAGE_COUNT = 4

/**
 * First-install welcome & onboarding experience for ShareFlow:
 * 1. Welcome overview (offline, verified, private).
 * 2. Dedicated 100% Ad-Free promise screen.
 * 3. How to use ShareFlow in 3 clear steps.
 * 4. Transparent breakdown of required permissions (with zero broad storage access).
 *
 * Layout honors LTR/RTL automatically via [ShareFlowTheme]:
 * - "Skip" sits at top-end (top-right in English LTR, top-left in Arabic RTL).
 * - "Next" / "Get Started" sits anchored at the bottom above system navigation insets.
 */
@Composable
fun OnboardingScreen(
    selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    onSelectLanguage: (AppLanguage) -> Unit = {},
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })
    val coroutineScope = rememberCoroutineScope()
    val semantic = ShareFlowDesign.semanticColors
    val spacing = ShareFlowDesign.spacing
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1

    BackHandler(enabled = pagerState.currentPage > 0) {
        coroutineScope.launch {
            pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0))
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val topGlowColor = if (semantic.isDark) {
        ShareFlowMarpleColors.Ruby.copy(alpha = 0.24f)
    } else {
        ShareFlowMarpleColors.Cream.copy(alpha = 0.65f)
    }
    val secondaryGlowColor = if (semantic.isDark) {
        ShareFlowMarpleColors.Coral.copy(alpha = 0.14f)
    } else {
        ShareFlowMarpleColors.Peach.copy(alpha = 0.25f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(topGlowColor, Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.14f),
                        radius = size.maxDimension * 0.55f
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.14f),
                    radius = size.maxDimension * 0.55f
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(secondaryGlowColor, Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.82f),
                        radius = size.maxDimension * 0.45f
                    ),
                    center = Offset(size.width * 0.85f, size.height * 0.82f),
                    radius = size.maxDimension * 0.45f
                )
            }
            .testTag("onboarding_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
        ) {
            // Top bar: step pill at start, Skip button at end (top-right in LTR, top-left in RTL)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Text(
                        text = stringResource(
                            R.string.onboarding_step_counter,
                            pagerState.currentPage + 1,
                            ONBOARDING_PAGE_COUNT
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                TextButton(
                    onClick = onComplete,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .minimumInteractiveComponentSize()
                        .testTag("onboarding_skip_button")
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip_action),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Swipeable onboarding pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("onboarding_pager")
            ) { page ->
                when (page) {
                    0 -> OnboardingWelcomePage(
                        selectedLanguage = selectedLanguage,
                        onSelectLanguage = onSelectLanguage
                    )
                    1 -> OnboardingAdFreePage()
                    2 -> OnboardingHowToUsePage()
                    else -> OnboardingPermissionsPage()
                }
            }

            // Bottom indicator dots + primary Next / Get Started button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.lg, vertical = spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.md)
            ) {
                OnboardingPageIndicators(
                    pageCount = ONBOARDING_PAGE_COUNT,
                    currentPage = pagerState.currentPage
                )

                PrimaryActionButton(
                    text = stringResource(
                        if (isLastPage) {
                            R.string.onboarding_get_started_action
                        } else {
                            R.string.onboarding_next_action
                        }
                    ),
                    leadingIcon = if (isLastPage) {
                        Icons.Filled.Check
                    } else {
                        Icons.AutoMirrored.Filled.ArrowForward
                    },
                    onClick = {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboarding_next_button")
                )
            }
        }
    }
}

@Composable
private fun OnboardingWelcomePage(
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    val spacing = ShareFlowDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag("onboarding_page_welcome"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(spacing.xs))

        // Official ShareFlow hero badge
        Box(
            modifier = Modifier
                .size(78.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            ShareFlowMarpleColors.Ruby,
                            ShareFlowMarpleColors.Coral,
                            ShareFlowMarpleColors.Peach
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.9f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_shareflow_foreground_art),
                contentDescription = stringResource(R.string.onboarding_app_icon_cd),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(60.dp)
                    .padding(3.dp)
            )
        }

        Spacer(modifier = Modifier.height(spacing.xs))

        OnboardingCategoryPill(
            text = stringResource(R.string.onboarding_welcome_badge),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        OnboardingLanguageSelector(
            selectedLanguage = selectedLanguage,
            onSelectLanguage = onSelectLanguage
        )

        Spacer(modifier = Modifier.height(spacing.md))

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OnboardingFeatureCard(
                icon = Icons.Filled.Wifi,
                iconTint = ShareFlowMarpleColors.Ruby,
                title = stringResource(R.string.onboarding_welcome_feat_offline_title),
                description = stringResource(R.string.onboarding_welcome_feat_offline_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.VerifiedUser,
                iconTint = ShareFlowMarpleColors.Coral,
                title = stringResource(R.string.onboarding_welcome_feat_verified_title),
                description = stringResource(R.string.onboarding_welcome_feat_verified_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.Lock,
                iconTint = ShareFlowDesign.semanticColors.success,
                title = stringResource(R.string.onboarding_welcome_feat_private_title),
                description = stringResource(R.string.onboarding_welcome_feat_private_desc)
            )
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Composable
private fun OnboardingLanguageSelector(
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit
) {
    val semantic = ShareFlowDesign.semanticColors
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, semantic.glassCardBorder.copy(alpha = 0.85f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("onboarding_language_selector")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_language_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val options = listOf(
                    Triple(AppLanguage.ARABIC, R.string.onboarding_language_arabic, "onboarding_lang_arabic"),
                    Triple(AppLanguage.ENGLISH, R.string.onboarding_language_english, "onboarding_lang_english"),
                    Triple(AppLanguage.SYSTEM, R.string.onboarding_language_system, "onboarding_lang_system")
                )
                options.forEach { (language, labelRes, tag) ->
                    val isSelected = selectedLanguage == language
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectLanguage(language) },
                        label = {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .testTag(tag)
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingAdFreePage() {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag("onboarding_page_ad_free"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(spacing.xs))

        // Dedicated Ad-Free Shield Emblem
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            semantic.successContainer,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        )
                    )
                )
                .border(
                    width = 2.dp,
                    brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.85f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = stringResource(R.string.onboarding_ad_free_badge),
                tint = semantic.success,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(spacing.md))

        OnboardingCategoryPill(
            text = stringResource(R.string.onboarding_ad_free_badge),
            containerColor = semantic.successContainer,
            contentColor = semantic.success
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_ad_free_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.xs))

        Text(
            text = stringResource(R.string.onboarding_ad_free_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.lg))

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OnboardingFeatureCard(
                icon = Icons.Filled.Block,
                iconTint = ShareFlowMarpleColors.Ruby,
                title = stringResource(R.string.onboarding_ad_free_point_1_title),
                description = stringResource(R.string.onboarding_ad_free_point_1_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.Security,
                iconTint = semantic.success,
                title = stringResource(R.string.onboarding_ad_free_point_2_title),
                description = stringResource(R.string.onboarding_ad_free_point_2_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.CheckCircle,
                iconTint = ShareFlowMarpleColors.Coral,
                title = stringResource(R.string.onboarding_ad_free_point_3_title),
                description = stringResource(R.string.onboarding_ad_free_point_3_desc)
            )
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Composable
private fun OnboardingHowToUsePage() {
    val spacing = ShareFlowDesign.spacing

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag("onboarding_page_how_to_use"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(spacing.xs))

        OnboardingHeroIconBadge(
            icon = Icons.Filled.QrCodeScanner,
            contentDescription = stringResource(R.string.onboarding_how_title)
        )

        Spacer(modifier = Modifier.height(spacing.md))

        OnboardingCategoryPill(
            text = stringResource(R.string.onboarding_how_badge),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_how_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.xs))

        Text(
            text = stringResource(R.string.onboarding_how_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.lg))

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OnboardingFeatureCard(
                icon = Icons.AutoMirrored.Filled.Send,
                iconTint = ShareFlowMarpleColors.Ruby,
                title = stringResource(R.string.onboarding_how_step_1_title),
                description = stringResource(R.string.onboarding_how_step_1_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.Wifi,
                iconTint = ShareFlowMarpleColors.Coral,
                title = stringResource(R.string.onboarding_how_step_2_title),
                description = stringResource(R.string.onboarding_how_step_2_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.QrCodeScanner,
                iconTint = ShareFlowMarpleColors.Peach,
                title = stringResource(R.string.onboarding_how_step_3_title),
                description = stringResource(R.string.onboarding_how_step_3_desc)
            )
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Composable
private fun OnboardingPermissionsPage() {
    val spacing = ShareFlowDesign.spacing
    val semantic = ShareFlowDesign.semanticColors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm)
            .testTag("onboarding_page_permissions"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(spacing.xs))

        OnboardingHeroIconBadge(
            icon = Icons.Filled.Security,
            contentDescription = stringResource(R.string.onboarding_perm_title)
        )

        Spacer(modifier = Modifier.height(spacing.md))

        OnboardingCategoryPill(
            text = stringResource(R.string.onboarding_perm_badge),
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
        )

        Spacer(modifier = Modifier.height(spacing.sm))

        Text(
            text = stringResource(R.string.onboarding_perm_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.xs))

        Text(
            text = stringResource(R.string.onboarding_perm_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(spacing.md))

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OnboardingFeatureCard(
                icon = Icons.Filled.WifiFind,
                iconTint = ShareFlowMarpleColors.Ruby,
                title = stringResource(R.string.onboarding_perm_wifi_title),
                description = stringResource(R.string.onboarding_perm_wifi_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.CameraAlt,
                iconTint = ShareFlowMarpleColors.Coral,
                title = stringResource(R.string.onboarding_perm_camera_title),
                description = stringResource(R.string.onboarding_perm_camera_desc)
            )
            OnboardingFeatureCard(
                icon = Icons.Filled.Notifications,
                iconTint = ShareFlowMarpleColors.Peach,
                title = stringResource(R.string.onboarding_perm_optional_title),
                description = stringResource(R.string.onboarding_perm_optional_desc)
            )
        }

        Spacer(modifier = Modifier.height(spacing.sm))

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = semantic.successContainer.copy(alpha = 0.85f),
            border = BorderStroke(1.dp, semantic.success.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = null,
                    tint = semantic.success,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.onboarding_perm_storage_badge),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.md))
    }
}

@Composable
private fun OnboardingHeroIconBadge(
    icon: ImageVector,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
            .border(
                width = 1.5.dp,
                brush = ShareFlowBrandGradients.signatureBorder(alpha = 0.75f),
                shape = RoundedCornerShape(28.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(46.dp)
        )
    }
}

@Composable
private fun OnboardingCategoryPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = CircleShape,
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun OnboardingFeatureCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    val semantic = ShareFlowDesign.semanticColors
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, semantic.glassCardBorder.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconTint.copy(alpha = if (semantic.isDark) 0.22f else 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OnboardingPageIndicators(
    pageCount: Int,
    currentPage: Int
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("onboarding_page_indicators")
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            val dotWidth by animateDpAsState(
                targetValue = if (isSelected) 28.dp else 8.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "onboarding_dot_width_$index"
            )
            val dotColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                label = "onboarding_dot_color_$index"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(dotWidth)
                    .clip(CircleShape)
                    .background(dotColor)
                    .testTag("onboarding_page_indicator_$index")
            )
        }
    }
}
