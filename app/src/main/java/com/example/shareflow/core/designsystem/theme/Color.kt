package com.example.shareflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.shareflow.domain.model.AccentColor

/**
 * SHARE FLOW — OFFICIAL PEARL WHITE & DEEP CHARCOAL COLOR SYSTEM
 *
 * Immutable Official Brand Palette (Preserved in both Light & Dark modes):
 * - Primary Ruby:    #CA2851 (Core actions, active navigation, brand anchor)
 * - Secondary Coral: #FF6766 (Receive / secondary actions, interactive focus, highlights)
 * - Accent Peach:    #FFB173 (Supporting brand accents, active gradients, soft badges)
 * - Soft Highlight:  #FFE3B3 (Signature brand highlight & warm accents)
 *
 * Official Dual Theme Identities:
 * - Light Mode (Pearl White): Warm, calm Pearl White (#F8F7F4) canvas with crisp white (#FFFFFF)
 *   cards, soft neutral borders (#EAE8E3), and high-contrast ink (#252525 / #777777).
 * - Dark Mode (Deep Charcoal): Rich Deep Charcoal (#171717) canvas (zero pure black #000000)
 *   with distinct dark gray cards (#222222), secondary surfaces (#292929), elevated surfaces
 *   (#303030), soft charcoal borders (#383838), and crisp ink (#FAFAFA / #AAAAAA).
 */
object ShareFlowMarpleColors {
    // Exact 4 Official Brand Identity Colors (NEVER REPLACED)
    val Ruby: Color = Color(0xFFCA2851)
    val Coral: Color = Color(0xFFFF6766)
    val Peach: Color = Color(0xFFFFB173)
    val Cream: Color = Color(0xFFFFE3B3)

    // Light Mode — Pearl White Official Palette
    val LightBackground: Color = Color(0xFFF8F7F4)           // Main Background
    val LightSurface: Color = Color(0xFFFFFFFF)              // Card Background & Elevated Surface
    val LightSurfaceContainerLow: Color = Color(0xFFFFFFFF)  // Card Surface
    val LightSurfaceContainer: Color = Color(0xFFF1F0EC)     // Secondary Surface
    val LightSurfaceVariant: Color = Color(0xFFF1F0EC)       // Secondary Surface
    val LightSurfaceContainerHigh: Color = Color(0xFFEBEAE5) // Subtle Hover / Chip Container
    val LightSurfaceContainerHighest: Color = Color(0xFFE4E2DC)
    val LightTextPrimary: Color = Color(0xFF252525)          // Primary Text
    val LightTextSecondary: Color = Color(0xFF777777)        // Secondary Text
    val LightBorder: Color = Color(0xFFEAE8E3)               // Divider / Border
    val LightOutline: Color = Color(0xFFC9C7C2)              // Outline / Disabled

    // Light Containers (Harmonized with Pearl White)
    val LightRubyContainer: Color = Color(0xFFFFE1E7)
    val LightOnRubyContainer: Color = Color(0xFF470016)
    val LightCoralContainer: Color = Color(0xFFFFE4E2)
    val LightOnCoralContainer: Color = Color(0xFF470308)
    val LightPeachContainer: Color = Color(0xFFFFECDC)
    val LightOnPeachContainer: Color = Color(0xFF351900)

    // Dark Mode — Deep Charcoal Official Palette (Zero Pure Black #000000)
    val DarkBackground: Color = Color(0xFF171717)            // Main Background
    val DarkSurfaceLowest: Color = Color(0xFF141414)         // Recessed Well
    val DarkSurface: Color = Color(0xFF222222)               // Card Background
    val DarkSurfaceContainerLow: Color = Color(0xFF222222)   // Card Surface
    val DarkSurfaceContainer: Color = Color(0xFF292929)      // Secondary Surface
    val DarkSurfaceElevated: Color = Color(0xFF303030)       // Elevated Surface
    val DarkSurfaceHighest: Color = Color(0xFF383838)        // Highest Neutral Container
    val DarkPrimary: Color = Color(0xFFFF6D8C)               // High-contrast Ruby accent on Charcoal
    val DarkOnPrimary: Color = Color(0xFF4B0016)
    val DarkPrimaryContainer: Color = Color(0xFF4D1A29)
    val DarkOnPrimaryContainer: Color = Color(0xFFFFDCE2)
    val DarkSecondary: Color = Color(0xFFFF8281)             // High-contrast Coral accent on Charcoal
    val DarkOnSecondary: Color = Color(0xFF4D080C)
    val DarkSecondaryContainer: Color = Color(0xFF4A2223)
    val DarkOnSecondaryContainer: Color = Color(0xFFFFDFDD)
    val DarkTertiary: Color = Color(0xFFFFB173)
    val DarkOnTertiary: Color = Color(0xFF3B1E00)
    val DarkTertiaryContainer: Color = Color(0xFF45301B)
    val DarkOnTertiaryContainer: Color = Color(0xFFFFE3C8)
    val DarkAccent: Color = Color(0xFFFFE3B3)
    val DarkTextPrimary: Color = Color(0xFFFAFAFA)           // Primary Text
    val DarkTextSecondary: Color = Color(0xFFAAAAAA)         // Secondary Text
    val DarkBorder: Color = Color(0xFF383838)                // Divider / Border
    val DarkOutline: Color = Color(0xFF666666)               // Outline / Disabled

    // Semantic Status Roles
    val LightError: Color = Color(0xFFC21E2C)
    val LightOnError: Color = Color(0xFFFFFFFF)
    val LightErrorContainer: Color = Color(0xFFFFDAD9)
    val LightOnErrorContainer: Color = Color(0xFF410004)

    val DarkError: Color = Color(0xFFFFB3B0)
    val DarkOnError: Color = Color(0xFF68000A)
    val DarkErrorContainer: Color = Color(0xFF5C191F)
    val DarkOnErrorContainer: Color = Color(0xFFFFDAD9)

    val LightSuccess: Color = Color(0xFF18794E)
    val LightOnSuccess: Color = Color(0xFFFFFFFF)
    val LightSuccessContainer: Color = Color(0xFFD9F6E6)

    val DarkSuccess: Color = Color(0xFF5DD89F)
    val DarkOnSuccess: Color = Color(0xFF04341E)
    val DarkSuccessContainer: Color = Color(0xFF1A3828)

    val LightWarning: Color = Color(0xFFB35812)
    val LightWarningContainer: Color = Color(0xFFFFE7C2)
    val DarkWarning: Color = Color(0xFFFFB173)
    val DarkWarningContainer: Color = Color(0xFF452E16)

    val LightDisabled: Color = Color(0xFFC9C7C2)             // Official Pearl White Disabled
    val LightOnDisabled: Color = Color(0xFF777777)
    val DarkDisabled: Color = Color(0xFF666666)              // Official Deep Charcoal Disabled
    val DarkOnDisabled: Color = Color(0xFFAAAAAA)

    val LightScrim: Color = Color(0x52171717)
    val DarkScrim: Color = Color(0xA60A0A0A)
    val Scrim: Color = DarkScrim

    // Liquid Glass Bottom Navigation & Surface Tokens
    val LightGlassNavSurface: Color = Color(0xEBFFFFFF)
    val LightGlassNavOverlay: Color = Color(0x33F8F7F4)
    val LightGlassNavBorder: Color = Color(0xCCEAE8E3)
    val LightGlassNavActiveIndicator: Color = Color(0x1ACA2851)

    val DarkGlassNavSurface: Color = Color(0xE8222222)
    val DarkGlassNavOverlay: Color = Color(0x2E303030)
    val DarkGlassNavBorder: Color = Color(0xCC383838)
    val DarkGlassNavActiveIndicator: Color = Color(0x2EFF6D8C)
}

/**
 * Share Flow primary color token namespace alias.
 */
typealias SFColors = ShareFlowMarpleColors

/**
 * Backward-compatible token alias object resolving cleanly to the Pearl White & Deep Charcoal palette.
 */
object ShareFlowBrandColors {
    val Ruby: Color = ShareFlowMarpleColors.Ruby
    val Coral: Color = ShareFlowMarpleColors.Coral
    val Peach: Color = ShareFlowMarpleColors.Peach
    val Cream: Color = ShareFlowMarpleColors.Cream

    val RoyalBlue: Color = ShareFlowMarpleColors.Ruby
    val SkyBlue: Color = ShareFlowMarpleColors.Coral
    val GoldenYellow: Color = ShareFlowMarpleColors.Peach
    val SoftCream: Color = ShareFlowMarpleColors.Cream
    val DeepNavy: Color = ShareFlowMarpleColors.DarkBackground

    val GlassSurfaceLowest: Color = ShareFlowMarpleColors.LightSurface
    val GlassSurfaceLow: Color = ShareFlowMarpleColors.LightSurfaceContainerLow
    val GlassSurface: Color = ShareFlowMarpleColors.LightSurfaceContainer
    val GlassSurfaceHigh: Color = ShareFlowMarpleColors.LightSurfaceContainerHigh
    val GlassSurfaceHighest: Color = ShareFlowMarpleColors.LightSurfaceContainerHighest

    val DeepNavyLowest: Color = ShareFlowMarpleColors.DarkSurfaceLowest
    val DeepNavyGlassSurface: Color = ShareFlowMarpleColors.DarkSurface

    val GlassNavBackground: Color = ShareFlowMarpleColors.LightGlassNavSurface
    val GlassNavBorder: Color = ShareFlowMarpleColors.LightGlassNavBorder
    val GlassCardBorder: Color = ShareFlowMarpleColors.LightBorder
    val GlassActiveBorder: Color = ShareFlowMarpleColors.Ruby.copy(alpha = 0.65f)
    val GlassSoftCreamHighlight: Color = ShareFlowMarpleColors.Cream.copy(alpha = 0.6f)

    val RoyalBlueContainer: Color = ShareFlowMarpleColors.LightRubyContainer
    val SkyBlueContainer: Color = ShareFlowMarpleColors.LightCoralContainer
    val GoldenYellowContainer: Color = ShareFlowMarpleColors.LightPeachContainer

    val InkOnSoftCream: Color = ShareFlowMarpleColors.LightTextPrimary
    val InkSecondaryOnSoftCream: Color = ShareFlowMarpleColors.LightTextSecondary
    val InkOnDeepNavy: Color = ShareFlowMarpleColors.DarkTextPrimary
    val InkSecondaryOnDeepNavy: Color = ShareFlowMarpleColors.DarkTextSecondary
    val InkOnRoyalBlue: Color = Color.White
    val InkOnSkyBlue: Color = Color.White
    val InkOnGoldenYellow: Color = ShareFlowMarpleColors.LightTextPrimary

    val ErrorSurfaceAccent: Color = ShareFlowMarpleColors.LightError
    val OnErrorSurfaceAccent: Color = ShareFlowMarpleColors.LightOnError
    val ErrorContainerGlass: Color = ShareFlowMarpleColors.LightErrorContainer
    val OnErrorContainerGlass: Color = ShareFlowMarpleColors.LightOnErrorContainer
}

/**
 * Official 4-Color Brand Gradients (Unchanged):
 * Ruby (#CA2851) -> Coral (#FF6766) -> Peach (#FFB173) -> Cream (#FFE3B3)
 */
object ShareFlowBrandGradients {
    val SignatureStops: List<Color> = listOf(
        ShareFlowMarpleColors.Ruby,
        ShareFlowMarpleColors.Coral,
        ShareFlowMarpleColors.Peach,
        ShareFlowMarpleColors.Cream
    )

    val MarpleActionStops: List<Color> = listOf(
        ShareFlowMarpleColors.Ruby,
        ShareFlowMarpleColors.Coral,
        ShareFlowMarpleColors.Peach
    )

    val PrimaryButtonStops: List<Color> = listOf(
        ShareFlowMarpleColors.Ruby,
        Color(0xFFE03E59),
        ShareFlowMarpleColors.Coral
    )

    val SendHeroStops: List<Color> = listOf(
        ShareFlowMarpleColors.Ruby,
        Color(0xFFAE1D46)
    )

    val ReceiveHeroStops: List<Color> = listOf(
        ShareFlowMarpleColors.Coral,
        Color(0xFFE64E54)
    )

    fun signatureHorizontal(): Brush = Brush.horizontalGradient(colors = SignatureStops)

    fun signatureLinear(): Brush = Brush.linearGradient(colors = SignatureStops)

    fun signatureBorder(alpha: Float = 0.85f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun signatureSubtleGlow(alpha: Float = 0.24f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun primaryActionSheen(): Brush = Brush.horizontalGradient(
        colors = PrimaryButtonStops
    )

    fun primaryButtonGradient(): Brush = Brush.horizontalGradient(
        colors = PrimaryButtonStops
    )
}

/**
 * Surface, Glass Navigation & Specular Highlight tokens for Pearl White (Light) & Deep Charcoal (Dark).
 */
@Immutable
data class SFGlassTokens(
    val isDark: Boolean,
    val cardBackground: Color,
    val cardElevatedBackground: Color,
    val cardSubtleBackground: Color,
    val topGlaze: Color,
    val bottomGlaze: Color,
    val borderTop: Color,
    val borderMiddle: Color,
    val borderBottom: Color,
    val activeBorder: Color,
    val ambientShadow: Color,
    val spotShadow: Color,
    val navBase: Color,
    val navGlazeTop: Color,
    val navGlazeBottom: Color,
    val navBorderTop: Color,
    val navBorderBottom: Color,
    val navActivePill: Color,
    val overlayBackground: Color,
    val snackbarBackground: Color,
    val snackbarContent: Color,
    val ambientGlowPrimary: Color,
    val ambientGlowSecondary: Color,
    val ambientGlowTertiary: Color
)

val LightSFGlassTokens = SFGlassTokens(
    isDark = false,
    cardBackground = ShareFlowMarpleColors.LightSurface,          // Crisp #FFFFFF card on #F8F7F4
    cardElevatedBackground = ShareFlowMarpleColors.LightSurface,  // Crisp #FFFFFF elevated surface
    cardSubtleBackground = ShareFlowMarpleColors.LightSurfaceContainer, // #F1F0EC secondary surface
    topGlaze = Color(0x33FFFFFF),
    bottomGlaze = Color.Transparent,                              // Zero peach/pink wash
    borderTop = ShareFlowMarpleColors.LightBorder,                // #EAE8E3
    borderMiddle = ShareFlowMarpleColors.LightBorder,             // #EAE8E3
    borderBottom = ShareFlowMarpleColors.LightBorder,             // #EAE8E3
    activeBorder = ShareFlowMarpleColors.Ruby.copy(alpha = 0.60f),
    ambientShadow = Color(0x12252525),
    spotShadow = Color(0x14252525),
    // Frosted translucent white glass capsule for bottom navigation (zero peach/cream wash)
    navBase = Color(0xEEFFFFFF),
    navGlazeTop = Color(0xC2FFFFFF),
    navGlazeBottom = Color(0x52F8F7F4),
    navBorderTop = Color(0xFFFFFFFF),
    navBorderBottom = Color(0xD9EAE8E3),
    navActivePill = ShareFlowMarpleColors.Ruby.copy(alpha = 0.11f),
    overlayBackground = Color(0xFAFFFFFF),
    snackbarBackground = Color(0xF5222222),
    snackbarContent = Color(0xFFFAFAFA),
    // Calm Pearl White background without pink or peach gradients
    ambientGlowPrimary = Color.Transparent,
    ambientGlowSecondary = Color.Transparent,
    ambientGlowTertiary = Color.Transparent
)

val DarkSFGlassTokens = SFGlassTokens(
    isDark = true,
    cardBackground = ShareFlowMarpleColors.DarkSurface,           // #222222 card on #171717
    cardElevatedBackground = ShareFlowMarpleColors.DarkSurfaceElevated, // #303030 elevated surface
    cardSubtleBackground = ShareFlowMarpleColors.DarkSurfaceContainer,  // #292929 secondary surface
    topGlaze = Color(0x0DFFFFFF),
    bottomGlaze = Color.Transparent,                              // Zero colored haze
    borderTop = ShareFlowMarpleColors.DarkBorder,                 // #383838
    borderMiddle = ShareFlowMarpleColors.DarkBorder,              // #383838
    borderBottom = ShareFlowMarpleColors.DarkBorder,              // #383838
    activeBorder = ShareFlowMarpleColors.DarkPrimary.copy(alpha = 0.65f),
    ambientShadow = Color(0x66000000),
    spotShadow = Color(0x4D000000),
    // Frosted translucent charcoal glass capsule for bottom navigation (zero navy cast)
    navBase = Color(0xE8222222),
    navGlazeTop = Color(0x1CFFFFFF),
    navGlazeBottom = Color(0x59171717),
    navBorderTop = Color(0x38FFFFFF),
    navBorderBottom = Color(0xCC383838),
    navActivePill = ShareFlowMarpleColors.DarkPrimary.copy(alpha = 0.18f),
    overlayBackground = Color(0xFA222222),
    snackbarBackground = Color(0xF5303030),
    snackbarContent = Color(0xFFFAFAFA),
    // Clean Deep Charcoal background without exaggerated colored glows
    ambientGlowPrimary = Color.Transparent,
    ambientGlowSecondary = Color.Transparent,
    ambientGlowTertiary = Color.Transparent
)

val LocalSFGlassTokens = staticCompositionLocalOf { LightSFGlassTokens }

/**
 * Official Light Mode ColorScheme — Pearl White (#F8F7F4) with crisp white cards & official brand palette.
 */
val LightColorScheme: ColorScheme = lightColorScheme(
    primary = ShareFlowMarpleColors.Ruby,
    onPrimary = Color.White,
    primaryContainer = ShareFlowMarpleColors.LightRubyContainer,
    onPrimaryContainer = ShareFlowMarpleColors.LightOnRubyContainer,
    inversePrimary = ShareFlowMarpleColors.DarkPrimary,

    secondary = ShareFlowMarpleColors.Coral,
    onSecondary = Color.White,
    secondaryContainer = ShareFlowMarpleColors.LightCoralContainer,
    onSecondaryContainer = ShareFlowMarpleColors.LightOnCoralContainer,

    tertiary = ShareFlowMarpleColors.Peach,
    onTertiary = ShareFlowMarpleColors.LightTextPrimary,
    tertiaryContainer = ShareFlowMarpleColors.LightPeachContainer,
    onTertiaryContainer = ShareFlowMarpleColors.LightOnPeachContainer,

    background = ShareFlowMarpleColors.LightBackground,
    onBackground = ShareFlowMarpleColors.LightTextPrimary,
    surface = ShareFlowMarpleColors.LightSurface,
    onSurface = ShareFlowMarpleColors.LightTextPrimary,
    surfaceVariant = ShareFlowMarpleColors.LightSurfaceVariant,
    onSurfaceVariant = ShareFlowMarpleColors.LightTextSecondary,

    surfaceContainerLowest = ShareFlowMarpleColors.LightSurface,
    surfaceContainerLow = ShareFlowMarpleColors.LightSurfaceContainerLow,
    surfaceContainer = ShareFlowMarpleColors.LightSurfaceContainer,
    surfaceContainerHigh = ShareFlowMarpleColors.LightSurfaceContainerHigh,
    surfaceContainerHighest = ShareFlowMarpleColors.LightSurfaceContainerHighest,

    inverseSurface = ShareFlowMarpleColors.DarkSurface,
    inverseOnSurface = ShareFlowMarpleColors.DarkTextPrimary,

    outline = ShareFlowMarpleColors.LightOutline,
    outlineVariant = ShareFlowMarpleColors.LightBorder,

    error = ShareFlowMarpleColors.LightError,
    onError = ShareFlowMarpleColors.LightOnError,
    errorContainer = ShareFlowMarpleColors.LightErrorContainer,
    onErrorContainer = ShareFlowMarpleColors.LightOnErrorContainer,
    scrim = ShareFlowMarpleColors.LightScrim
)

/**
 * Official Dark Mode ColorScheme — Deep Charcoal (#171717) with layered charcoal cards & brand accents.
 */
val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = ShareFlowMarpleColors.DarkPrimary,
    onPrimary = ShareFlowMarpleColors.DarkOnPrimary,
    primaryContainer = ShareFlowMarpleColors.DarkPrimaryContainer,
    onPrimaryContainer = ShareFlowMarpleColors.DarkOnPrimaryContainer,
    inversePrimary = ShareFlowMarpleColors.Ruby,

    secondary = ShareFlowMarpleColors.DarkSecondary,
    onSecondary = ShareFlowMarpleColors.DarkOnSecondary,
    secondaryContainer = ShareFlowMarpleColors.DarkSecondaryContainer,
    onSecondaryContainer = ShareFlowMarpleColors.DarkOnSecondaryContainer,

    tertiary = ShareFlowMarpleColors.DarkTertiary,
    onTertiary = ShareFlowMarpleColors.DarkOnTertiary,
    tertiaryContainer = ShareFlowMarpleColors.DarkTertiaryContainer,
    onTertiaryContainer = ShareFlowMarpleColors.DarkOnTertiaryContainer,

    background = ShareFlowMarpleColors.DarkBackground,
    onBackground = ShareFlowMarpleColors.DarkTextPrimary,
    surface = ShareFlowMarpleColors.DarkSurface,
    onSurface = ShareFlowMarpleColors.DarkTextPrimary,
    surfaceVariant = ShareFlowMarpleColors.DarkSurfaceContainer,
    onSurfaceVariant = ShareFlowMarpleColors.DarkTextSecondary,

    surfaceContainerLowest = ShareFlowMarpleColors.DarkSurfaceLowest,
    surfaceContainerLow = ShareFlowMarpleColors.DarkSurfaceContainerLow,
    surfaceContainer = ShareFlowMarpleColors.DarkSurfaceContainer,
    surfaceContainerHigh = ShareFlowMarpleColors.DarkSurfaceElevated,
    surfaceContainerHighest = ShareFlowMarpleColors.DarkSurfaceHighest,

    inverseSurface = ShareFlowMarpleColors.LightSurface,
    inverseOnSurface = ShareFlowMarpleColors.LightTextPrimary,

    outline = ShareFlowMarpleColors.DarkOutline,
    outlineVariant = ShareFlowMarpleColors.DarkBorder,

    error = ShareFlowMarpleColors.DarkError,
    onError = ShareFlowMarpleColors.DarkOnError,
    errorContainer = ShareFlowMarpleColors.DarkErrorContainer,
    onErrorContainer = ShareFlowMarpleColors.DarkOnErrorContainer,
    scrim = ShareFlowMarpleColors.DarkScrim
)

val ShareFlowBrandColorScheme: ColorScheme = LightColorScheme

fun shareFlowLightColorScheme(accentColor: AccentColor = AccentColor.COBALT): ColorScheme {
    val primarySeed = when (accentColor) {
        AccentColor.COBALT, AccentColor.VIOLET, AccentColor.EMERALD -> ShareFlowMarpleColors.Ruby
        AccentColor.CORAL -> ShareFlowMarpleColors.Coral
        AccentColor.CYAN -> ShareFlowMarpleColors.Peach
    }
    return LightColorScheme.copy(primary = primarySeed)
}

fun shareFlowDarkColorScheme(accentColor: AccentColor = AccentColor.COBALT): ColorScheme {
    val primarySeed = when (accentColor) {
        AccentColor.COBALT, AccentColor.VIOLET, AccentColor.EMERALD -> ShareFlowMarpleColors.DarkPrimary
        AccentColor.CORAL -> ShareFlowMarpleColors.DarkSecondary
        AccentColor.CYAN -> ShareFlowMarpleColors.DarkTertiary
    }
    return DarkColorScheme.copy(primary = primarySeed)
}

/**
 * Preview swatch color for settings badges — strictly bounded to the 4 official Share Flow brand colors.
 */
fun AccentColor.swatchColor(darkTheme: Boolean): Color = when (this) {
    AccentColor.COBALT -> if (darkTheme) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby
    AccentColor.CORAL -> if (darkTheme) ShareFlowMarpleColors.DarkSecondary else ShareFlowMarpleColors.Coral
    AccentColor.CYAN -> if (darkTheme) ShareFlowMarpleColors.DarkTertiary else ShareFlowMarpleColors.Peach
    AccentColor.EMERALD -> ShareFlowMarpleColors.Cream
    AccentColor.VIOLET -> ShareFlowMarpleColors.Ruby
}

/**
 * Semantic status, brand accent, and Liquid Glass tokens complementing ColorScheme.
 */
@Immutable
data class ShareFlowSemanticColors(
    val isDark: Boolean,
    val ruby: Color,
    val coral: Color,
    val peach: Color,
    val cream: Color,
    val sendAccent: Color,
    val onSendAccent: Color,
    val receiveAccent: Color,
    val onReceiveAccent: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val verifiedBadge: Color,
    val onVerifiedBadge: Color,
    val verifiedContainer: Color,
    val warningAccent: Color,
    val warningContainer: Color,
    val disabled: Color,
    val onDisabled: Color,
    val scrim: Color,
    val heroScrimStart: Color,
    val heroScrimEnd: Color,
    val glassNavBackground: Color,
    val glassNavOverlay: Color,
    val glassNavBorder: Color,
    val glassNavActivePill: Color,
    val glassNavSelectedContent: Color,
    val glassNavUnselectedContent: Color,
    val glassCardBorder: Color,
    val softCreamAccent: Color = ShareFlowMarpleColors.Cream,
    val goldenHighlight: Color = ShareFlowMarpleColors.Peach,
    val skyBlueIcon: Color = ShareFlowMarpleColors.Coral,
    val warning: Color = warningAccent
)

val LightShareFlowSemanticColors = ShareFlowSemanticColors(
    isDark = false,
    ruby = ShareFlowMarpleColors.Ruby,
    coral = ShareFlowMarpleColors.Coral,
    peach = ShareFlowMarpleColors.Peach,
    cream = ShareFlowMarpleColors.Cream,
    sendAccent = ShareFlowMarpleColors.Ruby,
    onSendAccent = Color.White,
    receiveAccent = ShareFlowMarpleColors.Coral,
    onReceiveAccent = Color.White,
    success = ShareFlowMarpleColors.LightSuccess,
    onSuccess = ShareFlowMarpleColors.LightOnSuccess,
    successContainer = ShareFlowMarpleColors.LightSuccessContainer,
    verifiedBadge = ShareFlowMarpleColors.Ruby,
    onVerifiedBadge = Color.White,
    verifiedContainer = ShareFlowMarpleColors.Cream,
    warningAccent = ShareFlowMarpleColors.LightWarning,
    warningContainer = ShareFlowMarpleColors.LightWarningContainer,
    disabled = ShareFlowMarpleColors.LightDisabled,
    onDisabled = ShareFlowMarpleColors.LightOnDisabled,
    scrim = ShareFlowMarpleColors.LightScrim,
    heroScrimStart = Color(0xE0252525),
    heroScrimEnd = Color(0x88383838),
    glassNavBackground = ShareFlowMarpleColors.LightGlassNavSurface,
    glassNavOverlay = ShareFlowMarpleColors.LightGlassNavOverlay,
    glassNavBorder = ShareFlowMarpleColors.LightGlassNavBorder,
    glassNavActivePill = ShareFlowMarpleColors.LightGlassNavActiveIndicator,
    glassNavSelectedContent = ShareFlowMarpleColors.Ruby,
    glassNavUnselectedContent = ShareFlowMarpleColors.LightTextSecondary,
    glassCardBorder = ShareFlowMarpleColors.LightBorder
)

val DarkShareFlowSemanticColors = ShareFlowSemanticColors(
    isDark = true,
    ruby = ShareFlowMarpleColors.DarkPrimary,
    coral = ShareFlowMarpleColors.DarkSecondary,
    peach = ShareFlowMarpleColors.DarkTertiary,
    cream = ShareFlowMarpleColors.DarkAccent,
    sendAccent = ShareFlowMarpleColors.Ruby,
    onSendAccent = Color.White,
    receiveAccent = ShareFlowMarpleColors.Coral,
    onReceiveAccent = Color.White,
    success = ShareFlowMarpleColors.DarkSuccess,
    onSuccess = ShareFlowMarpleColors.DarkOnSuccess,
    successContainer = ShareFlowMarpleColors.DarkSuccessContainer,
    verifiedBadge = ShareFlowMarpleColors.DarkAccent,
    onVerifiedBadge = ShareFlowMarpleColors.DarkBackground,
    verifiedContainer = ShareFlowMarpleColors.DarkPrimaryContainer,
    warningAccent = ShareFlowMarpleColors.DarkWarning,
    warningContainer = ShareFlowMarpleColors.DarkWarningContainer,
    disabled = ShareFlowMarpleColors.DarkDisabled,
    onDisabled = ShareFlowMarpleColors.DarkOnDisabled,
    scrim = ShareFlowMarpleColors.DarkScrim,
    heroScrimStart = Color(0xF0171717),
    heroScrimEnd = Color(0xB3292929),
    glassNavBackground = ShareFlowMarpleColors.DarkGlassNavSurface,
    glassNavOverlay = ShareFlowMarpleColors.DarkGlassNavOverlay,
    glassNavBorder = ShareFlowMarpleColors.DarkGlassNavBorder,
    glassNavActivePill = ShareFlowMarpleColors.DarkGlassNavActiveIndicator,
    glassNavSelectedContent = ShareFlowMarpleColors.DarkPrimary,
    glassNavUnselectedContent = ShareFlowMarpleColors.DarkTextSecondary,
    glassCardBorder = ShareFlowMarpleColors.DarkBorder
)

val OfficialShareFlowSemanticColors: ShareFlowSemanticColors = LightShareFlowSemanticColors

val LocalShareFlowSemanticColors = staticCompositionLocalOf { LightShareFlowSemanticColors }
