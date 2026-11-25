package com.example.shareflow.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.fragment.app.FragmentActivity
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.ThemeMode
import java.util.Locale

val LocalShareFlowLocale = staticCompositionLocalOf<Locale> { Locale.US }
val LocalShareFlowHostActivity = staticCompositionLocalOf<Activity?> { null }

private val DefaultShareFlowSpacing = ShareFlowSpacing()
private val DefaultShareFlowElevation = ShareFlowElevation()
private val DefaultShareFlowShapeTokens = ShareFlowShapeTokens()
private val DefaultSFMotionTokens = SFMotionTokens()

/**
 * Locale-overridden [ContextWrapper] that preserves access to the hosting [Activity] and prevents
 * `AndroidRuntimeException: Calling startActivity() from outside of an Activity context` crashes
 * when switching the UI locale at runtime (e.g., switching to Arabic RTL).
 */
class ShareFlowLocalizedContextWrapper(
    localizedBase: Context,
    val hostActivity: Activity?
) : ContextWrapper(localizedBase) {

    override fun startActivity(intent: Intent?) {
        if (intent == null) return
        val activity = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            val safeIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            super.startActivity(safeIntent)
        }
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        if (intent == null) return
        val activity = hostActivity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (activity != null) {
            activity.startActivity(intent, options)
        } else {
            val safeIntent = Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            super.startActivity(safeIntent, options)
        }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ShareFlowLocalizedContextWrapper -> hostActivity ?: baseContext.findActivity()
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

/**
 * Share Flow 2.0 unified design system token accessor.
 * Exposes Liquid Glass tokens, spacing/dimensions, elevations, shapes, motion, semantic colors, and locale.
 */
object ShareFlowDesign {
    val spacing: ShareFlowSpacing
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowSpacing.current

    val dimensions: SFDimensions
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowSpacing.current

    val elevation: ShareFlowElevation
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowElevation.current

    val semanticColors: ShareFlowSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowSemanticColors.current

    val glass: SFGlassTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalSFGlassTokens.current

    val motion: SFMotionTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalSFMotion.current

    val reducedMotion: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalSFReducedMotion.current

    val brandColors: ShareFlowBrandColors
        get() = ShareFlowBrandColors

    val brandGradients: ShareFlowBrandGradients
        get() = ShareFlowBrandGradients

    val shapes: ShareFlowShapeTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowShapeTokens.current

    val locale: Locale
        @Composable
        @ReadOnlyComposable
        get() = LocalShareFlowLocale.current
}

/**
 * Concise Share Flow 2.0 theme token alias.
 */
typealias SFTheme = ShareFlowDesign

/**
 * Resolves the active [Locale] from user-selected [AppLanguage] and system configuration.
 */
fun resolveShareFlowLocale(appLanguage: AppLanguage, systemLocale: Locale): Locale =
    when (appLanguage) {
        AppLanguage.SYSTEM -> systemLocale
        AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
        AppLanguage.ARABIC -> Locale.forLanguageTag("ar")
    }

/**
 * Share Flow 2.0 Theme Provider:
 * - Independent Light Mode (bright neutral + soft cream) and Deep Navy Dark Mode treatments
 * - Official brand identity (#CA2851, #FF6766, #FFB173, #FFE3B3) is always the primary color source
 *   (Android dynamic wallpaper color never replaces the brand identity)
 * - Liquid Glass tokens, smooth theme transitions, and reduced-motion accessibility awareness
 * - Live English (LTR) and Arabic (RTL) localization & typography switching without Activity context loss
 */
@Composable
fun ShareFlowTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    accentColor: AccentColor = AccentColor.COBALT,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val hostActivity = LocalShareFlowHostActivity.current ?: remember(baseContext) { baseContext.findActivity() }
    val systemLocale = remember(baseConfig.locales) {
        if (!baseConfig.locales.isEmpty) baseConfig.locales[0] ?: Locale.getDefault() else Locale.getDefault()
    }
    val activeLocale = remember(appLanguage, systemLocale.language) {
        resolveShareFlowLocale(appLanguage, systemLocale)
    }
    val needsLocaleOverride = appLanguage != AppLanguage.SYSTEM &&
        activeLocale.language != systemLocale.language

    val localizedConfig = remember(
        activeLocale.language,
        needsLocaleOverride,
        baseConfig.uiMode,
        baseConfig.fontScale,
        baseConfig.orientation,
        baseConfig.screenWidthDp,
        baseConfig.screenHeightDp
    ) {
        if (needsLocaleOverride) {
            Configuration(baseConfig).apply {
                setLocale(activeLocale)
                setLayoutDirection(activeLocale)
            }
        } else {
            baseConfig
        }
    }
    val localizedContext = remember(
        baseContext,
        hostActivity,
        activeLocale.language,
        needsLocaleOverride,
        baseConfig.uiMode,
        baseConfig.fontScale
    ) {
        if (needsLocaleOverride) {
            val configContext = runCatching {
                baseContext.createConfigurationContext(localizedConfig)
            }.getOrDefault(baseContext)
            ShareFlowLocalizedContextWrapper(
                localizedBase = configContext,
                hostActivity = hostActivity
            )
        } else {
            baseContext
        }
    }
    val layoutDirection = if (activeLocale.language == "ar" || localizedConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    val systemInDark = androidx.compose.foundation.isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> systemInDark
    }

    // Preserve the official Share Flow brand identity as the primary color source in all modes
    // (Android wallpaper dynamic color never overrides the official #CA2851 / #FF6766 / #FFB173 / #FFE3B3 palette).
    val targetColorScheme = remember(isDarkTheme, accentColor, useDynamicColor) {
        if (isDarkTheme) {
            shareFlowDarkColorScheme(accentColor)
        } else {
            shareFlowLightColorScheme(accentColor)
        }
    }
    val reducedMotion = rememberReducedMotionEnabled()
    val animatedColorScheme = animateShareFlowColorScheme(targetColorScheme, reducedMotion = reducedMotion)
    val semanticColors = if (isDarkTheme) DarkShareFlowSemanticColors else LightShareFlowSemanticColors
    val glassTokens = if (isDarkTheme) DarkSFGlassTokens else LightSFGlassTokens
    val typography = if (activeLocale.language == "ar") {
        ShareFlowArabicTypography
    } else {
        ShareFlowTypography
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.LaunchedEffect(isDarkTheme, hostActivity, view) {
            val window = hostActivity?.window ?: return@LaunchedEffect
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDarkTheme
            insetsController.isAppearanceLightNavigationBars = !isDarkTheme
            window.decorView.setBackgroundColor(
                if (isDarkTheme) {
                    ShareFlowMarpleColors.DarkBackground.toArgb()
                } else {
                    ShareFlowMarpleColors.LightBackground.toArgb()
                }
            )
        }
    }

    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current

    val providedValues = buildList<ProvidedValue<*>> {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedConfig)
        add(LocalLayoutDirection provides layoutDirection)
        add(LocalShareFlowLocale provides activeLocale)
        add(LocalShareFlowHostActivity provides hostActivity)
        add(LocalShareFlowSpacing provides DefaultShareFlowSpacing)
        add(LocalShareFlowElevation provides DefaultShareFlowElevation)
        add(LocalShareFlowShapeTokens provides DefaultShareFlowShapeTokens)
        add(LocalShareFlowSemanticColors provides semanticColors)
        add(LocalSFGlassTokens provides glassTokens)
        add(LocalSFMotion provides DefaultSFMotionTokens)
        add(LocalSFReducedMotion provides reducedMotion)
        if (activityResultRegistryOwner != null) {
            add(LocalActivityResultRegistryOwner provides activityResultRegistryOwner)
        }
        if (onBackPressedDispatcherOwner != null) {
            add(LocalOnBackPressedDispatcherOwner provides onBackPressedDispatcherOwner)
        }
    }.toTypedArray()

    CompositionLocalProvider(*providedValues) {
        MaterialTheme(
            colorScheme = animatedColorScheme,
            typography = typography,
            shapes = ShareFlowShapes,
            content = content
        )
    }
}

@Composable
private fun animateShareFlowColorScheme(
    target: ColorScheme,
    reducedMotion: Boolean
): ColorScheme {
    if (reducedMotion) return target
    val spec = tween<Color>(durationMillis = 220)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
    val background by animateColorAsState(target.background, spec, label = "background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        tertiary = tertiary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceContainerLow = surfaceContainerLow
    )
}
