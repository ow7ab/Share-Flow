package com.example.shareflow.core.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import coil.size.Scale
import coil.size.pxOrElse
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Typed Coil model wrapper for downsampled local video thumbnails so Coil caches decoded
 * video frames in its shared RAM & disk cache without decoding full video streams on Main Thread.
 */
data class VideoThumbnailModel(
    val uriString: String,
    val targetSizePx: Int = 224
)

/**
 * High-performance Coil [ImageLoader] configuration for ShareFlow:
 * - Bounded RAM memory cache (22% of app heap) to prevent GC churn and OOM during rapid flings.
 * - Dedicated 64 MB disk cache for decoded thumbnails.
 * - Hardware bitmaps + RGB_565 default for thumbnails (50% RAM reduction vs ARGB_8888).
 * - Built-in [AndroidVideoThumbnailFetcher] for local content:// and file:// videos.
 */
object ShareFlowCoilImageLoaderFactory {

    private const val MEMORY_CACHE_PERCENT = 0.22
    private const val DISK_CACHE_MAX_BYTES = 64L * 1024L * 1024L

    fun create(context: Context): ImageLoader {
        val appContext = context.applicationContext
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(MEMORY_CACHE_PERCENT)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(appContext.cacheDir, "sf_thumbnail_cache"))
                    .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .components {
                add(AndroidVideoThumbnailFetcher.Factory(appContext))
            }
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .crossfade(110)
            .respectCacheHeaders(false)
            .build()
    }
}

/**
 * Lightweight, IO-dispatched Coil [Fetcher] extracting strictly downsampled video thumbnails
 * via [android.content.ContentResolver.loadThumbnail] on API 29+ and [MediaMetadataRetriever]
 * fallback on API 24–28. Never executes disk/codec I/O on the Main Thread.
 */
class AndroidVideoThumbnailFetcher(
    private val appContext: Context,
    private val model: VideoThumbnailModel,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val width = options.size.width.pxOrElse { model.targetSizePx }.coerceIn(64, 512)
        val height = options.size.height.pxOrElse { model.targetSizePx }.coerceIn(64, 512)
        val uri = runCatching { Uri.parse(model.uriString) }.getOrNull() ?: return@withContext null

        val bitmap = loadViaContentResolver(uri, width, height)
            ?: loadViaMetadataRetriever(uri, width, height)
            ?: return@withContext null

        DrawableResult(
            drawable = BitmapDrawable(appContext.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    private fun loadViaContentResolver(uri: Uri, width: Int, height: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (uri.scheme != "content") return null
        return runCatching {
            appContext.contentResolver.loadThumbnail(uri, Size(width, height), null)
        }.getOrNull()
    }

    private fun loadViaMetadataRetriever(uri: Uri, width: Int, height: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            when (uri.scheme) {
                "file" -> retriever.setDataSource(uri.path)
                else -> retriever.setDataSource(appContext, uri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    height
                )
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    class Factory(private val appContext: Context) : Fetcher.Factory<VideoThumbnailModel> {
        override fun create(
            data: VideoThumbnailModel,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = AndroidVideoThumbnailFetcher(appContext, data, options)
    }
}

/**
 * Remembered, strictly downsampled Coil [ImageRequest] for scrolling lists and grids.
 * Prevents full-resolution 12MP–48MP image decoding during LazyColumn / LazyVerticalGrid scrolling.
 */
@Composable
fun rememberThumbnailImageRequest(
    uriString: String,
    targetSizePx: Int = 192,
    isVideo: Boolean = false
): ImageRequest {
    val context = LocalContext.current
    val safeSize = targetSizePx.coerceIn(64, 512)
    return remember(context, uriString, safeSize, isVideo) {
        val data: Any = if (isVideo) {
            VideoThumbnailModel(uriString = uriString, targetSizePx = safeSize)
        } else {
            Uri.parse(uriString)
        }
        val cacheKey = if (isVideo) "vid_${safeSize}_$uriString" else "img_${safeSize}_$uriString"
        ImageRequest.Builder(context)
            .data(data)
            .size(safeSize, safeSize)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .crossfade(100)
            .build()
    }
}

/**
 * Remembered, screen-bounded Coil [ImageRequest] for modal/detail previews (never decodes 8K raw bitmaps).
 */
@Composable
fun rememberPreviewImageRequest(
    uriString: String,
    maxSizePx: Int = 1080
): ImageRequest {
    val context = LocalContext.current
    val safeSize = maxSizePx.coerceIn(320, 1440)
    return remember(context, uriString, safeSize) {
        ImageRequest.Builder(context)
            .data(Uri.parse(uriString))
            .size(safeSize, safeSize)
            .scale(Scale.FIT)
            .precision(Precision.INEXACT)
            .allowHardware(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCacheKey("prev_${safeSize}_$uriString")
            .crossfade(140)
            .build()
    }
}
