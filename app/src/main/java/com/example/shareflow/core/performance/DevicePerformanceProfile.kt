package com.example.shareflow.core.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Runtime hardware-metric performance classification for adaptive resource management.
 *
 * Strictly derived from objective runtime system capabilities (RAM, CPU cores, memory class,
 * low-RAM flag, and Android API level) — never device brand/model heuristics — and NEVER degrades
 * data integrity, encryption, checksum verification, or core app features.
 */
@Immutable
enum class DevicePerformanceTier {
    LOW,
    BALANCED,
    HIGH
}

@Immutable
data class DevicePerformanceProfile(
    val tier: DevicePerformanceTier,
    val totalRamMb: Long,
    val cpuCoreCount: Int,
    val memoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val apiLevel: Int,
    val coilMemoryCachePercent: Double,
    val coilDiskCacheBytes: Long,
    val thumbnailCrossfadeMs: Int,
    val gridThumbnailTargetDp: Int,
    val gridThumbnailMaxPx: Int,
    val previewMaxSizePx: Int,
    val allowGlassBackdropBlur: Boolean,
    val allowPressScaleMicroAnimations: Boolean,
    val pagingPageSize: Int,
    val pagingPrefetchDistance: Int,
    val startupBackgroundWarmupDelayMs: Long,
    val qrAnalysisMinIntervalMs: Long
) {
    companion object {
        private const val MB = 1024L * 1024L

        fun forTier(
            tier: DevicePerformanceTier,
            totalRamMb: Long,
            cpuCoreCount: Int,
            memoryClassMb: Int,
            isLowRamDevice: Boolean,
            apiLevel: Int
        ): DevicePerformanceProfile = when (tier) {
            DevicePerformanceTier.LOW -> DevicePerformanceProfile(
                tier = DevicePerformanceTier.LOW,
                totalRamMb = totalRamMb,
                cpuCoreCount = cpuCoreCount,
                memoryClassMb = memoryClassMb,
                isLowRamDevice = isLowRamDevice,
                apiLevel = apiLevel,
                coilMemoryCachePercent = 0.12,
                coilDiskCacheBytes = 32L * MB,
                thumbnailCrossfadeMs = 0,
                gridThumbnailTargetDp = 136,
                gridThumbnailMaxPx = 256,
                previewMaxSizePx = 880,
                allowGlassBackdropBlur = false,
                allowPressScaleMicroAnimations = false,
                pagingPageSize = 24,
                pagingPrefetchDistance = 6,
                startupBackgroundWarmupDelayMs = 380L,
                qrAnalysisMinIntervalMs = 140L
            )

            DevicePerformanceTier.BALANCED -> DevicePerformanceProfile(
                tier = DevicePerformanceTier.BALANCED,
                totalRamMb = totalRamMb,
                cpuCoreCount = cpuCoreCount,
                memoryClassMb = memoryClassMb,
                isLowRamDevice = isLowRamDevice,
                apiLevel = apiLevel,
                coilMemoryCachePercent = 0.18,
                coilDiskCacheBytes = 48L * MB,
                thumbnailCrossfadeMs = 80,
                gridThumbnailTargetDp = 164,
                gridThumbnailMaxPx = 340,
                previewMaxSizePx = 1080,
                allowGlassBackdropBlur = apiLevel >= Build.VERSION_CODES.S,
                allowPressScaleMicroAnimations = true,
                pagingPageSize = 36,
                pagingPrefetchDistance = 10,
                startupBackgroundWarmupDelayMs = 160L,
                qrAnalysisMinIntervalMs = 90L
            )

            DevicePerformanceTier.HIGH -> DevicePerformanceProfile(
                tier = DevicePerformanceTier.HIGH,
                totalRamMb = totalRamMb,
                cpuCoreCount = cpuCoreCount,
                memoryClassMb = memoryClassMb,
                isLowRamDevice = isLowRamDevice,
                apiLevel = apiLevel,
                coilMemoryCachePercent = 0.22,
                coilDiskCacheBytes = 64L * MB,
                thumbnailCrossfadeMs = 110,
                gridThumbnailTargetDp = 180,
                gridThumbnailMaxPx = 420,
                previewMaxSizePx = 1440,
                allowGlassBackdropBlur = apiLevel >= Build.VERSION_CODES.S,
                allowPressScaleMicroAnimations = true,
                pagingPageSize = 48,
                pagingPrefetchDistance = 14,
                startupBackgroundWarmupDelayMs = 0L,
                qrAnalysisMinIntervalMs = 60L
            )
        }

        fun defaultBalanced(): DevicePerformanceProfile = forTier(
            tier = DevicePerformanceTier.BALANCED,
            totalRamMb = 4_096L,
            cpuCoreCount = 6,
            memoryClassMb = 256,
            isLowRamDevice = false,
            apiLevel = Build.VERSION.SDK_INT
        )
    }
}

/**
 * Deterministic runtime classifier selecting [DevicePerformanceTier] from real Android hardware
 * capabilities (never vendor/brand strings).
 */
object DevicePerformanceClassifier {

    private const val LOW_RAM_THRESHOLD_MB = 3_300L
    private const val HIGH_RAM_THRESHOLD_MB = 6_800L
    private const val LOW_MEMORY_CLASS_MB = 192
    private const val HIGH_MEMORY_CLASS_MB = 384

    @Volatile
    private var cachedProfile: DevicePerformanceProfile? = null

    fun classify(
        isLowRamDevice: Boolean,
        totalRamMb: Long,
        cpuCoreCount: Int,
        memoryClassMb: Int,
        apiLevel: Int
    ): DevicePerformanceProfile {
        val safeRamMb = totalRamMb.coerceAtLeast(0L)
        val safeCores = cpuCoreCount.coerceAtLeast(1)
        val safeMemClass = memoryClassMb.coerceAtLeast(16)

        val tier = when {
            isLowRamDevice -> DevicePerformanceTier.LOW
            safeRamMb in 1L..LOW_RAM_THRESHOLD_MB -> DevicePerformanceTier.LOW
            safeCores <= 4 && safeRamMb < 4_500L -> DevicePerformanceTier.LOW
            safeMemClass <= LOW_MEMORY_CLASS_MB && safeRamMb < 4_500L -> DevicePerformanceTier.LOW
            apiLevel < Build.VERSION_CODES.P && safeRamMb < 4_500L -> DevicePerformanceTier.LOW
            safeRamMb >= HIGH_RAM_THRESHOLD_MB &&
                safeCores >= 8 &&
                apiLevel >= Build.VERSION_CODES.R -> DevicePerformanceTier.HIGH
            safeRamMb >= HIGH_RAM_THRESHOLD_MB &&
                safeMemClass >= HIGH_MEMORY_CLASS_MB &&
                safeCores >= 6 -> DevicePerformanceTier.HIGH
            else -> DevicePerformanceTier.BALANCED
        }

        return DevicePerformanceProfile.forTier(
            tier = tier,
            totalRamMb = safeRamMb,
            cpuCoreCount = safeCores,
            memoryClassMb = safeMemClass,
            isLowRamDevice = isLowRamDevice,
            apiLevel = apiLevel
        )
    }

    fun fromContext(context: Context): DevicePerformanceProfile {
        cachedProfile?.let { return it }
        val appContext = context.applicationContext ?: context
        val activityManager = runCatching {
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()

        val isLowRam = runCatching { activityManager?.isLowRamDevice ?: false }.getOrDefault(false)
        val totalRamMb = runCatching {
            val info = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(info)
            if (info.totalMem > 0L) info.totalMem / (1024L * 1024L) else 4_096L
        }.getOrDefault(4_096L)
        val memoryClassMb = runCatching { activityManager?.memoryClass ?: 256 }.getOrDefault(256)
        val cpuCores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrDefault(4)

        val profile = classify(
            isLowRamDevice = isLowRam,
            totalRamMb = totalRamMb,
            cpuCoreCount = cpuCores,
            memoryClassMb = memoryClassMb,
            apiLevel = Build.VERSION.SDK_INT
        )
        cachedProfile = profile
        return profile
    }
}

val LocalDevicePerformanceProfile = staticCompositionLocalOf {
    DevicePerformanceProfile.defaultBalanced()
}
