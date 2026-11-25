package com.example.shareflow.data.update.fcm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.MainActivity
import com.example.R
import com.example.shareflow.data.update.CloudUpdateManager
import com.example.shareflow.data.update.GitHubReleasesParser
import com.example.shareflow.data.update.UpdateManager
import com.example.shareflow.data.update.UpdatePreferencesStore
import com.example.shareflow.navigation.ShareFlowRoutes

enum class PushNotificationHandlingOutcome {
    IGNORED_ALREADY_UP_TO_DATE,
    IGNORED_DUPLICATE_VERSION,
    SURFACED_IN_APP_FOREGROUND,
    POSTED_SYSTEM_NOTIFICATION,
    DEFERRED_NOTIFICATIONS_DISABLED
}

/**
 * Manages the dedicated Share Flow Updates notification channel, version deduplication,
 * Android 13+ POST_NOTIFICATIONS compliance, foreground in-app dialog handoff, and
 * background/killed system notification deep-linking to the Updates screen.
 */
class ShareFlowUpdateNotificationManager(
    private val context: Context,
    private val preferencesStore: UpdatePreferencesStore,
    private val updateManager: UpdateManager,
    private val cloudUpdateManager: CloudUpdateManager,
    private val isAppInForegroundProvider: () -> Boolean,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentVersionName: String = BuildConfig.VERSION_NAME
) {
    private val appContext = context.applicationContext ?: context

    fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.updates_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.updates_notification_channel_desc)
            enableLights(true)
            lightColor = ContextCompat.getColor(appContext, R.color.shareflow_brand_ruby)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun canPostUpdateNotifications(): Boolean {
        val hasRuntimePerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!hasRuntimePerm) return false
        return runCatching {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    /**
     * Handles a validated [FcmUpdatePayload] according to app state, version freshness, and
     * deduplication rules.
     */
    suspend fun handleIncomingUpdatePush(payload: FcmUpdatePayload): PushNotificationHandlingOutcome {
        val isNewer = GitHubReleasesParser.isRemoteReleaseNewer(
            remoteVersionCode = payload.versionCode,
            remoteVersionName = payload.versionName,
            installedVersionCode = currentVersionCode,
            installedVersionName = currentVersionName
        )
        if (!isNewer) {
            return PushNotificationHandlingOutcome.IGNORED_ALREADY_UP_TO_DATE
        }

        val snapshot = preferencesStore.getSnapshot()
        if (snapshot.lastNotifiedVersionCode >= payload.versionCode) {
            return PushNotificationHandlingOutcome.IGNORED_DUPLICATE_VERSION
        }

        preferencesStore.setLastNotifiedVersionCode(payload.versionCode)
        preferencesStore.clearSnoozeIfNewer(payload.versionCode)

        val details = payload.toUpdateDetails()
        cloudUpdateManager.applyPushUpdateManifest(payload.toCloudUpdateManifest())

        if (isAppInForegroundProvider()) {
            updateManager.onForegroundPushUpdateReceived(details)
            return PushNotificationHandlingOutcome.SURFACED_IN_APP_FOREGROUND
        }

        if (!canPostUpdateNotifications()) {
            // Still update in-memory state so user sees it next time they open the app
            updateManager.onForegroundPushUpdateReceived(details)
            return PushNotificationHandlingOutcome.DEFERRED_NOTIFICATIONS_DISABLED
        }

        postSystemUpdateNotification(payload)
        return PushNotificationHandlingOutcome.POSTED_SYSTEM_NOTIFICATION
    }

    fun buildOpenUpdatesPendingIntent(versionCode: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_UPDATES
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ROUTE, ShareFlowRoutes.UPDATES)
            putExtra(EXTRA_UPDATE_VERSION_CODE, versionCode)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, NOTIFICATION_ID, intent, flags)
    }

    private fun postSystemUpdateNotification(payload: FcmUpdatePayload) {
        ensureNotificationChannel()

        val title = payload.notificationTitle?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.updates_push_default_title)
        val body = payload.notificationBody?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.updates_push_default_body, payload.versionName)

        val largeIcon = runCatching {
            BitmapFactory.decodeResource(appContext.resources, R.mipmap.ic_launcher)
        }.getOrNull()

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setColor(ContextCompat.getColor(appContext, R.color.shareflow_brand_ruby))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildOpenUpdatesPendingIntent(payload.versionCode))
            .apply {
                if (largeIcon != null) {
                    setLargeIcon(largeIcon)
                }
            }

        runCatching {
            NotificationManagerCompat.from(appContext).notify(
                NOTIFICATION_TAG,
                NOTIFICATION_ID,
                builder.build()
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "share_flow_updates_channel"
        const val ACTION_OPEN_UPDATES = "OPEN_SHAREFLOW_UPDATES"
        const val EXTRA_OPEN_ROUTE = "shareflow_extra_open_route"
        const val EXTRA_UPDATE_VERSION_CODE = "shareflow_extra_update_version_code"
        const val NOTIFICATION_TAG = "shareflow_app_update"
        const val NOTIFICATION_ID = 41018
    }
}
