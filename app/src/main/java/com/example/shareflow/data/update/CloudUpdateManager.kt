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
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.common.SystemClock
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.ChecksumVerifier
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    data class Paused(
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
    NO_INTERNET,
    TIMEOUT,
    SSL_ERROR,
    RATE_LIMITED,
    SERVER_UNAVAILABLE,
    RELEASE_NOT_FOUND,
    INVALID_MANIFEST,
    INCOMPATIBLE_DEVICE,
    CHECKSUM_MISMATCH,
    PACKAGE_IDENTITY_MISMATCH,
    SIGNATURE_MISMATCH,
    DOWNGRADE_BLOCKED,
    DUPLICATE_VERSION_CONFLICT,
    VERSION_METADATA_MISMATCH,
    INVALID_APK,
    UNREADABLE_VERSION_METADATA,
    DOWNLOAD_INTERRUPTED,
    INSTALL_PERMISSION_REQUIRED,
    INSTALL_LAUNCH_FAILED
}

data class InstalledAppVersionInfo(
    val versionCode: Int,
    val versionName: String
)

/**
 * Resolves the real installed ShareFlow `versionCode` (`longVersionCode` on API 28+) and
 * `versionName` directly from Android's [PackageManager], falling back safely to [BuildConfig]
 * only when package metadata is unavailable (e.g., stripped test harness).
 */
object InstalledAppVersionResolver {
    @Suppress("DEPRECATION")
    fun resolve(context: Context): InstalledAppVersionInfo {
        val pm = context.packageManager
        val pkgName = context.packageName
        val pkgInfo: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkgName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                pm.getPackageInfo(pkgName, 0)
            }
        }.getOrNull()

        val resolvedCode = pkgInfo?.let { info ->
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
            if (raw in 1L..Int.MAX_VALUE.toLong()) raw.toInt() else null
        } ?: BuildConfig.VERSION_CODE

        val resolvedName = pkgInfo?.versionName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.VERSION_NAME

        return InstalledAppVersionInfo(
            versionCode = resolvedCode,
            versionName = resolvedName
        )
    }
}

data class InspectedApkMetadata(
    val packageName: String,
    val versionCode: Int,
    val versionName: String,
    val signingCertSha256Digests: Set<String>
)

sealed class ApkInspectionResult {
    data class Valid(val metadata: InspectedApkMetadata) : ApkInspectionResult()
    data class Rejected(
        val reason: CloudUpdateErrorReason,
        val detail: String,
        val inspectedVersionCode: Int? = null,
        val inspectedVersionName: String? = null
    ) : ApkInspectionResult()
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
     * Full pre-installation inspection of the actual downloaded APK file:
     * validates archive integrity, reads real APK `versionCode`/`versionName`, checks package ID,
     * verifies consistency against GitHub Release metadata, enforces `versionCode` precedence
     * against the installed app, and validates signing certificate continuity.
     */
    fun inspectUpdateApkDetailed(
        apkFile: File,
        expectedPackageName: String,
        expectedVersionCode: Int,
        expectedVersionName: String,
        currentInstalledVersionCode: Int,
        currentInstalledVersionName: String,
        expectedSigningCertSha256: String? = null
    ): ApkInspectionResult = when (
        val legacy = inspectUpdateApk(
            apkFile = apkFile,
            expectedPackageName = expectedPackageName,
            expectedMinVersionCode = expectedVersionCode,
            currentInstalledVersionCode = currentInstalledVersionCode
        )
    ) {
        is AppResult.Success -> ApkInspectionResult.Valid(
            InspectedApkMetadata(
                packageName = expectedPackageName,
                versionCode = expectedVersionCode,
                versionName = expectedVersionName,
                signingCertSha256Digests = emptySet()
            )
        )
        is AppResult.Failure -> {
            val msg = legacy.error.message
            val reason = when {
                msg.contains("signature", ignoreCase = true) ||
                    msg.contains("certificate", ignoreCase = true) -> CloudUpdateErrorReason.SIGNATURE_MISMATCH
                msg.contains("package ID", ignoreCase = true) -> CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH
                msg.contains("corrupt", ignoreCase = true) ||
                    msg.contains("not a valid Android APK", ignoreCase = true) -> CloudUpdateErrorReason.INVALID_APK
                msg.contains("metadata", ignoreCase = true) ||
                    msg.contains("mismatch", ignoreCase = true) -> CloudUpdateErrorReason.VERSION_METADATA_MISMATCH
                msg.contains("duplicate", ignoreCase = true) -> CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT
                msg.contains("unreadable", ignoreCase = true) -> CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA
                msg.contains("downgrade", ignoreCase = true) ||
                    msg.contains("older", ignoreCase = true) -> CloudUpdateErrorReason.DOWNGRADE_BLOCKED
                else -> CloudUpdateErrorReason.INVALID_MANIFEST
            }
            ApkInspectionResult.Rejected(reason = reason, detail = msg)
        }
    }

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
    // GitHub Releases 302 CDN redirects (release-assets.githubusercontent.com / objects.githubusercontent.com)
    // carry signed Azure SAS + JWT query tokens that routinely exceed 900-1800 characters.
    const val MAX_URL_LENGTH = 4096
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Strict public HTTPS URL validator:
     * - Allows only https:// scheme (zero cleartext HTTP, file://, javascript:, content://)
     * - Disallows credentials in URL (username/password)
     * - Disallows loopback, localhost, link-local, and RFC1918 private network addresses
     * - Uses OkHttp's canonical [okhttp3.HttpUrl] parser to support RFC-valid presigned CDN
     *   query parameters while neutralizing octal/hex/IPv6-mapped IPv4 SSRF bypasses
     */
    fun isValidPublicHttpsUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return false
        if (trimmed.any { it.isISOControl() || it.isWhitespace() }) return false
        val httpUrl = trimmed.toHttpUrlOrNull() ?: return false
        if (!httpUrl.scheme.equals("https", ignoreCase = true)) return false
        if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return false
        val host = httpUrl.host.lowercase().trim()
        if (host.isBlank() || !host.contains('.')) return false
        if (host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.endsWith(".home.arpa")
        ) {
            return false
        }
        if (isPrivateOrLoopbackHost(host)) return false
        return true
    }

    /**
     * Strict allowlist for push-triggered (FCM) ShareFlow APK download URLs:
     * must pass [isValidPublicHttpsUrl], point to an `.apk` resource, and belong to
     * ShareFlow's official GitHub Releases / GitHub Pages distribution origins.
     */
    fun isOfficialShareFlowReleaseApkUrl(raw: String): Boolean {
        if (!isValidPublicHttpsUrl(raw)) return false
        val httpUrl = raw.trim().toHttpUrlOrNull() ?: return false
        val host = httpUrl.host.lowercase()
        val path = httpUrl.encodedPath.lowercase()
        if (!path.endsWith(".apk")) return false
        return when (host) {
            "github.com" -> path.startsWith("/ow7ab/share-flow/releases/")
            "ow7ab.github.io" -> path.startsWith("/share-flow/")
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com" -> true
            else -> false
        }
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
        val clean = host.removePrefix("[").removeSuffix("]").lowercase()
        if (clean.contains(':')) {
            if (clean == "::1" || clean == "::" ||
                clean.startsWith("::ffff:") ||
                clean.startsWith("fc") ||
                clean.startsWith("fd") ||
                clean.startsWith("ff") ||
                clean.startsWith("fe8") ||
                clean.startsWith("fe9") ||
                clean.startsWith("fea") ||
                clean.startsWith("feb")
            ) {
                return true
            }
        }
        val ipv4Parts = clean.split('.')
        if (ipv4Parts.size == 4 && ipv4Parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = ipv4Parts[0].toInt()
            val b = ipv4Parts[1].toInt()
            val c = ipv4Parts[2].toInt()
            return a == 10 ||
                a == 127 ||
                a == 0 ||
                a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 168) ||
                (a == 198 && b in 18..19)
        }
        return false
    }
}

/**
 * Backwards-compatible wrapper delegating to [OkHttpCloudUpdateHttpClient] so all update traffic
 * benefits from OkHttp connection pooling, 6-hop HTTPS redirect validation, retry/backoff, and
 * HTTP Range resumable downloads.
 */
class HttpsCloudUpdateHttpClient(
    dispatchers: DispatcherProvider,
    context: Context? = null
) : CloudUpdateHttpClient by OkHttpCloudUpdateHttpClient(
    dispatchers = dispatchers,
    context = context
)

class AndroidApkPackageSecurityInspector(
    private val context: Context,
    private val archiveInfoProvider: ((apkFile: File, flags: Int) -> PackageInfo?)? = null,
    private val installedInfoProvider: ((packageName: String, flags: Int) -> PackageInfo?)? = null
) : ApkPackageSecurityInspector {

    override fun inspectUpdateApk(
        apkFile: File,
        expectedPackageName: String,
        expectedMinVersionCode: Int,
        currentInstalledVersionCode: Int
    ): AppResult<Unit> = when (
        val detailed = inspectUpdateApkDetailed(
            apkFile = apkFile,
            expectedPackageName = expectedPackageName,
            expectedVersionCode = expectedMinVersionCode,
            expectedVersionName = "",
            currentInstalledVersionCode = currentInstalledVersionCode,
            currentInstalledVersionName = ""
        )
    ) {
        is ApkInspectionResult.Valid -> AppResult.Success(Unit)
        is ApkInspectionResult.Rejected -> AppResult.Failure(AppError.Security(detailed.detail))
    }

    @Suppress("DEPRECATION")
    override fun inspectUpdateApkDetailed(
        apkFile: File,
        expectedPackageName: String,
        expectedVersionCode: Int,
        expectedVersionName: String,
        currentInstalledVersionCode: Int,
        currentInstalledVersionName: String,
        expectedSigningCertSha256: String?
    ): ApkInspectionResult {
        if (!apkFile.exists() || !apkFile.isFile || apkFile.length() <= 0L) {
            return ApkInspectionResult.Rejected(
                reason = CloudUpdateErrorReason.INVALID_APK,
                detail = "Downloaded update file is missing or empty."
            )
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        } else {
            PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
        }

        val archiveInfo: PackageInfo = runCatching {
            archiveInfoProvider?.invoke(apkFile, flags) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }
        }.getOrNull() ?: return ApkInspectionResult.Rejected(
            reason = CloudUpdateErrorReason.INVALID_APK,
            detail = "Downloaded file is corrupted or not a valid Android APK package."
        )

        // 1. Validate readable APK versionCode and versionName directly from the APK manifest
        val archiveVersionCodeLong = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            archiveInfo.versionCode.toLong()
        }
        val archiveVersionName = archiveInfo.versionName?.trim().orEmpty()
        if (archiveVersionCodeLong <= 0L || archiveVersionCodeLong > Int.MAX_VALUE.toLong() || archiveVersionName.isBlank()) {
            return ApkInspectionResult.Rejected(
                reason = CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA,
                detail = "Could not read valid versionCode ($archiveVersionCodeLong) or versionName ('$archiveVersionName') from the downloaded APK."
            )
        }
        val archiveVersionCode = archiveVersionCodeLong.toInt()

        // 2. Validate applicationId / package identity
        if (archiveInfo.packageName != expectedPackageName) {
            return ApkInspectionResult.Rejected(
                reason = CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH,
                detail = "APK package ID (${archiveInfo.packageName}) does not match ShareFlow ($expectedPackageName).",
                inspectedVersionCode = archiveVersionCode,
                inspectedVersionName = archiveVersionName
            )
        }

        // 3. Validate consistency between GitHub Release / version.json metadata and the ACTUAL APK
        if (expectedVersionCode > 0 && archiveVersionCode != expectedVersionCode) {
            return ApkInspectionResult.Rejected(
                reason = CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
                detail = "Release metadata conflict: release declared versionCode $expectedVersionCode" +
                    (if (expectedVersionName.isNotBlank()) " (v$expectedVersionName)" else "") +
                    ", but downloaded APK contains versionCode $archiveVersionCode (v$archiveVersionName). Installation stopped.",
                inspectedVersionCode = archiveVersionCode,
                inspectedVersionName = archiveVersionName
            )
        }
        if (expectedVersionName.isNotBlank()) {
            val normExpectedName = GitHubReleasesParser.normalizeVersionName(expectedVersionName)
            val normArchiveName = GitHubReleasesParser.normalizeVersionName(archiveVersionName)
            if (normExpectedName.isNotBlank() &&
                normArchiveName.isNotBlank() &&
                !normExpectedName.equals(normArchiveName, ignoreCase = true)
            ) {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
                    detail = "Release metadata conflict: release declared v$normExpectedName (versionCode $expectedVersionCode), but downloaded APK contains v$normArchiveName (versionCode $archiveVersionCode). Installation stopped.",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
        }

        // 4. Strictly evaluate actual APK versionCode against installed ShareFlow versionCode
        when (
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = archiveVersionCode,
                remoteVersionName = archiveVersionName,
                installedVersionCode = currentInstalledVersionCode,
                installedVersionName = currentInstalledVersionName
            )
        ) {
            VersionPrecedenceDecision.UPDATE_AVAILABLE -> Unit
            VersionPrecedenceDecision.DOWNGRADE_BLOCKED -> {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.DOWNGRADE_BLOCKED,
                    detail = "Downloaded APK versionCode ($archiveVersionCode, v$archiveVersionName) is older than installed versionCode ($currentInstalledVersionCode, v$currentInstalledVersionName). Downgrade blocked.",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
            VersionPrecedenceDecision.DUPLICATE_VERSION_CONFLICT -> {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
                    detail = "Downloaded APK uses duplicate versionCode $archiveVersionCode (v$archiveVersionName) matching installed versionCode $currentInstalledVersionCode (v$currentInstalledVersionName). Update rejected.",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
            VersionPrecedenceDecision.ALREADY_INSTALLED -> {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
                    detail = "Downloaded APK versionCode $archiveVersionCode (v$archiveVersionName) is already installed on this device.",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
            VersionPrecedenceDecision.UNREADABLE_VERSION_METADATA -> {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA,
                    detail = "Could not compare APK versionCode ($archiveVersionCode) against installed versionCode ($currentInstalledVersionCode).",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
        }

        // 5. Mandatory cryptographic signing certificate verification (NEVER bypassed)
        val installedInfo: PackageInfo? = runCatching {
            installedInfoProvider?.invoke(expectedPackageName, flags) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            return ApkInspectionResult.Rejected(
                reason = CloudUpdateErrorReason.SIGNATURE_MISMATCH,
                detail = "Update APK signing certificate does not match the installed ShareFlow signature.",
                inspectedVersionCode = archiveVersionCode,
                inspectedVersionName = archiveVersionName
            )
        }

        val normalizedExpectedCert = expectedSigningCertSha256?.trim()?.replace(":", "")?.lowercase()
        if (!normalizedExpectedCert.isNullOrBlank() && normalizedExpectedCert.length == 64) {
            val matchesExpectedCert = archiveCertDigests.any {
                CloudUpdateValidator.constantTimeHashEquals(normalizedExpectedCert, it)
            }
            if (!matchesExpectedCert) {
                return ApkInspectionResult.Rejected(
                    reason = CloudUpdateErrorReason.SIGNATURE_MISMATCH,
                    detail = "Downloaded APK signing certificate does not match the release manifest signingCertSha256.",
                    inspectedVersionCode = archiveVersionCode,
                    inspectedVersionName = archiveVersionName
                )
            }
        }

        return ApkInspectionResult.Valid(
            InspectedApkMetadata(
                packageName = archiveInfo.packageName,
                versionCode = archiveVersionCode,
                versionName = archiveVersionName,
                signingCertSha256Digests = archiveCertDigests
            )
        )
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
            installedInfoProvider?.invoke(expectedPackageName, flags) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(expectedPackageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                pm.getPackageInfo(expectedPackageName, flags)
            }
        }.getOrNull()
        val installedDigests = extractSigningCertSha256Digests(installedInfo)
        if (installedDigests.isEmpty()) return true
        return installedDigests.any { CloudUpdateValidator.constantTimeHashEquals(normalizedExpected, it) }
    }

    @Suppress("DEPRECATION")
    private fun extractSigningCertSha256Digests(pkgInfo: PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        val rawSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            if (signingInfo != null) {
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
 * Centralized Share Flow In-App Update Engine backed by GitHub Releases API + resilient OkHttp
 * resumable downloads, mandatory SHA-256 + signing certificate + package ID + anti-downgrade
 * verification, and Android PackageInstaller handoff.
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
    private val fallbackManifestUrl: String = DEFAULT_FALLBACK_MANIFEST_URL,
    private val enableGitHubReleasesPrimary: Boolean = false,
    private val githubReleasesApiUrl: String = BuildConfig.GITHUB_RELEASES_API_URL,
    private val preferencesStore: UpdatePreferencesStore = InMemoryUpdatePreferencesStore(),
    private val clock: Clock = SystemClock()
) {
    private val appContext = context.applicationContext ?: context
    private val _state = MutableStateFlow<CloudUpdateState>(CloudUpdateState.Idle)
    val state: StateFlow<CloudUpdateState> = _state.asStateFlow()

    private val _releaseHistory = MutableStateFlow<List<ReleaseHistoryItem>>(emptyList())
    val releaseHistory: StateFlow<List<ReleaseHistoryItem>> = _releaseHistory.asStateFlow()

    val diagnosticsFlow = preferencesStore.snapshot

    @Volatile
    private var lastKnownManifest: CloudUpdateManifest? = null

    fun resolveEffectiveManifestUrl(userConfiguredUrl: String): String {
        val custom = userConfiguredUrl.trim()
        if (custom.isNotEmpty()) return custom
        return defaultManifestUrl.trim()
    }

    /**
     * Checks for updates. When [enableGitHubReleasesPrimary] is active and no custom URL is passed,
     * queries the official GitHub Releases API first (populating both the latest stable release
     * and previous releases history in one call) and falls back gracefully to static `version.json`
     * endpoints if GitHub API is rate-limited or temporarily unreachable.
     */
    suspend fun checkForUpdates(manifestUrl: String = ""): CloudUpdateState {
        val isUsingDefaultEndpoint = manifestUrl.trim().isEmpty()
        if (isUsingDefaultEndpoint && enableGitHubReleasesPrimary) {
            return checkViaGitHubReleasesWithFallback()
        }

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
        return finalizeManifestFetch(fetchRes, sourceLabel = OFFICIAL_PORTAL_HOST)
    }

    private suspend fun checkViaGitHubReleasesWithFallback(): CloudUpdateState {
        _state.value = CloudUpdateState.Checking
        val apiListUrl = buildReleasesListUrl(githubReleasesApiUrl)
        val listFetch = if (CloudUpdateValidator.isValidPublicHttpsUrl(apiListUrl)) {
            httpClient.fetchManifestText(apiListUrl)
        } else {
            AppResult.Failure(AppError.Security("Invalid GitHub Releases API URL."))
        }

        if (listFetch is AppResult.Success) {
            val releases = GitHubReleasesParser.parseReleasesArray(listFetch.data)
            _releaseHistory.value = releases.map(GitHubReleasesParser::toHistoryItem)

            val latestStable = releases.firstOrNull { !it.isDraft && !it.isPrerelease }
            if (latestStable == null) {
                preferencesStore.recordCheckResult(
                    epochMillis = clock.nowEpochMillis(),
                    serverStatus = UpdateServerConnectionStatus.CONNECTED,
                    sourceLabel = GITHUB_SOURCE_LABEL
                )
                val upToDate = CloudUpdateState.UpToDate(
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    portalUrl = OFFICIAL_RELEASES_PAGE_URL
                )
                _state.value = upToDate
                return upToDate
            }

            // Enrich with version.json or .sha256 sidecar if attached to the release
            val sidecarManifest = latestStable.versionJsonAssetUrl
                ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
                ?.let { url ->
                    val raw = httpClient.fetchManifestText(url)
                    if (raw is AppResult.Success) {
                        (CloudUpdateValidator.parseManifestJson(raw.data) as? AppResult.Success)?.data
                    } else null
                }

            val sidecarShaText = if (sidecarManifest == null && latestStable.apkAsset?.sha256Digest == null) {
                latestStable.sha256SidecarAssetUrl
                    ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
                    ?.let { url ->
                        (httpClient.fetchManifestText(url) as? AppResult.Success)?.data
                    }
            } else {
                null
            }

            when (
                val resolution = GitHubReleasesParser.resolveCloudUpdateManifest(
                    release = latestStable,
                    sidecarManifest = sidecarManifest,
                    sidecarSha256Text = sidecarShaText
                )
            ) {
                is GitHubManifestResolution.Resolved -> {
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL
                    )
                    return evaluateResolvedManifest(resolution.manifest)
                }

                is GitHubManifestResolution.MissingVersionCode -> {
                    val portalResolved = tryResolveFromFallbackManifestEndpoints()
                    if (portalResolved != null) {
                        return portalResolved
                    }

                    val normalizedLatest = GitHubReleasesParser.normalizeVersionName(latestStable.versionName)
                    val normalizedInstalled = GitHubReleasesParser.normalizeVersionName(currentVersionName)
                    if (normalizedLatest.isNotBlank() &&
                        normalizedInstalled.isNotBlank() &&
                        GitHubReleasesParser.compareSemanticVersions(normalizedLatest, normalizedInstalled) <= 0
                    ) {
                        preferencesStore.recordCheckResult(
                            epochMillis = clock.nowEpochMillis(),
                            serverStatus = UpdateServerConnectionStatus.CONNECTED,
                            sourceLabel = GITHUB_SOURCE_LABEL
                        )
                        val upToDate = CloudUpdateState.UpToDate(
                            currentVersionName = currentVersionName,
                            currentVersionCode = currentVersionCode,
                            portalUrl = latestStable.htmlUrl.ifBlank { OFFICIAL_RELEASES_PAGE_URL }
                        )
                        _state.value = upToDate
                        return upToDate
                    }

                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA,
                        detail = "Release ${latestStable.tagName} does not provide a readable numeric versionCode (version.json or Build number)."
                    )
                    // GitHub Releases API responded HTTP 200 successfully; keep server status CONNECTED
                    // so metadata validation errors are never misreported as a network connection issue.
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL,
                        errorSummary = err.detail
                    )
                    _state.value = err
                    return err
                }

                is GitHubManifestResolution.MetadataConflict -> {
                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
                        detail = resolution.detail
                    )
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL,
                        errorSummary = err.detail
                    )
                    _state.value = err
                    return err
                }

                is GitHubManifestResolution.MissingApkOrChecksum -> {
                    val portalResolved = tryResolveFromFallbackManifestEndpoints()
                    if (portalResolved != null) {
                        return portalResolved
                    }
                    val manifestErr = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.INVALID_MANIFEST,
                        detail = "Release ${latestStable.tagName} is missing a verified APK asset or SHA-256 digest."
                    )
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL,
                        errorSummary = manifestErr.detail
                    )
                    _state.value = manifestErr
                    return manifestErr
                }

                GitHubManifestResolution.SkippedNonStable -> {
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL
                    )
                    val upToDate = CloudUpdateState.UpToDate(
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        portalUrl = latestStable.htmlUrl.ifBlank { OFFICIAL_RELEASES_PAGE_URL }
                    )
                    _state.value = upToDate
                    return upToDate
                }
            }
        }

        // GitHub API failed (e.g., rate-limited 403/429 or temporary 5xx) — try static fallback endpoints
        val primaryError = (listFetch as AppResult.Failure).error
        val fallbackCandidates = listOf(fallbackManifestUrl, defaultManifestUrl)
            .map { it.trim() }
            .distinct()
            .filter { it.isNotEmpty() && CloudUpdateValidator.isValidPublicHttpsUrl(it) }

        for (candidateUrl in fallbackCandidates) {
            val fallbackRes = httpClient.fetchManifestText(candidateUrl)
            if (fallbackRes is AppResult.Success) {
                return finalizeManifestFetch(fallbackRes, sourceLabel = GITHUB_SOURCE_LABEL)
            }
        }

        // All endpoints failed — map the root GitHub API failure accurately
        val errorReason = mapErrorToReason(primaryError)
        val serverStatus = mapReasonToServerStatus(errorReason)
        preferencesStore.recordCheckResult(
            epochMillis = clock.nowEpochMillis(),
            serverStatus = serverStatus,
            sourceLabel = GITHUB_SOURCE_LABEL,
            errorSummary = primaryError.message
        )
        val err = CloudUpdateState.Error(
            reason = errorReason,
            detail = primaryError.message
        )
        _state.value = err
        return err
    }

    private suspend fun tryResolveFromFallbackManifestEndpoints(): CloudUpdateState? {
        val fallbackCandidates = listOf(defaultManifestUrl, fallbackManifestUrl)
            .map { it.trim() }
            .distinct()
            .filter { it.isNotEmpty() && CloudUpdateValidator.isValidPublicHttpsUrl(it) }

        for (candidateUrl in fallbackCandidates) {
            val fallbackRes = httpClient.fetchManifestText(candidateUrl)
            if (fallbackRes is AppResult.Success) {
                val parsed = CloudUpdateValidator.parseManifestJson(fallbackRes.data)
                if (parsed is AppResult.Success) {
                    preferencesStore.recordCheckResult(
                        epochMillis = clock.nowEpochMillis(),
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        sourceLabel = GITHUB_SOURCE_LABEL
                    )
                    return evaluateResolvedManifest(parsed.data)
                }
            }
        }
        return null
    }

    private suspend fun finalizeManifestFetch(
        fetchRes: AppResult<String>,
        sourceLabel: String
    ): CloudUpdateState {
        val rawJson = when (fetchRes) {
            is AppResult.Success -> fetchRes.data
            is AppResult.Failure -> {
                val reason = mapErrorToReason(fetchRes.error)
                preferencesStore.recordCheckResult(
                    epochMillis = clock.nowEpochMillis(),
                    serverStatus = mapReasonToServerStatus(reason),
                    sourceLabel = sourceLabel,
                    errorSummary = fetchRes.error.message
                )
                val err = CloudUpdateState.Error(
                    reason = reason,
                    detail = fetchRes.error.message
                )
                _state.value = err
                return err
            }
        }

        val manifest = when (val parsed = CloudUpdateValidator.parseManifestJson(rawJson)) {
            is AppResult.Success -> parsed.data
            is AppResult.Failure -> {
                preferencesStore.recordCheckResult(
                    epochMillis = clock.nowEpochMillis(),
                    serverStatus = UpdateServerConnectionStatus.ERROR,
                    sourceLabel = sourceLabel,
                    errorSummary = parsed.error.message
                )
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.INVALID_MANIFEST,
                    detail = parsed.error.message
                )
                _state.value = err
                return err
            }
        }

        preferencesStore.recordCheckResult(
            epochMillis = clock.nowEpochMillis(),
            serverStatus = UpdateServerConnectionStatus.CONNECTED,
            sourceLabel = sourceLabel
        )
        return evaluateResolvedManifest(manifest)
    }

    private suspend fun evaluateResolvedManifest(manifest: CloudUpdateManifest): CloudUpdateState {
        if (manifest.minSupportedAndroidSdk > currentSdkInt) {
            val err = CloudUpdateState.Error(
                reason = CloudUpdateErrorReason.INCOMPATIBLE_DEVICE,
                detail = "This update requires Android API ${manifest.minSupportedAndroidSdk} or higher.",
                manifest = manifest
            )
            _state.value = err
            return err
        }

        val nextState = when (
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = manifest.versionCode,
                remoteVersionName = manifest.versionName,
                installedVersionCode = currentVersionCode,
                installedVersionName = currentVersionName
            )
        ) {
            VersionPrecedenceDecision.UPDATE_AVAILABLE -> {
                lastKnownManifest = manifest
                preferencesStore.clearSnoozeIfNewer(manifest.versionCode)
                CloudUpdateState.UpdateAvailable(manifest = manifest)
            }
            VersionPrecedenceDecision.ALREADY_INSTALLED -> {
                CloudUpdateState.UpToDate(
                    currentVersionName = currentVersionName,
                    currentVersionCode = currentVersionCode,
                    portalUrl = manifest.portalUrl
                )
            }
            VersionPrecedenceDecision.DUPLICATE_VERSION_CONFLICT -> {
                CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
                    detail = "Release v${manifest.versionName} declares duplicate versionCode ${manifest.versionCode}, matching installed v$currentVersionName ($currentVersionCode). Update rejected.",
                    manifest = manifest
                )
            }
            VersionPrecedenceDecision.DOWNGRADE_BLOCKED -> {
                CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.DOWNGRADE_BLOCKED,
                    detail = "Release v${manifest.versionName} (versionCode ${manifest.versionCode}) is older than installed v$currentVersionName (versionCode $currentVersionCode). Downgrade blocked.",
                    manifest = manifest
                )
            }
            VersionPrecedenceDecision.UNREADABLE_VERSION_METADATA -> {
                CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA,
                    detail = "Unable to read valid versionCode from release v${manifest.versionName} (${manifest.versionCode}) or installed build ($currentVersionCode).",
                    manifest = manifest
                )
            }
        }
        _state.value = nextState
        return nextState
    }

    /**
     * Surfaces a validated update received via FCM push notification immediately in the Updates UI.
     */
    fun applyPushUpdateManifest(manifest: CloudUpdateManifest) {
        val isNewer = GitHubReleasesParser.isRemoteReleaseNewer(
            remoteVersionCode = manifest.versionCode,
            remoteVersionName = manifest.versionName,
            installedVersionCode = currentVersionCode,
            installedVersionName = currentVersionName
        )
        if (!isNewer) return
        if (_state.value is CloudUpdateState.Downloading || _state.value is CloudUpdateState.Verifying) {
            return
        }
        lastKnownManifest = manifest
        _state.value = CloudUpdateState.UpdateAvailable(manifest = manifest)
    }

    suspend fun downloadAndVerifyUpdate(manifest: CloudUpdateManifest): CloudUpdateState =
        withContext(dispatchers.io) {
            lastKnownManifest = manifest

            // Pre-download strict versionCode precedence guard
            when (
                GitHubReleasesParser.evaluateVersionPrecedence(
                    remoteVersionCode = manifest.versionCode,
                    remoteVersionName = manifest.versionName,
                    installedVersionCode = currentVersionCode,
                    installedVersionName = currentVersionName
                )
            ) {
                VersionPrecedenceDecision.UPDATE_AVAILABLE -> Unit
                VersionPrecedenceDecision.ALREADY_INSTALLED -> {
                    val upToDate = CloudUpdateState.UpToDate(
                        currentVersionName = currentVersionName,
                        currentVersionCode = currentVersionCode,
                        portalUrl = manifest.portalUrl
                    )
                    _state.value = upToDate
                    return@withContext upToDate
                }
                VersionPrecedenceDecision.DUPLICATE_VERSION_CONFLICT -> {
                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
                        detail = "Release v${manifest.versionName} declares duplicate versionCode ${manifest.versionCode}, matching installed v$currentVersionName ($currentVersionCode). Update rejected.",
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
                VersionPrecedenceDecision.DOWNGRADE_BLOCKED -> {
                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.DOWNGRADE_BLOCKED,
                        detail = "Release v${manifest.versionName} (versionCode ${manifest.versionCode}) is older than installed v$currentVersionName (versionCode $currentVersionCode). Downgrade blocked.",
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
                VersionPrecedenceDecision.UNREADABLE_VERSION_METADATA -> {
                    val err = CloudUpdateState.Error(
                        reason = CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA,
                        detail = "Unable to read valid versionCode from release v${manifest.versionName} (${manifest.versionCode}).",
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
            }

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
            cleanupStaleUpdateCacheFiles(updatesDir, keepVersionCode = manifest.versionCode)
            val targetFile = File(updatesDir, "shareflow-v${manifest.versionCode}.apk")
            val partFile = File(updatesDir, "${targetFile.name}.part")
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
                    val cachedInspect = packageInspector.inspectUpdateApkDetailed(
                        apkFile = targetFile,
                        expectedPackageName = expectedPackageName,
                        expectedVersionCode = manifest.versionCode,
                        expectedVersionName = manifest.versionName,
                        currentInstalledVersionCode = currentVersionCode,
                        currentInstalledVersionName = currentVersionName,
                        expectedSigningCertSha256 = manifest.signingCertSha256
                    )
                    if (cachedInspect is ApkInspectionResult.Valid) {
                        val ready = CloudUpdateState.ReadyToInstall(
                            manifest = manifest,
                            verifiedApkPath = targetFile.absolutePath
                        )
                        _state.value = ready
                        return@withContext ready
                    }
                }
                runCatching { targetFile.delete() }
            }

            // Only prune unrelated stale files (keep current .part for resume, and keep verified files until replaced)
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != targetFile.name && old.name != partFile.name && old.name.endsWith(".part")) {
                    runCatching { old.delete() }
                }
            }

            val existingPartBytes = if (partFile.exists() && partFile.isFile) partFile.length() else 0L
            _state.value = CloudUpdateState.Downloading(
                manifest = manifest,
                downloadedBytes = existingPartBytes,
                totalBytes = manifest.fileSizeBytes
            )

            val downloadRes = try {
                httpClient.downloadApkToFile(
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
            } catch (ce: CancellationException) {
                // Preserve .part file if paused; pauseDownload() sets Paused state cleanly
                throw ce
            }

            val apkFile = when (downloadRes) {
                is AppResult.Success -> downloadRes.data
                is AppResult.Failure -> {
                    // Never delete a previously verified APK; leave .part intact for seamless retry/resume
                    val reason = mapDownloadFailureReason(downloadRes.error)
                    val err = CloudUpdateState.Error(
                        reason = reason,
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
                partFile.delete()
                val err = CloudUpdateState.Error(
                    reason = CloudUpdateErrorReason.CHECKSUM_MISMATCH,
                    detail = "SHA-256 verification failed. The downloaded update was discarded for safety.",
                    manifest = manifest
                )
                _state.value = err
                return@withContext err
            }

            // 2. Mandatory actual APK inspection: archive validity, package ID, GitHub-vs-APK
            //    metadata match, strict versionCode precedence, and signing certificate continuity
            when (
                val inspectRes = packageInspector.inspectUpdateApkDetailed(
                    apkFile = apkFile,
                    expectedPackageName = expectedPackageName,
                    expectedVersionCode = manifest.versionCode,
                    expectedVersionName = manifest.versionName,
                    currentInstalledVersionCode = currentVersionCode,
                    currentInstalledVersionName = currentVersionName,
                    expectedSigningCertSha256 = manifest.signingCertSha256
                )
            ) {
                is ApkInspectionResult.Valid -> Unit
                is ApkInspectionResult.Rejected -> {
                    apkFile.delete()
                    partFile.delete()
                    val err = CloudUpdateState.Error(
                        reason = inspectRes.reason,
                        detail = inspectRes.detail,
                        manifest = manifest
                    )
                    _state.value = err
                    return@withContext err
                }
            }

            // Clean up older verified APKs only AFTER the new APK passed all security checks
            updatesDir.listFiles()?.forEach { old ->
                if (old.name != apkFile.name) {
                    runCatching { old.delete() }
                }
            }

            val ready = CloudUpdateState.ReadyToInstall(
                manifest = manifest,
                verifiedApkPath = apkFile.absolutePath
            )
            _state.value = ready
            ready
        }

    /**
     * Transitions an active download to [CloudUpdateState.Paused] while preserving the `.part`
     * file so calling [downloadAndVerifyUpdate] again resumes via HTTP Range.
     */
    fun markDownloadPaused() {
        val current = _state.value
        if (current is CloudUpdateState.Downloading) {
            _state.value = CloudUpdateState.Paused(
                manifest = current.manifest,
                downloadedBytes = current.downloadedBytes,
                totalBytes = current.totalBytes
            )
        }
    }

    /**
     * Cancels an active/paused download, removes any `.part` temp file, and restores
     * [CloudUpdateState.UpdateAvailable] without deleting previously verified APKs.
     */
    fun cancelActiveDownload() {
        val manifest = when (val current = _state.value) {
            is CloudUpdateState.Downloading -> current.manifest
            is CloudUpdateState.Paused -> current.manifest
            is CloudUpdateState.Error -> current.manifest
            else -> lastKnownManifest
        }
        val updatesDir = File(appContext.cacheDir, UPDATES_CACHE_DIR)
        updatesDir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part")) {
                runCatching { file.delete() }
            }
        }
        _state.value = if (manifest != null) {
            CloudUpdateState.UpdateAvailable(manifest)
        } else {
            CloudUpdateState.Idle
        }
    }

    fun launchVerifiedUpdateInstaller(apkPath: String): UpdateInstallLaunchResult {
        val apkFile = File(apkPath)
        val updatesDir = File(appContext.cacheDir, UPDATES_CACHE_DIR)
        val canonicalBase = runCatching { updatesDir.canonicalPath + File.separator }.getOrNull() ?: return UpdateInstallLaunchResult.FAILED
        val canonicalTarget = runCatching { apkFile.canonicalPath }.getOrNull() ?: return UpdateInstallLaunchResult.FAILED
        if (!apkFile.exists() ||
            !apkFile.isFile ||
            apkFile.length() <= 0L ||
            !apkFile.name.endsWith(".apk", ignoreCase = true) ||
            !canonicalTarget.startsWith(canonicalBase)
        ) {
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
            val intent = com.example.shareflow.ui.viewer.ApkInstallerIntentHelper
                .targetExternalSystemInstaller(
                    appContext,
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            appContext.startActivity(intent)
            UpdateInstallLaunchResult.LAUNCHED
        }.getOrElse { UpdateInstallLaunchResult.FAILED }
    }

    fun resetState() {
        _state.value = CloudUpdateState.Idle
    }

    private fun buildReleasesListUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.contains("?")) {
            trimmed
        } else {
            "$trimmed?per_page=15"
        }
    }

    private fun mapErrorToReason(error: AppError): CloudUpdateErrorReason {
        val netEx = error.cause as? UpdateNetworkException
        return when (netEx?.kind) {
            UpdateNetworkFailureKind.NO_INTERNET -> CloudUpdateErrorReason.NO_INTERNET
            UpdateNetworkFailureKind.TIMEOUT -> CloudUpdateErrorReason.TIMEOUT
            UpdateNetworkFailureKind.SSL_ERROR -> CloudUpdateErrorReason.SSL_ERROR
            UpdateNetworkFailureKind.RATE_LIMITED -> CloudUpdateErrorReason.RATE_LIMITED
            UpdateNetworkFailureKind.SERVER_UNAVAILABLE -> CloudUpdateErrorReason.SERVER_UNAVAILABLE
            UpdateNetworkFailureKind.RELEASE_NOT_FOUND -> CloudUpdateErrorReason.RELEASE_NOT_FOUND
            UpdateNetworkFailureKind.REDIRECT_BLOCKED -> CloudUpdateErrorReason.INVALID_URL
            UpdateNetworkFailureKind.DNS_ERROR,
            UpdateNetworkFailureKind.HTTP_ERROR,
            UpdateNetworkFailureKind.IO_ERROR,
            null -> if (error is AppError.Security) {
                CloudUpdateErrorReason.INVALID_URL
            } else {
                CloudUpdateErrorReason.NETWORK_ERROR
            }
        }
    }

    private fun mapDownloadFailureReason(error: AppError): CloudUpdateErrorReason {
        val netEx = error.cause as? UpdateNetworkException
        return when (netEx?.kind) {
            UpdateNetworkFailureKind.NO_INTERNET -> CloudUpdateErrorReason.NO_INTERNET
            UpdateNetworkFailureKind.TIMEOUT -> CloudUpdateErrorReason.TIMEOUT
            UpdateNetworkFailureKind.SSL_ERROR -> CloudUpdateErrorReason.SSL_ERROR
            UpdateNetworkFailureKind.RATE_LIMITED -> CloudUpdateErrorReason.RATE_LIMITED
            UpdateNetworkFailureKind.REDIRECT_BLOCKED -> CloudUpdateErrorReason.INVALID_URL
            else -> CloudUpdateErrorReason.DOWNLOAD_INTERRUPTED
        }
    }

    private fun mapReasonToServerStatus(reason: CloudUpdateErrorReason): UpdateServerConnectionStatus =
        when (reason) {
            CloudUpdateErrorReason.NO_INTERNET -> UpdateServerConnectionStatus.OFFLINE
            CloudUpdateErrorReason.RATE_LIMITED -> UpdateServerConnectionStatus.RATE_LIMITED
            else -> UpdateServerConnectionStatus.ERROR
        }

    internal fun cleanupStaleUpdateCacheFiles(updatesDir: File, keepVersionCode: Int): Int {
        if (!updatesDir.exists() || !updatesDir.isDirectory) return 0
        val keepApkName = "shareflow-v$keepVersionCode.apk"
        val keepPartName = "$keepApkName.part"
        var removed = 0
        updatesDir.listFiles()?.forEach { child ->
            if (!child.isFile) return@forEach
            val name = child.name
            if (name == keepApkName || name == keepPartName) return@forEach
            if (name.startsWith("shareflow-v") && (name.endsWith(".apk") || name.endsWith(".apk.part"))) {
                if (runCatching { child.delete() }.getOrDefault(false)) {
                    removed++
                }
            }
        }
        return removed
    }

    companion object {
        const val UPDATES_CACHE_DIR = "shareflow_updates"
        const val GITHUB_SOURCE_LABEL = "GitHub Releases (ow7ab/Share-Flow)"
        const val OFFICIAL_RELEASES_PAGE_URL = "https://github.com/ow7ab/Share-Flow/releases"
        const val OFFICIAL_PORTAL_URL = "https://ow7ab.github.io/Share-Flow/"
        const val OFFICIAL_PORTAL_HOST = "github.com/ow7ab/Share-Flow"
        const val DEFAULT_FALLBACK_MANIFEST_URL =
            "https://github.com/ow7ab/Share-Flow/releases/latest/download/version.json"
    }
}
