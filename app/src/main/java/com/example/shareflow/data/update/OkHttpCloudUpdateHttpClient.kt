package com.example.shareflow.data.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.DispatcherProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Classifies network/HTTP failures with high precision so the UI can show actionable diagnostics
 * instead of collapsing every failure into a generic "network failed" message.
 */
enum class UpdateNetworkFailureKind {
    NO_INTERNET,
    DNS_ERROR,
    TIMEOUT,
    SSL_ERROR,
    RATE_LIMITED,
    RELEASE_NOT_FOUND,
    SERVER_UNAVAILABLE,
    REDIRECT_BLOCKED,
    HTTP_ERROR,
    IO_ERROR
}

class UpdateNetworkException(
    val kind: UpdateNetworkFailureKind,
    val httpCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * OkHttp-backed cloud update HTTP client featuring:
 * - Public HTTPS-only redirect validation up to 6 hops (GitHub -> CDN/S3)
 * - Bounded exponential backoff retry for transient 5xx/timeout/socket blips
 * - Rate-limit detection (HTTP 429 & GitHub HTTP 403 X-RateLimit-Remaining: 0)
 * - Resumable APK download via HTTP Range (206 Partial Content) preserving `.part` files on pause
 * - Strict protection against deleting previously verified APKs on failed downloads
 */
class OkHttpCloudUpdateHttpClient(
    private val dispatchers: DispatcherProvider,
    private val context: Context? = null,
    private val okHttpClient: OkHttpClient = createDefaultClient(),
    private val maxRetryAttempts: Int = DEFAULT_MAX_RETRIES,
    private val baseRetryDelayMs: Long = DEFAULT_BASE_RETRY_DELAY_MS
) : CloudUpdateHttpClient {

    override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> =
        fetchText(manifestUrl = manifestUrl, maxBytes = MAX_API_RESPONSE_BYTES)

    suspend fun fetchText(
        manifestUrl: String,
        maxBytes: Int = MAX_API_RESPONSE_BYTES,
        extraHeaders: Map<String, String> = emptyMap()
    ): AppResult<String> = withContext(dispatchers.io) {
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(manifestUrl)) {
            return@withContext AppResult.Failure(
                AppError.Security("Only public HTTPS URLs are permitted.")
            )
        }
        if (isDefinitelyOffline()) {
            return@withContext AppResult.Failure(
                AppError.Network(
                    message = "No active internet connection.",
                    cause = UpdateNetworkException(
                        kind = UpdateNetworkFailureKind.NO_INTERNET,
                        message = "No active internet connection."
                    )
                )
            )
        }

        var attempt = 0
        var lastFailure: AppResult.Failure? = null

        while (attempt < maxRetryAttempts) {
            currentCoroutineContext().ensureActive()
            attempt++
            val result = executeValidatedGetText(manifestUrl, maxBytes, extraHeaders)
            if (result is AppResult.Success) {
                return@withContext result
            }
            lastFailure = result as AppResult.Failure
            val networkEx = result.error.cause as? UpdateNetworkException
            val shouldRetry = attempt < maxRetryAttempts && isRetryableFailure(networkEx, result.error)
            if (!shouldRetry) {
                return@withContext result
            }
            val delayMs = computeBackoffMs(attempt, networkEx?.retryAfterSeconds)
            Log.d(TAG, "Transient update request error (attempt $attempt/$maxRetryAttempts), retrying in ${delayMs}ms")
            delay(delayMs)
        }

        lastFailure ?: AppResult.Failure(AppError.Network("Unable to reach update server."))
    }

    override suspend fun downloadApkToFile(
        apkUrl: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppResult<File> = downloadApkResumable(
        apkUrl = apkUrl,
        destinationFile = destinationFile,
        resumeFromExistingPart = true,
        onProgress = onProgress
    )

    /**
     * Streams the APK into `<destinationFile>.part`, resuming with HTTP `Range: bytes=<offset>-`
     * when a valid partial file exists. On cancellation (pause), keeps `.part` intact; on
     * transient network errors, retries automatically with backoff without touching any
     * previously verified [destinationFile].
     */
    suspend fun downloadApkResumable(
        apkUrl: String,
        destinationFile: File,
        resumeFromExistingPart: Boolean = true,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppResult<File> = withContext(dispatchers.io) {
        if (!CloudUpdateValidator.isValidPublicHttpsUrl(apkUrl)) {
            return@withContext AppResult.Failure(AppError.Security("APK download URL must use public HTTPS."))
        }
        if (isDefinitelyOffline()) {
            return@withContext AppResult.Failure(
                AppError.Network(
                    message = "No active internet connection.",
                    cause = UpdateNetworkException(
                        kind = UpdateNetworkFailureKind.NO_INTERNET,
                        message = "No active internet connection."
                    )
                )
            )
        }
        val parent = destinationFile.parentFile
            ?: return@withContext AppResult.Failure(AppError.Storage("Invalid update cache destination."))
        parent.mkdirs()

        val tempPartFile = File(parent, "${destinationFile.name}.part")
        if (!resumeFromExistingPart && tempPartFile.exists()) {
            tempPartFile.delete()
        }

        var attempt = 0
        var lastFailure: AppResult.Failure? = null

        while (attempt < maxRetryAttempts) {
            currentCoroutineContext().ensureActive()
            attempt++
            val existingOffset = if (resumeFromExistingPart && tempPartFile.exists() && tempPartFile.isFile) {
                tempPartFile.length().coerceAtLeast(0L)
            } else {
                0L
            }

            val headers = buildMap {
                put("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                if (existingOffset > 0L) {
                    put("Range", "bytes=$existingOffset-")
                }
            }

            val attemptResult = try {
                executeValidatedDownloadAttempt(
                    initialUrl = apkUrl,
                    headers = headers,
                    tempPartFile = tempPartFile,
                    destinationFile = destinationFile,
                    existingOffset = existingOffset,
                    onProgress = onProgress
                )
            } catch (ce: CancellationException) {
                // Keep tempPartFile intact so user can seamlessly Resume after Pause!
                throw ce
            }

            if (attemptResult is AppResult.Success) {
                return@withContext attemptResult
            }

            lastFailure = attemptResult as AppResult.Failure
            val networkEx = attemptResult.error.cause as? UpdateNetworkException
            if (attempt < maxRetryAttempts && isRetryableFailure(networkEx, attemptResult.error)) {
                val delayMs = computeBackoffMs(attempt, networkEx?.retryAfterSeconds)
                Log.d(TAG, "APK download interrupted (attempt $attempt/$maxRetryAttempts), resuming in ${delayMs}ms")
                delay(delayMs)
            } else {
                return@withContext attemptResult
            }
        }

        lastFailure ?: AppResult.Failure(AppError.Network("Download interrupted."))
    }

    private suspend fun executeValidatedDownloadAttempt(
        initialUrl: String,
        headers: Map<String, String>,
        tempPartFile: File,
        destinationFile: File,
        existingOffset: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): AppResult<File> {
        val responseResult = openValidatedResponse(initialUrl, headers)
        val response = when (responseResult) {
            is AppResult.Success -> responseResult.data
            is AppResult.Failure -> return responseResult
        }

        return response.use { res ->
            try {
                val code = res.code
                // HTTP 416 Range Not Satisfiable -> clear stale .part file and signal clean restart
                if (code == 416) {
                    tempPartFile.delete()
                    return AppResult.Failure(
                        AppError.Network(
                            message = "Partial download range expired; restarting cleanly.",
                            cause = UpdateNetworkException(
                                kind = UpdateNetworkFailureKind.SERVER_UNAVAILABLE,
                                httpCode = 416,
                                message = "HTTP 416 Range Not Satisfiable"
                            )
                        )
                    )
                }
                if (code !in 200..299) {
                    return mapHttpErrorResponse(res)
                }

                val isPartialAppend = (code == 206 && existingOffset > 0L)
                val body = res.body ?: return AppResult.Failure(
                    AppError.Network("Empty APK response body.")
                )
                val bodyLength = body.contentLength()
                val totalExpectedBytes = when {
                    isPartialAppend -> parseTotalFromContentRange(res.header("Content-Range"))
                        ?: if (bodyLength > 0L) existingOffset + bodyLength else -1L
                    bodyLength > 0L -> bodyLength
                    else -> -1L
                }
                if (totalExpectedBytes > CloudUpdateValidator.MAX_APK_BYTES) {
                    return AppResult.Failure(AppError.Validation("APK size exceeds safe 250 MB limit."))
                }

                var downloaded = if (isPartialAppend) existingOffset else 0L
                if (!isPartialAppend && tempPartFile.exists()) {
                    tempPartFile.delete()
                }
                var lastReportAt = 0L
                onProgress(downloaded, totalExpectedBytes)

                body.byteStream().use { input ->
                    FileOutputStream(tempPartFile, isPartialAppend).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            downloaded += read
                            if (downloaded > CloudUpdateValidator.MAX_APK_BYTES) {
                                tempPartFile.delete()
                                return AppResult.Failure(
                                    AppError.Validation("Downloaded APK exceeded safe 250 MB limit.")
                                )
                            }
                            output.write(buffer, 0, read)
                            val now = System.currentTimeMillis()
                            if (now - lastReportAt >= 120L) {
                                lastReportAt = now
                                onProgress(downloaded, totalExpectedBytes)
                            }
                        }
                        output.fd.sync()
                    }
                }

                onProgress(downloaded, if (totalExpectedBytes > 0L) totalExpectedBytes else downloaded)
                if (destinationFile.exists()) destinationFile.delete()
                if (!tempPartFile.renameTo(destinationFile)) {
                    tempPartFile.copyTo(destinationFile, overwrite = true)
                    tempPartFile.delete()
                }
                AppResult.Success(destinationFile)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                classifyThrowableFailure(t)
            }
        }
    }

    private suspend fun executeValidatedGetText(
        initialUrl: String,
        maxBytes: Int,
        extraHeaders: Map<String, String>
    ): AppResult<String> {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json, application/json;q=0.9, text/plain;q=0.5, */*;q=0.1")
            put("X-GitHub-Api-Version", "2022-11-28")
            put("Cache-Control", "no-cache")
            putAll(extraHeaders)
        }
        val responseResult = openValidatedResponse(initialUrl, headers)
        val response = when (responseResult) {
            is AppResult.Success -> responseResult.data
            is AppResult.Failure -> return responseResult
        }

        return response.use { res ->
            try {
                if (!res.isSuccessful) {
                    return mapHttpErrorResponse(res)
                }
                val body = res.body ?: return AppResult.Failure(
                    AppError.Validation("Empty response body from update server.")
                )
                val declaredLength = body.contentLength()
                if (declaredLength > maxBytes) {
                    return AppResult.Failure(
                        AppError.Validation("Update response exceeds $maxBytes byte safety limit.")
                    )
                }
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(4096)
                    var total = 0
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        if (total > maxBytes) {
                            return AppResult.Failure(
                                AppError.Validation("Update response exceeds $maxBytes byte safety limit.")
                            )
                        }
                        out.write(buf, 0, read)
                    }
                }
                AppResult.Success(out.toString(Charsets.UTF_8.name()))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                classifyThrowableFailure(t)
            }
        }
    }

    private fun openValidatedResponse(
        initialUrl: String,
        headers: Map<String, String>
    ): AppResult<Response> {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            if (!CloudUpdateValidator.isValidPublicHttpsUrl(currentUrl)) {
                return AppResult.Failure(
                    AppError.Security(
                        message = "Redirect blocked: non-HTTPS or disallowed host.",
                        cause = UpdateNetworkException(
                            kind = UpdateNetworkFailureKind.REDIRECT_BLOCKED,
                            message = "Redirect blocked: non-HTTPS or disallowed host."
                        )
                    )
                )
            }

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .get()
                .header("User-Agent", "ShareFlow-Android/${BuildConfig.VERSION_NAME}")
            val isGitHubApiHost = currentUrl.contains("://api.github.com/", ignoreCase = true)
            headers.forEach { (k, v) ->
                // Send GitHub API version header strictly to api.github.com (never CDN/Azure storage hops)
                if (!isGitHubApiHost && k.equals("X-GitHub-Api-Version", ignoreCase = true)) {
                    return@forEach
                }
                reqBuilder.header(k, v)
            }

            val response = try {
                okHttpClient.newCall(reqBuilder.build()).execute()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                return classifyThrowableFailure(t)
            }

            if (response.code in REDIRECT_STATUS_CODES) {
                val location = response.header("Location")?.trim().orEmpty()
                val requestUrl = response.request.url
                response.close()
                if (location.isBlank()) {
                    return AppResult.Failure(
                        AppError.Network(
                            message = "Update server returned empty redirect Location header.",
                            cause = UpdateNetworkException(
                                kind = UpdateNetworkFailureKind.REDIRECT_BLOCKED,
                                httpCode = response.code,
                                message = "Empty redirect Location header"
                            )
                        )
                    )
                }
                val resolved = requestUrl.resolve(location)?.toString().orEmpty()
                if (!CloudUpdateValidator.isValidPublicHttpsUrl(resolved)) {
                    return AppResult.Failure(
                        AppError.Security(
                            message = "Blocked insecure or non-HTTPS redirect target.",
                            cause = UpdateNetworkException(
                                kind = UpdateNetworkFailureKind.REDIRECT_BLOCKED,
                                httpCode = response.code,
                                message = "Blocked non-HTTPS redirect target"
                            )
                        )
                    )
                }
                currentUrl = resolved
                redirects++
                continue
            }

            return AppResult.Success(response)
        }

        return AppResult.Failure(
            AppError.Network(
                message = "Too many redirects while contacting update server.",
                cause = UpdateNetworkException(
                    kind = UpdateNetworkFailureKind.REDIRECT_BLOCKED,
                    message = "Too many redirects ($MAX_REDIRECTS)"
                )
            )
        )
    }

    private fun mapHttpErrorResponse(response: Response): AppResult.Failure {
        val code = response.code
        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()
        val rateRemaining = response.header("X-RateLimit-Remaining")?.trim()?.toIntOrNull()
        val isRateLimited = code == 429 || (code == 403 && rateRemaining == 0)

        val kind = when {
            isRateLimited -> UpdateNetworkFailureKind.RATE_LIMITED
            code == 404 -> UpdateNetworkFailureKind.RELEASE_NOT_FOUND
            code in 500..599 -> UpdateNetworkFailureKind.SERVER_UNAVAILABLE
            else -> UpdateNetworkFailureKind.HTTP_ERROR
        }
        val msg = when (kind) {
            UpdateNetworkFailureKind.RATE_LIMITED ->
                "GitHub API rate limit reached (HTTP $code). Please retry shortly."
            UpdateNetworkFailureKind.RELEASE_NOT_FOUND ->
                "No published release found at update endpoint (HTTP 404)."
            UpdateNetworkFailureKind.SERVER_UNAVAILABLE ->
                "Update server temporarily unavailable (HTTP $code)."
            else -> "Update server returned HTTP $code."
        }
        return AppResult.Failure(
            AppError.Network(
                message = msg,
                cause = UpdateNetworkException(
                    kind = kind,
                    httpCode = code,
                    retryAfterSeconds = retryAfter,
                    message = msg
                )
            )
        )
    }

    private fun classifyThrowableFailure(t: Throwable): AppResult.Failure {
        val (kind, msg) = when (t) {
            is SSLException ->
                UpdateNetworkFailureKind.SSL_ERROR to "Secure TLS/SSL handshake failed: ${t.message ?: "certificate error"}"
            is SocketTimeoutException ->
                UpdateNetworkFailureKind.TIMEOUT to "Connection timed out while contacting update server."
            is UnknownHostException -> {
                if (isDefinitelyOffline()) {
                    UpdateNetworkFailureKind.NO_INTERNET to "No active internet connection."
                } else {
                    UpdateNetworkFailureKind.DNS_ERROR to "DNS lookup failed for update server."
                }
            }
            is SecurityException ->
                UpdateNetworkFailureKind.REDIRECT_BLOCKED to (t.message ?: "Blocked unsafe redirect.")
            else ->
                UpdateNetworkFailureKind.IO_ERROR to (t.message ?: "Unable to reach update server.")
        }
        val error = if (kind == UpdateNetworkFailureKind.REDIRECT_BLOCKED) {
            AppError.Security(msg, UpdateNetworkException(kind = kind, message = msg, cause = t))
        } else {
            AppError.Network(msg, UpdateNetworkException(kind = kind, message = msg, cause = t))
        }
        return AppResult.Failure(error)
    }

    private fun isRetryableFailure(networkEx: UpdateNetworkException?, error: AppError): Boolean {
        if (error is AppError.Security || error is AppError.Validation) return false
        return when (networkEx?.kind) {
            UpdateNetworkFailureKind.TIMEOUT,
            UpdateNetworkFailureKind.SERVER_UNAVAILABLE,
            UpdateNetworkFailureKind.IO_ERROR -> true
            UpdateNetworkFailureKind.NO_INTERNET,
            UpdateNetworkFailureKind.DNS_ERROR,
            UpdateNetworkFailureKind.SSL_ERROR,
            UpdateNetworkFailureKind.RATE_LIMITED,
            UpdateNetworkFailureKind.RELEASE_NOT_FOUND,
            UpdateNetworkFailureKind.REDIRECT_BLOCKED,
            UpdateNetworkFailureKind.HTTP_ERROR -> false
            null -> error is AppError.Network
        }
    }

    private fun computeBackoffMs(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null && retryAfterSeconds in 1..5) {
            return retryAfterSeconds * 1000L
        }
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 4)
        return (baseRetryDelayMs * multiplier).coerceAtMost(5_000L)
    }

    private fun parseTotalFromContentRange(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex < 0 || slashIndex >= contentRange.lastIndex) return null
        return contentRange.substring(slashIndex + 1).trim().toLongOrNull()?.takeIf { it > 0L }
    }

    private fun isDefinitelyOffline(): Boolean {
        val ctx = context ?: return false
        return runCatching {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return true
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "ShareFlowUpdateHttp"
        const val MAX_REDIRECTS = 6
        const val MAX_API_RESPONSE_BYTES = 512 * 1024
        private const val DEFAULT_MAX_RETRIES = 3
        private const val DEFAULT_BASE_RETRY_DELAY_MS = 500L
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        fun createDefaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }
}
