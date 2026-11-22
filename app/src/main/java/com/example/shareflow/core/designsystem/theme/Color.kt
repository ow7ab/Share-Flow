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
 * OFFICIAL SHAREFLOW MARPLE COLOR SYSTEM + MATERIAL 3 + iOS GLASSMORPHISM
 *
 * Primary Marple Reference Palette:
 * - Ruby:  #CA2851 (Main action buttons, selected navigation, active controls, primary progress)
 * - Coral: #FF6766 (Secondary actions, interactive highlights, selected chips, active tabs, focus)
 * - Peach: #FFB173 (Secondary cards, informational elements, soft backgrounds, progress highlights)
 * - Cream: #FFE3B3 (Light theme accent surfaces, selected content backgrounds, soft separators)
 */
object ShareFlowMarpleColors {
    // Exact 4 Marple Primary Identity Colors
    val Ruby: Color = Color(0xFFCA2851)
    val Coral: Color = Color(0xFFFF6766)
    val Peach: Color = Color(0xFFFFB173)
    val Cream: Color = Color(0xFFFFE3B3)

    // Light Theme Surfaces & Typography
    val LightBackground: Color = Color(0xFFFFF9F5)
    val LightSurface: Color = Color(0xFFFFFFFF)
    val LightSurfaceContainerLow: Color = Color(0xFFFCF3EE)
    val LightSurfaceContainer: Color = Color(0xFFF8EDE8)
    val LightSurfaceVariant: Color = Color(0xFFF8EDE8)
    val LightSurfaceContainerHigh: Color = Color(0xFFF2E2DC)
    val LightSurfaceContainerHighest: Color = Color(0xFFEAD7D0)
    val LightTextPrimary: Color = Color(0xFF292126)
    val LightTextSecondary: Color = Color(0xFF75676D)
    val LightBorder: Color = Color(0xFFEADDE0)
    val LightOutline: Color = Color(0xFFCBB6BC)

    // Light Containers
    val LightRubyContainer: Color = Color(0xFFFFD9DF)
    val LightOnRubyContainer: Color = Color(0xFF3F0013)
    val LightCoralContainer: Color = Color(0xFFFFDAD7)
    val LightOnCoralContainer: Color = Color(0xFF410004)
    val LightPeachContainer: Color = Color(0xFFFFE5CF)
    val LightOnPeachContainer: Color = Color(0xFF2E1500)

    // Dark Theme Surfaces & Typography (Adapted Marple contrast, zero pure black)
    val DarkBackground: Color = Color(0xFF171316)
    val DarkSurface: Color = Color(0xFF211A1E)
    val DarkSurfaceLowest: Color = Color(0xFF120E11)
    val DarkSurfaceContainerLow: Color = Color(0xFF251C21)
    val DarkSurfaceContainer: Color = Color(0xFF2B2026)
    val DarkSurfaceElevated: Color = Color(0xFF34252C)
    val DarkSurfaceHighest: Color = Color(0xFF3F2D36)
    val DarkPrimary: Color = Color(0xFFFF8198)
    val DarkOnPrimary: Color = Color(0xFF54001A)
    val DarkPrimaryContainer: Color = Color(0xFF7D1132)
    val DarkOnPrimaryContainer: Color = Color(0xFFFFD9DF)
    val DarkSecondary: Color = Color(0xFFFF8986)
    val DarkOnSecondary: Color = Color(0xFF56090C)
    val DarkSecondaryContainer: Color = Color(0xFF732325)
    val DarkOnSecondaryContainer: Color = Color(0xFFFFDAD7)
    val DarkTertiary: Color = Color(0xFFFFB77F)
    val DarkOnTertiary: Color = Color(0xFF4B2700)
    val DarkTertiaryContainer: Color = Color(0xFF693B0C)
    val DarkOnTertiaryContainer: Color = Color(0xFFFFDCC3)
    val DarkAccent: Color = Color(0xFFFFE3B3)
    val DarkTextPrimary: Color = Color(0xFFFFF5F1)
    val DarkTextSecondary: Color = Color(0xFFD0BFC3)
    val DarkBorder: Color = Color(0xFF554149)
    val DarkOutline: Color = Color(0xFF8C747C)

    // Semantic Status Roles (Error, Success, Warning, Disabled, Scrim)
    val LightError: Color = Color(0xFFBA1A1A)
    val LightOnError: Color = Color(0xFFFFFFFF)
    val LightErrorContainer: Color = Color(0xFFFFDAD6)
    val LightOnErrorContainer: Color = Color(0xFF410002)

    val DarkError: Color = Color(0xFFFFB4AB)
    val DarkOnError: Color = Color(0xFF690005)
    val DarkErrorContainer: Color = Color(0xFF93000A)
    val DarkOnErrorContainer: Color = Color(0xFFFFDAD6)

    val LightSuccess: Color = Color(0xFF1E7E53)
    val LightOnSuccess: Color = Color(0xFFFFFFFF)
    val LightSuccessContainer: Color = Color(0xFFD7F5E4)

    val DarkSuccess: Color = Color(0xFF72D9A6)
    val DarkOnSuccess: Color = Color(0xFF063821)
    val DarkSuccessContainer: Color = Color(0xFF135134)

    val LightWarning: Color = Color(0xFFB85D19)
    val LightWarningContainer: Color = Color(0xFFFFE3B3)
    val DarkWarning: Color = Color(0xFFFFB173)
    val DarkWarningContainer: Color = Color(0xFF5A3210)

    val LightDisabled: Color = Color(0xFFD9CCD0)
    val LightOnDisabled: Color = Color(0xFF8E7E84)
    val DarkDisabled: Color = Color(0xFF3E3037)
    val DarkOnDisabled: Color = Color(0xFF7E6B73)

    val Scrim: Color = Color(0x88171316)

    // iOS-Inspired Glassmorphism Navigation Tokens
    val LightGlassNavSurface: Color = Color(0xD9FFF9F5)
    val LightGlassNavOverlay: Color = Color(0x66FFE3B3)
    val LightGlassNavBorder: Color = Color(0xB3EADDE0)
    val LightGlassNavActiveIndicator: Color = Color(0x1FCA2851)

    val DarkGlassNavSurface: Color = Color(0xD6211A1E)
    val DarkGlassNavOverlay: Color = Color(0x3334252C)
    val DarkGlassNavBorder: Color = Color(0x80554149)
    val DarkGlassNavActiveIndicator: Color = Color(0x33FF8198)
}

/**
 * Backward-compatible token alias object so every existing screen reference resolves cleanly
 * to the Marple Design System tokens.
 */
object ShareFlowBrandColors {
    val Ruby: Color = ShareFlowMarpleColors.Ruby
    val Coral: Color = ShareFlowMarpleColors.Coral
    val Peach: Color = ShareFlowMarpleColors.Peach
    val Cream: Color = ShareFlowMarpleColors.Cream

    // Mapped semantic aliases for existing components
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
 * Official Marple 4-Color Signature Gradient:
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

    fun signatureHorizontal(): Brush = Brush.horizontalGradient(colors = SignatureStops)

    fun signatureLinear(): Brush = Brush.linearGradient(colors = SignatureStops)

    fun signatureBorder(alpha: Float = 0.85f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun signatureSubtleGlow(alpha: Float = 0.26f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun primaryActionSheen(): Brush = Brush.horizontalGradient(
        colors = MarpleActionStops
    )
}

/**
 * Complete Material 3 Light Theme ColorScheme built on the Marple visual identity.
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
    scrim = ShareFlowMarpleColors.Scrim
)

/**
 * Complete Material 3 Dark Theme ColorScheme built on the adapted Marple visual identity.
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
    scrim = ShareFlowMarpleColors.Scrim
)

val ShareFlowBrandColorScheme: ColorScheme = LightColorScheme

fun shareFlowLightColorScheme(accentColor: AccentColor = AccentColor.COBALT): ColorScheme {
    val primarySeed = when (accentColor) {
        AccentColor.COBALT, AccentColor.VIOLET -> ShareFlowMarpleColors.Ruby
        AccentColor.CORAL -> ShareFlowMarpleColors.Coral
        AccentColor.CYAN -> ShareFlowMarpleColors.Peach
        AccentColor.EMERALD -> ShareFlowMarpleColors.Ruby
    }
    return LightColorScheme.copy(primary = primarySeed)
}

fun shareFlowDarkColorScheme(accentColor: AccentColor = AccentColor.COBALT): ColorScheme {
    val primarySeed = when (accentColor) {
        AccentColor.COBALT, AccentColor.VIOLET -> ShareFlowMarpleColors.DarkPrimary
        AccentColor.CORAL -> ShareFlowMarpleColors.DarkSecondary
        AccentColor.CYAN -> ShareFlowMarpleColors.DarkTertiary
        AccentColor.EMERALD -> ShareFlowMarpleColors.DarkPrimary
    }
    return DarkColorScheme.copy(primary = primarySeed)
}

/**
 * Preview swatch color for settings badges — bounded to the 4 Marple reference colors.
 */
fun AccentColor.swatchColor(darkTheme: Boolean): Color = when (this) {
    AccentColor.COBALT -> if (darkTheme) ShareFlowMarpleColors.DarkPrimary else ShareFlowMarpleColors.Ruby
    AccentColor.CORAL -> if (darkTheme) ShareFlowMarpleColors.DarkSecondary else ShareFlowMarpleColors.Coral
    AccentColor.CYAN -> if (darkTheme) ShareFlowMarpleColors.DarkTertiary else ShareFlowMarpleColors.Peach
    AccentColor.EMERALD -> ShareFlowMarpleColors.Cream
    AccentColor.VIOLET -> ShareFlowMarpleColors.Ruby
}

/**
 * Semantic status, Marple accent, and iOS Glassmorphism tokens complementing Material 3 ColorScheme.
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
    scrim = ShareFlowMarpleColors.Scrim,
    heroScrimStart = Color(0xE629151D),
    heroScrimEnd = Color(0x99521B2D),
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
    onReceiveAccent = ShareFlowMarpleColors.DarkBackground,
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
    scrim = ShareFlowMarpleColors.Scrim,
    heroScrimStart = Color(0xF0171316),
    heroScrimEnd = Color(0xAA2B2026),
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
