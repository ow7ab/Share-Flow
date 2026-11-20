package com.example.shareflow.core.designsystem.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun Context.findFragmentActivity(): FragmentActivity? = findActivity() as? FragmentActivity

object ShareFlowDesign {
    val spacing: ShareFlowSpacing
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
 * Resolves the active [Locale] from user-selected [AppLanguage] and system configuration.
 */
fun resolveShareFlowLocale(appLanguage: AppLanguage, systemLocale: Locale): Locale =
    when (appLanguage) {
        AppLanguage.SYSTEM -> systemLocale
        AppLanguage.ENGLISH -> Locale.forLanguageTag("en")
        AppLanguage.ARABIC -> Locale.forLanguageTag("ar")
    }

/**
 * Complete Material 3 theme provider for ShareFlow supporting:
 * - Light, Dark, and System Default themes
 * - Android 12+ Dynamic Color
 * - User-selected Accent Colors
 * - Smooth theme color transitions
 * - Live English (LTR) and Arabic (RTL) localization & typography switching
 */
@Composable
fun ShareFlowTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    accentColor: AccentColor = AccentColor.COBALT,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val systemLocale = remember(baseConfig) {
        baseConfig.locales[0] ?: Locale.getDefault()
    }
    val activeLocale = remember(appLanguage, systemLocale) {
        resolveShareFlowLocale(appLanguage, systemLocale)
    }
    val needsLocaleOverride = appLanguage != AppLanguage.SYSTEM &&
        activeLocale.language != systemLocale.language

    val localizedConfig = remember(baseConfig, activeLocale, needsLocaleOverride) {
        if (needsLocaleOverride) {
            Configuration(baseConfig).apply {
                setLocale(activeLocale)
                setLayoutDirection(activeLocale)
            }
        } else {
            baseConfig
        }
    }
    val localizedContext = remember(baseContext, localizedConfig, needsLocaleOverride) {
        if (needsLocaleOverride) {
            baseContext.createConfigurationContext(localizedConfig)
        } else {
            baseContext
        }
    }
    val layoutDirection = if (localizedConfig.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    val targetColorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(baseContext) else dynamicLightColorScheme(baseContext)
        }
        darkTheme -> shareFlowDarkColorScheme(accentColor)
        else -> shareFlowLightColorScheme(accentColor)
    }

    val animatedColorScheme = animateShareFlowColorScheme(targetColorScheme)
    val semanticColors = if (darkTheme) DarkShareFlowSemanticColors else LightShareFlowSemanticColors
    val typography = if (activeLocale.language == "ar") {
        ShareFlowArabicTypography
    } else {
        ShareFlowTypography
    }

    val hostActivity = LocalShareFlowHostActivity.current ?: remember(baseContext) { baseContext.findActivity() }
    val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current
    val onBackPressedDispatcherOwner = LocalOnBackPressedDispatcherOwner.current

    val providedValues = buildList<ProvidedValue<*>> {
        add(LocalContext provides localizedContext)
        add(LocalConfiguration provides localizedConfig)
        add(LocalLayoutDirection provides layoutDirection)
        add(LocalShareFlowLocale provides activeLocale)
        add(LocalShareFlowHostActivity provides hostActivity)
        add(LocalShareFlowSpacing provides ShareFlowSpacing())
        add(LocalShareFlowElevation provides ShareFlowElevation())
        add(LocalShareFlowShapeTokens provides ShareFlowShapeTokens())
        add(LocalShareFlowSemanticColors provides semanticColors)
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
private fun animateShareFlowColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 220)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val background by animateColorAsState(target.background, spec, label = "background")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        background = background,
        surface = surface,
        surfaceContainerLow = surfaceContainerLow
    )
}
