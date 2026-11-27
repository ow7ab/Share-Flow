package com.example.shareflow.data.update.fcm

import android.util.Log
import com.example.ShareFlowApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives instant Share Flow release push notifications from Firebase Cloud Messaging
 * (`share_flow_updates` topic), validates payloads strictly, refreshes topic subscription on
 * token rotation, and delegates foreground vs background display to [ShareFlowUpdateNotificationManager].
 *
 * Both [onNewToken] and [onMessageReceived] are dispatched by FCM on background worker threads,
 * so bounded [runBlocking] with [withTimeoutOrNull] guarantees DataStore deduplication and
 * system notification delivery finish reliably before Android destroys the service when the
 * app is backgrounded or killed.
 */
class ShareFlowFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Never log raw FCM registration token
        Log.d(TAG, "FCM registration token rotated; ensuring topic subscription.")
        val app = application as? ShareFlowApplication ?: return
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(FCM_WORK_TIMEOUT_MS) {
                    app.container.fcmUpdateTopicCoordinator.onTokenRefreshed()
                }
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w(TAG, "Failed to refresh FCM topic subscription: ${err.javaClass.simpleName}")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val app = application as? ShareFlowApplication ?: return

        val payload = FcmUpdatePayload.fromMap(
            data = message.data,
            fallbackTitle = message.notification?.title,
            fallbackBody = message.notification?.body
        ) ?: run {
            Log.d(TAG, "Ignored non-update or malformed FCM payload.")
            return
        }

        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(FCM_WORK_TIMEOUT_MS) {
                    app.container.updateNotificationManager.handleIncomingUpdatePush(payload)
                }
            }
        }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w(TAG, "Error handling FCM update push: ${err.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "ShareFlowFcmService"
        private const val FCM_WORK_TIMEOUT_MS = 10_000L
    }
}
