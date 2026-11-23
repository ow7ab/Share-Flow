package com.example

import android.app.Application
import com.example.shareflow.core.di.AppContainer
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.data.local.preferences.StartupAppearanceCache

/**
 * ShareFlow Android Application entry point initializing the modular dependency container.
 */
class ShareFlowApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        StartupAppearanceCache(this).syncSavedNightModeOnLaunch()
        container = DefaultAppContainer(this)
    }
}
