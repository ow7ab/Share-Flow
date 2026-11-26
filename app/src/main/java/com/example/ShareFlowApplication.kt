package com.example

import android.app.Application
import android.content.ComponentCallbacks2
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

    @Volatile
    private var initializedImageLoader: ImageLoader? = null

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
        ).also { loader ->
            initializedImageLoader = loader
        }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val loader = initializedImageLoader ?: return
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                runCatching { loader.memoryCache?.clear() }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                runCatching { loader.memoryCache?.trimMemory(level) }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { initializedImageLoader?.memoryCache?.clear() }
    }
}
