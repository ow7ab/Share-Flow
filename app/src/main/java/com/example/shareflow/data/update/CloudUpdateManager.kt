package com.example.shareflow.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.ChecksumVerifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CloudUpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val minSupportedAndroidSdk: Int = 24,
    val mandatory: Boolean = false,
    val releaseNotesAr: String = "",
    val releaseNotesEn: String = "",
    val portalUrl: String? = null,
    val publishedAt: String = "",
    val signingCertSha256: String? = null
)

sealed class CloudUpdateState {
    data object Idle : CloudUpdateState()
    data object PortalNotConfigured : CloudUpdateState()
    data object Checking : CloudUpdateState()
    data class UpToDate(
        val currentVersionName: String,
        val currentVersionCode: Int,
        val portalUrl: String? = null
    ) : CloudUpdateState()

    data class UpdateAvailable(
        val manifest: CloudUpdateManifest
    ) : CloudUpdateState()

    data class Downloading(
        val manifest: CloudUpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : CloudUpdateState() {
        val progress: Float
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
    }

    data class Verifying(
        val manifest: CloudUpdateManifest
    ) : CloudUpdateState()

    data class ReadyToInstall(
        val manifest: CloudUpdateManifest,
        val verifiedApkPath: String
    ) : CloudUpdateState()

    data class Error(
        val reason: CloudUpdateErrorReason,
        val detail: String,
        val manifest: CloudUpdateManifest? = null
    ) : CloudUpdateState()
}

enum class CloudUpdateErrorReason {
    INVALID_URL,
    NETWORK_ERROR,
    INVALID_MANIFEST,
    INCOMPATIBLE_DEVICE,
    CHECKSUM_MISMATCH,
    PACKAGE_IDENTITY_MISMATCH,
    SIGNATURE_MISMATCH,
    DOWNGRADE_BLOCKED,
    DOWNLOAD_INTERRUPTED,
    INSTALL_PERMISSION_REQUIRED,
    INSTALL_LAUNCH_FAILED
}

enum class UpdateInstallLaunchResult {
    LAUNCHED,
    PERMISSION_REQUIRED,
    FAILED
}

interface CloudUpdateHttpClient {
    suspend fun fetchManifestText(manifestUrl: String): AppResult<String>
    suspend fun downloadApkToFile(
        apkUrl: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppResult<File>
}

interface ApkPackageSecurityInspector {
    fun inspectUpdateApk(
        apkFile: File,
        expectedPackageName: String,
        expectedMinVersionCode: Int,
        currentInstalledVersionCode: Int
    ): AppResult<Unit>

    /**
     * Checks before downloading the APK whether an advertised release signing certificate SHA-256
     * matches the currently installed ShareFlow package (prevents wasting bandwidth on incompatible
     * debug-vs-release updates that Android would refuse to install in-place).
     */
    fun isInstalledSignatureCompatible(
        expectedPackageName: String,
        expectedCertSha256: String
    ): Boolean = true
}

object CloudUpdateValidator {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 250L * 1024L * 1024L
    const val MAX_URL_LENGTH = 512
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Strict public HTTPS URL validator:
     * - Allows only https:// scheme (zero cleartext HTTP, file://, javascript:, content://)
     * - Disallows credentials in URL (userInfo)
     * - Disallows loopback, localhost, link-local, and RFC1918 private network addresses
     */
    fun isValidPublicHttpsUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return false
        if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (!uri.userInfo.isNullOrEmpty()) return false
        val host = uri.host?.lowercase()?.trim().orEmpty()
        if (host.isBlank() || !host.contains('.')) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (isPrivateOrLoopbackHost(host)) return false
        return true
    }

    fun parseManifestJson(rawJson: String): AppResult<CloudUpdateManifest> {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty() || trimmed.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            return AppResult.Failure(AppError.Validation("Update manifest is empty or exceeds maximum allowed size."))
        }
        return runCatching {
            val json = JSONObject(trimmed)
            val versionCode = json.optInt("versionCode", -1)
            if (versionCode <= 0) {
                return AppResult.Failure(AppError.Validation("Manifest missing valid positive versionCode."))
            }
            val versionName = json.optString("versionName", "").trim()
            if (versionName.isBlank() || versionName.length > 32 || versionName.any { it.isISOControl() }) {
                return AppResult.Failure(AppError.Validation("Manifest missing valid versionName."))
            }
            val apkDownloadUrl = json.optString("apkDownloadUrl", "").trim()
                .ifEmpty { json.optString("apkUrl", "").trim() }
            if (!isValidPublicHttpsUrl(apkDownloadUrl)) {
                return AppResult.Failure(AppError.Security("Manifest apkDownloadUrl/apkUrl must be a valid public HTTPS URL."))
            }
            val sha256 = json.optString("sha256", "").trim().lowercase()
            if (!SHA256_REGEX.matches(sha256)) {
                return AppResult.Failure(AppError.Security("Manifest must include a valid 64-character SHA-256 checksum."))
            }
            val fileSizeBytes = json.optLong("fileSizeBytes", 0L)
            if (fileSizeBytes < 0L || fileSizeBytes > MAX_APK_BYTES) {
                return AppResult.Failure(AppError.Validation("Manifest fileSizeBytes is out of safe bounds."))
            }
            val minSdk = json.optInt("minSupportedAndroidSdk", 24).coerceAtLeast(21)
            val mandatory = json.optBoolean("mandatory", false)
            val genericReleaseNotes = parseReleaseNotesField(json)
            val releaseNotesAr = json.optString("releaseNotesAr", "").trim().ifEmpty { genericReleaseNotes }.take(2000)
            val releaseNotesEn = json.optString("releaseNotesEn", "").trim().ifEmpty { genericReleaseNotes }.take(2000)
            val rawPortalUrl = json.optString("portalUrl", "").trim()
            val portalUrl = rawPortalUrl.takeIf { it.isNotEmpty() && isValidPublicHttpsUrl(it) }
            val publishedAt = json.optString("publishedAt", "").trim().take(64)
            val rawSigningCert = json.optString("signingCertSha256", "").trim().replace(":", "").lowercase()
            val signingCertSha256 = rawSigningCert.takeIf { it.isNotEmpty() && SHA256_REGEX.matches(it) }

            AppResult.Success(
                CloudUpdateManifest(
                    versionCode = versionCode,
                    versionName = versionName,
                    apkDownloadUrl = apkDownloadUrl,
                    sha256 = sha256,
                    fileSizeBytes = fileSizeBytes,
                    minSupportedAndroidSdk = minSdk,
                    mandatory = mandatory,
                    releaseNotesAr = releaseNotesAr,
                    releaseNotesEn = releaseNotesEn,
                    portalUrl = portalUrl,
                    publishedAt = publishedAt,
                    signingCertSha256 = signingCertSha256
                )
            )
        }.getOrElse { err ->
            AppResult.Failure(AppError.Validation("Invalid update manifest JSON: ${err.message ?: "parse error"}"))
        }
    }

    private fun parseReleaseNotesField(json: JSONObject): String {
        val notesArray = json.optJSONArray("releaseNotes")
        if (notesArray != null) {
            val lines = buildList {
                for (i in 0 until notesArray.length()) {
                    val item = notesArray.optString(i, "").trim()
                    if (item.isNotEmpty()) add("• $item")
                }
            }
            if (lines.isNotEmpty()) return lines.joinToString("\n").take(2000)
        }
        return json.optString("releaseNotes", "").trim().take(2000)
    }

    fun constantTimeHashEquals(expectedHex: String, actualHex: String): Boolean {
        val a = expectedHex.trim().lowercase().toByteArray(Charsets.UTF_8)
        val b = actualHex.trim().lowercase().toByteArray(Charsets.UTF_8)
        return a.isNotEmpty() && MessageDigest.isEqual(a, b)
    }

    private fun isPrivateOrLoopbackHost(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        if (clean == "::1" || clean == "::" ||
            clean.startsWith("::ffff:") ||
            clean.startsWith("fe80:") ||
            clean.startsWith("fc") ||
            clean.startsWith("fd")
        ) {
            return true
        }
        val ipv4Parts = clean.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            return a == 10 ||
                a == 127 ||
                a == 0 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        return false
    }
}

class HttpsCloudUpdateHttpClient(
    private val dispatchers: DispatcherProvider
) : CloudUpdateHttpClient {

    override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> = withContext(dispatchers.io) {
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(manifestUrl)) {
            return@withContext AppResult.Failure(AppError.Security("Only public HTTPS URLs are permitted."))
        }
        runCatching {
            val conn = openValidatedHttpsConnection(manifestUrl)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext AppResult.Failure(AppError.Network("Update server returned HTTP $code"))
                }
                val out = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(4096)
                    var total = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > CloudUpdateValidator.MAX_MANIFEST_BYTES) {
                            return@withContext AppResult.Failure(
                                AppError.Validation("Update manifest exceeds ${CloudUpdateValidator.MAX_MANIFEST_BYTES} byte limit.")
                            )
                        }
                        out.write(buf, 0, read)
                    }
                }
                AppResult.Success(out.toString(Charsets.UTF_8.name()))
            } finally {
                conn.disconnect()
            }
        }.getOrElse { err ->
            if (err is CancellationException) throw err
            AppResult.Failure(AppError.Network(err.message ?: "Unable to reach update server.", err))
        }
    }

    override suspend fun downloadApkToFile(
        apkUrl: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppResult<File> = withContext(dispatchers.io) {
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(apkUrl)) {
            return@withContext AppResult.Failure(AppError.Security("APK download URL must use public HTTPS."))
        }
        val parent = destinationFile.parentFile
            ?: return@withContext AppResult.Failure(AppError.Storage("Invalid update cache destination."))
        parent.mkdirs()

        val tempPartFile = File(parent, "${destinationFile.name}.part")
        if (tempPartFile.exists()) tempPartFile.delete()

        try {
            val conn = openValidatedHttpsConnection(apkUrl, readTimeoutMs = 30_000)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext AppResult.Failure(AppError.Network("APK download failed with HTTP $code"))
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > CloudUpdateValidator.MAX_APK_BYTES) {
                    return@withContext AppResult.Failure(AppError.Validation("APK size exceeds safe 250 MB limit."))
                }
                var downloaded = 0L
                var lastReportAt = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tempPartFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloaded += read
                            if (downloaded > CloudUpdateValidator.MAX_APK_BYTES) {
                                return@withContext AppResult.Failure(
                                    AppError.Validation("Downloaded APK exceeded safe 250 MB limit.")
                                )
                            }
                            output.write(buffer, 0, read)
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= 120L) {
                                lastReportAt = now
                                onProgress(downloaded, contentLength)
                            }
                        }
                        output.fd.sync()
                    }
                }
                onProgress(downloaded, if (contentLength > 0L) contentLength else downloaded)
                if (destinationFile.exists()) destinationFile.delete()
                if (!tempPartFile.renameTo(destinationFile)) {
                    tempPartFile.copyTo(destinationFile, overwrite = true)
                    tempPartFile.delete()
                }
                AppResult.Success(destinationFile)
            } finally {
                conn.disconnect()
            }
        } catch (ce: CancellationException) {
            tempPartFile.delete()
            throw ce
        } catch (t: Throwable) {
            tempPartFile.delete()
            AppResult.Failure(AppError.Network(t.message ?: "Download interrupted.", t))
        }
    }

    private fun openValidatedHttpsConnection(
        initialUrl: String,
        connectTimeoutMs: Int = 12_000,
        readTimeoutMs: Int = 15_000
    ): HttpsURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(currentUrl)) {
                throw SecurityException("Redirect blocked: non-HTTPS or disallowed host")
            }
            val conn = (URL(currentUrl).openConnection() as? HttpsURLConnection)
                ?: throw SecurityException("Connection did not resolve to HTTPS")
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, application/vnd.android.package-archive, */*")
            conn.setRequestProperty("User-Agent", "ShareFlow-Android/${BuildConfig.VERSION_NAME}")
            conn.connect()

            val code = conn.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")?.trim().orEmpty()
                conn.disconnect()
                if (location.isBlank()) {
                    throw IllegalStateException("Empty redirect Location header")
                }
                val resolved = runCatching { URI(currentUrl).resolve(location).toString() }.getOrNull().orEmpty()
                if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolved)) {
                    throw SecurityException("Blocked unsafe redirect target")
                }
                currentUrl = resolved
                redirects++
                continue
            }
            return conn
        }
        throw IllegalStateException("Too many redirects while contacting update server")
    }

    companion object {
        private const val MAX_REDIRECTS = 3
    }
}

class AndroidApkPackageSecurityInspector(
    private val context: Context
) : ApkPackageSecurityInspector {

    @Suppress("DEPRECATION")
    override fun inspectUpdateApk(
        apkFile: File,
        expectedPackageName: String,
        expectedMinVersionCode: Int,
        currentInstalledVersionCode: Int
    ): AppResult<Unit> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        } else {
            PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
        }

        val archiveInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: return AppResult.Failure(
            AppError.Security("Downloaded file is not a valid Android APK package.")
        )

        // 1. Package identity check (prevents unauthorized replacement with a different app)
        if (archiveInfo.packageName != expectedPackageName) {
            return AppResult.Failure(
                AppError.Security("APK package ID (${archiveInfo.packageName}) does not match ShareFlow ($expectedPackageName).")
            )
        }

        // 2. Anti-downgrade check
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }
        if (archiveVersionCode <= currentInstalledVersionCode.toLong() ||
            archiveVersionCode < expectedMinVersionCode.toLong()
        ) {
            return AppResult.Failure(
                AppError.Security("APK version ($archiveVersionCode) is not newer than installed version ($currentInstalledVersionCode).")
            )
        }

        // 3. Signing certificate compatibility check (preserves user data and prevents unauthorized replacement)
        val installedInfo: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(expectedPackageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(expectedPackageName, flags)
            }
        }.getOrNull()

        val installedCertDigests = extractSigningCertSha256Digests(installedInfo)
        val archiveCertDigests = extractSigningCertSha256Digests(archiveInfo)

        if (installedCertDigests.isEmpty() ||
            archiveCertDigests.isEmpty() ||
            installedCertDigests.intersect(archiveCertDigests).isEmpty()
        ) {
            return AppResult.Failure(
                AppError.Security("Update APK signing certificate does not match the installed ShareFlow signature.")
            )
        }

        return AppResult.Success(Unit)
    }

    @Suppress("DEPRECATION")
    override fun isInstalledSignatureCompatible(
        expectedPackageName: String,
        expectedCertSha256: String
    ): Boolean {
        val normalizedExpected = expectedCertSha256.trim().replace(":", "").lowercase()
        if (normalizedExpected.length != 64) return true
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val installedInfo: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(expectedPackageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(expectedPackageName, flags)
            }
        }.getOrNull()
        val installedDigests = extractSigningCertSha256Digests(installedInfo)
        // If runtime cannot read installed signatures (e.g., stripped test env), defer to full APK inspection.
        if (installedDigests.isEmpty()) return true
        return installedDigests.any { CloudUpdateValidator.constantTimeHashEquals(normalizedExpected, it) }
    }

    @Suppress("DEPRECATION")
    private fun extractSigningCertSha256Digests(pkgInfo: PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        val rawSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptySet()
            val candidates = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory?.takeIf { it.isNotEmpty() }
                    ?: signingInfo.apkContentsSigners
            }
            candidates?.toList().orEmpty()
        } else {
            pkgInfo.signatures?.toList().orEmpty()
        }

        val md = MessageDigest.getInstance("SHA-256")
        return rawSignatures.mapNotNull { sig ->
            runCatching {
                md.reset()
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }
}

/**
 * Coordinates secure HTTPS cloud version checks, streaming APK download, cryptographic SHA-256 +
 * signing certificate verification, and Android system installer handoff (always with user consent).
 */
class CloudUpdateManager(
    private val context: Context,
    private val httpClient: CloudUpdateHttpClient,
    private val checksumVerifier: ChecksumVerifier,
    private val packageInspector: ApkPackageSecurityInspector,
    private val permissionManager: PermissionManager,
    private val dispatchers: DispatcherProvider,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val currentSdkInt: Int = Build.VERSION.SDK_INT,
    private val expectedPackageName: String = context.packageName,
    private val defaultManifestUrl: String = BuildConfig.CLOUD_UPDATE_MANIFEST_URL,
    private val fallbackManifestUrl: String = DEFAULT_FALLBACK_MANIFEST_URL
) {
    private val appContext = context.applicationContext ?: context
    private val _state = MutableStateFlow<CloudUpdateState>(CloudUpdateState.Idle)
    val state: StateFlow<CloudUpdateState> = _state.asStateFlow()

    fun resolveEffectiveManifestUrl(userConfiguredUrl: String): String {
        val custom = userConfiguredUrl.trim()
        if (custom.isNotEmpty()) return custom
        return defaultManifestUrl.trim()
    }

    suspend fun checkForUpdates(manifestUrl: String): CloudUpdateState {
        val isUsingDefaultEndpoint = manifestUrl.trim().isEmpty()
        val effectiveUrl = resolveEffectiveManifestUrl(manifestUrl)
        if (effectiveUrl.isBlank()) {
            val notConfigured = CloudUpdateState.PortalNotConfigured
            _state.value = notConfigured
            return notConfigured
        }
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(effectiveUrl)) {
            val err = CloudUpdateState.Error(
                reason = CloudUpdateErrorReason.INVALID_URL,
                detail = "Cloud update portal URL must be a valid public HTTPS address."
            )
            _state.value = err
            return err
        }

        _state.value = CloudUpdateState.Checking
        val primaryFetch = httpClient.fetchManifestText(effectiveUrl)
        val fetchRes = if (
            primaryFetch is AppResult.Failure &&
            isUsingDefaultEndpoint &&
            fallbackManifestUrl.isNotBlank() &&
            !fallbackManifestUrl.equals(effectiveUrl, ignoreCase = true) &&
            CloudUpdateValidator.isValidPublicHttpsUrl(fallbackManifestUrl)
        ) {
            httpClient.fetchManifestText(fallbackManifestUrl)
        } else {
            primaryFetch
        }
        val rawJson = when (fetchRes) {
            is AppResult.Success -> fetchRes.data
            is AppResult.Failure -> {
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.NETWORK_ERROR,
                    detail = fetchRes.error.message
                )
                _state.value = err
                return err
            }
        }

        val manifest = when (val parsed = CloudUpdateValidator.parseManifestJson(rawJson)) {
            is AppResult.Success -> parsed.data
            is AppResult.Failure -> {
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.INVALID_MANIFEST,
                    detail = parsed.error.message
                )
                _state.value = err
                return err
            }
        }

        if (manifest.minSupportedAndroidSdk > currentSdkInt) {
            val err = CloudUpdateState.Error(
                reason = CloudUpdateErrorReason.INCOMPATIBLE_DEVICE,
                detail = "This update requires Android API ${manifest.minSupportedAndroidSdk} or higher.",
                manifest = manifest
            )
            _state.value = err
            return err
        }

        val nextState = if (manifest.versionCode > currentVersionCode) {
            CloudUpdateState.UpdateAvailable(manifest = manifest)
        } else {
            CloudUpdateState.UpToDate(
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                portalUrl = manifest.portalUrl
            )
        }
        _state.value = nextState
        return nextState
    }

    suspend fun downloadAndVerifyUpdate(manifest: CloudUpdateManifest): CloudUpdateState =
        withContext(dispatchers.io) {
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(manifest.apkDownloadUrl)) {
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.INVALID_URL,
                    detail = "APK download URL must use public HTTPS.",
                    manifest = manifest
                )
                _state.value = err
                return@withContext err
            }

            if (!manifest.signingCertSha256.isNullOrBlank() &&
                !packageInspector.isInstalledSignatureCompatible(
                    expectedPackageName = expectedPackageName,
                    expectedCertSha256 = manifest.signingCertSha256
                )
            ) {
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.SIGNATURE_MISMATCH,
                    detail = "Release signing certificate does not match the currently installed ShareFlow build.",
                    manifest = manifest
                )
                _state.value = err
                return@withContext err
            }

            val updatesDir = File(appContext.cacheDir, UPDATES_CACHE_DIR).apply { mkdirs() }
            val targetFile = File(updatesDir, "shareflow-v${manifest.versionCode}.apk")
            // Path traversal confinement guard
            val canonicalBase = updatesDir.canonicalPath + File.separator
            if (!targetFile.canonicalPath.startsWith(canonicalBase)) {
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED,
                    detail = "Unsafe update file path rejected.",
                    manifest = manifest
                )
                _state.value = err
                return@withContext err
            }

            // Prune stale older update APKs while keeping any matching cached version for verification
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != targetFile.name) {
                    runCatching { old.delete() }
                }
            }

            // Avoid re-downloading the exact same version if a verified local copy already exists
            if (targetFile.exists() && targetFile.isFile && targetFile.length() > 0L) {
                _state.value = CloudUpdateState.Verifying(manifest)
                val cachedHashResult = runCatching {
                    targetFile.inputStream().use { stream ->
                        checksumVerifier.verifySha256(stream, manifest.sha256)
                    }
                }.getOrNull()
                val cachedHashOk = cachedHashResult is AppResult.Success && cachedHashResult.data
                if (cachedHashOk) {
                    val cachedInspect = packageInspector.inspectUpdateApk(
                        apkFile = targetFile,
                        expectedPackageName = expectedPackageName,
                        expectedMinVersionCode = manifest.versionCode,
                        currentInstalledVersionCode = currentVersionCode
                    )
                    if (cachedInspect is AppResult.Success) {
                        val ready = CloudUpdateState.ReadyToInstall(
                            manifest = manifest,
                            verifiedApkPath = targetFile.absolutePath
                        )
                        _state.value = ready
                        return@withContext ready
                    }
                }
                // Cached file was stale, corrupted, or failed inspection — discard and re-download cleanly
                runCatching { targetFile.delete() }
            }

            _state.value = CloudUpdateState.Downloading(
                manifest = manifest,
                downloadedBytes = 0L,
                totalBytes = manifest.fileSizeBytes
            )

            val downloadRes = httpClient.downloadApkToFile(
                apkUrl = manifest.apkDownloadUrl,
                destinationFile = targetFile,
                onProgress = { downloaded, total ->
                    val effectiveTotal = if (total > 0L) total else manifest.fileSizeBytes
                    _state.value = CloudUpdateState.Downloading(
                        manifest = manifest,
                        downloadedBytes = downloaded,
                        totalBytes = effectiveTotal
                    )
                }
            )

            val apkFile = when (downloadRes) {
                is AppResult.Success -> downloadRes.data
                is AppResult.Failure -> {
                    targetFile.delete()
                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED,
                        detail = downloadRes.error.message,
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
            }

            _state.value = CloudUpdateState.Verifying(manifest)

            // 1. Cryptographic SHA-256 integrity verification (constant-time comparison)
            val hashVerification = runCatching {
                apkFile.inputStream().use { stream ->
                    checksumVerifier.verifySha256(stream, manifest.sha256)
                }
            }.getOrNull()
            if (hashVerification !is AppResult.Success || !hashVerification.data) {
                apkFile.delete()
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.CHECKSUM_MISMATCH,
                    detail = "SHA-256 verification failed. The downloaded update was discarded for safety.",
                    manifest = manifest
                )
                _state.value = err
                return@withContext err
            }

            // 2. Package identity, anti-downgrade, and signing certificate compatibility verification
            when (
                val inspectRes = packageInspector.inspectUpdateApk(
                    apkFile = apkFile,
                    expectedPackageName = expectedPackageName,
                    expectedMinVersionCode = manifest.versionCode,
                    currentInstalledVersionCode = currentVersionCode
                )
            ) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> {
                    apkFile.delete()
                    val msg = inspectRes.error.message
                    val reason = when {
                        msg.contains("signature", ignoreCase = true) ||
                            msg.contains("certificate", ignoreCase = true) -> CloudUpdateErrorReason.SIGNATURE_MISMATCH
                        msg.contains("package ID", ignoreCase = true) -> CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH
                        msg.contains("version", ignoreCase = true) -> CloudUpdateErrorReason.DOWNGRADE_BLOCKED
                        else -> CloudUpdateErrorReason.INVALID_MANIFEST
                    }
                    val err = CloudUpdateState.Error(
                        reason = reason,
                        detail = msg,
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
            }

            val ready = CloudUpdateState.ReadyToInstall(
                manifest = manifest,
                verifiedApkPath = apkFile.absolutePath
            )
            _state.value = ready
            ready
        }

    fun launchVerifiedUpdateInstaller(apkPath: String): UpdateInstallLaunchResult {
        val apkFile = File(apkPath)
        val updatesDir = File(appContext.cacheDir, UPDATES_CACHE_DIR)
        val canonicalBase = runCatching { updatesDir.canonicalPath + File.separator }.getOrNull() ?: return UpdateInstallLaunchResult.FAILED
        val canonicalTarget = runCatching { apkFile.canonicalPath }.getOrNull() ?: return UpdateInstallLaunchResult.FAILED
        if (!apkFile.exists() || !canonicalTarget.startsWith(canonicalBase)) {
            return UpdateInstallLaunchResult.FAILED
        }
        if (!permissionManager.canRequestPackageInstalls()) {
            return UpdateInstallLaunchResult.PERMISSION_REQUIRED
        }
        return runCatching {
            val contentUri: Uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            UpdateInstallLaunchResult.LAUNCHED
        }.getOrElse { UpdateInstallLaunchResult.FAILED }
    }

    fun resetState() {
        _state.value = CloudUpdateState.Idle
    }

    companion object {
        const val UPDATES_CACHE_DIR = "shareflow_updates"
        const val DEFAULT_FALLBACK_MANIFEST_URL =
            "https://github.com/ow7ab/Share-Flow-Releases/releases/latest/download/version.json"
    }
}
