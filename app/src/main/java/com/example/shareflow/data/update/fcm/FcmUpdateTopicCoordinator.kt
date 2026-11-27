package com.example.shareflow.data.update.fcm

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.shareflow.data.update.FcmSubscriptionState
import com.example.shareflow.data.update.UpdatePreferencesStore
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Abstraction over Firebase Cloud Messaging topic subscription for clean unit/Robolectric testing
 * and graceful degradation when `google-services.json` is not yet provisioned in a local build.
 */
interface FcmTopicBackend {
    fun isFirebaseConfigured(): Boolean
    suspend fun subscribeToTopic(topic: String): Boolean
}

class FirebaseFcmTopicBackend(
    private val context: Context
) : FcmTopicBackend {
    private val appContext = context.applicationContext ?: context

    override fun isFirebaseConfigured(): Boolean = runCatching {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            true
        } else {
            FirebaseApp.initializeApp(appContext) != null
        }
    }.getOrDefault(false)

    override suspend fun subscribeToTopic(topic: String): Boolean {
        if (!isFirebaseConfigured()) return false
        return withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    FirebaseMessaging.getInstance()
                        .subscribeToTopic(topic)
                        .addOnCompleteListener { task ->
                            if (cont.isActive) {
                                cont.resume(task.isSuccessful)
                            }
                        }
                }.onFailure { err ->
                    Log.w(TAG, "FCM topic subscription error: ${err.javaClass.simpleName}")
                    if (cont.isActive) {
                        cont.resume(false)
                    }
                }
            }
        } ?: false
    }

    companion object {
        private const val TAG = "ShareFlowFcmBackend"
        private const val SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

/**
 * Coordinates automatic, login-free FCM device registration and subscription to
 * [TOPIC_SHARE_FLOW_UPDATES]:
 * - Subscribes automatically on first launch
 * - Re-verifies subscription after app upgrades or FCM token refresh (`onNewToken`)
 * - Prevents redundant duplicate topic subscriptions on every launch
 * - Never crashes if Firebase `google-services.json` has not been configured yet
 */
class FcmUpdateTopicCoordinator(
    private val backend: FcmTopicBackend,
    private val preferencesStore: UpdatePreferencesStore,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE
) {
    suspend fun ensureSubscribed(forceResubscribe: Boolean = false): FcmSubscriptionState {
        if (!backend.isFirebaseConfigured()) {
            preferencesStore.setFcmSubscriptionState(
                state = FcmSubscriptionState.NOT_CONFIGURED,
                appVersionCode = currentVersionCode
            )
            return FcmSubscriptionState.NOT_CONFIGURED
        }

        val current = preferencesStore.getSnapshot()
        val alreadySubscribedForCurrentBuild =
            current.fcmSubscriptionState == FcmSubscriptionState.SUBSCRIBED &&
                current.fcmSubscribedAppVersionCode == currentVersionCode

        if (!forceResubscribe && alreadySubscribedForCurrentBuild) {
            return FcmSubscriptionState.SUBSCRIBED
        }

        val success = backend.subscribeToTopic(TOPIC_SHARE_FLOW_UPDATES)
        val nextState = if (success) {
            FcmSubscriptionState.SUBSCRIBED
        } else {
            FcmSubscriptionState.FAILED
        }
        preferencesStore.setFcmSubscriptionState(
            state = nextState,
            appVersionCode = currentVersionCode
        )
        return nextState
    }

    suspend fun onTokenRefreshed(): FcmSubscriptionState =
        ensureSubscribed(forceResubscribe = true)

    companion object {
        const val TOPIC_SHARE_FLOW_UPDATES = "share_flow_updates"
    }
}
