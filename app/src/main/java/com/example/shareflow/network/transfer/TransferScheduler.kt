package com.example.shareflow.network.transfer

import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounded concurrency transfer scheduler managing active coroutine jobs safely,
 * applying concurrency backpressure, and tracking real sliding-window throughput & ETA.
 */
class TransferScheduler(
    private val dispatchers: DispatcherProvider,
    maxConcurrentTransfers: Int = 2
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val concurrencyGate = Semaphore(maxConcurrentTransfers.coerceAtLeast(1))
    private val activeJobs = ConcurrentHashMap<String, Job>()

    fun schedule(
        sessionId: String,
        block: suspend () -> Unit
    ): Job {
        activeJobs.remove(sessionId)?.cancel()
        val job = scope.launch {
            concurrencyGate.withPermit {
                try {
                    block()
                } catch (ce: CancellationException) {
                    throw ce
                } finally {
                    activeJobs.remove(sessionId)
                }
            }
        }
        activeJobs[sessionId] = job
        return job
    }

    fun isRunning(sessionId: String): Boolean = activeJobs[sessionId]?.isActive == true

    fun cancel(sessionId: String) {
        activeJobs.remove(sessionId)?.cancel()
    }

    fun cancelAll() {
        val snapshot = activeJobs.values.toList()
        activeJobs.clear()
        snapshot.forEach { it.cancel() }
    }

    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}

/**
 * Computes real transfer speed (bytes/sec) and remaining time (seconds) strictly from observed
 * bytes transferred over a bounded sliding time window. Never fabricates or simulates progress.
 */
class TransferSpeedTracker(
    private val clock: Clock = SystemClock(),
    private val windowMillis: Long = 3_000L
) {
    private data class Sample(val timestampMs: Long, val deltaBytes: Long)

    private val lock = Any()
    private val samples = ArrayDeque<Sample>()
    private var windowBytes = 0L
    private var startedAtMs = 0L

    fun recordBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            if (startedAtMs == 0L) startedAtMs = now
            samples.addLast(Sample(now, deltaBytes))
            windowBytes += deltaBytes
            pruneLocked(now)
        }
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            windowBytes = 0L
            startedAtMs = 0L
        }
    }

    fun currentSpeedBytesPerSec(): Long {
        val now = clock.nowEpochMillis()
        synchronized(lock) {
            pruneLocked(now)
            if (samples.isEmpty() || windowBytes <= 0L) return 0L
            val oldestTs = samples.peekFirst()?.timestampMs ?: now
            val elapsedMs = (now - oldestTs).coerceAtLeast(250L)
            return ((windowBytes * 1000L) / elapsedMs).coerceAtLeast(0L)
        }
    }

    fun estimateRemainingSeconds(remainingBytes: Long): Long? {
        if (remainingBytes <= 0L) return 0L
        val speed = currentSpeedBytesPerSec()
        if (speed <= 0L) return null
        return ((remainingBytes + speed - 1L) / speed).coerceAtLeast(1L)
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoff = nowMs - windowMillis
        while (samples.isNotEmpty() && samples.peekFirst()!!.timestampMs < cutoff) {
            val expired = samples.removeFirst()
            windowBytes = (windowBytes - expired.deltaBytes).coerceAtLeast(0L)
        }
    }
}
