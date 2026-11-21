package com.example.shareflow.ui.common

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandColors
import com.example.shareflow.core.designsystem.theme.ShareFlowBrandGradients
import com.example.shareflow.domain.model.FileCategory
import com.example.shareflow.domain.model.FileSource
import com.example.shareflow.domain.model.LocalFileEntry
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferItem
import com.example.shareflow.domain.model.TransferState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val packageNameCache = LruCache<String, String>(96)

    @Suppress("DEPRECATION")
    suspend fun loadPackageNameForApkPathOrUri(context: Context, pathOrUri: String): String? {
        val resolvedFile = resolveLocalApkFile(pathOrUri) ?: return null
        val key = "${resolvedFile.absolutePath}:${resolvedFile.lastModified()}"
        synchronized(packageNameCache) { packageNameCache.get(key) }?.let { return it }
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
                val pkg = pkgInfo.packageName?.takeIf { it.isNotBlank() } ?: return@runCatching null
                synchronized(packageNameCache) { packageNameCache.put(key, pkg) }
                pkg
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
 * - Optionally overlays a circular progress indicator directly on top of the app icon during
 *   active file transfer to visually represent completion percentage.
 * - Renders smooth Coil thumbnail for photos and videos.
 * - Renders crisp Material 3 category icon for documents, audio, archives, and other files.
 */
@Composable
fun TransferItemVisualAvatar(
    item: TransferDisplayItem,
    size: Dp = 46.dp,
    showCircularProgressOverlay: Boolean = false,
    progressOverlayTestTag: String? = null,
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

    val shouldRenderOverlay = showCircularProgressOverlay &&
        item.isApp &&
        item.state != TransferState.COMPLETED &&
        item.state != TransferState.CANCELLED &&
        item.state != TransferState.FAILED

    val animatedProgress by animateFloatAsState(
        targetValue = item.progressFraction.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "app_icon_circular_progress_${item.key}"
    )

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

            if (shouldRenderOverlay) {
                val pctInt = (animatedProgress * 100f).toInt().coerceIn(0, 100)
                val progressCd = stringResource(R.string.transfer_app_progress_cd, item.displayTitle, pctInt)
                val signatureSweep = Brush.sweepGradient(ShareFlowBrandGradients.SignatureStops)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .background(ShareFlowBrandColors.DeepNavy.copy(alpha = 0.68f))
                        .semantics {
                            contentDescription = progressCd
                            progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress, 0f..1f)
                        }
                        .testTag(progressOverlayTestTag ?: "transfer_app_icon_progress_${item.key}")
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (size >= 50.dp) 4.dp else 3.dp)
                    ) {
                        val strokePx = (if (size >= 50.dp) 3.dp else 2.5.dp).toPx()
                        val inset = strokePx / 2f
                        val arcSize = Size(
                            width = (this.size.width - strokePx).coerceAtLeast(1f),
                            height = (this.size.height - strokePx).coerceAtLeast(1f)
                        )
                        val topLeft = Offset(inset, inset)
                        // Subtle Sky Blue circular track
                        drawArc(
                            color = ShareFlowBrandColors.SkyBlue.copy(alpha = 0.28f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                        // Official Share Flow Signature Gradient arc
                        val sweep = (animatedProgress * 360f).coerceIn(0f, 360f)
                        if (sweep > 0.5f) {
                            drawArc(
                                brush = signatureSweep,
                                startAngle = -90f,
                                sweepAngle = sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokePx + 0.6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                    Text(
                        text = "$pctInt%",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = if (size >= 50.dp) 11.sp else 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp
                        ),
                        color = ShareFlowBrandColors.SoftCream,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Animated application icon for received apps appearing in the receiving device's transfer history.
 * Plays a crisp spring scale-up entrance animation paired with a subtle radial brand glow halo
 * (Sky Blue -> Golden Yellow over Deep Navy).
 */
@Composable
fun ReceivedHistoryAppIcon(
    apkPathOrUri: String?,
    appDisplayName: String,
    recordKey: String,
    iconSize: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    val appIcon = rememberAppIconBitmap(apkPathOrUri = apkPathOrUri)
    val glowPrimary = ShareFlowBrandColors.SkyBlue
    val glowSecondary = ShareFlowBrandColors.GoldenYellow

    val scaleAnim = remember(recordKey) { Animatable(0.76f) }
    val glowAlphaAnim = remember(recordKey) { Animatable(0f) }

    LaunchedEffect(recordKey) {
        launch {
            scaleAnim.animateTo(
                targetValue = 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        }
        launch {
            glowAlphaAnim.animateTo(
                targetValue = 0.44f,
                animationSpec = tween(durationMillis = 260)
            )
            glowAlphaAnim.animateTo(
                targetValue = 0.18f,
                animationSpec = tween(durationMillis = 520)
            )
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(iconSize + 10.dp)
            .drawBehind {
                val glowAlpha = glowAlphaAnim.value
                if (glowAlpha > 0.01f) {
                    val radius = (size.minDimension / 2f) * 1.12f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                glowPrimary.copy(alpha = glowAlpha),
                                glowSecondary.copy(alpha = glowAlpha * 0.42f),
                                Color.Transparent
                            ),
                            center = center,
                            radius = radius
                        ),
                        radius = radius,
                        center = center
                    )
                }
            }
    ) {
        Box(
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                }
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                border = BorderStroke(
                    width = 1.dp,
                    color = glowPrimary.copy(alpha = (glowAlphaAnim.value * 1.15f).coerceIn(0.16f, 0.52f))
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (appIcon != null) {
                        Image(
                            bitmap = appIcon,
                            contentDescription = appDisplayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.small)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Android,
                            contentDescription = appDisplayName,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(iconSize * 0.56f)
                        )
                    }
                }
            }

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .size(18.dp)
                    .align(Alignment.BottomEnd)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(R.string.history_direction_received),
                        tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                }
            }
        }
    }
}
