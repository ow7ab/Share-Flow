package com.example.shareflow.data.update

import android.os.Build
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Strongly-typed update details returned by [UpdateManager] after fetching and validating
 * the latest ShareFlow release from GitHub Releases or `version.json`.
 */
data class UpdateDetails(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val minSupportedAndroidSdk: Int = 24,
    val mandatory: Boolean = false,
    val releaseNotes: String = "",
    val releaseNotesAr: String = "",
    val releaseNotesEn: String = "",
    val portalUrl: String? = null,
    val publishedAt: String = "",
    val signingCertSha256: String? = null,
    val isUpdateAvailable: Boolean = false
) {
    fun localizedReleaseNotes(isArabic: Boolean): String = if (isArabic) {
        releaseNotesAr.ifBlank { releaseNotes.ifBlank { releaseNotesEn } }
    } else {
        releaseNotesEn.ifBlank { releaseNotes.ifBlank { releaseNotesAr } }
    }

    fun toCloudUpdateManifest(): CloudUpdateManifest = CloudUpdateManifest(
        versionCode = versionCode,
        versionName = versionName,
        apkDownloadUrl = apkDownloadUrl,
        sha256 = sha256,
        fileSizeBytes = fileSizeBytes,
        minSupportedAndroidSdk = minSupportedAndroidSdk,
        mandatory = mandatory,
        releaseNotesAr = releaseNotesAr.ifBlank { releaseNotes },
        releaseNotesEn = releaseNotesEn.ifBlank { releaseNotes },
        portalUrl = portalUrl,
        publishedAt = publishedAt,
        signingCertSha256 = signingCertSha256
    )
}

/**
 * Background launch & periodic update-check coordinator powered by [OkHttpClient].
 * Supports:
 * - Official GitHub Releases API primary resolution (with static `version.json` fallback)
 * - Periodic interval gating (default 6 hours)
 * - Persistent per-version snooze ("Later") that automatically unblocks for newer releases
 * - Immediate foreground FCM push notification dialog surfacing
 */
class UpdateManager(
    private val okHttpClient: OkHttpClient = createDefaultOkHttpClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val manifestUrl: String = BuildConfig.CLOUD_UPDATE_MANIFEST_URL,
    private val fallbackManifestUrl: String = CloudUpdateManager.DEFAULT_FALLBACK_MANIFEST_URL,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentSdkInt: Int = Build.VERSION.SDK_INT,
    private val currentVersionName: String = BuildConfig.VERSION_NAME,
    private val enableGitHubReleasesApi: Boolean = false,
    private val githubReleasesApiUrl: String = BuildConfig.GITHUB_RELEASES_API_URL,
    private val preferencesStore: UpdatePreferencesStore = InMemoryUpdatePreferencesStore(),
    private val clock: Clock = SystemClock()
) {
    private val _detectedUpdate = MutableStateFlow<UpdateDetails?>(null)
    val detectedUpdate: StateFlow<UpdateDetails?> = _detectedUpdate.asStateFlow()

    /**
     * Emits an immediate in-app dialog trigger when a valid FCM push arrives while the app is
     * actively in the foreground.
     */
    private val _foregroundPushVersionCode = MutableStateFlow<Int?>(null)
    val foregroundPushVersionCode: StateFlow<Int?> = _foregroundPushVersionCode.asStateFlow()

    /**
     * Runs [checkForUpdate] only if the periodic interval has elapsed (or no check was recorded yet).
     */
    suspend fun checkForUpdateIfDue(
        intervalMillis: Long = UpdatePreferencesStore.DEFAULT_PERIODIC_CHECK_INTERVAL_MS
    ): AppResult<UpdateDetails>? {
        val now = clock.nowEpochMillis()
        if (!preferencesStore.isPeriodicCheckDue(now, intervalMillis)) {
            return null
        }
        return checkForUpdate(respectSnooze = true)
    }

    /**
     * Checks for updates on [dispatcher], validates HTTPS & SHA-256 fields, compares versionCode
     * & semantic versionName with installed build, and populates [detectedUpdate] if newer and
     * not snoozed.
     */
    suspend fun checkForUpdate(respectSnooze: Boolean = false): AppResult<UpdateDetails> =
        withContext(dispatcher) {
            if (enableGitHubReleasesApi) {
                val ghResult = checkViaGitHubReleases(respectSnooze)
                if (ghResult != null) {
                    return@withContext ghResult
                }
            }

            val primaryUrl = manifestUrl.trim()
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(primaryUrl)) {
                return@withContext AppResult.Failure(
                    AppError.Security("Official update manifest URL must be a valid public HTTPS URL.")
                )
            }

            val primaryResult = fetchTextWithOkHttp(primaryUrl)
            val rawJsonResult = if (
                primaryResult is AppResult.Failure &&
                fallbackManifestUrl.isNotBlank() &&
                !fallbackManifestUrl.equals(primaryUrl, ignoreCase = true) &&
                CloudUpdateValidator.isValidPublicHttpsUrl(fallbackManifestUrl)
            ) {
                fetchTextWithOkHttp(fallbackManifestUrl)
            } else {
                primaryResult
            }

            val rawJson = when (rawJsonResult) {
                is AppResult.Success -> rawJsonResult.data
                is AppResult.Failure -> return@withContext rawJsonResult
            }

            val manifest = when (val parsed = CloudUpdateValidator.parseManifestJson(rawJson)) {
                is AppResult.Success -> parsed.data
                is AppResult.Failure -> return@withContext parsed
            }

            preferencesStore.recordCheckResult(
                epochMillis = clock.nowEpochMillis(),
                serverStatus = UpdateServerConnectionStatus.CONNECTED,
                sourceLabel = CloudUpdateManager.GITHUB_SOURCE_LABEL
            )

            finalizeDetails(manifest, respectSnooze)
        }

    private suspend fun checkViaGitHubReleases(respectSnooze: Boolean): AppResult<UpdateDetails>? {
        val listUrl = githubReleasesApiUrl.trim().trimEnd('/').let { base ->
            if (base.contains("?")) base else "$base?per_page=10"
        }
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(listUrl)) return null

        val listResult = fetchTextWithOkHttp(listUrl, maxBytes = OkHttpCloudUpdateHttpClient.MAX_API_RESPONSE_BYTES)
        if (listResult !is AppResult.Success) {
            return null // fall through to static version.json fallbacks
        }

        val releases = GitHubReleasesParser.parseReleasesArray(listResult.data)
        val latestStable = releases.firstOrNull { !it.isDraft && !it.isPrerelease }
        if (latestStable == null) {
            preferencesStore.recordCheckResult(
                epochMillis = clock.nowEpochMillis(),
                serverStatus = UpdateServerConnectionStatus.CONNECTED,
                sourceLabel = CloudUpdateManager.GITHUB_SOURCE_LABEL
            )
            _detectedUpdate.value = null
            return AppResult.Success(
                UpdateDetails(
                    versionCode = currentVersionCode,
                    versionName = currentVersionName,
                    apkDownloadUrl = "",
                    sha256 = "",
                    fileSizeBytes = 0L,
                    portalUrl = CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL,
                    isUpdateAvailable = false
                )
            )
        }

        val sidecarManifest = latestStable.versionJsonAssetUrl
            ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
            ?.let { url ->
                (fetchTextWithOkHttp(url) as? AppResult.Success)?.data?.let { raw ->
                    (CloudUpdateValidator.parseManifestJson(raw) as? AppResult.Success)?.data
                }
            }

        val sidecarShaText = if (sidecarManifest == null && latestStable.apkAsset?.sha256Digest == null) {
            latestStable.sha256SidecarAssetUrl
                ?.takeIf { CloudUpdateValidator.isValidPublicHttpsUrl(it) }
                ?.let { url -> (fetchTextWithOkHttp(url) as? AppResult.Success)?.data }
        } else {
            null
        }

        val manifest = GitHubReleasesParser.toCloudUpdateManifest(
            release = latestStable,
            sidecarManifest = sidecarManifest,
            sidecarSha256Text = sidecarShaText,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName
        ) ?: return null

        preferencesStore.recordCheckResult(
            epochMillis = clock.nowEpochMillis(),
            serverStatus = UpdateServerConnectionStatus.CONNECTED,
            sourceLabel = CloudUpdateManager.GITHUB_SOURCE_LABEL
        )
        return finalizeDetails(manifest, respectSnooze)
    }

    private suspend fun finalizeDetails(
        manifest: CloudUpdateManifest,
        respectSnooze: Boolean
    ): AppResult<UpdateDetails> {
        val isCompatible = manifest.minSupportedAndroidSdk <= currentSdkInt
        val isNewer = GitHubReleasesParser.isRemoteReleaseNewer(
            remoteVersionCode = manifest.versionCode,
            remoteVersionName = manifest.versionName,
            installedVersionCode = currentVersionCode,
            installedVersionName = currentVersionName
        ) && isCompatible

        val details = UpdateDetails(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            apkDownloadUrl = manifest.apkDownloadUrl,
            sha256 = manifest.sha256,
            fileSizeBytes = manifest.fileSizeBytes,
            minSupportedAndroidSdk = manifest.minSupportedAndroidSdk,
            mandatory = manifest.mandatory,
            releaseNotes = manifest.releaseNotesEn.ifBlank { manifest.releaseNotesAr },
            releaseNotesAr = manifest.releaseNotesAr,
            releaseNotesEn = manifest.releaseNotesEn,
            portalUrl = manifest.portalUrl ?: CloudUpdateManager.OFFICIAL_RELEASES_PAGE_URL,
            publishedAt = manifest.publishedAt,
            signingCertSha256 = manifest.signingCertSha256,
            isUpdateAvailable = isNewer
        )

        if (isNewer) {
            preferencesStore.clearSnoozeIfNewer(manifest.versionCode)
            val snapshot = preferencesStore.getSnapshot()
            val isSnoozed = respectSnooze &&
                !manifest.mandatory &&
                snapshot.snoozedVersionCode >= manifest.versionCode
            _detectedUpdate.value = if (isSnoozed) null else details
        } else {
            _detectedUpdate.value = null
        }

        return AppResult.Success(details)
    }

    /**
     * Surfaces an incoming validated update from FCM while the app is open in the foreground,
     * triggering the in-app Update Details Dialog immediately.
     */
    suspend fun onForegroundPushUpdateReceived(details: UpdateDetails) {
        val isNewer = GitHubReleasesParser.isRemoteReleaseNewer(
            remoteVersionCode = details.versionCode,
            remoteVersionName = details.versionName,
            installedVersionCode = currentVersionCode,
            installedVersionName = currentVersionName
        ) && details.minSupportedAndroidSdk <= currentSdkInt
        if (!isNewer) return
        preferencesStore.clearSnoozeIfNewer(details.versionCode)
        _detectedUpdate.value = details.copy(isUpdateAvailable = true)
        _foregroundPushVersionCode.value = details.versionCode
    }

    fun consumeForegroundPushTrigger() {
        _foregroundPushVersionCode.value = null
    }

    /**
     * Dismisses the currently surfaced optional update prompt and records a per-version snooze
     * so the user is not nagged repeatedly for the same version (automatically clears on newer releases).
     * Mandatory updates cannot be dismissed via this call.
     */
    fun dismissOptionalUpdate() {
        val current = _detectedUpdate.value ?: return
        if (!current.mandatory) {
            _detectedUpdate.value = null
            _foregroundPushVersionCode.value = null
        }
    }

    suspend fun snoozeOptionalUpdate() {
        val current = _detectedUpdate.value ?: return
        if (!current.mandatory) {
            preferencesStore.setSnoozedVersionCode(current.versionCode)
            _detectedUpdate.value = null
            _foregroundPushVersionCode.value = null
        }
    }

    private suspend fun fetchTextWithOkHttp(
        url: String,
        maxBytes: Int = CloudUpdateValidator.MAX_MANIFEST_BYTES
    ): AppResult<String> {
        var attempt = 0
        var lastFailure: AppResult.Failure? = null
        while (attempt < MAX_RETRIES) {
            attempt++
            val result = executeSingleGet(url, maxBytes)
            if (result is AppResult.Success) return result
            lastFailure = result as AppResult.Failure
            val isSecurityOrValidation = result.error is AppError.Security || result.error is AppError.Validation
            val netEx = result.error.cause as? UpdateNetworkException
            val retryable = !isSecurityOrValidation && (
                netEx == null ||
                    netEx.kind == UpdateNetworkFailureKind.TIMEOUT ||
                    netEx.kind == UpdateNetworkFailureKind.SERVER_UNAVAILABLE ||
                    netEx.kind == UpdateNetworkFailureKind.IO_ERROR
                )
            if (!retryable || attempt >= MAX_RETRIES) return result
            delay(400L * attempt)
        }
        return lastFailure ?: AppResult.Failure(AppError.Network("Network error while checking for updates."))
    }

    private fun executeSingleGet(url: String, maxBytes: Int): AppResult<String> {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount <= MAX_REDIRECTS) {
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(currentUrl)) {
                return AppResult.Failure(
                    AppError.Security("Blocked non-HTTPS or private network update manifest URL.")
                )
            }

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "application/vnd.github+json, application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ShareFlow-Android/${BuildConfig.VERSION_NAME}")
            if (redirectCount == 0 && currentUrl.contains("api.github.com", ignoreCase = true)) {
                reqBuilder.header("X-GitHub-Api-Version", "2022-11-28")
            }

            val nextRedirectUrl: String? = try {
                okHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    if (!CloudUpdateValidator.isValidPublicHttpsUrl(finalUrl)) {
                        return AppResult.Failure(
                            AppError.Security("Blocked non-HTTPS or private network redirect while fetching version.json.")
                        )
                    }

                    if (response.code in REDIRECT_STATUS_CODES) {
                        val location = response.header("Location")?.trim().orEmpty()
                        if (location.isBlank()) {
                            return AppResult.Failure(
                                AppError.Network("Update server returned empty redirect Location header.")
                            )
                        }
                        val resolved = runCatching {
                            URI(currentUrl).resolve(location).toString()
                        }.getOrNull().orEmpty()
                        if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolved)) {
                            return AppResult.Failure(
                                AppError.Security("Blocked non-HTTPS or private network redirect target.")
                            )
                        }
                        resolved
                    } else if (!response.isSuccessful) {
                        val code = response.code
                        val rateRemaining = response.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
                        val kind = when {
                            code == 429 || (code == 403 && rateRemaining == 0) -> UpdateNetworkFailureKind.RATE_LIMITED
                            code == 404 -> UpdateNetworkFailureKind.RELEASE_NOT_FOUND
                            code in 500..599 -> UpdateNetworkFailureKind.SERVER_UNAVAILABLE
                            else -> UpdateNetworkFailureKind.HTTP_ERROR
                        }
                        return AppResult.Failure(
                            AppError.Network(
                                message = "Update server returned HTTP $code.",
                                cause = UpdateNetworkException(
                                    kind = kind,
                                    httpCode = code,
                                    message = "Update server returned HTTP $code."
                                )
                            )
                        )
                    } else {
                        val body = response.body ?: return AppResult.Failure(
                            AppError.Validation("Empty update manifest response body.")
                        )
                        val contentLength = body.contentLength()
                        if (contentLength > maxBytes) {
                            return AppResult.Failure(
                                AppError.Validation("Update manifest exceeds maximum safe size.")
                            )
                        }
                        val bytes = body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            val out = ByteArrayOutputStream()
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > maxBytes) {
                                    return AppResult.Failure(
                                        AppError.Validation("Update manifest exceeds maximum safe size.")
                                    )
                                }
                                out.write(buffer, 0, read)
                            }
                            out.toByteArray()
                        }
                        return AppResult.Success(bytes.toString(Charsets.UTF_8))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SSLException) {
                return AppResult.Failure(
                    AppError.Network(
                        message = "SSL/TLS verification failed while checking for updates.",
                        cause = UpdateNetworkException(UpdateNetworkFailureKind.SSL_ERROR, message = e.message ?: "SSL error", cause = e)
                    )
                )
            } catch (e: SocketTimeoutException) {
                return AppResult.Failure(
                    AppError.Network(
                        message = "Connection timed out while checking for updates.",
                        cause = UpdateNetworkException(UpdateNetworkFailureKind.TIMEOUT, message = e.message ?: "Timeout", cause = e)
                    )
                )
            } catch (e: UnknownHostException) {
                return AppResult.Failure(
                    AppError.Network(
                        message = "Unable to resolve update host.",
                        cause = UpdateNetworkException(UpdateNetworkFailureKind.DNS_ERROR, message = e.message ?: "DNS error", cause = e)
                    )
                )
            } catch (e: IOException) {
                return AppResult.Failure(
                    AppError.Network(
                        message = e.message ?: "Network error while checking for updates.",
                        cause = UpdateNetworkException(UpdateNetworkFailureKind.IO_ERROR, message = e.message ?: "IO error", cause = e)
                    )
                )
            } catch (e: Exception) {
                return AppResult.Failure(AppError.Network(e.message ?: "Unexpected error while checking for updates."))
            }

            if (nextRedirectUrl != null) {
                currentUrl = nextRedirectUrl
                redirectCount++
            }
        }

        return AppResult.Failure(
            AppError.Network("Too many redirects while fetching version.json.")
        )
    }

    companion object {
        private const val MAX_REDIRECTS = 6
        private const val MAX_RETRIES = 2
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        fun createDefaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
