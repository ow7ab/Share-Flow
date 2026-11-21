package com.example.shareflow.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.domain.model.ApkExtractionStatus
import com.example.shareflow.domain.model.ExtractedApkArtifact
import com.example.shareflow.domain.model.InstalledAppInfo
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ReceivedApkIntegrityStatus
import com.example.shareflow.domain.model.ShareFlowApkPartManifest
import com.example.shareflow.domain.model.ShareFlowAppBundleManifest
import com.example.shareflow.domain.model.ShareableAppInfo
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.repository.AppSharingRepository
import com.example.shareflow.domain.repository.TransferHistoryRepository
import com.example.shareflow.network.transfer.ChunkProcessor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Metadata extracted from an APK archive via Android's [PackageManager.getPackageArchiveInfo].
 */
data class ApkArchiveMetadata(
    val packageName: String,
    val appLabel: String?,
    val versionName: String?,
    val versionCode: Long
)

/**
 * Abstraction for inspecting an on-disk APK archive using Android's [PackageManager].
 */
interface ApkArchiveInspector {
    fun inspectApkArchive(apkFile: File): ApkArchiveMetadata?
}

class AndroidApkArchiveInspector(
    private val context: Context
) : ApkArchiveInspector {
    override fun inspectApkArchive(apkFile: File): ApkArchiveMetadata? {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) return null
        return runCatching {
            val pm = context.packageManager
            val archiveInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }
            if (archiveInfo == null || archiveInfo.packageName.isNullOrBlank()) {
                return null
            }
            val appInfo = archiveInfo.applicationInfo
            val label = if (appInfo != null) {
                appInfo.sourceDir = apkFile.absolutePath
                appInfo.publicSourceDir = apkFile.absolutePath
                runCatching { pm.getApplicationLabel(appInfo)?.toString()?.takeIf { it.isNotBlank() } }
                    .getOrNull()
            } else {
                null
            }
            ApkArchiveMetadata(
                packageName = archiveInfo.packageName,
                appLabel = label,
                versionName = archiveInfo.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(archiveInfo)
            )
        }.getOrNull()
    }
}

/**
 * Production Android implementation of [AppSharingRepository].
 *
 * Security & privacy invariants:
 * - Discovers strictly real installed apps visible under Android package visibility rules (`<queries>`).
 * - Never fabricates installed applications.
 * - Reads ONLY public APK installation files (`publicSourceDir`/`sourceDir` & split APK paths).
 * - Never reads `/data/data`, private app directories, credentials, or app databases.
 * - Streams APK extraction in 64 KB chunks with live SHA-256 calculation (bounded RAM).
 * - Verifies received APK files on disk (existence + SHA-256 + PackageManager archive parse) before
 *   allowing Android's system installation intent via scoped [FileProvider].
 * - Never silently installs or bypasses Android installation security.
 */
class AndroidAppSharingRepository(
    private val context: Context,
    private val transferHistoryRepository: TransferHistoryRepository,
    private val checksumVerifier: ChecksumVerifier,
    private val apkArchiveInspector: ApkArchiveInspector = AndroidApkArchiveInspector(context),
    private val receiveRootProvider: () -> File = { ChunkProcessor.defaultReceiveRoot(context) },
    private val exportRootProvider: () -> File = { File(context.cacheDir, APK_EXPORT_DIR_NAME) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AppSharingRepository {

    override suspend fun listShareableApps(includeSystemApps: Boolean): AppResult<List<InstalledAppInfo>> =
        when (val result = discoverInstalledApps(includeSystemApps)) {
            is AppResult.Success -> AppResult.Success(result.data.map { it.toInstalledAppInfo() })
            is AppResult.Failure -> result
        }

    override suspend fun discoverInstalledApps(
        includeSystemApps: Boolean
    ): AppResult<List<ShareableAppInfo>> = withContext(ioDispatcher) {
        runCatching {
            val pm = context.packageManager
            val visiblePackageNames = linkedSetOf<String>()

            // 1. Query launchable apps allowed by our least-privilege <queries> manifest entry
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolvedActivities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
            resolvedActivities.forEach { info ->
                val pkg = info.activityInfo?.packageName
                if (!pkg.isNullOrBlank()) {
                    visiblePackageNames.add(pkg)
                }
            }

            // 2. Also include any other packages Android already makes visible to this app
            //    (strictly bounded by Android 11+ package visibility; never uses QUERY_ALL_PACKAGES).
            runCatching {
                val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledPackages(0)
                }
                installed.forEach { pkgInfo ->
                    if (!pkgInfo.packageName.isNullOrBlank()) {
                        visiblePackageNames.add(pkgInfo.packageName)
                    }
                }
            }

            val discovered = visiblePackageNames.mapNotNull { pkgName ->
                inspectInstalledPackage(pm, pkgName, includeSystemApps)
            }.sortedWith(
                compareBy<ShareableAppInfo> { it.appName.lowercase() }
                    .thenBy { it.packageName.lowercase() }
            )

            AppResult.Success(discovered)
        }.getOrElse { t ->
            AppResult.Failure(
                AppError.Storage("Unable to query visible installed applications", t)
            )
        }
    }

    override fun loadAppIcon(packageName: String): Drawable? = runCatching {
        context.packageManager.getApplicationIcon(packageName)
    }.getOrNull()

    override suspend fun extractApksForSharing(
        apps: List<ShareableAppInfo>
    ): AppResult<List<ExtractedApkArtifact>> = withContext(ioDispatcher) {
        if (apps.isEmpty()) {
            return@withContext AppResult.Failure(
                AppError.Validation("Select at least one application to share.")
            )
        }

        val unextractable = apps.firstOrNull { !it.isExtractable }
        if (unextractable != null) {
            return@withContext AppResult.Failure(
                AppError.PermissionDenied(
                    unextractable.unavailableReason
                        ?: "${unextractable.appName} cannot be extracted under Android package restrictions."
                )
            )
        }

        val exportRoot = exportRootProvider().apply { if (!exists()) mkdirs() }
        pruneExpiredExports(exportRoot)
        val batchDir = File(exportRoot, "batch_${System.currentTimeMillis()}").apply { mkdirs() }

        val artifacts = mutableListOf<ExtractedApkArtifact>()

        for (app in apps) {
            val basePath = app.baseApkPath
            if (basePath.isNullOrBlank()) {
                return@withContext AppResult.Failure(
                    AppError.NotFound("Public APK path unavailable for ${app.appName}.")
                )
            }
            val baseFile = File(basePath)
            if (!baseFile.exists() || !baseFile.isFile || !baseFile.canRead()) {
                return@withContext AppResult.Failure(
                    AppError.PermissionDenied("Base APK for ${app.appName} is unreadable.")
                )
            }

            val safeAppStem = sanitizeSegment("${app.appName}_v${app.versionName}")
            if (app.isSplitApk && app.splitApkPaths.isNotEmpty()) {
                val relativeFolder = "${safeAppStem}_split_apk"
                val targetFolder = File(batchDir, relativeFolder).apply { mkdirs() }

                val bundleParts = mutableListOf<ShareFlowApkPartManifest>()
                val baseDest = File(targetFolder, "base.apk")
                when (val copyRes = streamCopyWithSha256(baseFile, baseDest)) {
                    is AppResult.Failure -> return@withContext copyRes
                    is AppResult.Success -> {
                        bundleParts.add(
                            ShareFlowApkPartManifest(
                                fileName = baseDest.name,
                                sizeBytes = copyRes.data.sizeBytes,
                                sha256 = copyRes.data.sha256Hex,
                                isBase = true
                            )
                        )
                        artifacts.add(
                            ExtractedApkArtifact(
                                packageName = app.packageName,
                                appName = app.appName,
                                versionName = app.versionName,
                                fileName = baseDest.name,
                                relativePath = relativeFolder,
                                localFilePath = baseDest.absolutePath,
                                uriString = Uri.fromFile(baseDest).toString(),
                                sizeBytes = copyRes.data.sizeBytes,
                                sha256Checksum = copyRes.data.sha256Hex,
                                isSplitPart = true
                            )
                        )
                    }
                }

                app.splitApkPaths.forEachIndexed { idx, splitPath ->
                    val splitSource = File(splitPath)
                    if (!splitSource.exists() || !splitSource.isFile || !splitSource.canRead()) {
                        return@withContext AppResult.Failure(
                            AppError.PermissionDenied(
                                "Split APK part #${idx + 1} for ${app.appName} is inaccessible."
                            )
                        )
                    }
                    val rawSplitStem = splitSource.nameWithoutExtension.ifBlank { "split_${idx + 1}" }
                    val splitDestName = "${sanitizeSegment(rawSplitStem)}.apk"
                    val splitDest = allocateUniqueExportFile(targetFolder, splitDestName)
                    when (val splitCopy = streamCopyWithSha256(splitSource, splitDest)) {
                        is AppResult.Failure -> return@withContext splitCopy
                        is AppResult.Success -> {
                            bundleParts.add(
                                ShareFlowApkPartManifest(
                                    fileName = splitDest.name,
                                    sizeBytes = splitCopy.data.sizeBytes,
                                    sha256 = splitCopy.data.sha256Hex,
                                    isBase = false
                                )
                            )
                            artifacts.add(
                                ExtractedApkArtifact(
                                    packageName = app.packageName,
                                    appName = app.appName,
                                    versionName = app.versionName,
                                    fileName = splitDest.name,
                                    relativePath = relativeFolder,
                                    localFilePath = splitDest.absolutePath,
                                    uriString = Uri.fromFile(splitDest).toString(),
                                    sizeBytes = splitCopy.data.sizeBytes,
                                    sha256Checksum = splitCopy.data.sha256Hex,
                                    isSplitPart = true
                                )
                            )
                        }
                    }
                }

                // Emit verified JSON bundle manifest so receiver validates and installs all split parts together.
                val manifest = ShareFlowAppBundleManifest(
                    packageName = app.packageName,
                    appName = app.appName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    baseApkFileName = baseDest.name,
                    parts = bundleParts
                )
                val manifestJson = manifest.toJsonString()
                val manifestFile = File(targetFolder, BUNDLE_MANIFEST_FILE_NAME)
                manifestFile.writeText(manifestJson, Charsets.UTF_8)
                val manifestSha = MessageDigest.getInstance("SHA-256")
                    .digest(manifestFile.readBytes())
                    .joinToString("") { "%02x".format(it) }
                artifacts.add(
                    ExtractedApkArtifact(
                        packageName = app.packageName,
                        appName = app.appName,
                        versionName = app.versionName,
                        fileName = manifestFile.name,
                        relativePath = relativeFolder,
                        localFilePath = manifestFile.absolutePath,
                        uriString = Uri.fromFile(manifestFile).toString(),
                        sizeBytes = manifestFile.length(),
                        sha256Checksum = manifestSha,
                        isSplitPart = true,
                        isBundleManifest = true
                    )
                )
            } else {
                val destName = "$safeAppStem.apk"
                val destFile = allocateUniqueExportFile(batchDir, destName)
                when (val copyRes = streamCopyWithSha256(baseFile, destFile)) {
                    is AppResult.Failure -> return@withContext copyRes
                    is AppResult.Success -> {
                        artifacts.add(
                            ExtractedApkArtifact(
                                packageName = app.packageName,
                                appName = app.appName,
                                versionName = app.versionName,
                                fileName = destFile.name,
                                relativePath = "",
                                localFilePath = destFile.absolutePath,
                                uriString = Uri.fromFile(destFile).toString(),
                                sizeBytes = copyRes.data.sizeBytes,
                                sha256Checksum = copyRes.data.sha256Hex,
                                isSplitPart = false
                            )
                        )
                    }
                }
            }
        }

        AppResult.Success(artifacts)
    }

    override fun observeReceivedApks(): Flow<List<ReceivedApkInfo>> =
        transferHistoryRepository.observeAllRecords().map { records ->
            withContext(ioDispatcher) {
                val receivedApkRecords = records.filter { rec ->
                    rec.direction == TransferDirection.RECEIVED &&
                        rec.status == TransferStatus.COMPLETED_VERIFIED &&
                        (rec.fileType == TransferFileType.APK ||
                            rec.fileName.endsWith(".apk", ignoreCase = true))
                }

                val seenCanonicalPaths = mutableSetOf<String>()
                val results = mutableListOf<ReceivedApkInfo>()

                for (rec in receivedApkRecords) {
                    val resolvedFile = resolveLocalFileFromUri(rec.localUri)
                    if (resolvedFile == null) {
                        results.add(
                            ReceivedApkInfo(
                                id = rec.id,
                                fileName = rec.fileName.substringAfterLast('/'),
                                filePath = rec.localUri.orEmpty(),
                                fileSizeBytes = rec.fileSizeBytes,
                                packageName = null,
                                appLabel = null,
                                versionName = null,
                                versionCode = null,
                                expectedSha256 = rec.sha256Checksum,
                                actualSha256 = null,
                                integrityStatus = ReceivedApkIntegrityStatus.FILE_MISSING,
                                statusDetail = "Received APK file path is unavailable on disk.",
                                receivedAtEpochMillis = rec.completedAtEpochMillis ?: rec.startedAtEpochMillis,
                                peerDeviceName = rec.peerDeviceName
                            )
                        )
                        continue
                    }
                    // For split APK bundles, represent the entire bundle via base.apk instead of showing
                    // separate entries for resource/ABI config splits (which PackageManager cannot parse standalone).
                    val parentHasManifest = resolvedFile.parentFile?.let {
                        File(it, BUNDLE_MANIFEST_FILE_NAME).exists()
                    } == true
                    if (parentHasManifest && !resolvedFile.name.equals("base.apk", ignoreCase = true)) {
                        runCatching { resolvedFile.canonicalPath }.getOrNull()?.let { seenCanonicalPaths.add(it) }
                        continue
                    }
                    runCatching { resolvedFile.canonicalPath }.getOrNull()?.let { seenCanonicalPaths.add(it) }
                    results.add(
                        verifyReceivedApkInternal(
                            apkFile = resolvedFile,
                            expectedSha256 = rec.sha256Checksum,
                            recordId = rec.id,
                            peerDeviceName = rec.peerDeviceName,
                            receivedAtEpochMillis = rec.completedAtEpochMillis ?: rec.startedAtEpochMillis
                        )
                    )
                }

                // Also inspect any .apk files physically stored inside the ShareFlow receive root
                // that are not yet linked to a history record (never marked VERIFIED_INSTALLABLE without checksum).
                val receiveRoot = receiveRootProvider()
                if (receiveRoot.exists() && receiveRoot.isDirectory) {
                    receiveRoot.walkTopDown()
                        .maxDepth(3)
                        .filter { file ->
                            if (!file.isFile || !file.extension.equals("apk", ignoreCase = true)) return@filter false
                            if (file.absolutePath.contains(".shareflow_staging")) return@filter false
                            val parentHasManifest = file.parentFile?.let {
                                File(it, BUNDLE_MANIFEST_FILE_NAME).exists()
                            } == true
                            if (parentHasManifest && !file.name.equals("base.apk", ignoreCase = true)) {
                                return@filter false
                            }
                            true
                        }
                        .forEach { file ->
                            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                            if (seenCanonicalPaths.add(canonical)) {
                                results.add(
                                    verifyReceivedApkInternal(
                                        apkFile = file,
                                        expectedSha256 = null,
                                        recordId = "disk_${canonical.hashCode()}",
                                        peerDeviceName = null,
                                        receivedAtEpochMillis = file.lastModified()
                                    )
                                )
                            }
                        }
                }

                results.sortedByDescending { it.receivedAtEpochMillis }
            }
        }

    override suspend fun verifyReceivedApk(
        apkFile: File,
        expectedSha256: String?,
        recordId: String?,
        peerDeviceName: String?,
        receivedAtEpochMillis: Long
    ): ReceivedApkInfo = withContext(ioDispatcher) {
        verifyReceivedApkInternal(
            apkFile = apkFile,
            expectedSha256 = expectedSha256,
            recordId = recordId ?: "apk_${apkFile.absolutePath.hashCode()}",
            peerDeviceName = peerDeviceName,
            receivedAtEpochMillis = receivedAtEpochMillis
        )
    }

    override suspend fun createSystemInstallIntent(
        apkFile: File,
        expectedSha256: String?
    ): AppResult<Intent> = withContext(ioDispatcher) {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return@withContext AppResult.Failure(
                AppError.NotFound("APK file is missing or empty on disk.")
            )
        }
        if (!apkFile.extension.equals("apk", ignoreCase = true)) {
            return@withContext AppResult.Failure(
                AppError.Security("Refused to launch installer for non-APK file.")
            )
        }

        val canonicalTarget = runCatching { apkFile.canonicalFile }.getOrElse {
            return@withContext AppResult.Failure(AppError.Security("Unable to canonicalize APK path."))
        }
        val canonicalReceiveRoot = runCatching { receiveRootProvider().canonicalFile }.getOrElse {
            return@withContext AppResult.Failure(AppError.Storage("Unable to resolve received files directory."))
        }
        val rootPrefix = canonicalReceiveRoot.path.trimEnd(File.separatorChar) + File.separator
        if (!canonicalTarget.path.startsWith(rootPrefix)) {
            return@withContext AppResult.Failure(
                AppError.Security("APK file is outside ShareFlow's verified received directory.")
            )
        }

        // Re-verify integrity right before invoking Android's system package installer
        val verifiedInfo = verifyReceivedApkInternal(
            apkFile = canonicalTarget,
            expectedSha256 = expectedSha256,
            recordId = "install_check",
            peerDeviceName = null,
            receivedAtEpochMillis = canonicalTarget.lastModified()
        )
        if (verifiedInfo.integrityStatus == ReceivedApkIntegrityStatus.CHECKSUM_MISMATCH) {
            return@withContext AppResult.Failure(
                AppError.IntegrityMismatch(
                    message = "APK SHA-256 verification failed. Installation blocked for security.",
                    expectedHash = expectedSha256.orEmpty(),
                    actualHash = verifiedInfo.actualSha256.orEmpty()
                )
            )
        }
        if (verifiedInfo.integrityStatus == ReceivedApkIntegrityStatus.INVALID_APK_ARCHIVE ||
            verifiedInfo.integrityStatus == ReceivedApkIntegrityStatus.FILE_MISSING
        ) {
            return@withContext AppResult.Failure(
                AppError.Validation(verifiedInfo.statusDetail)
            )
        }
        if (expectedSha256 != null && verifiedInfo.integrityStatus != ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE) {
            return@withContext AppResult.Failure(
                AppError.Validation("APK integrity could not be confirmed.")
            )
        }

        val authority = "${context.packageName}.fileprovider"
        val contentUri = runCatching {
            FileProvider.getUriForFile(context, authority, canonicalTarget)
        }.getOrElse { t ->
            return@withContext AppResult.Failure(
                AppError.Security("Unable to create scoped FileProvider URI for APK installation.", t)
            )
        }

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppResult.Success(installIntent)
    }

    private fun inspectInstalledPackage(
        pm: PackageManager,
        packageName: String,
        includeSystemApps: Boolean
    ): ShareableAppInfo? {
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return null

        val appInfo = pkgInfo.applicationInfo ?: return null
        val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        if (isSystemApp && !includeSystemApps) return null

        val label = runCatching {
            pm.getApplicationLabel(appInfo)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull() ?: packageName

        val versionName = pkgInfo.versionName?.takeIf { it.isNotBlank() } ?: "1.0"
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        // Strictly read public APK installation paths — NEVER private app data directories.
        val basePath = appInfo.publicSourceDir?.takeIf { it.isNotBlank() }
            ?: appInfo.sourceDir?.takeIf { it.isNotBlank() }
        val splitPaths = (appInfo.splitPublicSourceDirs ?: appInfo.splitSourceDirs)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val isSplitApk = splitPaths.isNotEmpty()

        val evaluation = evaluateExtractionAvailability(basePath, splitPaths)
        val categoryLabel = resolveAppCategoryLabel(appInfo)
        val installedAt = maxOf(pkgInfo.lastUpdateTime, pkgInfo.firstInstallTime)

        return ShareableAppInfo(
            packageName = packageName,
            appName = label,
            versionName = versionName,
            versionCode = versionCode,
            baseApkPath = basePath,
            splitApkPaths = splitPaths,
            totalApkSizeBytes = evaluation.totalSizeBytes,
            isSystemApp = isSystemApp,
            isSplitApk = isSplitApk,
            extractionStatus = evaluation.status,
            unavailableReason = evaluation.unavailableReason,
            categoryLabel = categoryLabel,
            installedAtEpochMillis = installedAt
        )
    }

    private fun resolveAppCategoryLabel(appInfo: ApplicationInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return ""
        return when (appInfo.category) {
            ApplicationInfo.CATEGORY_GAME -> "Games"
            ApplicationInfo.CATEGORY_AUDIO -> "Audio"
            ApplicationInfo.CATEGORY_VIDEO -> "Video"
            ApplicationInfo.CATEGORY_IMAGE -> "Photo"
            ApplicationInfo.CATEGORY_SOCIAL -> "Social"
            ApplicationInfo.CATEGORY_NEWS -> "News"
            ApplicationInfo.CATEGORY_MAPS -> "Maps"
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Productivity"
            else -> ""
        }
    }

    private data class ExtractionEvaluation(
        val status: ApkExtractionStatus,
        val totalSizeBytes: Long?,
        val unavailableReason: String?
    )

    private fun evaluateExtractionAvailability(
        basePath: String?,
        splitPaths: List<String>
    ): ExtractionEvaluation {
        if (basePath.isNullOrBlank()) {
            return ExtractionEvaluation(
                status = ApkExtractionStatus.UNAVAILABLE,
                totalSizeBytes = null,
                unavailableReason = "Android did not expose a public APK path for this app."
            )
        }

        val baseFile = File(basePath)
        if (!baseFile.exists() || !baseFile.isFile || !baseFile.canRead()) {
            return ExtractionEvaluation(
                status = ApkExtractionStatus.UNAVAILABLE,
                totalSizeBytes = null,
                unavailableReason = "Base APK file is protected or unreadable on this device."
            )
        }

        val baseLength = baseFile.length().takeIf { it > 0L }
            ?: return ExtractionEvaluation(
                status = ApkExtractionStatus.UNAVAILABLE,
                totalSizeBytes = null,
                unavailableReason = "Base APK size is unavailable or restricted."
            )

        if (splitPaths.isEmpty()) {
            return ExtractionEvaluation(
                status = ApkExtractionStatus.EXTRACTABLE,
                totalSizeBytes = baseLength,
                unavailableReason = null
            )
        }

        var totalBytes = baseLength
        var unreadableSplits = 0
        for (splitPath in splitPaths) {
            val splitFile = File(splitPath)
            if (splitFile.exists() && splitFile.isFile && splitFile.canRead() && splitFile.length() > 0L) {
                totalBytes += splitFile.length()
            } else {
                unreadableSplits++
            }
        }

        return if (unreadableSplits > 0) {
            ExtractionEvaluation(
                status = ApkExtractionStatus.UNAVAILABLE,
                totalSizeBytes = totalBytes,
                unavailableReason = "Split APK ($unreadableSplits of ${splitPaths.size} split files restricted) cannot be extracted completely."
            )
        } else {
            ExtractionEvaluation(
                status = ApkExtractionStatus.SPLIT_APK_EXTRACTABLE,
                totalSizeBytes = totalBytes,
                unavailableReason = null
            )
        }
    }

    private fun verifyReceivedApkInternal(
        apkFile: File,
        expectedSha256: String?,
        recordId: String,
        peerDeviceName: String?,
        receivedAtEpochMillis: Long
    ): ReceivedApkInfo {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return ReceivedApkInfo(
                id = recordId,
                fileName = apkFile.name,
                filePath = apkFile.absolutePath,
                fileSizeBytes = 0L,
                packageName = null,
                appLabel = null,
                versionName = null,
                versionCode = null,
                expectedSha256 = expectedSha256,
                actualSha256 = null,
                integrityStatus = ReceivedApkIntegrityStatus.FILE_MISSING,
                statusDetail = "APK file is missing or empty on disk.",
                receivedAtEpochMillis = receivedAtEpochMillis,
                peerDeviceName = peerDeviceName
            )
        }

        val actualSize = apkFile.length()
        val actualSha256 = runCatching {
            FileInputStream(apkFile).use { stream ->
                when (val res = checksumVerifier.computeSha256(stream)) {
                    is AppResult.Success -> res.data
                    is AppResult.Failure -> null
                }
            }
        }.getOrNull()

        val normalizedExpected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (normalizedExpected != null) {
            val matches = actualSha256 != null &&
                MessageDigest.isEqual(
                    normalizedExpected.toByteArray(Charsets.UTF_8),
                    actualSha256.lowercase().toByteArray(Charsets.UTF_8)
                )
            if (!matches) {
                return ReceivedApkInfo(
                    id = recordId,
                    fileName = apkFile.name,
                    filePath = apkFile.absolutePath,
                    fileSizeBytes = actualSize,
                    packageName = null,
                    appLabel = null,
                    versionName = null,
                    versionCode = null,
                    expectedSha256 = normalizedExpected,
                    actualSha256 = actualSha256,
                    integrityStatus = ReceivedApkIntegrityStatus.CHECKSUM_MISMATCH,
                    statusDetail = "SHA-256 checksum does not match the verified transfer digest. File may be corrupted or modified.",
                    receivedAtEpochMillis = receivedAtEpochMillis,
                    peerDeviceName = peerDeviceName
                )
            }
        }

        val archiveMeta = apkArchiveInspector.inspectApkArchive(apkFile)
        if (archiveMeta == null) {
            return ReceivedApkInfo(
                id = recordId,
                fileName = apkFile.name,
                filePath = apkFile.absolutePath,
                fileSizeBytes = actualSize,
                packageName = null,
                appLabel = null,
                versionName = null,
                versionCode = null,
                expectedSha256 = normalizedExpected,
                actualSha256 = actualSha256,
                integrityStatus = ReceivedApkIntegrityStatus.INVALID_APK_ARCHIVE,
                statusDetail = "File is not a valid Android APK package archive and cannot be installed.",
                receivedAtEpochMillis = receivedAtEpochMillis,
                peerDeviceName = peerDeviceName
            )
        }

        val companionManifest = apkFile.parentFile
            ?.let { File(it, BUNDLE_MANIFEST_FILE_NAME) }
            ?.takeIf { it.exists() && it.isFile }
        val siblingApks = if (companionManifest != null) {
            apkFile.parentFile?.listFiles()
                ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
                ?.sortedBy { if (it.name.equals("base.apk", ignoreCase = true)) 0 else 1 }
                ?.map { it.absolutePath }
                .orEmpty()
        } else {
            emptyList()
        }
        val isSplitBundle = companionManifest != null && siblingApks.size > 1

        return if (normalizedExpected != null) {
            ReceivedApkInfo(
                id = recordId,
                fileName = apkFile.name,
                filePath = apkFile.absolutePath,
                fileSizeBytes = actualSize,
                packageName = archiveMeta.packageName,
                appLabel = archiveMeta.appLabel ?: archiveMeta.packageName,
                versionName = archiveMeta.versionName ?: "1.0",
                versionCode = archiveMeta.versionCode,
                expectedSha256 = normalizedExpected,
                actualSha256 = actualSha256,
                integrityStatus = ReceivedApkIntegrityStatus.VERIFIED_INSTALLABLE,
                statusDetail = if (isSplitBundle) {
                    "Verified split APK bundle (${siblingApks.size} parts) ready for PackageInstaller."
                } else {
                    "SHA-256 checksum and Android APK archive verified. Ready for system installer."
                },
                receivedAtEpochMillis = receivedAtEpochMillis,
                peerDeviceName = peerDeviceName,
                isSplitBundle = isSplitBundle,
                splitBundleApkPaths = siblingApks,
                bundleManifestPath = companionManifest?.absolutePath
            )
        } else {
            ReceivedApkInfo(
                id = recordId,
                fileName = apkFile.name,
                filePath = apkFile.absolutePath,
                fileSizeBytes = actualSize,
                packageName = archiveMeta.packageName,
                appLabel = archiveMeta.appLabel ?: archiveMeta.packageName,
                versionName = archiveMeta.versionName ?: "1.0",
                versionCode = archiveMeta.versionCode,
                expectedSha256 = null,
                actualSha256 = actualSha256,
                integrityStatus = ReceivedApkIntegrityStatus.UNVERIFIED_EXTERNAL,
                statusDetail = "Valid APK found on disk, but no ShareFlow transfer checksum record exists.",
                receivedAtEpochMillis = receivedAtEpochMillis,
                peerDeviceName = peerDeviceName,
                isSplitBundle = isSplitBundle,
                splitBundleApkPaths = siblingApks,
                bundleManifestPath = companionManifest?.absolutePath
            )
        }
    }

    private data class CopiedApkMetrics(
        val sizeBytes: Long,
        val sha256Hex: String
    )

    private fun streamCopyWithSha256(source: File, destination: File): AppResult<CopiedApkMetrics> =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    var read = input.read(buffer)
                    while (read != -1) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            totalBytes += read.toLong()
                        }
                        read = input.read(buffer)
                    }
                    output.fd.sync()
                }
            }
            if (totalBytes <= 0L || totalBytes != source.length()) {
                runCatching { destination.delete() }
                return AppResult.Failure(
                    AppError.Storage("Extracted APK byte count mismatch for ${source.name}.")
                )
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            AppResult.Success(CopiedApkMetrics(totalBytes, hex))
        }.getOrElse { t ->
            runCatching { destination.delete() }
            AppResult.Failure(AppError.Storage("Failed to extract APK ${source.name}", t))
        }

    private fun allocateUniqueExportFile(directory: File, desiredName: String): File {
        val dot = desiredName.lastIndexOf('.')
        val stem = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot > 0) desiredName.substring(dot) else ""
        var candidate = File(directory, desiredName)
        var counter = 1
        while (candidate.exists()) {
            candidate = File(directory, "${stem}_$counter$ext")
            counter++
        }
        return candidate
    }

    private fun resolveLocalFileFromUri(uriString: String?): File? {
        if (uriString.isNullOrBlank()) return null
        val uri = Uri.parse(uriString)
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }
            null -> File(uriString)
            else -> null
        }
    }

    private fun pruneExpiredExports(exportRoot: File) {
        val cutoff = System.currentTimeMillis() - EXPORT_CACHE_TTL_MS
        runCatching {
            exportRoot.listFiles()?.forEach { child ->
                if (child.lastModified() < cutoff) {
                    child.deleteRecursively()
                }
            }
        }
    }

    private fun sanitizeSegment(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "app" }

    companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val APK_EXPORT_DIR_NAME = "shareflow_apk_exports"
        const val BUNDLE_MANIFEST_FILE_NAME = "shareflow_app_manifest.json"
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val EXPORT_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
