package com.example.shareflow.ui.discovery

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size as AndroidSize
import android.view.MotionEvent
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.shareflow.core.designsystem.components.StatusPill
import com.example.shareflow.core.designsystem.theme.ShareFlowDesign
import com.example.shareflow.domain.model.QrScannerUiState
import com.example.shareflow.network.qr.QrCodeCodec
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Phase 6 Advanced QR Scanner powered by CameraX + ML Kit Barcode Scanning (with ZXing fallback).
 * Supports tap-to-focus, auto center focus, auto-zoom for distant QR codes, torch/flash toggle,
 * explicit scan state indicators, and duplicate scan prevention.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
fun AdvancedQrScannerView(
    scannerState: QrScannerUiState,
    onQrDecoded: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val semantic = ShareFlowDesign.semanticColors
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }

    var cameraRef by remember { mutableStateOf<Camera?>(null) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var activeZoomRatio by remember { mutableFloatStateOf(1.0f) }

    val scanLocked = remember { AtomicBoolean(false) }
    val lastDeliveredText = remember { mutableStateOf<String?>(null) }
    val lastDeliveredTime = remember { AtomicLong(0L) }
    val lastAutoZoomTime = remember { AtomicLong(0L) }

    // Unlock scanner automatically whenever state returns to SCANNING, INVALID_QR, EXPIRED_QR, or FAILURE
    LaunchedEffect(scannerState) {
        when (scannerState) {
            QrScannerUiState.SCANNING,
            QrScannerUiState.INVALID_QR,
            QrScannerUiState.EXPIRED_QR,
            QrScannerUiState.FAILURE -> {
                scanLocked.set(false)
            }
            QrScannerUiState.LOADING,
            QrScannerUiState.SUCCESS -> {
                scanLocked.set(true)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraRef?.cameraControl?.enableTorch(false) }
            runCatching { barcodeScanner.close() }
            runCatching { analysisExecutor.shutdownNow() }
        }
    }

    val frameAccentColor = when (scannerState) {
        QrScannerUiState.SUCCESS -> semantic.verifiedBadge
        QrScannerUiState.INVALID_QR,
        QrScannerUiState.EXPIRED_QR,
        QrScannerUiState.FAILURE -> MaterialTheme.colorScheme.error
        QrScannerUiState.LOADING -> MaterialTheme.colorScheme.tertiary
        QrScannerUiState.SCANNING -> MaterialTheme.colorScheme.primary
    }

    val stateLabelRes = when (scannerState) {
        QrScannerUiState.SCANNING -> R.string.smart_scanner_state_scanning
        QrScannerUiState.LOADING -> R.string.smart_scanner_state_loading
        QrScannerUiState.SUCCESS -> R.string.smart_scanner_state_success
        QrScannerUiState.INVALID_QR -> R.string.smart_scanner_state_invalid
        QrScannerUiState.EXPIRED_QR -> R.string.smart_scanner_state_expired
        QrScannerUiState.FAILURE -> R.string.smart_scanner_state_failure
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(292.dp)
            .clip(ShareFlowDesign.shapes.featuredCard)
            .testTag("qr_camera_preview_box")
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val cam = cameraRef
                        if (cam != null) {
                            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                .setAutoCancelDuration(3L, TimeUnit.SECONDS)
                                .build()
                            runCatching { cam.cameraControl.startFocusAndMetering(action) }
                        }
                        view.performClick()
                    }
                    true
                }

                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener(
                    {
                        runCatching {
                            val cameraProvider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            @Suppress("DEPRECATION")
                            val analysis = ImageAnalysis.Builder()
                                .setTargetResolution(AndroidSize(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            fun deliverCandidate(rawText: String) {
                                val normalized = rawText.trim()
                                if (normalized.isBlank()) return
                                val now = System.currentTimeMillis()
                                val duplicate = normalized == lastDeliveredText.value &&
                                    (now - lastDeliveredTime.get()) < 2_500L
                                if (duplicate || !scanLocked.compareAndSet(false, true)) return
                                lastDeliveredText.value = normalized
                                lastDeliveredTime.set(now)
                                ContextCompat.getMainExecutor(ctx).execute {
                                    onQrDecoded(normalized)
                                }
                            }

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (scanLocked.get()) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val mediaImage = imageProxy.image
                                if (mediaImage == null) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                val frameWidth = imageProxy.width.coerceAtLeast(1)

                                barcodeScanner.process(inputImage)
                                    .addOnSuccessListener(analysisExecutor) { barcodes ->
                                        val match = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                                        if (match != null) {
                                            val box = match.boundingBox
                                            if (box != null) {
                                                val boxWidthFraction = box.width().toFloat() / frameWidth.toFloat()
                                                val now = System.currentTimeMillis()
                                                if (boxWidthFraction in 0.01f..0.20f &&
                                                    (now - lastAutoZoomTime.get()) > 900L
                                                ) {
                                                    lastAutoZoomTime.set(now)
                                                    val cam = cameraRef
                                                    if (cam != null) {
                                                        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 2.0f
                                                        val currentZoom = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                                                        val nextZoom = (currentZoom * 1.35f).coerceAtMost(minOf(maxZoom, 2.5f))
                                                        ContextCompat.getMainExecutor(ctx).execute {
                                                            runCatching { cam.cameraControl.setZoomRatio(nextZoom) }
                                                            activeZoomRatio = nextZoom
                                                        }
                                                    }
                                                }
                                            }
                                            match.rawValue?.let(::deliverCandidate)
                                        } else if (!scanLocked.get()) {
                                            // Fast ZXing YUV fallback when ML Kit finds no barcode on low-contrast frames
                                            val yPlane = imageProxy.planes.firstOrNull()
                                            if (yPlane != null) {
                                                val buffer = yPlane.buffer
                                                val data = ByteArray(buffer.remaining())
                                                buffer.get(data)
                                                val zxingText = QrCodeCodec.decodeFromYuvLuminance(
                                                    yPlaneData = data,
                                                    width = imageProxy.width,
                                                    height = imageProxy.height
                                                )
                                                if (!zxingText.isNullOrBlank()) {
                                                    deliverCandidate(zxingText)
                                                }
                                            }
                                        }
                                    }
                                    .addOnCompleteListener {
                                        imageProxy.close()
                                    }
                            }

                            cameraProvider.unbindAll()
                            val boundCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                            cameraRef = boundCamera
                            hasFlashUnit = boundCamera.cameraInfo.hasFlashUnit()

                            // Auto center focus once bound
                            previewView.post {
                                val centerPoint = previewView.meteringPointFactory.createPoint(
                                    previewView.width / 2f,
                                    previewView.height / 2f
                                )
                                val action = FocusMeteringAction.Builder(centerPoint, FocusMeteringAction.FLAG_AF)
                                    .setAutoCancelDuration(4L, TimeUnit.SECONDS)
                                    .build()
                                runCatching { boundCamera.cameraControl.startFocusAndMetering(action) }
                            }
                        }
                    },
                    ContextCompat.getMainExecutor(ctx)
                )
                previewView
            }
        )

        // Crisp Material 3 corner viewfinder overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameSize = minOf(size.width, size.height) * 0.62f
            val left = (size.width - frameSize) / 2f
            val top = (size.height - frameSize) / 2f
            val right = left + frameSize
            val bottom = top + frameSize
            val corner = frameSize * 0.16f
            val stroke = 4.dp.toPx()

            // Top-left
            drawLine(frameAccentColor, Offset(left, top), Offset(left + corner, top), stroke, StrokeCap.Round)
            drawLine(frameAccentColor, Offset(left, top), Offset(left, top + corner), stroke, StrokeCap.Round)
            // Top-right
            drawLine(frameAccentColor, Offset(right, top), Offset(right - corner, top), stroke, StrokeCap.Round)
            drawLine(frameAccentColor, Offset(right, top), Offset(right, top + corner), stroke, StrokeCap.Round)
            // Bottom-left
            drawLine(frameAccentColor, Offset(left, bottom), Offset(left + corner, bottom), stroke, StrokeCap.Round)
            drawLine(frameAccentColor, Offset(left, bottom), Offset(left, bottom - corner), stroke, StrokeCap.Round)
            // Bottom-right
            drawLine(frameAccentColor, Offset(right, bottom), Offset(right - corner, bottom), stroke, StrokeCap.Round)
            drawLine(frameAccentColor, Offset(right, bottom), Offset(right, bottom - corner), stroke, StrokeCap.Round)
        }

        // Top overlay: Scanner status pill, auto-zoom badge, and flash toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(
                    label = stringResource(stateLabelRes),
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                    modifier = Modifier.testTag("qr_scanner_state_badge")
                )
                AnimatedVisibility(visible = activeZoomRatio > 1.05f) {
                    StatusPill(
                        label = stringResource(R.string.smart_scanner_auto_zoom_badge, activeZoomRatio),
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (hasFlashUnit) {
                Surface(
                    shape = CircleShape,
                    color = if (torchEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.65f)
                    }
                ) {
                    IconButton(
                        onClick = {
                            val next = !torchEnabled
                            torchEnabled = next
                            runCatching { cameraRef?.cameraControl?.enableTorch(next) }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("qr_scanner_torch_button")
                    ) {
                        Icon(
                            imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                            contentDescription = stringResource(R.string.smart_scanner_cd_toggle_torch),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        // Bottom overlay: Tap-to-focus & security hint
        Surface(
            color = Color.Black.copy(alpha = 0.68f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.smart_scanner_tap_to_focus_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * Decodes a QR code from a user-selected gallery image URI (zero-permission Android Photo Picker).
 * Uses ML Kit Barcode Scanning first, then falls back to ZXing Bitmap decoding.
 */
suspend fun decodeQrCodeFromGalleryUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    // 1. Try ML Kit directly from content URI
    val mlKitResult = runCatching {
        val image = InputImage.fromFilePath(context, uri)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        try {
            suspendCancellableCoroutine<String?> { continuation ->
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (continuation.isActive) {
                            continuation.resume(barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) continuation.resume(null)
                    }
            }
        } finally {
            runCatching { scanner.close() }
        }
    }.getOrNull()

    if (!mlKitResult.isNullOrBlank()) return@withContext mlKitResult.trim()

    // 2. Fallback to safe downsampled ZXing bitmap decoding
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sampleSize = 1
        while (maxDim / sampleSize > 1600) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return@runCatching null
        try {
            QrCodeCodec.decodeFromBitmap(bitmap)?.trim()
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}
