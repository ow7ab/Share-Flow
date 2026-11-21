package com.example.shareflow.data.apps

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.ChecksumVerifier
import com.example.shareflow.data.repository.AndroidApkArchiveInspector
import com.example.shareflow.data.repository.AndroidAppSharingRepository
import com.example.shareflow.data.repository.ApkArchiveInspector
import com.example.shareflow.domain.model.ReceivedApkInfo
import com.example.shareflow.domain.model.ShareFlowAppBundleManifest
import com.example.shareflow.network.transfer.ChunkProcessor
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class PackageInstallCallbackEvent(
    val sessionId: Int,
    val packageName: String,
    val status: Int,
    val statusMessage: String,
    val confirmationIntent: Intent?
)

internal object PackageInstallCallbackBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(PackageInstallCallbackEvent) -> Unit>()

    fun register(listener: (PackageInstallCallbackEvent) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: (PackageInstallCallbackEvent) -> Unit) {
        listeners.remove(listener)
    }

    fun dispatch(event: PackageInstallCallbackEvent) {
        for (listener in listeners) {
            listener(event)
        }
    }
}

/**
 * Validated single-APK or multi-split APK installation payload ready for Android [PackageInstaller].
 */
data class ValidatedInstallBundle(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkFiles: List<File>,
    val totalSizeBytes: Long,
    val isSplitBundle: Boolean,
    val isUpdateToExistingApp: Boolean,
    val installedVersionCode: Long?
)

/**
 * Observable installation progress & status reported directly from Android [PackageInstaller].
 */
sealed interface AppInstallSessionState {
    data object Idle : AppInstallSessionState

    data class Validating(val displayName: String) : AppInstallSessionState

    data class NeedsUnknownSourcesPermission(
        val bundle: ValidatedInstallBundle
    ) : AppInstallSessionState

    data class Staging(
        val packageName: String,
        val appName: String,
        val progressFraction: Float
    ) : AppInstallSessionState

    data class AwaitingUserConfirmation(
        val sessionId: Int,
        val packageName: String,
        val appName: String,
        val confirmationIntent: Intent
    ) : AppInstallSessionState

    data class Succeeded(
        val packageName: String,
        val appName: String
    ) : AppInstallSessionState

    data class Failed(
        val packageName: String,
        val appName: String,
        val reason: String,
        val wasCancelledByUser: Boolean = false,
        val fallbackApkFile: File? = null
    ) : AppInstallSessionState
}

/**
 * Phase 6 — Real Android [PackageInstaller.Session] manager for single and multi-split APKs.
 *
 * Security & Integrity Guarantees:
 * - Confines all APK files strictly inside ShareFlow's verified receive directory (canonical path check).
 * - Validates file existence, non-zero byte counts, SHA-256 checksums, valid APK archive headers,
 *   and identical packageName across base + every split APK before opening a PackageInstaller session.
 * - Verifies available storage space via [StatFs].
 * - Never bypasses Android's system installation dialog; always launches the system confirmation
 *   [Intent] delivered via [PackageInstaller.STATUS_PENDING_USER_ACTION].
 * - Checks `canRequestPackageInstalls()` and guides the user to Settings when needed, remembering
 *   the pending bundle to resume immediately upon return.
 */
class ApplicationInstallationManager(
    context: Context,
    private val checksumVerifier: ChecksumVerifier,
    private val permissionManager: PermissionManager,
    private val apkArchiveInspector: ApkArchiveInspector = AndroidApkArchiveInspector(context),
    private val receiveRootProvider: () -> File = { ChunkProcessor.defaultReceiveRoot(context) },
    private val availableStorageBytesProvider: (File) -> Long = { dir ->
        runCatching {
            val target = if (dir.exists()) dir else dir.parentFile ?: dir
            StatFs(target.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext

    private val _installState = MutableStateFlow<AppInstallSessionState>(AppInstallSessionState.Idle)
    val installState: StateFlow<AppInstallSessionState> = _installState.asStateFlow()

    @Volatile
    private var activeBundle: ValidatedInstallBundle? = null

    @Volatile
    private var activeSessionId: Int = -1

    private val callbackListener: (PackageInstallCallbackEvent) -> Unit = { event ->
        handleInstallCallback(event)
    }

    init {
        PackageInstallCallbackBus.register(callbackListener)
    }

    /**
     * Validates a single APK or split APK bundle (including companion `shareflow_app_manifest.json`
     * when present) without starting installation yet.
     */
    suspend fun validateReceivedAppForInstall(
        receivedApk: ReceivedApkInfo
    ): AppResult<ValidatedInstallBundle> = withContext(ioDispatcher) {
        val primaryFile = File(receivedApk.filePath)
        val manifestFile = receivedApk.bundleManifestPath?.let { File(it) }
            ?: primaryFile.parentFile?.let { File(it, AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME) }
                ?.takeIf { it.exists() && it.isFile }

        validateFilesInternal(
            primaryApkFile = primaryFile,
            expectedPrimarySha256 = receivedApk.expectedSha256,
            manifestFile = manifestFile
        )
    }

    /**
     * Direct file-based validation entry point (useful for unit tests and folder bundles).
     */
    suspend fun validateApkFiles(
        primaryApkFile: File,
        expectedPrimarySha256: String? = null,
        manifestFile: File? = null
    ): AppResult<ValidatedInstallBundle> = withContext(ioDispatcher) {
        validateFilesInternal(primaryApkFile, expectedPrimarySha256, manifestFile)
    }

    /**
     * Validates, checks unknown-sources permission, stages all APK parts into a single Android
     * [PackageInstaller.Session], and commits for explicit user confirmation.
     */
    suspend fun startInstallation(receivedApk: ReceivedApkInfo): AppResult<ValidatedInstallBundle> {
        _installState.value = AppInstallSessionState.Validating(
            displayName = receivedApk.appLabel ?: receivedApk.fileName
        )
        val validated = when (val res = validateReceivedAppForInstall(receivedApk)) {
            is AppResult.Failure -> {
                val primary = File(receivedApk.filePath).takeIf {
                    !receivedApk.isSplitBundle && it.exists() && it.isFile
                }
                _installState.value = AppInstallSessionState.Failed(
                    packageName = receivedApk.packageName.orEmpty(),
                    appName = receivedApk.appLabel ?: receivedApk.fileName,
                    reason = res.error.message,
                    fallbackApkFile = primary
                )
                return res
            }
            is AppResult.Success -> res.data
        }
        return installValidatedBundle(validated)
    }

    /**
     * Direct file-based installation entry point (e.g., from Custom File Viewer or Transfer History).
     * Automatically detects companion `shareflow_app_manifest.json` for split APK bundles.
     */
    suspend fun startInstallationFromFile(
        apkFile: File,
        expectedSha256: String? = null
    ): AppResult<ValidatedInstallBundle> {
        _installState.value = AppInstallSessionState.Validating(displayName = apkFile.name)
        val manifestFile = apkFile.parentFile?.let {
            File(it, AndroidAppSharingRepository.BUNDLE_MANIFEST_FILE_NAME)
        }?.takeIf { it.exists() && it.isFile }

        val validated = when (val res = validateApkFiles(apkFile, expectedSha256, manifestFile)) {
            is AppResult.Failure -> {
                _installState.value = AppInstallSessionState.Failed(
                    packageName = "",
                    appName = apkFile.name,
                    reason = res.error.message,
                    fallbackApkFile = apkFile.takeIf { manifestFile == null && it.exists() && it.isFile }
                )
                return res
            }
            is AppResult.Success -> res.data
        }
        return installValidatedBundle(validated)
    }

    /**
     * Creates a scoped [FileProvider] `Intent.ACTION_VIEW` fallback for single-APK installation
     * strictly confined inside ShareFlow's verified receive directory. Useful on OEM ROMs where
     * `PackageInstaller.Session` is restricted.
     */
    fun createSystemInstallerFallbackIntent(apkFile: File): Intent? {
        val canonicalRoot = runCatching { receiveRootProvider().canonicalFile }.getOrNull() ?: return null
        val rootPrefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        val canonicalApk = validateFileInsideRoot(apkFile, rootPrefix) ?: return null
        if (!canonicalApk.extension.equals("apk", ignoreCase = true)) return null
        val uri = runCatching {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", canonicalApk)
        }.getOrNull() ?: return null
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, AndroidAppSharingRepository.APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Resumes a pending installation after the user returns from Android's Install Unknown Apps screen.
     */
    suspend fun resumePendingInstallIfPermitted(): AppResult<ValidatedInstallBundle>? {
        val current = _installState.value
        if (current !is AppInstallSessionState.NeedsUnknownSourcesPermission) return null
        if (!permissionManager.canRequestPackageInstalls()) return null
        return installValidatedBundle(current.bundle)
    }

    suspend fun installValidatedBundle(
        bundle: ValidatedInstallBundle
    ): AppResult<ValidatedInstallBundle> = withContext(ioDispatcher) {
        activeBundle = bundle

        if (!permissionManager.canRequestPackageInstalls()) {
            _installState.value = AppInstallSessionState.NeedsUnknownSourcesPermission(bundle)
            return@withContext AppResult.Success(bundle)
        }

        _installState.value = AppInstallSessionState.Staging(
            packageName = bundle.packageName,
            appName = bundle.appName,
            progressFraction = 0f
        )

        val packageInstaller = appContext.packageManager.packageInstaller
        var session: PackageInstaller.Session? = null
        var sessionId = -1

        try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(bundle.totalSizeBytes)
                setAppPackageName(bundle.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            sessionId = packageInstaller.createSession(params)
            activeSessionId = sessionId
            session = packageInstaller.openSession(sessionId)

            var writtenTotal = 0L
            val buffer = ByteArray(STREAM_BUFFER_BYTES)

            bundle.apkFiles.forEachIndexed { index, apkFile ->
                val splitWriteName = "${index}_${apkFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
                session.openWrite(splitWriteName, 0L, apkFile.length()).use { outStream ->
                    FileInputStream(apkFile).use { inStream ->
                        var read = inStream.read(buffer)
                        while (read != -1) {
                            if (read > 0) {
                                outStream.write(buffer, 0, read)
                                writtenTotal += read.toLong()
                                val frac = (writtenTotal.toFloat() / bundle.totalSizeBytes.coerceAtLeast(1L))
                                    .coerceIn(0f, 0.98f)
                                _installState.value = AppInstallSessionState.Staging(
                                    packageName = bundle.packageName,
                                    appName = bundle.appName,
                                    progressFraction = frac
                                )
                            }
                            read = inStream.read(buffer)
                        }
                    }
                    session.fsync(outStream)
                }
            }

            val callbackIntent = Intent(appContext, PackageInstallStatusReceiver::class.java).apply {
                action = PackageInstallStatusReceiver.ACTION_INSTALL_RESULT
                putExtra(PackageInstallStatusReceiver.EXTRA_PACKAGE_NAME, bundle.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                callbackIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            session = null

            AppResult.Success(bundle)
        } catch (t: Throwable) {
            runCatching { session?.abandon() }
            runCatching { session?.close() }
            if (sessionId >= 0) {
                runCatching { packageInstaller.abandonSession(sessionId) }
            }
            val message = t.message ?: "Unable to stage application package for Android installation."
            _installState.value = AppInstallSessionState.Failed(
                packageName = bundle.packageName,
                appName = bundle.appName,
                reason = message,
                fallbackApkFile = bundle.apkFiles.singleOrNull().takeIf { !bundle.isSplitBundle }
            )
            AppResult.Failure(AppError.Storage(message, t))
        }
    }

    fun resetState() {
        activeBundle = null
        activeSessionId = -1
        _installState.value = AppInstallSessionState.Idle
    }

    fun dispose() {
        PackageInstallCallbackBus.unregister(callbackListener)
    }

    internal fun handleInstallCallback(event: PackageInstallCallbackEvent) {
        if (activeSessionId != -1 && event.sessionId != -1 && event.sessionId != activeSessionId) {
            return
        }
        val bundle = activeBundle
        val pkgName = event.packageName.ifBlank { bundle?.packageName.orEmpty() }
        val appName = bundle?.appName ?: pkgName.ifBlank { "Application" }

        val fallbackSingleApk = bundle?.apkFiles?.singleOrNull()?.takeIf { bundle.isSplitBundle.not() }

        when (event.status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = event.confirmationIntent
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    _installState.value = AppInstallSessionState.AwaitingUserConfirmation(
                        sessionId = event.sessionId,
                        packageName = pkgName,
                        appName = appName,
                        confirmationIntent = confirm
                    )
                } else {
                    _installState.value = AppInstallSessionState.Failed(
                        packageName = pkgName,
                        appName = appName,
                        reason = "Android installer did not provide a confirmation dialog.",
                        fallbackApkFile = fallbackSingleApk
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Succeeded(
                    packageName = pkgName,
                    appName = appName
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "Installation was cancelled by the user.",
                    wasCancelledByUser = true
                )
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "Package conflicts with an existing installed app (different signing key).",
                    fallbackApkFile = fallbackSingleApk
                )
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "This application package is incompatible with this device ABI or Android version.",
                    fallbackApkFile = fallbackSingleApk
                )
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "Invalid or incomplete APK package. Verify all split APK parts were received.",
                    fallbackApkFile = fallbackSingleApk
                )
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "Insufficient device storage to complete installation."
                )
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = "Installation was blocked by Android device policy or Play Protect.",
                    fallbackApkFile = fallbackSingleApk
                )
            }

            else -> {
                activeSessionId = -1
                _installState.value = AppInstallSessionState.Failed(
                    packageName = pkgName,
                    appName = appName,
                    reason = event.statusMessage.ifBlank { "Android package installation failed (code ${event.status})." },
                    fallbackApkFile = fallbackSingleApk
                )
            }
        }
    }

    private fun validateFilesInternal(
        primaryApkFile: File,
        expectedPrimarySha256: String?,
        manifestFile: File?
    ): AppResult<ValidatedInstallBundle> {
        val canonicalReceiveRoot = runCatching { receiveRootProvider().canonicalFile }.getOrElse {
            return AppResult.Failure(AppError.Storage("Unable to resolve received files directory."))
        }
        val rootPrefix = canonicalReceiveRoot.path.trimEnd(File.separatorChar) + File.separator

        if (manifestFile != null && manifestFile.exists() && manifestFile.isFile) {
            return validateSplitManifestBundle(
                manifestFile = manifestFile,
                rootPrefix = rootPrefix,
                canonicalReceiveRoot = canonicalReceiveRoot
            )
        }

        // Single APK validation
        val canonicalApk = validateFileInsideRoot(primaryApkFile, rootPrefix)
            ?: return AppResult.Failure(
                AppError.Security("APK file is missing, empty, or outside ShareFlow's verified directory.")
            )
        if (!canonicalApk.extension.equals("apk", ignoreCase = true)) {
            return AppResult.Failure(AppError.Security("Refused non-APK file for installation."))
        }

        if (!expectedPrimarySha256.isNullOrBlank()) {
            val actualHash = computeSha256(canonicalApk)
                ?: return AppResult.Failure(AppError.Storage("Unable to hash APK file."))
            if (!hashesEqual(expectedPrimarySha256, actualHash)) {
                return AppResult.Failure(
                    AppError.IntegrityMismatch(
                        message = "APK SHA-256 verification failed. Installation blocked for security.",
                        expectedHash = expectedPrimarySha256,
                        actualHash = actualHash
                    )
                )
            }
        }

        val archive = apkArchiveInspector.inspectApkArchive(canonicalApk)
            ?: return AppResult.Failure(
                AppError.Validation("File is not a valid Android APK package archive.")
            )

        val totalSize = canonicalApk.length()
        if (!hasEnoughStorage(canonicalReceiveRoot, totalSize)) {
            return AppResult.Failure(
                AppError.Storage("Insufficient free storage to stage and install ${archive.appLabel ?: archive.packageName}.")
            )
        }

        val existingVersion = queryInstalledVersionCode(archive.packageName)
        return AppResult.Success(
            ValidatedInstallBundle(
                packageName = archive.packageName,
                appName = archive.appLabel ?: archive.packageName,
                versionName = archive.versionName ?: "1.0",
                versionCode = archive.versionCode ?: 0L,
                apkFiles = listOf(canonicalApk),
                totalSizeBytes = totalSize,
                isSplitBundle = false,
                isUpdateToExistingApp = existingVersion != null,
                installedVersionCode = existingVersion
            )
        )
    }

    private fun validateSplitManifestBundle(
        manifestFile: File,
        rootPrefix: String,
        canonicalReceiveRoot: File
    ): AppResult<ValidatedInstallBundle> {
        val canonicalManifest = validateFileInsideRoot(manifestFile, rootPrefix)
            ?: return AppResult.Failure(
                AppError.Security("Split APK manifest is outside ShareFlow's verified receive directory.")
            )

        val manifest = runCatching {
            ShareFlowAppBundleManifest.fromJsonString(
                canonicalManifest.readText(Charsets.UTF_8)
            )
        }.getOrElse {
            return AppResult.Failure(
                AppError.Validation("Split APK bundle manifest is corrupted or unreadable.")
            )
        }

        if (manifest.packageName.isBlank() || manifest.parts.isEmpty()) {
            return AppResult.Failure(
                AppError.Validation("Split APK bundle manifest is missing package metadata or APK parts.")
            )
        }

        val bundleDir = canonicalManifest.parentFile
            ?: return AppResult.Failure(AppError.Storage("Bundle directory unavailable."))
        val resolvedFiles = mutableListOf<File>()
        var totalSize = 0L
        var baseSeen = false

        for (part in manifest.parts) {
            if (part.fileName.contains('/') || part.fileName.contains('\\') || part.fileName.contains("..")) {
                return AppResult.Failure(
                    AppError.Security("Blocked unsafe split APK filename in bundle manifest.")
                )
            }
            if (!part.fileName.endsWith(".apk", ignoreCase = true)) {
                return AppResult.Failure(
                    AppError.Security("Bundle manifest references a non-APK file: ${part.fileName}")
                )
            }
            val partFile = File(bundleDir, part.fileName)
            val canonicalPart = validateFileInsideRoot(partFile, rootPrefix)
                ?: return AppResult.Failure(
                    AppError.Validation("Required split APK part '${part.fileName}' is missing or incomplete.")
                )
            if (part.sizeBytes > 0L && canonicalPart.length() != part.sizeBytes) {
                return AppResult.Failure(
                    AppError.Validation("Split APK '${part.fileName}' size mismatch (expected ${part.sizeBytes} bytes).")
                )
            }
            val actualHash = computeSha256(canonicalPart)
                ?: return AppResult.Failure(AppError.Storage("Unable to hash split APK '${part.fileName}'."))
            if (!hashesEqual(part.sha256, actualHash)) {
                return AppResult.Failure(
                    AppError.IntegrityMismatch(
                        message = "SHA-256 verification failed for split APK '${part.fileName}'.",
                        expectedHash = part.sha256,
                        actualHash = actualHash
                    )
                )
            }

            val parsedPart = apkArchiveInspector.inspectApkArchive(canonicalPart)
            if (part.isBase) {
                if (parsedPart == null) {
                    return AppResult.Failure(
                        AppError.Validation("Base APK '${part.fileName}' is not a valid Android APK archive.")
                    )
                }
                if (parsedPart.packageName != manifest.packageName) {
                    return AppResult.Failure(
                        AppError.Security(
                            "Split APK package mismatch: '${part.fileName}' belongs to '${parsedPart.packageName}' instead of '${manifest.packageName}'."
                        )
                    )
                }
            } else {
                // Android's PackageManager.getPackageArchiveInfo often returns null for config-only splits
                // (e.g. split_config.xxhdpi.apk / split_config.ar.apk / split_config.arm64_v8a.apk).
                // When metadata is available, enforce strict packageName equality; otherwise verify
                // the split is a valid APK ZIP archive containing AndroidManifest.xml (in addition to SHA-256).
                if (parsedPart != null) {
                    if (parsedPart.packageName != manifest.packageName) {
                        return AppResult.Failure(
                            AppError.Security(
                                "Split APK package mismatch: '${part.fileName}' belongs to '${parsedPart.packageName}' instead of '${manifest.packageName}'."
                            )
                        )
                    }
                } else if (!isValidApkZipArchive(canonicalPart)) {
                    return AppResult.Failure(
                        AppError.Validation("Split APK '${part.fileName}' is not a valid Android APK archive.")
                    )
                }
            }

            if (part.isBase) {
                baseSeen = true
                resolvedFiles.add(0, canonicalPart)
            } else {
                resolvedFiles.add(canonicalPart)
            }
            totalSize += canonicalPart.length()
        }

        if (!baseSeen) {
            return AppResult.Failure(
                AppError.Validation("Split APK bundle is missing base.apk and cannot be installed.")
            )
        }

        if (!hasEnoughStorage(canonicalReceiveRoot, totalSize)) {
            return AppResult.Failure(
                AppError.Storage("Insufficient free storage to install split bundle ${manifest.appName}.")
            )
        }

        val existingVersion = queryInstalledVersionCode(manifest.packageName)
        return AppResult.Success(
            ValidatedInstallBundle(
                packageName = manifest.packageName,
                appName = manifest.appName,
                versionName = manifest.versionName,
                versionCode = manifest.versionCode,
                apkFiles = resolvedFiles,
                totalSizeBytes = totalSize,
                isSplitBundle = resolvedFiles.size > 1,
                isUpdateToExistingApp = existingVersion != null,
                installedVersionCode = existingVersion
            )
        )
    }

    private fun validateFileInsideRoot(file: File, rootPrefix: String): File? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return if (canonical.path.startsWith(rootPrefix)) canonical else null
    }

    private fun isValidApkZipArchive(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("AndroidManifest.xml") != null
        }
    }.getOrDefault(false)

    private fun hasEnoughStorage(rootDir: File, totalApkBytes: Long): Boolean {
        val required = totalApkBytes * 2L + STORAGE_HEADROOM_BYTES
        val available = availableStorageBytesProvider(rootDir)
        return available >= required
    }

    private fun queryInstalledVersionCode(packageName: String): Long? = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        PackageInfoCompat.getLongVersionCode(info)
    }.getOrNull()

    private fun computeSha256(file: File): String? = runCatching {
        FileInputStream(file).use { stream ->
            when (val res = checksumVerifier.computeSha256(stream)) {
                is AppResult.Success -> res.data
                is AppResult.Failure -> null
            }
        }
    }.getOrNull()

    private fun hashesEqual(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(
            expectedHex.trim().lowercase().toByteArray(Charsets.UTF_8),
            actualHex.trim().lowercase().toByteArray(Charsets.UTF_8)
        )

    companion object {
        private const val STREAM_BUFFER_BYTES = 64 * 1024
        private const val STORAGE_HEADROOM_BYTES = 16L * 1024L * 1024L
    }
}
