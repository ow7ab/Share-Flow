package com.example.shareflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.example.shareflow.domain.model.AccentColor

// ============================================================================
// Core Brand Palette — Tactile Signal & Velocity (Default Cobalt)
// ============================================================================
val CobaltPrimaryLight = Color(0xFF1A47E5)
val OnCobaltPrimaryLight = Color(0xFFFFFFFF)
val CobaltContainerLight = Color(0xFFDCE4FF)
val OnCobaltContainerLight = Color(0xFF061B66)

val CyanSecondaryLight = Color(0xFF007891)
val OnCyanSecondaryLight = Color(0xFFFFFFFF)
val CyanContainerLight = Color(0xFFC5F2FF)
val OnCyanContainerLight = Color(0xFF002630)

val CoralTertiaryLight = Color(0xFFD84E24)
val OnCoralTertiaryLight = Color(0xFFFFFFFF)
val CoralContainerLight = Color(0xFFFFDBD0)
val OnCoralTertiaryContainerLight = Color(0xFF3E1000)

// Additional User-Selectable Accent Palettes (WCAG AA+ Contrast Verified)
val EmeraldPrimaryLight = Color(0xFF0B7A53)
val OnEmeraldPrimaryLight = Color(0xFFFFFFFF)
val EmeraldContainerLight = Color(0xFFC9F6E2)
val OnEmeraldContainerLight = Color(0xFF002819)

val VioletPrimaryLight = Color(0xFF6342D6)
val OnVioletPrimaryLight = Color(0xFFFFFFFF)
val VioletContainerLight = Color(0xFFE6DEFF)
val OnVioletContainerLight = Color(0xFF1E0866)

val SurfaceLight = Color(0xFFF6F8FC)
val OnSurfaceLight = Color(0xFF0F172A)
val SurfaceVariantLight = Color(0xFFE7ECF6)
val OnSurfaceVariantLight = Color(0xFF424E66)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF0F4FA)
val SurfaceContainerLight = Color(0xFFE9EFF8)
val SurfaceContainerHighLight = Color(0xFFE1E8F3)
val SurfaceContainerHighestLight = Color(0xFFD7E0EE)
val OutlineLight = Color(0xFF6F7D97)
val OutlineVariantLight = Color(0xFFC8D2E3)

// ============================================================================
// Dark Mode — Deep Midnight Signal
// ============================================================================
val CobaltPrimaryDark = Color(0xFF8CA9FF)
val OnCobaltPrimaryDark = Color(0xFF00237A)
val CobaltContainerDark = Color(0xFF0D33B3)
val OnCobaltContainerDark = Color(0xFFDCE4FF)

val CyanSecondaryDark = Color(0xFF4DE0FF)
val OnCyanSecondaryDark = Color(0xFF003642)
val CyanContainerDark = Color(0xFF004F61)
val OnCyanContainerDark = Color(0xFFC5F2FF)

val CoralTertiaryDark = Color(0xFFFFB59D)
val OnCoralTertiaryDark = Color(0xFF5D1900)
val CoralContainerDark = Color(0xFF852908)
val OnCoralTertiaryContainerDark = Color(0xFFFFDBD0)

val EmeraldPrimaryDark = Color(0xFF5CE1AC)
val OnEmeraldPrimaryDark = Color(0xFF003824)
val EmeraldContainerDark = Color(0xFF005C3C)
val OnEmeraldContainerDark = Color(0xFFC9F6E2)

val VioletPrimaryDark = Color(0xFFC9B9FF)
val OnVioletPrimaryDark = Color(0xFF331199)
val VioletContainerDark = Color(0xFF4B29B8)
val OnVioletContainerDark = Color(0xFFE6DEFF)

val SurfaceDark = Color(0xFF091122)
val OnSurfaceDark = Color(0xFFE7EDF8)
val SurfaceVariantDark = Color(0xFF18243D)
val OnSurfaceVariantDark = Color(0xFFA9B7D1)
val SurfaceContainerLowestDark = Color(0xFF060C18)
val SurfaceContainerLowDark = Color(0xFF0E182D)
val SurfaceContainerDark = Color(0xFF13203A)
val SurfaceContainerHighDark = Color(0xFF1A2A4A)
val SurfaceContainerHighestDark = Color(0xFF22355B)
val OutlineDark = Color(0xFF7D8EAE)
val OutlineVariantDark = Color(0xFF283757)

// Error Colors
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

/**
 * Default Material 3 LightColorScheme for ShareFlow.
 */
val LightColorScheme: ColorScheme = shareFlowLightColorScheme(AccentColor.COBALT)

/**
 * Default Material 3 DarkColorScheme for ShareFlow.
 */
val DarkColorScheme: ColorScheme = shareFlowDarkColorScheme(AccentColor.COBALT)

fun shareFlowLightColorScheme(accentColor: AccentColor): ColorScheme {
    val (primary, onPrimary, primaryContainer, onPrimaryContainer) = when (accentColor) {
        AccentColor.COBALT -> AccentLightSet(
            CobaltPrimaryLight, OnCobaltPrimaryLight, CobaltContainerLight, OnCobaltContainerLight
        )
        AccentColor.CYAN -> AccentLightSet(
            CyanSecondaryLight, OnCyanSecondaryLight, CyanContainerLight, OnCyanContainerLight
        )
        AccentColor.EMERALD -> AccentLightSet(
            EmeraldPrimaryLight, OnEmeraldPrimaryLight, EmeraldContainerLight, OnEmeraldContainerLight
        )
        AccentColor.CORAL -> AccentLightSet(
            CoralTertiaryLight, OnCoralTertiaryLight, CoralContainerLight, OnCoralTertiaryContainerLight
        )
        AccentColor.VIOLET -> AccentLightSet(
            VioletPrimaryLight, OnVioletPrimaryLight, VioletContainerLight, OnVioletContainerLight
        )
    }

    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = CyanSecondaryLight,
        onSecondary = OnCyanSecondaryLight,
        secondaryContainer = CyanContainerLight,
        onSecondaryContainer = OnCyanContainerLight,
        tertiary = CoralTertiaryLight,
        onTertiary = OnCoralTertiaryLight,
        tertiaryContainer = CoralContainerLight,
        onTertiaryContainer = OnCoralTertiaryContainerLight,
        background = SurfaceLight,
        onBackground = OnSurfaceLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight
    )
}

fun shareFlowDarkColorScheme(accentColor: AccentColor): ColorScheme {
    val (primary, onPrimary, primaryContainer, onPrimaryContainer) = when (accentColor) {
        AccentColor.COBALT -> AccentLightSet(
            CobaltPrimaryDark, OnCobaltPrimaryDark, CobaltContainerDark, OnCobaltContainerDark
        )
        AccentColor.CYAN -> AccentLightSet(
            CyanSecondaryDark, OnCyanSecondaryDark, CyanContainerDark, OnCyanContainerDark
        )
        AccentColor.EMERALD -> AccentLightSet(
            EmeraldPrimaryDark, OnEmeraldPrimaryDark, EmeraldContainerDark, OnEmeraldContainerDark
        )
        AccentColor.CORAL -> AccentLightSet(
            CoralTertiaryDark, OnCoralTertiaryDark, CoralContainerDark, OnCoralTertiaryContainerDark
        )
        AccentColor.VIOLET -> AccentLightSet(
            VioletPrimaryDark, OnVioletPrimaryDark, VioletContainerDark, OnVioletContainerDark
        )
    }

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = CyanSecondaryDark,
        onSecondary = OnCyanSecondaryDark,
        secondaryContainer = CyanContainerDark,
        onSecondaryContainer = OnCyanContainerDark,
        tertiary = CoralTertiaryDark,
        onTertiary = OnCoralTertiaryDark,
        tertiaryContainer = CoralContainerDark,
        onTertiaryContainer = OnCoralTertiaryContainerDark,
        background = SurfaceDark,
        onBackground = OnSurfaceDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark
    )
}

/**
 * Preview swatch color representing each selectable [AccentColor] in Settings.
 */
fun AccentColor.swatchColor(darkTheme: Boolean): Color = when (this) {
    AccentColor.COBALT -> if (darkTheme) CobaltPrimaryDark else CobaltPrimaryLight
    AccentColor.CYAN -> if (darkTheme) CyanSecondaryDark else CyanSecondaryLight
    AccentColor.EMERALD -> if (darkTheme) EmeraldPrimaryDark else EmeraldPrimaryLight
    AccentColor.CORAL -> if (darkTheme) CoralTertiaryDark else CoralTertiaryLight
    AccentColor.VIOLET -> if (darkTheme) VioletPrimaryDark else VioletPrimaryLight
}

private data class AccentLightSet(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color
)

/**
 * Semantic status and domain-specific tokens complementing Material 3 ColorScheme.
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
    val heroScrimEnd: Color
)

val LightShareFlowSemanticColors = ShareFlowSemanticColors(
    sendAccent = Color(0xFF1A47E5),
    onSendAccent = Color(0xFFFFFFFF),
    receiveAccent = Color(0xFF007891),
    onReceiveAccent = Color(0xFFFFFFFF),
    verifiedBadge = Color(0xFF0B7A53),
    onVerifiedBadge = Color(0xFFFFFFFF),
    verifiedContainer = Color(0xFFD3F7E5),
    warningAccent = Color(0xFF9C4D00),
    warningContainer = Color(0xFFFFE5CC),
    heroScrimStart = Color(0xE6061533),
    heroScrimEnd = Color(0x99061A3B)
)

val DarkShareFlowSemanticColors = ShareFlowSemanticColors(
    sendAccent = Color(0xFF3E6BFF),
    onSendAccent = Color(0xFFFFFFFF),
    receiveAccent = Color(0xFF00B8D4),
    onReceiveAccent = Color(0xFF001F27),
    verifiedBadge = Color(0xFF5CE1AC),
    onVerifiedBadge = Color(0xFF003824),
    verifiedContainer = Color(0xFF0A4A2E),
    warningAccent = Color(0xFFFFB74D),
    warningContainer = Color(0xFF4F2B00),
    heroScrimStart = Color(0xEB050F26),
    heroScrimEnd = Color(0x99061A3B)
)

val LocalShareFlowSemanticColors = staticCompositionLocalOf { LightShareFlowSemanticColors }
