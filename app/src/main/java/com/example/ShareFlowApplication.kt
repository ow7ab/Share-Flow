package com.example

import android.app.Application
import com.example.shareflow.core.di.AppContainer
import com.example.shareflow.core.di.DefaultAppContainer

/**
 * ShareFlow Android Application entry point initializing the modular dependency container.
 */
class ShareFlowApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
