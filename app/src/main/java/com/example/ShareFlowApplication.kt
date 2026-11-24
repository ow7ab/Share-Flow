package com.example

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.shareflow.core.common.ShareFlowCoilImageLoaderFactory
import com.example.shareflow.core.di.AppContainer
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.data.local.preferences.StartupAppearanceCache

/**
 * ShareFlow Android Application entry point initializing the modular dependency container
 * and optimized global Coil [ImageLoader] (bounded RAM/disk thumbnail caches + video thumbnails).
 */
class ShareFlowApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        StartupAppearanceCache(this).syncSavedNightModeOnLaunch()
        container = DefaultAppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = ShareFlowCoilImageLoaderFactory.create(this)
}
