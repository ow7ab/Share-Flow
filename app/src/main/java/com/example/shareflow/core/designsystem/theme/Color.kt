package com.example.shareflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.shareflow.domain.model.AccentColor

/**
 * OFFICIAL SHARE FLOW BRAND COLOR SYSTEM — SOFT CREAM (#FEF3B3) PRIMARY IDENTITY
 *
 * Centralized, immutable brand color tokens built around Soft Cream (#FEF3B3) as the
 * primary application canvas color, paired with Deep Navy typography, Royal Blue primary
 * actions, Sky Blue secondary glass accents, Golden Yellow highlights, and the Signature
 * Gradient across a luminous Liquid Glass surface hierarchy.
 *
 * Mandatory Brand HEX Palette:
 * - Soft Cream:    #FEF3B3 (Primary application background & main brand canvas)
 * - Deep Navy:     #0A1821 (High-contrast typography, floating glass nav capsule, hero depth)
 * - Royal Blue:    #1D59BB (Primary actions, buttons, selected navigation states)
 * - Sky Blue:      #76B9FF (Secondary actions, glass borders, supporting visual accents)
 * - Golden Yellow: #FEBD19 (Important highlights, status indicators, visual emphasis)
 */
object ShareFlowBrandColors {
    // Mandatory Official Brand Colors (Exact HEX)
    val SoftCream: Color = Color(0xFFFEF3B3)
    val DeepNavy: Color = Color(0xFF0A1821)
    val RoyalBlue: Color = Color(0xFF1D59BB)
    val SkyBlue: Color = Color(0xFF76B9FF)
    val GoldenYellow: Color = Color(0xFFFEBD19)

    // Luminous Liquid Glass Surface Elevation Ladder (derived from Soft Cream #FEF3B3)
    val GlassSurfaceLowest: Color = Color(0xFFFFFCEB)
    val GlassSurfaceLow: Color = Color(0xFFFFF9DC)
    val GlassSurface: Color = SoftCream
    val GlassSurfaceHigh: Color = Color(0xFFF6E79A)
    val GlassSurfaceHighest: Color = Color(0xFFECE08B)

    // Deep Navy elevation alias retained for dark media/viewer overlays
    val DeepNavyLowest: Color = Color(0xFF071118)
    val DeepNavyGlassSurface: Color = Color(0xFF102331)

    // Floating Glass Navigation & Translucent Border Tokens
    val GlassNavBackground: Color = Color(0xEB0A1821)
    val GlassNavBorder: Color = Color(0x6676B9FF)
    val GlassCardBorder: Color = Color(0x471D59BB)
    val GlassActiveBorder: Color = Color(0x991D59BB)
    val GlassSoftCreamHighlight: Color = Color(0x88FEF3B3)

    // Brand Containers (derived exclusively from Royal Blue, Sky Blue, Golden Yellow on Soft Cream)
    val RoyalBlueContainer: Color = Color(0xFFDCEBFF)
    val SkyBlueContainer: Color = Color(0xFFE3F2FF)
    val GoldenYellowContainer: Color = Color(0xFFFFE596)

    // High-Contrast Readable Ink Tokens (WCAG AA/AAA Verified)
    val InkOnSoftCream: Color = DeepNavy                    // 15.8:1 contrast on #FEF3B3
    val InkSecondaryOnSoftCream: Color = Color(0xFF233B4D)  // 9.8:1 contrast on #FEF3B3
    val InkOnDeepNavy: Color = Color(0xFFF6FAFF)            // 17.0:1 contrast on #0A1821
    val InkSecondaryOnDeepNavy: Color = Color(0xFFBBD5EC)   // 11.4:1 contrast on #0A1821
    val InkOnRoyalBlue: Color = Color(0xFFFFFFFF)           // 6.7:1 contrast on #1D59BB
    val InkOnSkyBlue: Color = DeepNavy                      // 8.8:1 contrast on #76B9FF
    val InkOnGoldenYellow: Color = DeepNavy                 // 11.1:1 contrast on #FEBD19

    // System Error Tokens (high contrast on Soft Cream #FEF3B3)
    val ErrorSurfaceAccent: Color = Color(0xFFB3261E)
    val OnErrorSurfaceAccent: Color = Color(0xFFFFFFFF)
    val ErrorContainerGlass: Color = Color(0xFFFFDAD6)
    val OnErrorContainerGlass: Color = Color(0xFF410002)
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

    fun signatureBorder(alpha: Float = 0.90f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun signatureSubtleGlow(alpha: Float = 0.28f): Brush = Brush.horizontalGradient(
        colors = SignatureStops.map { it.copy(alpha = alpha) }
    )

    fun primaryActionSheen(): Brush = Brush.horizontalGradient(
        colors = listOf(
            ShareFlowBrandColors.RoyalBlue,
            Color(0xFF286ED4),
            ShareFlowBrandColors.RoyalBlue
        )
    )
}

/**
 * Official Material 3 ColorScheme with Soft Cream (#FEF3B3) as the primary application color.
 */
val ShareFlowBrandColorScheme: ColorScheme = lightColorScheme(
    primary = ShareFlowBrandColors.RoyalBlue,
    onPrimary = ShareFlowBrandColors.InkOnRoyalBlue,
    primaryContainer = ShareFlowBrandColors.RoyalBlueContainer,
    onPrimaryContainer = ShareFlowBrandColors.DeepNavy,
    inversePrimary = ShareFlowBrandColors.SkyBlue,

    secondary = ShareFlowBrandColors.SkyBlue,
    onSecondary = ShareFlowBrandColors.InkOnSkyBlue,
    secondaryContainer = ShareFlowBrandColors.SkyBlueContainer,
    onSecondaryContainer = ShareFlowBrandColors.DeepNavy,

    tertiary = ShareFlowBrandColors.GoldenYellow,
    onTertiary = ShareFlowBrandColors.InkOnGoldenYellow,
    tertiaryContainer = ShareFlowBrandColors.GoldenYellowContainer,
    onTertiaryContainer = ShareFlowBrandColors.DeepNavy,

    background = ShareFlowBrandColors.SoftCream,
    onBackground = ShareFlowBrandColors.InkOnSoftCream,
    surface = ShareFlowBrandColors.SoftCream,
    onSurface = ShareFlowBrandColors.InkOnSoftCream,
    surfaceVariant = ShareFlowBrandColors.GlassSurfaceHigh,
    onSurfaceVariant = ShareFlowBrandColors.InkSecondaryOnSoftCream,

    surfaceContainerLowest = ShareFlowBrandColors.GlassSurfaceLowest,
    surfaceContainerLow = ShareFlowBrandColors.GlassSurfaceLow,
    surfaceContainer = ShareFlowBrandColors.GlassSurface,
    surfaceContainerHigh = ShareFlowBrandColors.GlassSurfaceHigh,
    surfaceContainerHighest = ShareFlowBrandColors.GlassSurfaceHighest,

    inverseSurface = ShareFlowBrandColors.DeepNavy,
    inverseOnSurface = ShareFlowBrandColors.SoftCream,

    outline = ShareFlowBrandColors.RoyalBlue.copy(alpha = 0.45f),
    outlineVariant = ShareFlowBrandColors.GlassCardBorder,

    error = ShareFlowBrandColors.ErrorSurfaceAccent,
    onError = ShareFlowBrandColors.OnErrorSurfaceAccent,
    errorContainer = ShareFlowBrandColors.ErrorContainerGlass,
    onErrorContainer = ShareFlowBrandColors.OnErrorContainerGlass
)

// Backward-compatible scheme aliases bound strictly to the official Soft Cream (#FEF3B3) brand palette
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
    AccentColor.VIOLET -> ShareFlowBrandColors.DeepNavy
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
    verifiedBadge = ShareFlowBrandColors.DeepNavy,
    onVerifiedBadge = ShareFlowBrandColors.SoftCream,
    verifiedContainer = ShareFlowBrandColors.GoldenYellowContainer,
    warningAccent = ShareFlowBrandColors.DeepNavy,
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
