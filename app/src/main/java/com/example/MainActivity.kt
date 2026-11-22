package com.example

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.navigation.ShareFlowApp

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as ShareFlowApplication).container
        val initialSettings = appContainer.settingsRepository.peekInitialSettings()
        val systemInDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val initialIsDark = when (initialSettings.themeMode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> systemInDark
        }

        // Apply exact system bar appearance and native window background BEFORE the first frame
        // so the user never sees a Dark Mode flash when Light Mode (or vice versa) is saved.
        val barStyle = if (initialIsDark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        window.decorView.setBackgroundColor(
            if (initialIsDark) WINDOW_BG_DARK else WINDOW_BG_LIGHT
        )

        setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowApp(initialSettings = initialSettings)
            }
        }
    }

    companion object {
        private const val WINDOW_BG_LIGHT = 0xFFFFF9F5.toInt()
        private const val WINDOW_BG_DARK = 0xFF161114.toInt()
    }
}
