package com.example.shareflow.network.security

import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.network.TransferProtocol

/**
 * Thread-safe bounded nonce and timestamp freshness cache preventing handshake replay attacks.
 * Rejects messages outside the allowed time skew window or carrying a previously observed nonce.
 */
class ReplayProtectionCache(
    private val clock: Clock = SystemClock(),
    private val windowMillis: Long = TransferProtocol.REPLAY_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val lock = Any()
    private val seenNonces = LinkedHashMap<String, Long>(64, 0.75f, false)

    /**
     * Validates that [timestampEpochMillis] is within the allowed freshness window and that
     * ([peerDeviceId], [nonceBase64]) has never been seen before, recording it atomically on success.
     */
    fun recordAndValidate(
        peerDeviceId: String,
        nonceBase64: String,
        timestampEpochMillis: Long
    ): ReplayCheckResult {
        if (peerDeviceId.isBlank() || nonceBase64.length < MIN_NONCE_B64_LENGTH) {
            return ReplayCheckResult.InvalidNonce
        }

        val now = clock.nowEpochMillis()
        val delta = kotlin.math.abs(now - timestampEpochMillis)
        if (delta > windowMillis) {
            return ReplayCheckResult.StaleTimestamp(deltaMillis = delta)
        }

        val key = "$peerDeviceId|$nonceBase64"
        synchronized(lock) {
            evictExpiredLocked(now)
            if (seenNonces.containsKey(key)) {
                return ReplayCheckResult.ReplayedNonce
            }
            if (seenNonces.size >= maxEntries) {
                val oldestKey = seenNonces.entries.firstOrNull()?.key
                if (oldestKey != null) {
                    seenNonces.remove(oldestKey)
                }
            }
            seenNonces[key] = now + windowMillis
            return ReplayCheckResult.Accepted
        }
    }

    fun clear() {
        synchronized(lock) {
            seenNonces.clear()
        }
    }

    private fun evictExpiredLocked(now: Long) {
        val iterator = seenNonces.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= now) {
                iterator.remove()
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 1024
        private const val MIN_NONCE_B64_LENGTH = 16
    }
}

sealed interface ReplayCheckResult {
    data object Accepted : ReplayCheckResult
    data object ReplayedNonce : ReplayCheckResult
    data object InvalidNonce : ReplayCheckResult
    data class StaleTimestamp(val deltaMillis: Long) : ReplayCheckResult
}
