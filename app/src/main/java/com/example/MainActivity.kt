package com.example

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import com.example.shareflow.core.di.LocalAppContainer
import com.example.shareflow.navigation.ShareFlowApp

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as ShareFlowApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides appContainer) {
                ShareFlowApp()
            }
        }
    }
}
