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
 * SHARE FLOW 2.0 — OFFICIAL COLOR SYSTEM
 * Hybrid Design Direction: Apple Liquid Glass + Samsung One UI + Premium Minimal
 *
 * Immutable Official Brand Palette:
 * - Primary Ruby:    #CA2851 (Core actions, active navigation, brand anchor)
 * - Secondary Coral: #FF6766 (Receive / secondary actions, interactive focus, highlights)
 * - Accent Peach:    #FFB173 (Warm supporting highlights, active gradients, soft badges)
 * - Cream Accent:    #FFE3B3 (Light mode warm glass wash, specular accents, soft surfaces)
 *
 * Dual Independent Theme Treatments:
 * - Light Mode: Clean bright neutral & soft warm cream surfaces with crisp dark ink.
 * - Dark Mode:  Deep Navy & dark neutral surfaces (zero pure black #000000) with crisp light ink.
 */
object ShareFlowMarpleColors {
    // Exact 4 Official Brand Identity Colors (NEVER REPLACED)
    val Ruby: Color = Color(0xFFCA2851)
    val Coral: Color = Color(0xFFFF6766)
    val Peach: Color = Color(0xFFFFB173)
    val Cream: Color = Color(0xFFFFE3B3)

    // Light Mode — Bright Clean Neutral + Subtle Warm Cream Surfaces
    val LightBackground: Color = Color(0xFFFFF9F5)
    val LightSurface: Color = Color(0xFFFFFFFF)
    val LightSurfaceContainerLow: Color = Color(0xFFFCF5F0)
    val LightSurfaceContainer: Color = Color(0xFFF7EEE8)
    val LightSurfaceVariant: Color = Color(0xFFF5EAE3)
    val LightSurfaceContainerHigh: Color = Color(0xFFEFE1D9)
    val LightSurfaceContainerHighest: Color = Color(0xFFE6D5CC)
    val LightTextPrimary: Color = Color(0xFF1D1A22)
    val LightTextSecondary: Color = Color(0xFF615A68)
    val LightBorder: Color = Color(0xFFE9DCE0)
    val LightOutline: Color = Color(0xFFC5B4BC)

    // Light Containers
    val LightRubyContainer: Color = Color(0xFFFFDCE2)
    val LightOnRubyContainer: Color = Color(0xFF470016)
    val LightCoralContainer: Color = Color(0xFFFFDFDD)
    val LightOnCoralContainer: Color = Color(0xFF470308)
    val LightPeachContainer: Color = Color(0xFFFFE9D6)
    val LightOnPeachContainer: Color = Color(0xFF351900)

    // Dark Mode — Deep Navy & Dark Neutral Surfaces (Zero Pure Black)
    val DarkBackground: Color = Color(0xFF0D1220)
    val DarkSurfaceLowest: Color = Color(0xFF090D18)
    val DarkSurface: Color = Color(0xFF141B2D)
    val DarkSurfaceContainerLow: Color = Color(0xFF182136)
    val DarkSurfaceContainer: Color = Color(0xFF1D2740)
    val DarkSurfaceElevated: Color = Color(0xFF232F4D)
    val DarkSurfaceHighest: Color = Color(0xFF2C3A5E)
    val DarkPrimary: Color = Color(0xFFFF6D8C)
    val DarkOnPrimary: Color = Color(0xFF4B0016)
    val DarkPrimaryContainer: Color = Color(0xFF6E1232)
    val DarkOnPrimaryContainer: Color = Color(0xFFFFDCE2)
    val DarkSecondary: Color = Color(0xFFFF8281)
    val DarkOnSecondary: Color = Color(0xFF4D080C)
    val DarkSecondaryContainer: Color = Color(0xFF662028)
    val DarkOnSecondaryContainer: Color = Color(0xFFFFDFDD)
    val DarkTertiary: Color = Color(0xFFFFB173)
    val DarkOnTertiary: Color = Color(0xFF3B1E00)
    val DarkTertiaryContainer: Color = Color(0xFF563414)
    val DarkOnTertiaryContainer: Color = Color(0xFFFFE3C8)
    val DarkAccent: Color = Color(0xFFFFE3B3)
    val DarkTextPrimary: Color = Color(0xFFF4F6FC)
    val DarkTextSecondary: Color = Color(0xFFA6B2CB)
    val DarkBorder: Color = Color(0xFF314063)
    val DarkOutline: Color = Color(0xFF637499)

    // Semantic Status Roles
    val LightError: Color = Color(0xFFC21E2C)
    val LightOnError: Color = Color(0xFFFFFFFF)
    val LightErrorContainer: Color = Color(0xFFFFDAD9)
    val LightOnErrorContainer: Color = Color(0xFF410004)

    val DarkError: Color = Color(0xFFFFB3B0)
    val DarkOnError: Color = Color(0xFF68000A)
    val DarkErrorContainer: Color = Color(0xFF8C141F)
    val DarkOnErrorContainer: Color = Color(0xFFFFDAD9)

    val LightSuccess: Color = Color(0xFF18794E)
    val LightOnSuccess: Color = Color(0xFFFFFFFF)
    val LightSuccessContainer: Color = Color(0xFFD9F6E6)

    val DarkSuccess: Color = Color(0xFF5DD89F)
    val DarkOnSuccess: Color = Color(0xFF04341E)
    val DarkSuccessContainer: Color = Color(0xFF133B2E)

    val LightWarning: Color = Color(0xFFB35812)
    val LightWarningContainer: Color = Color(0xFFFFE7C2)
    val DarkWarning: Color = Color(0xFFFFB173)
    val DarkWarningContainer: Color = Color(0xFF4A2E14)

    val LightDisabled: Color = Color(0xFFE0D5DA)
    val LightOnDisabled: Color = Color(0xFF8F848C)
    val DarkDisabled: Color = Color(0xFF26314A)
    val DarkOnDisabled: Color = Color(0xFF6C7A99)

    val LightScrim: Color = Color(0x660D1220)
    val DarkScrim: Color = Color(0xA6050811)
    val Scrim: Color = DarkScrim

    // Liquid Glass Navigation & Surface Tokens
    val LightGlassNavSurface: Color = Color(0xD9FFFFFF)
    val LightGlassNavOverlay: Color = Color(0x4DFFE3B3)
    val LightGlassNavBorder: Color = Color(0xBFE9DCE0)
    val LightGlassNavActiveIndicator: Color = Color(0x1DCA2851)

    val DarkGlassNavSurface: Color = Color(0xD4141B2D)
    val DarkGlassNavOverlay: Color = Color(0x38232F4D)
    val DarkGlassNavBorder: Color = Color(0x99384A73)
    val DarkGlassNavActiveIndicator: Color = Color(0x33FF6D8C)
}

/**
 * Share Flow 2.0 primary color token namespace alias.
 */
typealias SFColors = ShareFlowMarpleColors

/**
 * Backward-compatible token alias object so every existing screen reference resolves cleanly
 * to the Share Flow 2.0 color tokens.
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
 * Official 4-Color Brand Gradients:
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
 * Liquid Glass Surface & Specular Highlight tokens for Light Mode & Deep Navy Dark Mode.
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
    cardBackground = Color(0xE0FFFFFF),
    cardElevatedBackground = Color(0xF0FFFFFF),
    cardSubtleBackground = Color(0xB8FCF5F0),
    topGlaze = Color(0x99FFFFFF),
    bottomGlaze = Color(0x1FFFE3B3),
    borderTop = Color(0xF2FFFFFF),
    borderMiddle = Color(0x99E9DCE0),
    borderBottom = Color(0xCCE5D5DA),
    activeBorder = ShareFlowMarpleColors.Ruby.copy(alpha = 0.60f),
    ambientShadow = Color(0x1A1D1A22),
    spotShadow = ShareFlowMarpleColors.Ruby.copy(alpha = 0.12f),
    navBase = Color(0xCCFFFFFF),
    navGlazeTop = Color(0x88FFFFFF),
    navGlazeBottom = Color(0x38FFE3B3),
    navBorderTop = Color(0xFAFFFFFF),
    navBorderBottom = Color(0xCCE3D3D8),
    navActivePill = ShareFlowMarpleColors.Ruby.copy(alpha = 0.11f),
    overlayBackground = Color(0xF7FFFBF8),
    snackbarBackground = Color(0xF21D2740),
    snackbarContent = Color(0xFFF4F6FC),
    ambientGlowPrimary = ShareFlowMarpleColors.Cream.copy(alpha = 0.56f),
    ambientGlowSecondary = ShareFlowMarpleColors.Peach.copy(alpha = 0.18f),
    ambientGlowTertiary = ShareFlowMarpleColors.Ruby.copy(alpha = 0.06f)
)

val DarkSFGlassTokens = SFGlassTokens(
    isDark = true,
    cardBackground = Color(0xCC182136),
    cardElevatedBackground = Color(0xE01F2B47),
    cardSubtleBackground = Color(0xA6141B2D),
    topGlaze = Color(0x1CFFFFFF),
    bottomGlaze = Color(0x14090D18),
    borderTop = Color(0x36FFFFFF),
    borderMiddle = Color(0x70314063),
    borderBottom = Color(0x24FFFFFF),
    activeBorder = ShareFlowMarpleColors.DarkPrimary.copy(alpha = 0.65f),
    ambientShadow = Color(0x8803060E),
    spotShadow = ShareFlowMarpleColors.Ruby.copy(alpha = 0.22f),
    navBase = Color(0xBD141B2D),
    navGlazeTop = Color(0x22FFFFFF),
    navGlazeBottom = Color(0x4D0D1220),
    navBorderTop = Color(0x40FFFFFF),
    navBorderBottom = Color(0x73314063),
    navActivePill = ShareFlowMarpleColors.DarkPrimary.copy(alpha = 0.18f),
    overlayBackground = Color(0xF5182136),
    snackbarBackground = Color(0xF5232F4D),
    snackbarContent = Color(0xFFF4F6FC),
    ambientGlowPrimary = ShareFlowMarpleColors.Ruby.copy(alpha = 0.16f),
    ambientGlowSecondary = Color(0xFF243B6B).copy(alpha = 0.34f),
    ambientGlowTertiary = ShareFlowMarpleColors.Peach.copy(alpha = 0.09f)
)

val LocalSFGlassTokens = staticCompositionLocalOf { LightSFGlassTokens }

/**
 * Light Mode ColorScheme built on Share Flow 2.0 neutral + warm cream surfaces & official brand palette.
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
 * Deep Navy Dark Mode ColorScheme built on Share Flow 2.0 dark neutral surfaces & controlled brand accents.
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
    heroScrimStart = Color(0xE61D1A22),
    heroScrimEnd = Color(0x99471329),
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
    heroScrimStart = Color(0xF00D1220),
    heroScrimEnd = Color(0xAA1D2740),
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
