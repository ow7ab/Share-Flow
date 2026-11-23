package com.example.shareflow.data.local.preferences

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.example.shareflow.domain.model.AccentColor
import com.example.shareflow.domain.model.AppLanguage
import com.example.shareflow.domain.model.AppSettings
import com.example.shareflow.domain.model.ThemeMode

/**
 * Tiny synchronous appearance cache mirrored from DataStore so MainActivity and ShareFlowApp
 * resolve the user's exact persisted ThemeMode, AccentColor, Dynamic Color, and AppLanguage
 * BEFORE the very first Compose frame is drawn — eliminating startup Dark/Light theme flash.
 *
 * Also syncs Android 12+ (API 31+) per-app [UiModeManager.setApplicationNightMode] so even the
 * pre-process OS splash window opens in the user's saved Light or Dark theme.
 *
 * Stores ONLY non-sensitive UI appearance tokens (never credentials, files, or keys).
 */
class StartupAppearanceCache(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var memorySnapshot: AppSettings? = null

    fun isInitialized(): Boolean = memorySnapshot != null || runCatching {
        prefs.getBoolean(KEY_INITIALIZED, false)
    }.getOrDefault(false)

    fun readOrNull(): AppSettings? {
        memorySnapshot?.let { return it }
        val initialized = runCatching { prefs.getBoolean(KEY_INITIALIZED, false) }.getOrDefault(false)
        if (!initialized) return null

        return runCatching {
            val themeMode = prefs.getString(KEY_THEME_MODE, null)
                ?.let { raw -> runCatching { ThemeMode.valueOf(raw) }.getOrNull() }
                ?: ThemeMode.SYSTEM

            val accentColor = prefs.getString(KEY_ACCENT_COLOR, null)
                ?.let { raw -> runCatching { AccentColor.valueOf(raw) }.getOrNull() }
                ?: AccentColor.COBALT

            val appLanguage = prefs.getString(KEY_APP_LANGUAGE, null)
                ?.let { raw -> runCatching { AppLanguage.valueOf(raw) }.getOrNull() }
                ?: AppLanguage.SYSTEM

            val useDynamicColor = prefs.getBoolean(KEY_USE_DYNAMIC_COLOR, false)
            val hasCompletedOnboarding = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)

            AppSettings(
                themeMode = themeMode,
                useDynamicColor = useDynamicColor,
                accentColor = accentColor,
                appLanguage = appLanguage,
                hasCompletedOnboarding = hasCompletedOnboarding
            ).also { memorySnapshot = it }
        }.getOrNull()
    }

    fun write(settings: AppSettings) {
        val normalized = AppSettings(
            themeMode = settings.themeMode,
            useDynamicColor = settings.useDynamicColor,
            accentColor = settings.accentColor,
            appLanguage = settings.appLanguage,
            hasCompletedOnboarding = settings.hasCompletedOnboarding
        )
        val previousTheme = memorySnapshot?.themeMode
        val alreadyPersisted = memorySnapshot == normalized && runCatching {
            prefs.getBoolean(KEY_INITIALIZED, false)
        }.getOrDefault(false)
        if (alreadyPersisted) {
            return
        }
        memorySnapshot = normalized
        runCatching {
            prefs.edit()
                .putBoolean(KEY_INITIALIZED, true)
                .putString(KEY_THEME_MODE, normalized.themeMode.name)
                .putBoolean(KEY_USE_DYNAMIC_COLOR, normalized.useDynamicColor)
                .putString(KEY_ACCENT_COLOR, normalized.accentColor.name)
                .putString(KEY_APP_LANGUAGE, normalized.appLanguage.name)
                .putBoolean(KEY_ONBOARDING_COMPLETED, normalized.hasCompletedOnboarding)
                .apply()
        }

        if (previousTheme != normalized.themeMode) {
            syncSystemApplicationNightMode(normalized.themeMode)
        }
    }

    fun updateThemeMode(themeMode: ThemeMode) {
        val current = readOrNull() ?: AppSettings()
        write(current.copy(themeMode = themeMode))
    }

    fun updateDynamicColor(enabled: Boolean) {
        val current = readOrNull() ?: AppSettings()
        write(current.copy(useDynamicColor = enabled))
    }

    fun updateAccentColor(accentColor: AccentColor) {
        val current = readOrNull() ?: AppSettings()
        write(current.copy(accentColor = accentColor))
    }

    fun updateAppLanguage(language: AppLanguage) {
        val current = readOrNull() ?: AppSettings()
        write(current.copy(appLanguage = language))
    }

    fun markOnboardingCompleted() {
        val current = readOrNull() ?: AppSettings()
        write(current.copy(hasCompletedOnboarding = true))
    }

    fun resetToDefaults() {
        val preservedOnboarding = readOrNull()?.hasCompletedOnboarding ?: false
        write(AppSettings(hasCompletedOnboarding = preservedOnboarding))
    }

    private fun syncSystemApplicationNightMode(themeMode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            val uiModeManager = appContext.getSystemService(UiModeManager::class.java) ?: return
            val targetNightMode = when (themeMode) {
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            }
            uiModeManager.setApplicationNightMode(targetNightMode)
        }
    }

    companion object {
        private const val PREFS_NAME = "shareflow_startup_appearance"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_USE_DYNAMIC_COLOR = "use_dynamic_color"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_ONBOARDING_COMPLETED = "has_completed_onboarding"
    }
}
