package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.shareflow.core.common.ShareFlowCoilImageLoaderFactory
import com.example.shareflow.core.di.AppContainer
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.data.local.preferences.StartupAppearanceCache
import com.example.shareflow.data.update.fcm.ShareFlowUpdateNotificationManager

/**
 * ShareFlow Android Application entry point initializing the modular dependency container
 * and adaptive global Coil [ImageLoader] (tier-scaled RAM/disk thumbnail caches + video thumbnails).
 *
 * Keeps `onCreate()` strictly lightweight so cold start never initializes OkHttp, DataStore,
 * Room, or Firebase Messaging synchronously on the Main Thread before Frame 1.
 */
class ShareFlowApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        StartupAppearanceCache(this).syncSavedNightModeOnLaunch()
        container = DefaultAppContainer(this)
        registerActivityLifecycleCallbacks(container.appForegroundTracker)
        ShareFlowUpdateNotificationManager.ensureNotificationChannel(this)
    }

    override fun newImageLoader(): ImageLoader =
        ShareFlowCoilImageLoaderFactory.create(
            context = this,
            performanceProfile = container.devicePerformanceProfile
        )
}
