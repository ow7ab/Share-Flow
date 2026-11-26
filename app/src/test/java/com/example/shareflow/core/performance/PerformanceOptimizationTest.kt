package com.example.shareflow.core.performance

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.di.DefaultAppContainer
import com.example.shareflow.data.update.fcm.ShareFlowUpdateNotificationManager
import com.example.shareflow.network.qr.QrCodeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PerformanceOptimizationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `device classifier assigns LOW BALANCED and HIGH tiers strictly from hardware metrics`() {
        val lowRamFlagProfile = DevicePerformanceClassifier.classify(
            isLowRamDevice = true,
            totalRamMb = 4_096L,
            cpuCoreCount = 8,
            memoryClassMb = 256,
            apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        assertEquals(DevicePerformanceTier.LOW, lowRamFlagProfile.tier)
        assertFalse(lowRamFlagProfile.allowGlassBackdropBlur)
        assertFalse(lowRamFlagProfile.allowPressScaleMicroAnimations)
        assertEquals(0, lowRamFlagProfile.thumbnailCrossfadeMs)
        assertTrue(lowRamFlagProfile.startupBackgroundWarmupDelayMs > 0L)

        val lowMemoryBudgetProfile = DevicePerformanceClassifier.classify(
            isLowRamDevice = false,
            totalRamMb = 2_048L,
            cpuCoreCount = 4,
            memoryClassMb = 128,
            apiLevel = Build.VERSION_CODES.R
        )
        assertEquals(DevicePerformanceTier.LOW, lowMemoryBudgetProfile.tier)

        val balancedProfile = DevicePerformanceClassifier.classify(
            isLowRamDevice = false,
            totalRamMb = 4_096L,
            cpuCoreCount = 6,
            memoryClassMb = 256,
            apiLevel = Build.VERSION_CODES.TIRAMISU
        )
        assertEquals(DevicePerformanceTier.BALANCED, balancedProfile.tier)
        assertTrue(balancedProfile.allowGlassBackdropBlur)
        assertTrue(balancedProfile.allowPressScaleMicroAnimations)

        val highProfile = DevicePerformanceClassifier.classify(
            isLowRamDevice = false,
            totalRamMb = 8_192L,
            cpuCoreCount = 8,
            memoryClassMb = 512,
            apiLevel = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        )
        assertEquals(DevicePerformanceTier.HIGH, highProfile.tier)
        assertEquals(0L, highProfile.startupBackgroundWarmupDelayMs)
        assertTrue(highProfile.coilMemoryCachePercent > balancedProfile.coilMemoryCachePercent)
        assertTrue(balancedProfile.coilMemoryCachePercent > lowRamFlagProfile.coilMemoryCachePercent)
        assertTrue(highProfile.pagingPageSize > balancedProfile.pagingPageSize)
        assertTrue(balancedProfile.pagingPageSize > lowRamFlagProfile.pagingPageSize)
        assertTrue(highProfile.qrAnalysisMinIntervalMs < lowRamFlagProfile.qrAnalysisMinIntervalMs)
    }

    @Test
    fun `offline ZXing QrCodeCodec round-trips both Bitmap and stride-padded YUV luminance planes`() {
        val payload = "shareflow://pair?v=2&host=192.168.43.1&port=48721&tok=sf_verify_019283"
        val bitmap = QrCodeCodec.renderQrBitmap(
            payload = payload,
            sizePx = 320,
            foregroundColor = Color.BLACK,
            backgroundColor = Color.WHITE
        )
        assertNotNull("Rendered QR bitmap must not be null", bitmap)
        val nonNullBitmap = bitmap!!

        // 1. Bitmap decoding (gallery import path)
        assertEquals(payload, QrCodeCodec.decodeFromBitmap(nonNullBitmap))

        // 2. Stride-padded YUV Y-plane decoding (CameraX ImageAnalysis zero-MLKit path)
        val width = nonNullBitmap.width
        val height = nonNullBitmap.height
        val rowStride = width + 32 // Simulate hardware buffer alignment padding
        val yPlane = ByteArray(rowStride * height) { 0xFF.toByte() }
        val pixels = IntArray(width * height)
        nonNullBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            val pixelRow = y * width
            for (x in 0 until width) {
                val pixel = pixels[pixelRow + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yPlane[rowOffset + x] = luma.coerceIn(0, 255).toByte()
            }
        }

        val detailed = QrCodeCodec.decodeFromYuvLuminanceDetailed(
            yPlaneData = yPlane,
            width = width,
            height = height,
            rowStride = rowStride
        )
        assertNotNull("Stride-padded YUV QR must decode cleanly", detailed)
        assertEquals(payload, detailed?.text)
        val fraction = detailed?.boundingWidthFraction
        assertNotNull("Decoded QR should expose bounding width fraction for auto-zoom", fraction)
        assertTrue("Bounding width fraction must be in (0, 1]", fraction != null && fraction > 0f && fraction <= 1f)
    }

    @Test
    fun `cold start container construction and notification channel setup keep heavy subsystems lazy`() {
        val container = DefaultAppContainer(context)
        ShareFlowUpdateNotificationManager.ensureNotificationChannel(context)

        assertFalse("Room database must remain lazy during cold start", container.isDatabaseInitialized)
        assertFalse("UpdateManager (OkHttp/DataStore) must remain lazy during cold start", container.isUpdateManagerInitialized)
        assertFalse("CloudUpdateManager must remain lazy during cold start", container.isCloudUpdateInitialized)
        assertFalse("VaultManager must remain lazy during cold start", container.isVaultInitialized)
        assertFalse("SmartConnectionCoordinator must remain lazy during cold start", container.isSmartCoordinatorInitialized)
        assertNotNull("DevicePerformanceProfile must resolve safely", container.devicePerformanceProfile)
    }

    @Test
    fun `application memory trim callbacks safely clear or trim caches under RAM pressure`() {
        val app = context as com.example.ShareFlowApplication
        // Must not crash or eagerly instantiate Coil when called before any image load
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        app.onLowMemory()

        // After creating an ImageLoader, trim/clear paths must remain safe and non-throwing
        val loader = app.newImageLoader()
        assertNotNull("Adaptive Coil ImageLoader must initialize cleanly", loader)
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        app.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        app.onLowMemory()
    }
}
