package com.example.shareflow.network.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.abs

/**
 * Pure ISO/IEC 18004 QR bit matrix suitable for crisp vector rendering on Jetpack Compose Canvas.
 */
data class QrCodeMatrix(
    val width: Int,
    val height: Int,
    val modules: BooleanArray
) {
    fun get(x: Int, y: Int): Boolean = modules[y * width + x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QrCodeMatrix) return false
        return width == other.width && height == other.height && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + modules.contentHashCode()
        return result
    }
}

/**
 * Decoded QR payload with optional normalized bounding width (`0f..1f`) for distant auto-zoom hints.
 */
data class QrDecodeResult(
    val text: String,
    val boundingWidthFraction: Float? = null
)

/**
 * High-speed offline ISO/IEC 18004 QR code encoder and YUV/Bitmap decoder backed by ZXing Core.
 * Requires zero native `.so` binaries, zero internet connectivity, and zero Google Play Services.
 */
object QrCodeCodec {

    private val decodeHints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true
    )

    private val threadLocalQrReader = ThreadLocal.withInitial { QRCodeReader() }

    fun encodeQrMatrix(payload: String): QrCodeMatrix? {
        if (payload.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
            val bitMatrix = QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                0,
                0,
                hints
            )
            val width = bitMatrix.width
            val height = bitMatrix.height
            val modules = BooleanArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    modules[y * width + x] = bitMatrix.get(x, y)
                }
            }
            QrCodeMatrix(width = width, height = height, modules = modules)
        }.getOrNull()
    }

    fun decodeFromYuvLuminance(
        yPlaneData: ByteArray,
        width: Int,
        height: Int
    ): String? = decodeFromYuvLuminanceDetailed(
        yPlaneData = yPlaneData,
        width = width,
        height = height,
        rowStride = width
    )?.text

    /**
     * Decodes a QR code directly from a CameraX Y-plane luminance buffer with stride awareness,
     * fast center-crop pass, full-frame fallback, and inverted/histogram binarizer resilience.
     */
    fun decodeFromYuvLuminanceDetailed(
        yPlaneData: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width
    ): QrDecodeResult? {
        if (width <= 0 || height <= 0 || rowStride < width) return null
        val requiredBytes = rowStride * (height - 1) + width
        if (yPlaneData.size < requiredBytes) return null

        val reader = threadLocalQrReader.get() ?: QRCodeReader()

        // 1. Fast center-region pass matching the on-screen viewfinder (reduces binarization work)
        val cropSide = (minOf(width, height) * 0.72f).toInt().coerceAtLeast(120)
        if (cropSide < width && cropSide < height) {
            val left = ((width - cropSide) / 2).coerceAtLeast(0)
            val top = ((height - cropSide) / 2).coerceAtLeast(0)
            val croppedSource = PlanarYUVLuminanceSource(
                yPlaneData,
                rowStride,
                height,
                left,
                top,
                cropSide,
                cropSide,
                false
            )
            decodeWithFallbacks(reader, croppedSource)?.let { result ->
                return QrDecodeResult(
                    text = result.text,
                    boundingWidthFraction = estimateBoundingWidthFraction(result, width)
                )
            }
        }

        // 2. Full-frame pass for off-center or tilted QR codes
        val fullSource = PlanarYUVLuminanceSource(
            yPlaneData,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val fullResult = decodeWithFallbacks(reader, fullSource) ?: return null
        return QrDecodeResult(
            text = fullResult.text,
            boundingWidthFraction = estimateBoundingWidthFraction(fullResult, width)
        )
    }

    fun renderQrBitmap(
        payload: String,
        sizePx: Int = 900,
        foregroundColor: Int = Color.rgb(11, 26, 60),
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        if (payload.isBlank() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2
            )
            val matrix = QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) foregroundColor else backgroundColor
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }

    fun decodeFromBitmap(bitmap: Bitmap): String? = runCatching {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return@runCatching null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val reader = threadLocalQrReader.get() ?: QRCodeReader()
        decodeWithFallbacks(reader, source)?.text
    }.getOrNull()

    private fun decodeWithFallbacks(
        reader: QRCodeReader,
        source: LuminanceSource
    ): Result? {
        val hybridResult = runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), decodeHints)
        }.getOrNull()
        reader.reset()
        if (hybridResult != null && !hybridResult.text.isNullOrBlank()) {
            return hybridResult
        }

        val histogramResult = runCatching {
            reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), decodeHints)
        }.getOrNull()
        reader.reset()
        if (histogramResult != null && !histogramResult.text.isNullOrBlank()) {
            return histogramResult
        }

        return null
    }

    private fun estimateBoundingWidthFraction(result: Result, frameWidth: Int): Float? {
        val points = result.resultPoints ?: return null
        if (points.size < 2 || frameWidth <= 0) return null
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        for (point in points) {
            if (point != null) {
                if (point.x < minX) minX = point.x
                if (point.x > maxX) maxX = point.x
            }
        }
        if (minX == Float.MAX_VALUE || maxX == Float.MIN_VALUE) return null
        val span = abs(maxX - minX)
        return (span / frameWidth.toFloat()).takeIf { it > 0f && it <= 1f }
    }
}
