package com.example.shareflow.network.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

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
 * Offline ISO/IEC 18004 QR code encoder and YUV frame decoder backed by ZXing Core.
 * Requires zero internet connectivity and zero external services.
 */
object QrCodeCodec {

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
    ): String? {
        if (width <= 0 || height <= 0 || yPlaneData.size < width * height) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(
                yPlaneData,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true
                    )
                )
            }
            reader.decodeWithState(bitmap)?.text
        }.getOrNull()
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
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
        reader.decodeWithState(binary)?.text
    }.getOrNull()
}
