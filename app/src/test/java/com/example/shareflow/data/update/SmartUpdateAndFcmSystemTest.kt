package com.example.shareflow.data.update

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.designsystem.theme.ShareFlowTheme
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.update.fcm.FcmTopicBackend
import com.example.shareflow.data.update.fcm.FcmUpdatePayload
import com.example.shareflow.data.update.fcm.FcmUpdateTopicCoordinator
import com.example.shareflow.data.update.fcm.PushNotificationHandlingOutcome
import com.example.shareflow.data.update.fcm.ShareFlowUpdateNotificationManager
import com.example.shareflow.domain.model.ThemeMode
import com.example.shareflow.navigation.ShareFlowRoutes
import com.example.shareflow.testutil.TestDispatcherProvider
import com.example.shareflow.ui.update.UpdateAvailableDialog
import com.example.shareflow.ui.update.UpdatesScreenContent
import java.io.File
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SmartUpdateAndFcmSystemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sampleApkBytes = "PK\u0003\u0004shareflow-resumable-verified-apk-content-1234567890".toByteArray(Charsets.UTF_8)
    private val sampleApkSha256: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(sampleApkBytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun buildClientWithInterceptor(
        interceptor: Interceptor,
        maxRetryAttempts: Int = 3,
        dispatcher: TestDispatcher
    ): OkHttpCloudUpdateHttpClient {
        val okHttp = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(interceptor)
            .build()
        return OkHttpCloudUpdateHttpClient(
            dispatchers = TestDispatcherProvider(dispatcher),
            context = null,
            okHttpClient = okHttp,
            maxRetryAttempts = maxRetryAttempts,
            baseRetryDelayMs = 5L
        )
    }

    private fun okResponse(
        chain: Interceptor.Chain,
        code: Int = 200,
        body: ByteArray = ByteArray(0),
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "ERR")
            .body(body.toResponseBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    @Test
    fun `GitHubReleasesParser parses uppercase V tags, native sha256 digest, filters drafts and prereleases`() {
        val rawArray = """
            [
              {
                "id": 100,
                "tag_name": "v1.0.21-draft",
                "name": "Draft Release",
                "draft": true,
                "prerelease": false,
                "published_at": "2026-11-25T10:00:00Z",
                "body": "Draft notes",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/v1.0.21-draft",
                "assets": []
              },
              {
                "id": 99,
                "tag_name": "V1.0.20",
                "name": "Share Flow V1.0.20 (Build 21)",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-25T09:00:00Z",
                "body": "تحسينات شاملة لنظام التحديثات والإشعارات.",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.0.20",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "size": ${sampleApkBytes.size},
                    "digest": "sha256:$sampleApkSha256",
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/V1.0.20/Share-Flow.apk"
                  },
                  {
                    "name": "version.json",
                    "size": 512,
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/V1.0.20/version.json"
                  }
                ]
              },
              {
                "id": 98,
                "tag_name": "V1.0.12",
                "name": "Beta 1.0.12",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-20T09:00:00Z",
                "body": "Prerelease notes",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.0.12",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "size": 1000,
                    "digest": "sha256:$sampleApkSha256",
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/V1.0.12/Share-Flow.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = GitHubReleasesParser.parseReleasesArray(rawArray)
        assertEquals("Draft releases must be filtered out", 2, parsed.size)
        val stable = parsed[0]
        val prerelease = parsed[1]

        assertEquals("1.0.20", stable.versionName)
        assertEquals(sampleApkSha256, stable.apkAsset?.sha256Digest)
        assertNotNull(stable.versionJsonAssetUrl)

        val manifest = GitHubReleasesParser.toCloudUpdateManifest(
            release = stable,
            currentVersionCode = 19,
            currentVersionName = "1.0.18"
        )
        assertNotNull(manifest)
        assertEquals("1.0.20", manifest!!.versionName)
        assertEquals(sampleApkSha256, manifest.sha256)
        assertTrue(manifest.versionCode > 19)

        // Prerelease must NOT become a production update manifest
        assertNull(
            GitHubReleasesParser.toCloudUpdateManifest(
                release = prerelease,
                currentVersionCode = 19,
                currentVersionName = "1.0.18"
            )
        )

        assertTrue(GitHubReleasesParser.isRemoteReleaseNewer(20, "1.0.19", 19, "1.0.18"))
        assertFalse(GitHubReleasesParser.isRemoteReleaseNewer(19, "1.0.18", 19, "1.0.18"))
        assertTrue(GitHubReleasesParser.compareSemanticVersions("V1.0.12", "v1.0.11") > 0)
    }

    @Test
    fun `OkHttpCloudUpdateHttpClient follows 5 HTTPS redirects, blocks cleartext and private IP redirects`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val hopClient = buildClientWithInterceptor(
            dispatcher = dispatcher,
            interceptor = Interceptor { chain ->
                val url = chain.request().url.toString()
                when {
                    url.endsWith("/hop0") -> okResponse(chain, 302, headers = mapOf("Location" to "https://cdn.github.com/hop1"))
                    url.endsWith("/hop1") -> okResponse(chain, 302, headers = mapOf("Location" to "https://cdn.github.com/hop2"))
                    url.endsWith("/hop2") -> okResponse(chain, 302, headers = mapOf("Location" to "https://cdn.github.com/hop3"))
                    url.endsWith("/hop3") -> okResponse(chain, 302, headers = mapOf("Location" to "https://cdn.github.com/hop4"))
                    url.endsWith("/hop4") -> okResponse(chain, 302, headers = mapOf("Location" to "https://cdn.github.com/hop5"))
                    url.endsWith("/hop5") -> okResponse(chain, 200, body = """{"ok":true}""".toByteArray())
                    url.endsWith("/cleartext") -> okResponse(chain, 301, headers = mapOf("Location" to "http://shareflows.org/version.json"))
                    url.endsWith("/ssrf") -> okResponse(chain, 302, headers = mapOf("Location" to "https://192.168.1.5/evil.apk"))
                    else -> okResponse(chain, 404)
                }
            }
        )

        val ok = hopClient.fetchManifestText("https://api.github.com/hop0")
        assertTrue("5 HTTPS redirects should succeed within 6-hop cap", ok is AppResult.Success)
        assertEquals("""{"ok":true}""", (ok as AppResult.Success).data)

        val cleartext = hopClient.fetchManifestText("https://ow7ab.github.io/cleartext")
        assertTrue(cleartext is AppResult.Failure)
        val cleartextEx = (cleartext as AppResult.Failure).error.cause as? UpdateNetworkException
        assertEquals(UpdateNetworkFailureKind.REDIRECT_BLOCKED, cleartextEx?.kind)

        val ssrf = hopClient.fetchManifestText("https://api.github.com/ssrf")
        assertTrue(ssrf is AppResult.Failure)
        val ssrfEx = (ssrf as AppResult.Failure).error.cause as? UpdateNetworkException
        assertEquals(UpdateNetworkFailureKind.REDIRECT_BLOCKED, ssrfEx?.kind)
    }

    @Test
    fun `OkHttpCloudUpdateHttpClient retries transient 503 and classifies 429, 404, timeout, and SSL cleanly`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var transientHits = 0
        val client = buildClientWithInterceptor(
            dispatcher = dispatcher,
            interceptor = Interceptor { chain ->
                when (chain.request().url.encodedPath) {
                    "/flaky" -> {
                        transientHits++
                        if (transientHits < 3) okResponse(chain, 503)
                        else okResponse(chain, 200, body = "[]".toByteArray())
                    }
                    "/ratelimit" -> okResponse(chain, 403, headers = mapOf("X-RateLimit-Remaining" to "0"))
                    "/missing" -> okResponse(chain, 404)
                    "/timeout" -> throw SocketTimeoutException("read timed out")
                    "/ssl" -> throw SSLHandshakeException("untrusted cert")
                    else -> okResponse(chain, 500)
                }
            }
        )

        val flaky = client.fetchManifestText("https://api.github.com/flaky")
        assertTrue("Transient 503 should recover after retries", flaky is AppResult.Success)
        assertEquals(3, transientHits)

        val rateLimit = client.fetchManifestText("https://api.github.com/ratelimit") as AppResult.Failure
        assertEquals(UpdateNetworkFailureKind.RATE_LIMITED, (rateLimit.error.cause as? UpdateNetworkException)?.kind)

        val missing = client.fetchManifestText("https://api.github.com/missing") as AppResult.Failure
        assertEquals(UpdateNetworkFailureKind.RELEASE_NOT_FOUND, (missing.error.cause as? UpdateNetworkException)?.kind)

        val timeout = client.fetchManifestText("https://api.github.com/timeout") as AppResult.Failure
        assertEquals(UpdateNetworkFailureKind.TIMEOUT, (timeout.error.cause as? UpdateNetworkException)?.kind)

        val ssl = client.fetchManifestText("https://api.github.com/ssl") as AppResult.Failure
        assertEquals(UpdateNetworkFailureKind.SSL_ERROR, (ssl.error.cause as? UpdateNetworkException)?.kind)
    }

    @Test
    fun `OkHttpCloudUpdateHttpClient resumes partial APK via HTTP 206 and preserves existing verified APK on failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val tempDir = File(context.cacheDir, "smart_update_resume_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val destFile = File(tempDir, "shareflow-update.apk")
        val partFile = File(tempDir, "shareflow-update.apk.part")

        // Seed existing verified APK to prove failed downloads never erase it
        val previousVerifiedPayload = "previous-good-apk".toByteArray()
        destFile.writeBytes(previousVerifiedPayload)

        // Seed partial download (.part) with first 16 bytes
        val splitPoint = 16
        partFile.writeBytes(sampleApkBytes.copyOfRange(0, splitPoint))

        var receivedRangeHeader: String? = null
        val resumeClient = buildClientWithInterceptor(
            dispatcher = dispatcher,
            interceptor = Interceptor { chain ->
                if (chain.request().url.encodedPath.endsWith("/fail.apk")) {
                    okResponse(chain, 404)
                } else {
                    receivedRangeHeader = chain.request().header("Range")
                    val remaining = sampleApkBytes.copyOfRange(splitPoint, sampleApkBytes.size)
                    okResponse(
                        chain = chain,
                        code = 206,
                        body = remaining,
                        contentType = "application/vnd.android.package-archive",
                        headers = mapOf(
                            "Content-Range" to "bytes $splitPoint-${sampleApkBytes.size - 1}/${sampleApkBytes.size}"
                        )
                    )
                }
            }
        )

        // 1. Failing download must NOT delete previously verified destFile
        val failed = resumeClient.downloadApkResumable(
            apkUrl = "https://github.com/ow7ab/Share-Flow/fail.apk",
            destinationFile = destFile,
            resumeFromExistingPart = true,
            onProgress = { _, _ -> }
        )
        assertTrue(failed is AppResult.Failure)
        assertTrue("Previously verified APK must remain intact", destFile.exists())
        assertEquals(previousVerifiedPayload.toList(), destFile.readBytes().toList())

        // 2. Resuming from .part sends Range header and reconstructs exact full APK bytes
        val resumed = resumeClient.downloadApkResumable(
            apkUrl = "https://github.com/ow7ab/Share-Flow/Share-Flow.apk",
            destinationFile = destFile,
            resumeFromExistingPart = true,
            onProgress = { _, _ -> }
        )
        assertTrue("HTTP 206 resume should succeed: $resumed", resumed is AppResult.Success)
        assertEquals("bytes=$splitPoint-", receivedRangeHeader)
        assertEquals(sampleApkBytes.toList(), destFile.readBytes().toList())
        assertFalse(".part temp file should be promoted cleanly", partFile.exists())
        tempDir.deleteRecursively()
    }

    @Test
    fun `CloudUpdateManager treats prerelease-only GitHub Releases repo as UpToDate CONNECTED and populates history`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = InMemoryUpdatePreferencesStore()
        val prereleaseOnlyJson = """
            [
              {
                "id": 1,
                "tag_name": "V1.0.12",
                "name": "V1.0.12",
                "draft": false,
                "prerelease": true,
                "published_at": "2026-11-22T09:00:00Z",
                "body": "Beta test release",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.0.12",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "size": ${sampleApkBytes.size},
                    "digest": "sha256:$sampleApkSha256",
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/V1.0.12/Share-Flow.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val httpClient = object : CloudUpdateHttpClient {
            override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> {
                return if (manifestUrl.contains("api.github.com")) {
                    AppResult.Success(prereleaseOnlyJson)
                } else {
                    AppResult.Failure(AppError.Security("Blocked http://shareflows.org redirect"))
                }
            }

            override suspend fun downloadApkToFile(
                apkUrl: String,
                destinationFile: File,
                onProgress: (Long, Long) -> Unit
            ): AppResult<File> = AppResult.Success(destinationFile)
        }

        val passInspector = object : ApkPackageSecurityInspector {
            override fun inspectUpdateApk(
                apkFile: File,
                expectedPackageName: String,
                expectedMinVersionCode: Int,
                currentInstalledVersionCode: Int
            ): AppResult<Unit> = AppResult.Success(Unit)
        }

        val manager = CloudUpdateManager(
            context = context,
            dispatchers = TestDispatcherProvider(dispatcher),
            permissionManager = PermissionManager(context),
            httpClient = httpClient,
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = passInspector,
            preferencesStore = prefs,
            currentVersionCode = 19,
            currentVersionName = "1.0.18",
            enableGitHubReleasesPrimary = true
        )

        val state = manager.checkForUpdates("")
        assertTrue("Prerelease-only repo must resolve as UpToDate, not Network Error, got $state", state is CloudUpdateState.UpToDate)
        assertEquals(1, manager.releaseHistory.value.size)
        assertEquals("V1.0.12", manager.releaseHistory.value.first().tagName)
        assertEquals(UpdateServerConnectionStatus.CONNECTED, prefs.getSnapshot().serverStatus)
        assertTrue(prefs.getSnapshot().lastCheckEpochMillis > 0L)
    }

    @Test
    fun `UpdateManager enforces 6-hour periodic check interval and per-version snooze semantics`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = InMemoryUpdatePreferencesStore()
        var nowMs = 1_000_000_000L
        var networkCalls = 0
        var servedVersionCode = 20
        var servedVersionName = "1.0.19"

        val okHttp = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor { chain ->
                networkCalls++
                val body = """
                    {
                      "versionCode": $servedVersionCode,
                      "versionName": "$servedVersionName",
                      "apkDownloadUrl": "https://github.com/ow7ab/Share-Flow/releases/download/v$servedVersionName/Share-Flow.apk",
                      "sha256": "$sampleApkSha256",
                      "fileSizeBytes": ${sampleApkBytes.size},
                      "mandatory": false,
                      "releaseNotesAr": "إصدار جديد",
                      "releaseNotesEn": "New release"
                    }
                """.trimIndent()
                okResponse(chain, 200, body = body.toByteArray())
            }
            .build()

        val manager = UpdateManager(
            okHttpClient = okHttp,
            dispatcher = dispatcher,
            manifestUrl = "https://ow7ab.github.io/Share-Flow/version.json",
            fallbackManifestUrl = "",
            currentVersionCode = 19,
            currentVersionName = "1.0.18",
            enableGitHubReleasesApi = false,
            preferencesStore = prefs,
            clock = object : Clock {
                override fun nowEpochMillis(): Long = nowMs
            }
        )

        // First periodic check runs and detects version 20
        val first = manager.checkForUpdateIfDue()
        assertTrue(first is AppResult.Success)
        assertEquals(1, networkCalls)
        assertEquals(20, manager.detectedUpdate.value?.versionCode)

        // Immediate second periodic check within 6 hours is skipped (returns null)
        nowMs += 60_000L
        val skipped = manager.checkForUpdateIfDue()
        assertNull("Periodic check within 6h must skip duplicate network calls", skipped)
        assertEquals(1, networkCalls)

        // Snooze optional version 20 -> clears detectedUpdate & persists snooze
        manager.snoozeOptionalUpdate()
        assertNull(manager.detectedUpdate.value)
        assertEquals(20, prefs.getSnapshot().snoozedVersionCode)

        // After 6+ hours, same version 20 stays suppressed when respectSnooze=true
        nowMs += UpdatePreferencesStore.DEFAULT_PERIODIC_CHECK_INTERVAL_MS + 1_000L
        manager.checkForUpdateIfDue()
        assertEquals(2, networkCalls)
        assertNull("Snoozed version 20 must remain suppressed", manager.detectedUpdate.value)

        // When newer version 21 arrives after another interval, snooze automatically clears!
        servedVersionCode = 21
        servedVersionName = "1.0.20"
        nowMs += UpdatePreferencesStore.DEFAULT_PERIODIC_CHECK_INTERVAL_MS + 1_000L
        manager.checkForUpdateIfDue()
        assertEquals(3, networkCalls)
        assertEquals(21, manager.detectedUpdate.value?.versionCode)
    }

    @Test
    fun `FcmUpdatePayload validates strictly and rejects insecure or malformed payloads`() {
        val valid = FcmUpdatePayload.fromMap(
            mapOf(
                "type" to "app_update",
                "versionCode" to "20",
                "versionName" to "v1.0.19",
                "apkUrl" to "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
                "sha256" to sampleApkSha256,
                "fileSizeBytes" to "18000000",
                "publishedAt" to "2026-11-25T10:00:00Z",
                "releaseNotes" to "تحسينات فورية",
                "targetRoute" to "updates"
            )
        )
        assertNotNull(valid)
        assertEquals("1.0.19", valid!!.versionName)
        assertEquals(20, valid.versionCode)
        assertEquals(ShareFlowRoutes.UPDATES, valid.targetRoute)

        // Reject cleartext HTTP APK URL
        assertNull(
            FcmUpdatePayload.fromMap(
                mapOf(
                    "type" to "app_update",
                    "versionCode" to "20",
                    "versionName" to "1.0.19",
                    "apkUrl" to "http://evil.example/app.apk",
                    "sha256" to sampleApkSha256
                )
            )
        )

        // Reject invalid SHA-256
        assertNull(
            FcmUpdatePayload.fromMap(
                mapOf(
                    "type" to "app_update",
                    "versionCode" to "20",
                    "versionName" to "1.0.19",
                    "apkUrl" to "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
                    "sha256" to "short"
                )
            )
        )

        // Reject unrelated push message type
        assertNull(
            FcmUpdatePayload.fromMap(
                mapOf(
                    "type" to "promo",
                    "versionCode" to "20",
                    "versionName" to "1.0.19",
                    "apkUrl" to "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
                    "sha256" to sampleApkSha256
                )
            )
        )
    }

    @Test
    fun `FcmUpdateTopicCoordinator subscribes once without login, re-subscribes on token refresh, and handles missing Firebase`() = runTest {
        val prefs = InMemoryUpdatePreferencesStore()
        var subscribeCount = 0
        var configured = false
        val backend = object : FcmTopicBackend {
            override fun isFirebaseConfigured(): Boolean = configured
            override suspend fun subscribeToTopic(topic: String): Boolean {
                assertEquals(FcmUpdateTopicCoordinator.TOPIC_SHARE_FLOW_UPDATES, topic)
                subscribeCount++
                return true
            }
        }

        val coordinator = FcmUpdateTopicCoordinator(
            backend = backend,
            preferencesStore = prefs,
            currentVersionCode = 19
        )

        // 1. Missing google-services.json -> graceful NOT_CONFIGURED, zero crash
        assertEquals(FcmSubscriptionState.NOT_CONFIGURED, coordinator.ensureSubscribed())
        assertEquals(0, subscribeCount)

        // 2. Firebase configured -> auto-subscribes once
        configured = true
        assertEquals(FcmSubscriptionState.SUBSCRIBED, coordinator.ensureSubscribed())
        assertEquals(1, subscribeCount)

        // 3. Subsequent launch for same versionCode -> skips duplicate subscribe
        assertEquals(FcmSubscriptionState.SUBSCRIBED, coordinator.ensureSubscribed())
        assertEquals(1, subscribeCount)

        // 4. Token refreshed -> forces re-subscription
        assertEquals(FcmSubscriptionState.SUBSCRIBED, coordinator.onTokenRefreshed())
        assertEquals(2, subscribeCount)
    }

    @Test
    fun `ShareFlowUpdateNotificationManager routes foreground vs background pushes, deduplicates, and deep-links to updates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val prefs = InMemoryUpdatePreferencesStore()
        val updateManager = UpdateManager(
            okHttpClient = OkHttpClient(),
            dispatcher = dispatcher,
            currentVersionCode = 19,
            currentVersionName = "1.0.18",
            enableGitHubReleasesApi = false,
            preferencesStore = prefs
        )
        val passInspector = object : ApkPackageSecurityInspector {
            override fun inspectUpdateApk(
                apkFile: File,
                expectedPackageName: String,
                expectedMinVersionCode: Int,
                currentInstalledVersionCode: Int
            ): AppResult<Unit> = AppResult.Success(Unit)
        }
        val cloudUpdateManager = CloudUpdateManager(
            context = context,
            dispatchers = TestDispatcherProvider(dispatcher),
            permissionManager = PermissionManager(context),
            httpClient = object : CloudUpdateHttpClient {
                override suspend fun fetchManifestText(manifestUrl: String) = AppResult.Failure(AppError.Network("unused"))
                override suspend fun downloadApkToFile(apkUrl: String, destinationFile: File, onProgress: (Long, Long) -> Unit) =
                    AppResult.Success(destinationFile)
            },
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = passInspector,
            preferencesStore = prefs,
            currentVersionCode = 19,
            currentVersionName = "1.0.18"
        )

        var inForeground = true
        val notificationManager = ShareFlowUpdateNotificationManager(
            context = context,
            preferencesStore = prefs,
            updateManager = updateManager,
            cloudUpdateManager = cloudUpdateManager,
            isAppInForegroundProvider = { inForeground },
            currentVersionCode = 19,
            currentVersionName = "1.0.18"
        )

        fun payloadFor(code: Int, name: String) = FcmUpdatePayload(
            versionCode = code,
            versionName = name,
            apkDownloadUrl = "https://github.com/ow7ab/Share-Flow/releases/download/v$name/Share-Flow.apk",
            sha256 = sampleApkSha256,
            fileSizeBytes = sampleApkBytes.size.toLong(),
            publishedAt = "2026-11-25T10:00:00Z",
            releaseNotesAr = "تحديث ذكي",
            releaseNotesEn = "Smart update",
            mandatory = false,
            signingCertSha256 = null,
            targetRoute = ShareFlowRoutes.UPDATES,
            notificationTitle = "تحديث جديد متوفر | Share Flow",
            notificationBody = "يتوفر الآن إصدار جديد (v$name)"
        )

        // 1. Already installed version -> ignored
        assertEquals(
            PushNotificationHandlingOutcome.IGNORED_ALREADY_UP_TO_DATE,
            notificationManager.handleIncomingUpdatePush(payloadFor(19, "1.0.18"))
        )

        // 2. Foreground push for v1.0.19 (code 20) -> surfaces in-app dialog immediately
        inForeground = true
        assertEquals(
            PushNotificationHandlingOutcome.SURFACED_IN_APP_FOREGROUND,
            notificationManager.handleIncomingUpdatePush(payloadFor(20, "1.0.19"))
        )
        assertEquals(20, updateManager.foregroundPushVersionCode.value)
        assertTrue(cloudUpdateManager.state.value is CloudUpdateState.UpdateAvailable)

        // 3. Duplicate push for version 20 -> ignored
        assertEquals(
            PushNotificationHandlingOutcome.IGNORED_DUPLICATE_VERSION,
            notificationManager.handleIncomingUpdatePush(payloadFor(20, "1.0.19"))
        )

        // 4. Background push for version 21 without POST_NOTIFICATIONS -> deferred cleanly (no crash)
        inForeground = false
        val app = context.applicationContext as Application
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PushNotificationHandlingOutcome.DEFERRED_NOTIFICATIONS_DISABLED,
            notificationManager.handleIncomingUpdatePush(payloadFor(21, "1.0.20"))
        )

        // 5. Background push for version 22 with POST_NOTIFICATIONS granted -> posts system notification & deep-link
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(
            PushNotificationHandlingOutcome.POSTED_SYSTEM_NOTIFICATION,
            notificationManager.handleIncomingUpdatePush(payloadFor(22, "1.0.21"))
        )
        val pendingIntent = notificationManager.buildOpenUpdatesPendingIntent(22)
        val savedIntent = shadowOf(pendingIntent).savedIntent
        assertEquals(ShareFlowUpdateNotificationManager.ACTION_OPEN_UPDATES, savedIntent.action)
        assertEquals(ShareFlowRoutes.UPDATES, savedIntent.getStringExtra(ShareFlowUpdateNotificationManager.EXTRA_OPEN_ROUTE))
        assertEquals(22, savedIntent.getIntExtra(ShareFlowUpdateNotificationManager.EXTRA_UPDATE_VERSION_CODE, -1))
    }

    @Test
    fun `UpdatesScreenContent and UpdateAvailableDialog render update controls, Pause Resume, and history cleanly`() {
        val manifest = CloudUpdateManifest(
            versionCode = 20,
            versionName = "1.0.19",
            apkDownloadUrl = "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
            sha256 = sampleApkSha256,
            fileSizeBytes = 18_450_000L,
            minSupportedAndroidSdk = 24,
            mandatory = false,
            releaseNotesAr = "نظام تحديث فوري عبر FCM",
            releaseNotesEn = "Instant FCM update system",
            portalUrl = "https://github.com/ow7ab/Share-Flow/releases",
            publishedAt = "2026-11-25"
        )
        val history = listOf(
            ReleaseHistoryItem(
                tagName = "V1.0.12",
                versionName = "1.0.12",
                title = "Share Flow V1.0.12",
                publishedAt = "2026-11-22",
                releaseNotes = "Previous build notes",
                fileSizeBytes = 15_000_000L,
                htmlUrl = "https://github.com/ow7ab/Share-Flow/releases/tag/V1.0.12",
                isPrerelease = true,
                hasApkAsset = true
            )
        )
        var checkClicked = false
        var resumeClicked = false

        composeTestRule.setContent {
            ShareFlowTheme(themeMode = ThemeMode.DARK) {
                UpdatesScreenContent(
                    updateState = CloudUpdateState.Paused(
                        manifest = manifest,
                        downloadedBytes = 9_225_000L,
                        totalBytes = 18_450_000L
                    ),
                    diagnostics = UpdateDiagnosticsSnapshot(
                        lastCheckEpochMillis = 1_764_000_000_000L,
                        serverStatus = UpdateServerConnectionStatus.CONNECTED,
                        activeSourceLabel = "GitHub Releases",
                        fcmSubscriptionState = FcmSubscriptionState.SUBSCRIBED
                    ),
                    releaseHistory = history,
                    autoCheckEnabled = true,
                    hasNotificationsPermission = true,
                    onNavigateBack = {},
                    onCheckForUpdates = { checkClicked = true },
                    onDownloadOrResume = { resumeClicked = true },
                    onPauseDownload = {},
                    onCancelDownload = {},
                    onInstallVerifiedUpdate = {},
                    onAutoCheckChanged = {},
                    onRequestNotificationPermission = {},
                    onOpenExternalReleaseUrl = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("updates_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("updates_list")
            .performScrollToNode(hasTestTag("updates_check_now_button"))
        composeTestRule.onNodeWithTag("updates_check_now_button").performClick()
        assertTrue(checkClicked)
        composeTestRule.onNodeWithTag("updates_list")
            .performScrollToNode(hasTestTag("updates_resume_download_button"))
        composeTestRule.onNodeWithTag("updates_resume_download_button").performClick()
        assertTrue(resumeClicked)
    }

    @Test
    fun `UpdateAvailableDialog triggers View Details and Later callbacks`() {
        val update = UpdateDetails(
            versionCode = 20,
            versionName = "1.0.19",
            apkDownloadUrl = "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.19/Share-Flow.apk",
            sha256 = sampleApkSha256,
            fileSizeBytes = 18_450_000L,
            mandatory = false,
            releaseNotes = "Instant FCM notifications and resumable downloads",
            releaseNotesAr = "إشعارات تحديث فورية وتنزيل قابل للاستئناف",
            releaseNotesEn = "Instant FCM notifications and resumable downloads",
            portalUrl = "https://github.com/ow7ab/Share-Flow/releases",
            publishedAt = "2026-11-25",
            isUpdateAvailable = true
        )
        var viewedDetails = false
        var dismissed = false

        composeTestRule.setContent {
            ShareFlowTheme(themeMode = ThemeMode.LIGHT) {
                UpdateAvailableDialog(
                    update = update,
                    downloadState = CloudUpdateState.UpdateAvailable(update.toCloudUpdateManifest()),
                    onDownloadNow = {},
                    onViewDetails = { viewedDetails = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeTestRule.onNodeWithTag("update_available_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithTag("update_dialog_view_details_button").performScrollTo().performClick()
        assertTrue(viewedDetails)
        composeTestRule.onNodeWithTag("update_dialog_later_button").performScrollTo().performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `CloudUpdateValidator and OkHttp client allow long GitHub Releases presigned CDN HTTPS redirects while blocking HTTP and oversized URLs`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val longJwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." + "a".repeat(720) + "." + "b".repeat(260)
        val longPresignedCdnUrl =
            "https://release-assets.githubusercontent.com/github-production-release-asset/123456789/" +
                "9f8e7d6c-5b4a-4321-8765-abcdef123456?sp=r&sv=2018-11-09&sr=b&spr=https" +
                "&se=2026-11-25T16%3A30%3A00Z&rscd=attachment%3B+filename%3DShare-Flow.apk" +
                "&rsct=application%2Foctet-stream&skoid=96c2d410-5711-43a1-aedd-ab1947aa7ab0" +
                "&sktid=398a6654-997b-47e9-b12b-9515b896b4de&skt=2026-11-25T15%3A30%3A00Z" +
                "&ske=2026-11-25T16%3A30%3A00Z&sks=b&skv=2018-11-09&sig=ABCDEF123456%3D" +
                "&jwt=$longJwt"

        assertTrue("Presigned URL should exceed old 512-char cap", longPresignedCdnUrl.length > 1200)
        assertTrue(
            "Valid long GitHub CDN presigned HTTPS redirect URL must be accepted",
            CloudUpdateValidator.isValidPublicHttpsUrl(longPresignedCdnUrl)
        )
        // Security checks remain strict
        assertFalse(
            "Cleartext HTTP redirect must still be blocked",
            CloudUpdateValidator.isValidPublicHttpsUrl(longPresignedCdnUrl.replaceFirst("https://", "http://"))
        )
        assertFalse(
            "Unbounded >4096 char URL must still be blocked",
            CloudUpdateValidator.isValidPublicHttpsUrl("https://release-assets.githubusercontent.com/a?" + "x".repeat(4200))
        )
        assertFalse(
            "Private IPv4 loopback must still be blocked",
            CloudUpdateValidator.isValidPublicHttpsUrl("https://127.0.0.1/Share-Flow.apk")
        )

        // End-to-end OkHttp 302 redirect test from github.com -> >1200-char release-assets.githubusercontent.com
        val requestedUrls = mutableListOf<String>()
        val httpClient = buildClientWithInterceptor(
            interceptor = Interceptor { chain ->
                val req = chain.request()
                val urlStr = req.url.toString()
                requestedUrls.add(urlStr)
                if (urlStr.startsWith("https://github.com/")) {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(302)
                        .message("Found")
                        .header("Location", longPresignedCdnUrl)
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    assertEquals(longPresignedCdnUrl, urlStr)
                    assertTrue(
                        "X-GitHub-Api-Version must not be leaked to CDN host",
                        req.header("X-GitHub-Api-Version") == null
                    )
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", sampleApkBytes.size.toString())
                        .body(sampleApkBytes.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }
            },
            maxRetryAttempts = 1,
            dispatcher = dispatcher
        )
        val tempDir = File(context.cacheDir, "redirect_cdn_test_${System.nanoTime()}").apply { mkdirs() }
        val destFile = File(tempDir, "Share-Flow-v1.0.19.apk")
        try {
            val downloadResult = httpClient.downloadApkResumable(
                apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/V1.0.19/Share-Flow.apk",
                destinationFile = destFile,
                resumeFromExistingPart = true,
                onProgress = { _, _ -> }
            )

            assertTrue("Expected redirected GitHub APK download to succeed, got $downloadResult", downloadResult is AppResult.Success)
            assertEquals(2, requestedUrls.size)
            assertTrue(destFile.exists())
            assertEquals(sampleApkBytes.size.toLong(), destFile.length())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
