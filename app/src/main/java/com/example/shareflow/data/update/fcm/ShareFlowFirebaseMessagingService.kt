package com.example.shareflow.data.update.fcm

import android.util.Log
import com.example.ShareFlowApplication
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives instant Share Flow release push notifications from Firebase Cloud Messaging
 * (`share_flow_updates` topic), validates payloads strictly, refreshes topic subscription on
 * token rotation, and delegates foreground vs background display to [ShareFlowUpdateNotificationManager].
 */
class ShareFlowFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Never log raw FCM registration token
        Log.d(TAG, "FCM registration token rotated; ensuring topic subscription.")
        val app = application as? ShareFlowApplication ?: return
        serviceScope.launch {
            runCatching {
                app.container.fcmUpdateTopicCoordinator.onTokenRefreshed()
            }.onFailure { err ->
                Log.w(TAG, "Failed to refresh FCM topic subscription: ${err.javaClass.simpleName}")
            }
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

        serviceScope.launch {
            runCatching {
                app.container.updateNotificationManager.handleIncomingUpdatePush(payload)
            }.onFailure { err ->
                Log.w(TAG, "Error handling FCM update push: ${err.javaClass.simpleName}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ShareFlowFcmService"
    }
}
