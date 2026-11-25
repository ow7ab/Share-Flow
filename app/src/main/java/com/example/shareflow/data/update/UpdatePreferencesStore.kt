package com.example.shareflow.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.shareFlowUpdatePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "shareflow_update_prefs"
)

enum class UpdateServerConnectionStatus {
    UNKNOWN,
    CHECKING,
    CONNECTED,
    OFFLINE,
    RATE_LIMITED,
    ERROR
}

enum class FcmSubscriptionState {
    UNKNOWN,
    NOT_CONFIGURED,
    SUBSCRIBED,
    FAILED
}

data class UpdateDiagnosticsSnapshot(
    val lastCheckEpochMillis: Long = 0L,
    val serverStatus: UpdateServerConnectionStatus = UpdateServerConnectionStatus.UNKNOWN,
    val activeSourceLabel: String = "GitHub Releases",
    val snoozedVersionCode: Int = 0,
    val lastNotifiedVersionCode: Int = 0,
    val fcmSubscriptionState: FcmSubscriptionState = FcmSubscriptionState.UNKNOWN,
    val fcmSubscribedAppVersionCode: Int = 0,
    val lastErrorSummary: String? = null
)

/**
 * Isolated DataStore for Share Flow update diagnostics, snooze persistence, notification
 * deduplication, and FCM topic registration state (never modifies existing user settings or Room DB).
 */
interface UpdatePreferencesStore {
    val snapshot: Flow<UpdateDiagnosticsSnapshot>

    suspend fun getSnapshot(): UpdateDiagnosticsSnapshot
    suspend fun recordCheckResult(
        epochMillis: Long,
        serverStatus: UpdateServerConnectionStatus,
        sourceLabel: String,
        errorSummary: String? = null
    )
    suspend fun setSnoozedVersionCode(versionCode: Int)
    suspend fun clearSnoozeIfNewer(versionCode: Int)
    suspend fun setLastNotifiedVersionCode(versionCode: Int)
    suspend fun setFcmSubscriptionState(
        state: FcmSubscriptionState,
        appVersionCode: Int
    )

    suspend fun isPeriodicCheckDue(
        nowEpochMillis: Long,
        intervalMillis: Long = DEFAULT_PERIODIC_CHECK_INTERVAL_MS
    ): Boolean {
        val last = getSnapshot().lastCheckEpochMillis
        return last <= 0L || (nowEpochMillis - last) >= intervalMillis
    }

    companion object {
        const val DEFAULT_PERIODIC_CHECK_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L
    }
}

class DataStoreUpdatePreferencesStore(
    private val dataStore: DataStore<Preferences>
) : UpdatePreferencesStore {

    private object Keys {
        val LAST_CHECK_MILLIS = longPreferencesKey("last_check_epoch_millis")
        val SERVER_STATUS = stringPreferencesKey("last_server_status")
        val SOURCE_LABEL = stringPreferencesKey("active_source_label")
        val SNOOZED_VERSION_CODE = intPreferencesKey("snoozed_version_code")
        val LAST_NOTIFIED_VERSION_CODE = intPreferencesKey("last_notified_version_code")
        val FCM_SUBSCRIPTION_STATE = stringPreferencesKey("fcm_subscription_state")
        val FCM_SUBSCRIBED_APP_VERSION = intPreferencesKey("fcm_subscribed_app_version")
        val LAST_ERROR_SUMMARY = stringPreferencesKey("last_error_summary")
    }

    override val snapshot: Flow<UpdateDiagnosticsSnapshot> = dataStore.data
        .catch { err ->
            if (err is IOException) emit(emptyPreferences()) else throw err
        }
        .map { prefs -> prefs.toSnapshot() }

    override suspend fun getSnapshot(): UpdateDiagnosticsSnapshot = snapshot.first()

    override suspend fun recordCheckResult(
        epochMillis: Long,
        serverStatus: UpdateServerConnectionStatus,
        sourceLabel: String,
        errorSummary: String?
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.LAST_CHECK_MILLIS] = epochMillis
            prefs[Keys.SERVER_STATUS] = serverStatus.name
            prefs[Keys.SOURCE_LABEL] = sourceLabel
            if (errorSummary.isNullOrBlank()) {
                prefs.remove(Keys.LAST_ERROR_SUMMARY)
            } else {
                prefs[Keys.LAST_ERROR_SUMMARY] = errorSummary.take(240)
            }
        }
    }

    override suspend fun setSnoozedVersionCode(versionCode: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.SNOOZED_VERSION_CODE] = versionCode.coerceAtLeast(0)
        }
    }

    override suspend fun clearSnoozeIfNewer(versionCode: Int) {
        dataStore.edit { prefs ->
            val currentSnoozed = prefs[Keys.SNOOZED_VERSION_CODE] ?: 0
            if (versionCode > currentSnoozed && currentSnoozed > 0) {
                prefs[Keys.SNOOZED_VERSION_CODE] = 0
            }
        }
    }

    override suspend fun setLastNotifiedVersionCode(versionCode: Int) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.LAST_NOTIFIED_VERSION_CODE] ?: 0
            if (versionCode > current) {
                prefs[Keys.LAST_NOTIFIED_VERSION_CODE] = versionCode
            }
        }
    }

    override suspend fun setFcmSubscriptionState(
        state: FcmSubscriptionState,
        appVersionCode: Int
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.FCM_SUBSCRIPTION_STATE] = state.name
            if (state == FcmSubscriptionState.SUBSCRIBED) {
                prefs[Keys.FCM_SUBSCRIBED_APP_VERSION] = appVersionCode
            }
        }
    }

    private fun Preferences.toSnapshot(): UpdateDiagnosticsSnapshot {
        val status = runCatching {
            UpdateServerConnectionStatus.valueOf(this[Keys.SERVER_STATUS] ?: "")
        }.getOrDefault(UpdateServerConnectionStatus.UNKNOWN)
        val fcmState = runCatching {
            FcmSubscriptionState.valueOf(this[Keys.FCM_SUBSCRIPTION_STATE] ?: "")
        }.getOrDefault(FcmSubscriptionState.UNKNOWN)
        return UpdateDiagnosticsSnapshot(
            lastCheckEpochMillis = this[Keys.LAST_CHECK_MILLIS] ?: 0L,
            serverStatus = status,
            activeSourceLabel = this[Keys.SOURCE_LABEL] ?: "GitHub Releases",
            snoozedVersionCode = this[Keys.SNOOZED_VERSION_CODE] ?: 0,
            lastNotifiedVersionCode = this[Keys.LAST_NOTIFIED_VERSION_CODE] ?: 0,
            fcmSubscriptionState = fcmState,
            fcmSubscribedAppVersionCode = this[Keys.FCM_SUBSCRIBED_APP_VERSION] ?: 0,
            lastErrorSummary = this[Keys.LAST_ERROR_SUMMARY]
        )
    }
}

/**
 * In-memory implementation for fast, isolated unit & Robolectric testing.
 */
class InMemoryUpdatePreferencesStore(
    initial: UpdateDiagnosticsSnapshot = UpdateDiagnosticsSnapshot()
) : UpdatePreferencesStore {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val snapshot: Flow<UpdateDiagnosticsSnapshot> = state

    override suspend fun getSnapshot(): UpdateDiagnosticsSnapshot = state.value

    override suspend fun recordCheckResult(
        epochMillis: Long,
        serverStatus: UpdateServerConnectionStatus,
        sourceLabel: String,
        errorSummary: String?
    ) {
        state.value = state.value.copy(
            lastCheckEpochMillis = epochMillis,
            serverStatus = serverStatus,
            activeSourceLabel = sourceLabel,
            lastErrorSummary = errorSummary
        )
    }

    override suspend fun setSnoozedVersionCode(versionCode: Int) {
        state.value = state.value.copy(snoozedVersionCode = versionCode.coerceAtLeast(0))
    }

    override suspend fun clearSnoozeIfNewer(versionCode: Int) {
        if (versionCode > state.value.snoozedVersionCode && state.value.snoozedVersionCode > 0) {
            state.value = state.value.copy(snoozedVersionCode = 0)
        }
    }

    override suspend fun setLastNotifiedVersionCode(versionCode: Int) {
        if (versionCode > state.value.lastNotifiedVersionCode) {
            state.value = state.value.copy(lastNotifiedVersionCode = versionCode)
        }
    }

    override suspend fun setFcmSubscriptionState(
        state: FcmSubscriptionState,
        appVersionCode: Int
    ) {
        this.state.value = this.state.value.copy(
            fcmSubscriptionState = state,
            fcmSubscribedAppVersionCode = if (state == FcmSubscriptionState.SUBSCRIBED) {
                appVersionCode
            } else {
                this.state.value.fcmSubscribedAppVersionCode
            }
        )
    }
}
