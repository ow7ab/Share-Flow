package com.example.shareflow.data.update

import android.os.Build
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Strongly-typed update details returned by [UpdateManager] after fetching and validating
 * `version.json` from the official ShareFlow update website.
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
 * Background update-check service powered by [OkHttpClient] that fetches `version.json`
 * from [BuildConfig.CLOUD_UPDATE_MANIFEST_URL], validates HTTPS & SHA-256 manifest fields,
 * compares `versionCode` against the installed build, and publishes newer versions via
 * [detectedUpdate] so [com.example.shareflow.ui.update.UpdateAvailableDialog] triggers automatically.
 */
class UpdateManager(
    private val okHttpClient: OkHttpClient = createDefaultOkHttpClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val manifestUrl: String = BuildConfig.CLOUD_UPDATE_MANIFEST_URL,
    private val fallbackManifestUrl: String = CloudUpdateManager.DEFAULT_FALLBACK_MANIFEST_URL,
    private val currentVersionCode: Int = BuildConfig.VERSION_CODE,
    private val currentSdkInt: Int = Build.VERSION.SDK_INT
) {
    private val _detectedUpdate = MutableStateFlow<UpdateDetails?>(null)
    val detectedUpdate: StateFlow<UpdateDetails?> = _detectedUpdate.asStateFlow()

    /**
     * Fetches `version.json` on [dispatcher], parses and validates it, compares `versionCode`
     * with [currentVersionCode], and returns [UpdateDetails].
     *
     * When a newer compatible release is detected (`versionCode > currentVersionCode` and
     * `minSupportedAndroidSdk <= currentSdkInt`), [detectedUpdate] is automatically populated.
     */
    suspend fun checkForUpdate(): AppResult<UpdateDetails> = withContext(dispatcher) {
        val primaryUrl = manifestUrl.trim()
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(primaryUrl)) {
            return@withContext AppResult.Failure(
                AppError.Security("Official update manifest URL must be a valid public HTTPS URL.")
            )
        }

        val primaryResult = fetchManifestWithOkHttp(primaryUrl)
        val rawJsonResult = if (
            primaryResult is AppResult.Failure &&
            fallbackManifestUrl.isNotBlank() &&
            !fallbackManifestUrl.equals(primaryUrl, ignoreCase = true) &&
            CloudUpdateValidator.isValidPublicHttpsUrl(fallbackManifestUrl)
        ) {
            fetchManifestWithOkHttp(fallbackManifestUrl)
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

        val isCompatible = manifest.minSupportedAndroidSdk <= currentSdkInt
        val isNewer = manifest.versionCode > currentVersionCode && isCompatible

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
            portalUrl = manifest.portalUrl ?: CloudUpdateManager.OFFICIAL_PORTAL_URL,
            publishedAt = manifest.publishedAt,
            signingCertSha256 = manifest.signingCertSha256,
            isUpdateAvailable = isNewer
        )

        _detectedUpdate.value = if (isNewer) details else null
        AppResult.Success(details)
    }

    /**
     * Dismisses the currently surfaced optional update prompt.
     * Mandatory updates cannot be dismissed via this call.
     */
    fun dismissOptionalUpdate() {
        val current = _detectedUpdate.value ?: return
        if (!current.mandatory) {
            _detectedUpdate.value = null
        }
    }

    private fun fetchManifestWithOkHttp(url: String): AppResult<String> {
        var currentUrl = url
        var redirectCount = 0

        while (redirectCount <= MAX_REDIRECTS) {
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(currentUrl)) {
                return AppResult.Failure(
                    AppError.Security("Blocked non-HTTPS or private network update manifest URL.")
                )
            }

            val request = Request.Builder()
                .url(currentUrl)
                .get()
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ShareFlow-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val nextRedirectUrl: String? = try {
                okHttpClient.newCall(request).execute().use { response ->
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
                            java.net.URI(currentUrl).resolve(location).toString()
                        }.getOrNull().orEmpty()
                        if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolved)) {
                            return AppResult.Failure(
                                AppError.Security("Blocked non-HTTPS or private network redirect target.")
                            )
                        }
                        resolved
                    } else if (!response.isSuccessful) {
                        return AppResult.Failure(
                            AppError.Network("Update server returned HTTP ${response.code}.")
                        )
                    } else {
                        val body = response.body ?: return AppResult.Failure(
                            AppError.Validation("Empty update manifest response body.")
                        )
                        val contentLength = body.contentLength()
                        if (contentLength > CloudUpdateValidator.MAX_MANIFEST_BYTES) {
                            return AppResult.Failure(
                                AppError.Validation("Update manifest exceeds maximum safe size.")
                            )
                        }
                        val bytes = body.byteStream().use { input ->
                            val buffer = ByteArray(4096)
                            val out = java.io.ByteArrayOutputStream()
                            var total = 0
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > CloudUpdateValidator.MAX_MANIFEST_BYTES) {
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
            } catch (e: IOException) {
                return AppResult.Failure(AppError.Network(e.message ?: "Network error while checking for updates."))
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
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        fun createDefaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
