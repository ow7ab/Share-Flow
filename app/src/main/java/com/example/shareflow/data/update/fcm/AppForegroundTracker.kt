package com.example.shareflow.data.update.fcm

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lightweight, zero-dependency foreground/background tracker registered on [Application]
 * so incoming FCM update pushes know whether to open the in-app Update Details Dialog
 * (foreground) or post a system notification (background/killed).
 */
class AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    private val startedActivities = AtomicInteger(0)

    val isInForeground: Boolean
        get() = startedActivities.get() > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivities.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
