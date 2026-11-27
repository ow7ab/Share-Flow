package com.example

import android.animation.ObjectAnimator
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.data.update.fcm.ShareFlowUpdateNotificationManager
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.navigation.ShareFlowApp
import com.example.shareflow.navigation.ShareFlowRoutes
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : FragmentActivity() {

    @Volatile
    private var isLaunchThemeReady: Boolean = false

    private val pendingNavigationRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install AndroidX SplashScreen BEFORE super.onCreate() so the system splash window
        // seamlessly transitions into our resolved Deep Navy Dark or Light theme with zero flash.
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isLaunchThemeReady }

        super.onCreate(savedInstanceState)

        val appContainer = (application as ShareFlowApplication).container
        val initialSettings = appContainer.settingsRepository.peekInitialSettings()
        val systemInDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialIsDark = resolveInitialDarkTheme(
            themeMode = initialSettings.themeMode,
            systemInDark = systemInDark
        )
        val targetWindowBg = if (initialIsDark) WINDOW_BG_DARK else WINDOW_BG_LIGHT

        // Apply exact system bar appearance and native window background BEFORE Frame 1 is rendered
        // so Deep Charcoal (#171717) or Pearl White (#F8F7F4) is active immediately with zero flash.
        val barStyle = if (initialIsDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.setBackgroundColor(targetWindowBg)
        enableHighRefreshRateIfSupported()

        // Ensure the SplashScreen exit overlay matches the resolved user theme (even when the saved
        // app theme overrides the current OS night mode) and cross-fades cleanly without flicker.
        splashScreen.setOnExitAnimationListener { splashViewProvider ->
            runCatching {
                val splashView = splashViewProvider.view
                splashView.setBackgroundColor(targetWindowBg)
                ObjectAnimator.ofFloat(splashView, View.ALPHA, 1f, 0f).apply {
                    duration = SPLASH_EXIT_FADE_MS
                    interpolator = DecelerateInterpolator()
                    doOnEnd { splashViewProvider.remove() }
                    start()
                }
            }.onFailure {
                runCatching { splashViewProvider.remove() }
            }
        }

        // Release splash condition once theme tokens, window background, and edge-to-edge bars are applied.
        isLaunchThemeReady = true
        extractDeepLinkRoute(intent)?.let { route ->
            pendingNavigationRoute.value = route
        }

        setContent {
            val externalRoute by pendingNavigationRoute.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowApp(
                    initialSettings = initialSettings,
                    pendingExternalRoute = externalRoute,
                    onConsumeExternalRoute = { pendingNavigationRoute.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDeepLinkRoute(intent)?.let { route ->
            pendingNavigationRoute.value = route
        }
    }

    private fun extractDeepLinkRoute(intent: Intent?): String? {
        if (intent == null) return null
        if (intent.action == ShareFlowUpdateNotificationManager.ACTION_OPEN_UPDATES) {
            return ShareFlowRoutes.UPDATES
        }
        val extraRoute = intent.getStringExtra(ShareFlowUpdateNotificationManager.EXTRA_OPEN_ROUTE)
            ?: intent.getStringExtra("targetRoute")
        return if (extraRoute.equals(ShareFlowRoutes.UPDATES, ignoreCase = true)) {
            ShareFlowRoutes.UPDATES
        } else {
            null
        }
    }

    /**
     * Opts into the display's highest native refresh rate (e.g., 90Hz / 120Hz) at the current
     * physical resolution so Compose spring animations render at full smoothness on high-refresh
     * screens while staying seamlessly compatible with standard 60Hz panels.
     */
    private fun enableHighRefreshRateIfSupported() {
        runCatching {
            val activeDisplay: Display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display ?: return
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay ?: return
            }
            val currentMode = activeDisplay.mode
            val bestMode = activeDisplay.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return
            if (bestMode.refreshRate > currentMode.refreshRate) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        }
    }

    companion object {
        internal const val WINDOW_BG_LIGHT = 0xFFF8F7F4.toInt()
        internal const val WINDOW_BG_DARK = 0xFF171717.toInt()
        private const val SPLASH_EXIT_FADE_MS = 140L

        internal fun resolveInitialDarkTheme(
            themeMode: ThemeMode,
            systemInDark: Boolean
        ): Boolean = when (themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemInDark
        }
    }
}
