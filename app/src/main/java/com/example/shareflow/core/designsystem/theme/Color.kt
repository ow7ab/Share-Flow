package com.example.shareflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.shareflow.domain.model.AccentColor

/**
 * OFFICIAL SHARE FLOW BRAND COLOR SYSTEM
 *
 * Centralized, immutable brand color tokens implementing the mandatory 5-color Share Flow
 * visual identity with a refined iOS-inspired Liquid Glass aesthetic over Deep Navy.
 *
 * Mandatory Brand HEX Palette:
 * - Deep Navy:     #0A1821 (Primary application background)
 * - Royal Blue:    #1D59BB (Primary actions, buttons, selected navigation states)
 * - Sky Blue:      #76B9FF (Secondary actions, icons, glass borders, supporting visuals)
 * - Soft Cream:    #FEF3B3 (Selected light surfaces, subtle highlights, readable text accents)
 * - Golden Yellow: #FEBD19 (Important highlights, status indicators, visual emphasis)
 */
object ShareFlowBrandColors {
    // Mandatory Official Brand Colors (Exact HEX)
    val DeepNavy: Color = Color(0xFF0A1821)
    val RoyalBlue: Color = Color(0xFF1D59BB)
    val SkyBlue: Color = Color(0xFF76B9FF)
    val SoftCream: Color = Color(0xFFFEF3B3)
    val GoldenYellow: Color = Color(0xFFFEBD19)

    // Liquid Glass Surface Elevation Ladder (strictly derived from Deep Navy #0A1821)
    val DeepNavyLowest: Color = Color(0xFF071118)
    val GlassSurfaceLow: Color = Color(0xFF102331)
    val GlassSurface: Color = Color(0xFF152C3D)
    val GlassSurfaceHigh: Color = Color(0xFF1B374D)
    val GlassSurfaceHighest: Color = Color(0xFF234560)

    // Floating Glass Navigation & Translucent Border Tokens
    val GlassNavBackground: Color = Color(0xE00A1821)
    val GlassNavBorder: Color = Color(0x5276B9FF)
    val GlassCardBorder: Color = Color(0x3376B9FF)
    val GlassActiveBorder: Color = Color(0x8876B9FF)
    val GlassSoftCreamHighlight: Color = Color(0x55FEF3B3)

    // Brand Containers (derived exclusively from Royal Blue, Sky Blue, Golden Yellow on Deep Navy)
    val RoyalBlueContainer: Color = Color(0xFF153D7E)
    val SkyBlueContainer: Color = Color(0xFF123452)
    val GoldenYellowContainer: Color = Color(0xFF33280B)

    // High-Contrast Readable Ink Tokens on Deep Navy / Brand Surfaces (WCAG AA+ Verified)
    val InkOnDeepNavy: Color = Color(0xFFF6FAFF)
    val InkSecondaryOnDeepNavy: Color = Color(0xFFBBD5EC)
    val InkOnRoyalBlue: Color = Color(0xFFFFFFFF)
    val InkOnSkyBlue: Color = DeepNavy
    val InkOnGoldenYellow: Color = DeepNavy
    val InkOnSoftCream: Color = DeepNavy

    // System Error Tokens (reserved exclusively for destructive/failure states)
    val ErrorSurfaceAccent: Color = Color(0xFFFF8A80)
    val OnErrorSurfaceAccent: Color = DeepNavy
    val ErrorContainerGlass: Color = Color(0xFF47181E)
    val OnErrorContainerGlass: Color = Color(0xFFFFDAD6)
}

/**
 * Official Share Flow Signature Gradient:
 * Royal Blue (#1D59BB) -> Sky Blue (#76B9FF) -> Soft Cream (#FEF3B3) -> Golden Yellow (#FEBD19)
 */
object ShareFlowBrandGradients {
    val SignatureStops: List<Color> = listOf(
        ShareFlowBrandColors.RoyalBlue,
        ShareFlowBrandColors.SkyBlue,
        ShareFlowBrandColors.SoftCream,
        ShareFlowBrandColors.GoldenYellow
    )

    fun signatureHorizontal(): Brush = Brush.horizontalGradient(colors = SignatureStops)

    fun signatureLinear(): Brush = Brush.linearGradient(colors = SignatureStops)

    fun signatureBorder(alpha: Float = 0.85f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun signatureSubtleGlow(alpha: Float = 0.24f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    /**
     * Liquid Glass hero & primary button sheen keeping crisp white text legibility
     * while honoring Royal Blue -> Sky Blue depth.
     */
    fun primaryActionSheen(): Brush = Brush.horizontalGradient(
        colors = listOf(
            ShareFlowBrandColors.RoyalBlue,
            Color(0xFF286ED4),
            ShareFlowBrandColors.RoyalBlue
        )
    )
}

/**
 * Official Material 3 ColorScheme implementing the mandatory Share Flow Brand Color System.
 */
val ShareFlowBrandColorScheme: ColorScheme = darkColorScheme(
    primary = ShareFlowBrandColors.RoyalBlue,
    onPrimary = ShareFlowBrandColors.InkOnRoyalBlue,
    primaryContainer = ShareFlowBrandColors.RoyalBlueContainer,
    onPrimaryContainer = ShareFlowBrandColors.SoftCream,
    inversePrimary = ShareFlowBrandColors.SkyBlue,

    secondary = ShareFlowBrandColors.SkyBlue,
    onSecondary = ShareFlowBrandColors.InkOnSkyBlue,
    secondaryContainer = ShareFlowBrandColors.SkyBlueContainer,
    onSecondaryContainer = ShareFlowBrandColors.SkyBlue,

    tertiary = ShareFlowBrandColors.GoldenYellow,
    onTertiary = ShareFlowBrandColors.InkOnGoldenYellow,
    tertiaryContainer = ShareFlowBrandColors.GoldenYellowContainer,
    onTertiaryContainer = ShareFlowBrandColors.SoftCream,

    background = ShareFlowBrandColors.DeepNavy,
    onBackground = ShareFlowBrandColors.InkOnDeepNavy,
    surface = ShareFlowBrandColors.DeepNavy,
    onSurface = ShareFlowBrandColors.InkOnDeepNavy,
    surfaceVariant = ShareFlowBrandColors.GlassSurface,
    onSurfaceVariant = ShareFlowBrandColors.InkSecondaryOnDeepNavy,

    surfaceContainerLowest = ShareFlowBrandColors.DeepNavyLowest,
    surfaceContainerLow = ShareFlowBrandColors.GlassSurfaceLow,
    surfaceContainer = ShareFlowBrandColors.GlassSurface,
    surfaceContainerHigh = ShareFlowBrandColors.GlassSurfaceHigh,
    surfaceContainerHighest = ShareFlowBrandColors.GlassSurfaceHighest,

    inverseSurface = ShareFlowBrandColors.SoftCream,
    inverseOnSurface = ShareFlowBrandColors.InkOnSoftCream,

    outline = ShareFlowBrandColors.SkyBlue.copy(alpha = 0.48f),
    outlineVariant = ShareFlowBrandColors.GlassCardBorder,

    error = ShareFlowBrandColors.ErrorSurfaceAccent,
    onError = ShareFlowBrandColors.OnErrorSurfaceAccent,
    errorContainer = ShareFlowBrandColors.ErrorContainerGlass,
    onErrorContainer = ShareFlowBrandColors.OnErrorContainerGlass
)

// Backward-compatible scheme aliases bound strictly to the official Share Flow brand palette
val DarkColorScheme: ColorScheme = ShareFlowBrandColorScheme
val LightColorScheme: ColorScheme = ShareFlowBrandColorScheme

fun shareFlowDarkColorScheme(@Suppress("UNUSED_PARAMETER") accentColor: AccentColor): ColorScheme =
    ShareFlowBrandColorScheme

fun shareFlowLightColorScheme(@Suppress("UNUSED_PARAMETER") accentColor: AccentColor): ColorScheme =
    ShareFlowBrandColorScheme

/**
 * Preview swatch color for settings badges — strictly bounded to official Share Flow brand colors.
 */
fun AccentColor.swatchColor(@Suppress("UNUSED_PARAMETER") darkTheme: Boolean): Color = when (this) {
    AccentColor.COBALT -> ShareFlowBrandColors.RoyalBlue
    AccentColor.CYAN -> ShareFlowBrandColors.SkyBlue
    AccentColor.EMERALD -> ShareFlowBrandColors.SoftCream
    AccentColor.CORAL -> ShareFlowBrandColors.GoldenYellow
    AccentColor.VIOLET -> ShareFlowBrandColors.RoyalBlue
}

/**
 * Semantic status and Liquid Glass brand tokens complementing Material 3 ColorScheme.
 */
@Immutable
data class ShareFlowSemanticColors(
    val sendAccent: Color,
    val onSendAccent: Color,
    val receiveAccent: Color,
    val onReceiveAccent: Color,
    val verifiedBadge: Color,
    val onVerifiedBadge: Color,
    val verifiedContainer: Color,
    val warningAccent: Color,
    val warningContainer: Color,
    val heroScrimStart: Color,
    val heroScrimEnd: Color,
    val glassNavBackground: Color = ShareFlowBrandColors.GlassNavBackground,
    val glassNavBorder: Color = ShareFlowBrandColors.GlassNavBorder,
    val glassCardBorder: Color = ShareFlowBrandColors.GlassCardBorder,
    val softCreamAccent: Color = ShareFlowBrandColors.SoftCream,
    val goldenHighlight: Color = ShareFlowBrandColors.GoldenYellow,
    val skyBlueIcon: Color = ShareFlowBrandColors.SkyBlue
)

val OfficialShareFlowSemanticColors = ShareFlowSemanticColors(
    sendAccent = ShareFlowBrandColors.RoyalBlue,
    onSendAccent = ShareFlowBrandColors.InkOnRoyalBlue,
    receiveAccent = ShareFlowBrandColors.SkyBlue,
    onReceiveAccent = ShareFlowBrandColors.InkOnSkyBlue,
    verifiedBadge = ShareFlowBrandColors.GoldenYellow,
    onVerifiedBadge = ShareFlowBrandColors.InkOnGoldenYellow,
    verifiedContainer = ShareFlowBrandColors.GoldenYellowContainer,
    warningAccent = ShareFlowBrandColors.SoftCream,
    warningContainer = ShareFlowBrandColors.GoldenYellowContainer,
    heroScrimStart = Color(0xF20A1821),
    heroScrimEnd = Color(0xA60A1821),
    glassNavBackground = ShareFlowBrandColors.GlassNavBackground,
    glassNavBorder = ShareFlowBrandColors.GlassNavBorder,
    glassCardBorder = ShareFlowBrandColors.GlassCardBorder,
    softCreamAccent = ShareFlowBrandColors.SoftCream,
    goldenHighlight = ShareFlowBrandColors.GoldenYellow,
    skyBlueIcon = ShareFlowBrandColors.SkyBlue
)

val LightShareFlowSemanticColors: ShareFlowSemanticColors = OfficialShareFlowSemanticColors
val DarkShareFlowSemanticColors: ShareFlowSemanticColors = OfficialShareFlowSemanticColors

val LocalShareFlowSemanticColors = staticCompositionLocalOf { OfficialShareFlowSemanticColors }
