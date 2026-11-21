package com.example.shareflow.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thread-safe in-memory LRU cache for asynchronously decoded Android application icons
 * (both installed packages and local/received APK files). Keeps Compose scrolling and transfer
 * animations completely free of main-thread bitmap decoding jank.
 */
object AppIconBitmapCache {
    private val cache = LruCache<String, ImageBitmap>(96)

    fun get(key: String): ImageBitmap? = synchronized(cache) { cache.get(key) }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(cache) { cache.put(key, bitmap) }
    }

    suspend fun loadForPackage(context: Context, packageName: String, sizePx: Int = 96): ImageBitmap? {
        if (packageName.isBlank()) return null
        val key = "pkg:$packageName"
        get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = drawable.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
                put(key, bitmap)
                bitmap
            }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun loadForApkPathOrUri(context: Context, pathOrUri: String, sizePx: Int = 96): ImageBitmap? {
        if (pathOrUri.isBlank()) return null
        val resolvedFile = resolveLocalApkFile(pathOrUri) ?: return null
        val key = "apk:${resolvedFile.absolutePath}:${resolvedFile.lastModified()}"
        get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        resolvedFile.absolutePath,
                        PackageManager.PackageInfoFlags.of(0L)
                    )
                } else {
                    pm.getPackageArchiveInfo(resolvedFile.absolutePath, 0)
                } ?: return@runCatching null

                val appInfo = pkgInfo.applicationInfo ?: return@runCatching null
                appInfo.sourceDir = resolvedFile.absolutePath
                appInfo.publicSourceDir = resolvedFile.absolutePath
                val drawable = appInfo.loadIcon(pm)
                val bitmap = drawable.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
                put(key, bitmap)
                bitmap
            }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun loadLabelForApkPathOrUri(context: Context, pathOrUri: String): String? {
        val resolvedFile = resolveLocalApkFile(pathOrUri) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = context.packageManager
                val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(
                        resolvedFile.absolutePath,
                        PackageManager.PackageInfoFlags.of(0L)
                    )
                } else {
                    pm.getPackageArchiveInfo(resolvedFile.absolutePath, 0)
                } ?: return@runCatching null
                val appInfo = pkgInfo.applicationInfo ?: return@runCatching null
                appInfo.sourceDir = resolvedFile.absolutePath
                appInfo.publicSourceDir = resolvedFile.absolutePath
                pm.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
    }

    fun resolveLocalApkFile(pathOrUri: String): File? {
        val candidatePath = when {
            pathOrUri.startsWith("file://", ignoreCase = true) -> runCatching {
                Uri.parse(pathOrUri).path
            }.getOrNull()
            pathOrUri.startsWith("content://", ignoreCase = true) -> null
            else -> pathOrUri
        } ?: return null

        val file = File(candidatePath)
        if (file.isDirectory) {
            val baseApk = File(file, "base.apk")
            if (baseApk.exists() && baseApk.isFile) return baseApk
            return file.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        }
        return file.takeIf { it.exists() && it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
    }
}

@Composable
fun rememberAppIconBitmap(
    packageName: String? = null,
    apkPathOrUri: String? = null
): ImageBitmap? {
    val context = LocalContext.current
    val cacheKey = when {
        !packageName.isNullOrBlank() -> "pkg:$packageName"
        !apkPathOrUri.isNullOrBlank() -> {
            val file = AppIconBitmapCache.resolveLocalApkFile(apkPathOrUri)
            if (file != null) "apk:${file.absolutePath}:${file.lastModified()}" else null
        }
        else -> null
    }
    val initial = cacheKey?.let { AppIconBitmapCache.get(it) }

    val icon by produceState(initialValue = initial, key1 = packageName, key2 = apkPathOrUri) {
        if (value != null) return@produceState
        val loaded = when {
            !packageName.isNullOrBlank() -> AppIconBitmapCache.loadForPackage(context, packageName)
            !apkPathOrUri.isNullOrBlank() -> AppIconBitmapCache.loadForApkPathOrUri(context, apkPathOrUri)
            else -> null
        }
        if (loaded != null) {
            value = loaded
        }
    }
    return icon
}

/**
 * Consumer-friendly representation of a transferring or completed item, hiding split-APK internals
 * (`base.apk`, `split_config.*.apk`, `shareflow_apk_bundle.json`) and raw package identifiers.
 */
data class TransferDisplayItem(
    val key: String,
    val displayTitle: String,
    val fileType: TransferFileType,
    val mimeType: String,
    val isApp: Boolean,
    val fileSizeBytes: Long,
    val bytesTransferred: Long,
    val state: TransferState,
    val localUri: String?,
    val iconApkPathOrUri: String?,
    val rawFileName: String,
    val bundleFolderName: String? = null
) {
    val progressFraction: Float
        get() = when {
            state == TransferState.COMPLETED -> 1f
            fileSizeBytes <= 0L -> 0f
            else -> (bytesTransferred.toFloat() / fileSizeBytes.toFloat()).coerceIn(0f, 1f)
        }

    val progressPercent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

object TransferDisplayPresentation {
    private const val BUNDLE_MANIFEST_FILE_NAME = "shareflow_apk_bundle.json"
    private val VERSION_SUFFIX_REGEX = Regex("([_\\-. ]v?\\d+(?:\\.\\d+){0,3})+$", RegexOption.IGNORE_CASE)

    fun isHiddenInternalArtifact(fileName: String): Boolean =
        fileName.equals(BUNDLE_MANIFEST_FILE_NAME, ignoreCase = true)

    fun isSplitBundleFolder(relativePath: String): Boolean {
        val folder = relativePath.trim().trim('/').substringBefore('/')
        return folder.endsWith("_split_apk", ignoreCase = true)
    }

    fun isAppItem(item: TransferItem): Boolean =
        item.fileType == TransferFileType.APK ||
            item.fileName.endsWith(".apk", ignoreCase = true) ||
            item.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true) ||
            isSplitBundleFolder(item.relativePath)

    /**
     * Converts technical APK/split-bundle names such as
     * `WhatsApp_v2.24.10_split_apk` or `Telegram_v10.5.apk` into a clean user-facing app name.
     */
    fun cleanAppDisplayName(rawFileName: String, relativePath: String = ""): String {
        val folderRoot = relativePath.trim().trim('/').substringBefore('/')
        val source = if (folderRoot.endsWith("_split_apk", ignoreCase = true)) {
            folderRoot.removeSuffix("_split_apk")
        } else {
            rawFileName
                .substringAfterLast('/')
                .removeSuffix(".apk")
                .removeSuffix(".APK")
                .removeSuffix("_split_apk")
        }

        val withoutVersion = source.replace(VERSION_SUFFIX_REGEX, "")
        val normalized = withoutVersion
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        return normalized.ifBlank {
            source.replace('_', ' ').trim().ifBlank { rawFileName }
        }
    }

    /**
     * Groups split-APK bundle files into a single clean App item and strips internal manifests,
     * while preserving normal files (photos, videos, audio, documents, archives) for viewing.
     */
    fun groupTransferItemsForDisplay(items: List<TransferItem>): List<TransferDisplayItem> {
        if (items.isEmpty()) return emptyList()

        val result = mutableListOf<TransferDisplayItem>()
        val splitGroups = linkedMapOf<String, MutableList<TransferItem>>()
        val orderedEntries = mutableListOf<Pair<String?, TransferItem?>>()

        items.forEach { item ->
            if (isSplitBundleFolder(item.relativePath)) {
                val folderKey = item.relativePath.trim().trim('/').substringBefore('/')
                if (folderKey !in splitGroups) {
                    splitGroups[folderKey] = mutableListOf()
                    orderedEntries.add(folderKey to null)
                }
                splitGroups[folderKey]?.add(item)
            } else if (!isHiddenInternalArtifact(item.fileName)) {
                orderedEntries.add(null to item)
            }
        }

        orderedEntries.forEach { (bundleFolder, singleItem) ->
            if (bundleFolder != null) {
                val parts = splitGroups[bundleFolder].orEmpty()
                if (parts.isEmpty()) return@forEach
                val basePart = parts.firstOrNull { it.fileName.equals("base.apk", ignoreCase = true) }
                    ?: parts.firstOrNull { it.fileName.endsWith(".apk", ignoreCase = true) }
                    ?: parts.first()
                val totalBytes = parts.sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
                val transferredBytes = parts.sumOf {
                    if (it.state == TransferState.COMPLETED) it.fileSizeBytes else it.bytesTransferred.coerceAtLeast(0L)
                }
                val combinedState = resolveCombinedState(parts)
                val localUri = basePart.destinationUri ?: basePart.sourceUri
                val iconPath = basePart.destinationUri ?: basePart.sourceUri ?: basePart.tempFilePath

                result.add(
                    TransferDisplayItem(
                        key = "bundle_$bundleFolder",
                        displayTitle = cleanAppDisplayName(basePart.fileName, bundleFolder),
                        fileType = TransferFileType.APK,
                        mimeType = "application/vnd.android.package-archive",
                        isApp = true,
                        fileSizeBytes = totalBytes,
                        bytesTransferred = transferredBytes,
                        state = combinedState,
                        localUri = localUri,
                        iconApkPathOrUri = iconPath,
                        rawFileName = basePart.fileName,
                        bundleFolderName = bundleFolder
                    )
                )
            } else if (singleItem != null) {
                val isApp = isAppItem(singleItem)
                val cleanTitle = if (isApp) {
                    cleanAppDisplayName(singleItem.fileName, singleItem.relativePath)
                } else {
                    singleItem.displayPath
                }
                val localUri = singleItem.destinationUri ?: singleItem.sourceUri
                result.add(
                    TransferDisplayItem(
                        key = singleItem.itemId,
                        displayTitle = cleanTitle,
                        fileType = if (isApp) TransferFileType.APK else singleItem.fileType,
                        mimeType = singleItem.mimeType,
                        isApp = isApp,
                        fileSizeBytes = singleItem.fileSizeBytes,
                        bytesTransferred = if (singleItem.state == TransferState.COMPLETED) {
                            singleItem.fileSizeBytes
                        } else {
                            singleItem.bytesTransferred
                        },
                        state = singleItem.state,
                        localUri = localUri,
                        iconApkPathOrUri = if (isApp) (localUri ?: singleItem.tempFilePath) else null,
                        rawFileName = singleItem.fileName
                    )
                )
            }
        }

        return result
    }

    fun toLocalFileEntry(item: TransferDisplayItem): LocalFileEntry? {
        val rawUri = item.localUri?.takeIf { it.isNotBlank() } ?: return null
        val normalizedUri = if (rawUri.startsWith("content://") || rawUri.startsWith("file://")) {
            rawUri
        } else {
            Uri.fromFile(File(rawUri)).toString()
        }
        val category = when (item.fileType) {
            TransferFileType.IMAGE -> FileCategory.IMAGES
            TransferFileType.VIDEO -> FileCategory.VIDEOS
            TransferFileType.AUDIO -> FileCategory.AUDIO
            TransferFileType.DOCUMENT -> FileCategory.DOCUMENTS
            TransferFileType.APK -> FileCategory.APKS
            TransferFileType.ARCHIVE -> FileCategory.ARCHIVES
            TransferFileType.OTHER -> FileCategory.OTHER
        }
        return LocalFileEntry(
            uriString = normalizedUri,
            displayName = item.displayTitle,
            mimeType = item.mimeType,
            sizeBytes = item.fileSizeBytes,
            lastModifiedEpochMillis = System.currentTimeMillis(),
            category = category,
            isDirectory = false,
            parentUriString = null,
            source = FileSource.SAF_DOCUMENT
        )
    }

    private fun resolveCombinedState(parts: List<TransferItem>): TransferState = when {
        parts.all { it.state == TransferState.COMPLETED } -> TransferState.COMPLETED
        parts.any { it.state == TransferState.FAILED } -> TransferState.FAILED
        parts.any { it.state == TransferState.CANCELLED } -> TransferState.CANCELLED
        parts.any { it.state == TransferState.TRANSFERRING } -> TransferState.TRANSFERRING
        parts.any { it.state == TransferState.VERIFYING } -> TransferState.VERIFYING
        parts.any { it.state == TransferState.PAUSED } -> TransferState.PAUSED
        parts.any { it.state == TransferState.CONNECTING } -> TransferState.CONNECTING
        parts.any { it.state == TransferState.WAITING_APPROVAL } -> TransferState.WAITING_APPROVAL
        else -> TransferState.PENDING
    }
}

/**
 * Clean visual avatar for transferring/received items:
 * - Renders actual extracted APK/installed app icon for Android apps.
 * - Renders smooth Coil thumbnail for photos and videos.
 * - Renders crisp Material 3 category icon for documents, audio, archives, and other files.
 */
@Composable
fun TransferItemVisualAvatar(
    item: TransferDisplayItem,
    size: Dp = 46.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appIcon = if (item.isApp) {
        rememberAppIconBitmap(apkPathOrUri = item.iconApkPathOrUri)
    } else {
        null
    }

    val isVisualMedia = !item.isApp && (
        item.fileType == TransferFileType.IMAGE ||
            item.fileType == TransferFileType.VIDEO ||
            item.mimeType.startsWith("image/", ignoreCase = true) ||
            item.mimeType.startsWith("video/", ignoreCase = true)
        )
    val mediaModel = if (isVisualMedia && !item.localUri.isNullOrBlank()) {
        val raw = item.localUri
        if (raw.startsWith("content://") || raw.startsWith("file://")) Uri.parse(raw) else File(raw)
    } else {
        null
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = when {
            item.isApp -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
            item.fileType == TransferFileType.IMAGE || item.fileType == TransferFileType.VIDEO ->
                MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                appIcon != null -> {
                    Image(
                        bitmap = appIcon,
                        contentDescription = item.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.medium)
                    )
                }

                mediaModel != null -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(mediaModel)
                            .crossfade(true)
                            .size(160)
                            .build(),
                        contentDescription = item.displayTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(MaterialTheme.shapes.medium)
                    )
                    if (item.fileType == TransferFileType.VIDEO || item.mimeType.startsWith("video/", ignoreCase = true)) {
                        Icon(
                            imageVector = Icons.Filled.PlayCircle,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                else -> {
                    val fallbackIcon = when (item.fileType) {
                        TransferFileType.APK -> Icons.Filled.Android
                        TransferFileType.IMAGE -> Icons.Filled.Image
                        TransferFileType.VIDEO -> Icons.Filled.PlayCircle
                        TransferFileType.AUDIO -> Icons.Filled.AudioFile
                        TransferFileType.DOCUMENT -> Icons.Filled.Description
                        TransferFileType.ARCHIVE -> Icons.Filled.Archive
                        TransferFileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                    Icon(
                        imageVector = fallbackIcon,
                        contentDescription = item.displayTitle,
                        tint = if (item.isApp) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(size * 0.54f)
                    )
                }
            }
        }
    }
}
