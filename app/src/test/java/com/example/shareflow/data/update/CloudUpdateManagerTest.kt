package com.example.shareflow.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.testutil.TestDispatcherProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudUpdateManagerTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sampleApkBytes = "PK\u0003\u0004shareflow-verified-apk-payload".toByteArray(Charsets.UTF_8)
    private val sampleApkSha256: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(sampleApkBytes)
            .joinToString("") { "%02x".format(it) }
    }

    private fun validManifestJson(
        versionCode: Int = 3,
        versionName: String = "1.0.2",
        apkUrl: String = "https://shareflow.sd/downloads/shareflow-1.0.2.apk",
        sha256: String = sampleApkSha256,
        minSdk: Int = 24
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkDownloadUrl": "$apkUrl",
          "sha256": "$sha256",
          "fileSizeBytes": ${sampleApkBytes.size},
          "minSupportedAndroidSdk": $minSdk,
          "mandatory": false,
          "releaseNotesAr": "تحسين سرعة الربط المباشر عبر QR وإصلاحات أمنية.",
          "releaseNotesEn": "Faster direct QR pairing and security enhancements.",
          "portalUrl": "https://shareflow.sd",
          "publishedAt": "2026-11-21"
        }
    """.trimIndent()

    @Test
    fun `version metadata and default official website manifest URL are valid and consistent`() {
        assertTrue("VERSION_CODE must be monotonic >= 25", BuildConfig.VERSION_CODE >= 25)
        assertEquals("1.1.2", BuildConfig.VERSION_NAME)
        assertTrue(
            "VERSION_NAME must follow semantic versioning",
            Regex("^\\d+\\.\\d+\\.\\d+([-.][0-9A-Za-z.]+)?$").matches(BuildConfig.VERSION_NAME)
        )
        assertEquals("com.shareflow", BuildConfig.APPLICATION_ID)
        assertEquals(
            "https://ow7ab.github.io/Share-Flow/version.json",
            BuildConfig.CLOUD_UPDATE_MANIFEST_URL
        )
        assertTrue(CloudUpdateValidator.isValidPublicHttpsUrl(BuildConfig.CLOUD_UPDATE_MANIFEST_URL))
        assertTrue(CloudUpdateValidator.isValidPublicHttpsUrl(CloudUpdateManager.DEFAULT_FALLBACK_MANIFEST_URL))
    }

    @Test
    fun `manifest parser accepts exact release workflow version json schema with Share-Flow apk`() {
        val workflowManifest = """
            {
              "versionCode": 12,
              "versionName": "1.0.11",
              "apkDownloadUrl": "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.11/Share-Flow.apk",
              "sha256": "$sampleApkSha256",
              "fileSizeBytes": ${sampleApkBytes.size},
              "minSupportedAndroidSdk": 24,
              "mandatory": false,
              "releaseNotesAr": "إصدار رسمي موقّع وموثّق من تطبيق Share Flow جاهز للتثبيت المباشر.",
              "releaseNotesEn": "Verified signed Share Flow Android release ready for direct APK installation.",
              "portalUrl": "https://ow7ab.github.io/Share-Flow/",
              "publishedAt": "2026-11-22T09:00:00Z",
              "releaseTag": "v1.0.11",
              "signingCertSha256": "$sampleApkSha256"
            }
        """.trimIndent()

        val parsed = CloudUpdateValidator.parseManifestJson(workflowManifest)
        assertTrue("Workflow version.json must parse cleanly", parsed is AppResult.Success)
        val data = (parsed as AppResult.Success).data
        assertEquals(12, data.versionCode)
        assertEquals("1.0.11", data.versionName)
        assertTrue(data.apkDownloadUrl.endsWith("/Share-Flow.apk"))
        assertEquals("https://ow7ab.github.io/Share-Flow/", data.portalUrl)
        assertEquals(sampleApkSha256, data.signingCertSha256)
    }

    @Test
    fun `manifest parser accepts unified website version json with apkUrl and releaseNotes array`() {
        val unifiedManifest = """
            {
              "versionCode": 13,
              "versionName": "1.0.12",
              "apkUrl": "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
              "sha256": "$sampleApkSha256",
              "fileSizeBytes": ${sampleApkBytes.size},
              "releaseNotes": [
                "تحسين سرعة الاتصال المباشر",
                "واجهة تحديث تلقائي أكثر استقراراً"
              ]
            }
        """.trimIndent()

        val parsed = CloudUpdateValidator.parseManifestJson(unifiedManifest)
        assertTrue("Unified version.json with apkUrl and releaseNotes array must parse", parsed is AppResult.Success)
        val data = (parsed as AppResult.Success).data
        assertEquals(13, data.versionCode)
        assertEquals("1.0.12", data.versionName)
        assertEquals("https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk", data.apkDownloadUrl)
        assertTrue(data.releaseNotesAr.contains("تحسين سرعة الاتصال المباشر"))
        assertTrue(data.releaseNotesEn.contains("واجهة تحديث تلقائي أكثر استقراراً"))
    }

    @Test
    fun `validator enforces strict public HTTPS URLs and blocks unsafe hosts`() {
        assertTrue(CloudUpdateValidator.isValidPublicHttpsUrl("https://shareflow.sd/version.json"))
        assertTrue(CloudUpdateValidator.isValidPublicHttpsUrl("https://cdn.shareflow.sd/releases/latest.json"))

        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl(""))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("http://shareflow.sd/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://localhost/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://127.0.0.1/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://192.168.1.5/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://10.0.0.1/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://172.16.0.4/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://169.254.169.254/latest/meta-data"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://[::ffff:127.0.0.1]/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("https://user:pass@shareflow.sd/version.json"))
        assertFalse(CloudUpdateValidator.isValidPublicHttpsUrl("javascript:alert(1)"))
    }

    @Test
    fun `manifest parser rejects missing SHA-256 or cleartext APK URL`() {
        val valid = CloudUpdateValidator.parseManifestJson(validManifestJson())
        assertTrue(valid is AppResult.Success)

        val badSha = CloudUpdateValidator.parseManifestJson(validManifestJson(sha256 = "not-a-valid-sha"))
        assertTrue(badSha is AppResult.Failure)

        val cleartextApk = CloudUpdateValidator.parseManifestJson(
            validManifestJson(apkUrl = "http://shareflow.sd/app.apk")
        )
        assertTrue(cleartextApk is AppResult.Failure)

        val badVersion = CloudUpdateValidator.parseManifestJson(validManifestJson(versionCode = 0))
        assertTrue(badVersion is AppResult.Failure)
    }

    @Test
    fun `checkForUpdates returns PortalNotConfigured when no portal URL or default manifest is set`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            defaultManifestUrl = ""
        )

        val state = manager.checkForUpdates("")
        assertEquals(CloudUpdateState.PortalNotConfigured, state)
    }

    @Test
    fun `resolveEffectiveManifestUrl defaults to official GitHub Pages version json`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit))
        )

        assertEquals(
            "https://ow7ab.github.io/Share-Flow/version.json",
            manager.resolveEffectiveManifestUrl("")
        )
        assertEquals(
            "https://shareflow.sd/version.json",
            manager.resolveEffectiveManifestUrl("https://shareflow.sd/version.json")
        )
    }

    @Test
    fun `checkForUpdates automatically falls back to GitHub Releases version json when official website fails`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val requestedUrls = mutableListOf<String>()
        val httpClient = FakeCloudUpdateHttpClient(
            manifestResolver = { url ->
                requestedUrls += url
                if (url == CloudUpdateManager.DEFAULT_FALLBACK_MANIFEST_URL) {
                    AppResult.Success(validManifestJson(versionCode = 13, versionName = "1.0.12"))
                } else {
                    AppResult.Failure(AppError.Network("Website temporarily unreachable"))
                }
            }
        )
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = httpClient,
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            currentVersionCode = 12
        )

        val state = manager.checkForUpdates("")
        assertTrue("Expected UpdateAvailable via fallback but got $state", state is CloudUpdateState.UpdateAvailable)
        assertEquals(
            listOf(
                "https://ow7ab.github.io/Share-Flow/version.json",
                CloudUpdateManager.DEFAULT_FALLBACK_MANIFEST_URL
            ),
            requestedUrls
        )
    }

    @Test
    fun `checkForUpdates reports UpToDate when remote version is not newer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(manifestResponse = AppResult.Success(validManifestJson(versionCode = 2, versionName = "1.0.2"))),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            currentVersionCode = 2
        )

        val state = manager.checkForUpdates("https://shareflow.sd/version.json")
        assertTrue(state is CloudUpdateState.UpToDate)
        assertEquals("https://shareflow.sd", (state as CloudUpdateState.UpToDate).portalUrl)
    }

    @Test
    fun `downloadAndVerifyUpdate reaches ReadyToInstall when SHA-256 and package checks pass`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(
                manifestResponse = AppResult.Success(validManifestJson(versionCode = 3)),
                apkBytes = sampleApkBytes
            ),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            currentVersionCode = 2
        )

        val available = manager.checkForUpdates("https://shareflow.sd/version.json") as CloudUpdateState.UpdateAvailable
        val ready = manager.downloadAndVerifyUpdate(available.manifest)

        assertTrue("Expected ReadyToInstall but got $ready", ready is CloudUpdateState.ReadyToInstall)
        val apkFile = File((ready as CloudUpdateState.ReadyToInstall).verifiedApkPath)
        assertTrue(apkFile.exists())
        apkFile.delete()
    }

    @Test
    fun `downloadAndVerifyUpdate reuses verified cached APK without re-downloading same version`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val trackingHttp = FakeCloudUpdateHttpClient(
            manifestResponse = AppResult.Success(validManifestJson(versionCode = 14, versionName = "1.0.13")),
            apkBytes = sampleApkBytes
        )
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = trackingHttp,
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            currentVersionCode = 12
        )

        val available = manager.checkForUpdates("https://shareflow.sd/version.json") as CloudUpdateState.UpdateAvailable
        val firstReady = manager.downloadAndVerifyUpdate(available.manifest)
        val secondReady = manager.downloadAndVerifyUpdate(available.manifest)

        assertTrue(firstReady is CloudUpdateState.ReadyToInstall)
        assertTrue(secondReady is CloudUpdateState.ReadyToInstall)
        assertEquals("Same verified version must not be downloaded twice", 1, trackingHttp.downloadCount)

        File((firstReady as CloudUpdateState.ReadyToInstall).verifiedApkPath).delete()
    }

    @Test
    fun `downloadAndVerifyUpdate rejects and deletes APK on SHA-256 mismatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(
                manifestResponse = AppResult.Success(validManifestJson(versionCode = 3)),
                apkBytes = "tampered-apk-payload".toByteArray(Charsets.UTF_8)
            ),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit)),
            currentVersionCode = 2
        )

        val available = manager.checkForUpdates("https://shareflow.sd/version.json") as CloudUpdateState.UpdateAvailable
        val result = manager.downloadAndVerifyUpdate(available.manifest)

        assertTrue(result is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.CHECKSUM_MISMATCH, (result as CloudUpdateState.Error).reason)

        val updatesDir = File(context.cacheDir, CloudUpdateManager.UPDATES_CACHE_DIR)
        assertTrue(updatesDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `downloadAndVerifyUpdate rejects and deletes APK on signing certificate or package mismatch`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val sigMismatchManager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(
                manifestResponse = AppResult.Success(validManifestJson(versionCode = 3)),
                apkBytes = sampleApkBytes
            ),
            inspector = FakeApkSecurityInspector(
                AppResult.Failure(AppError.Security("Update APK signing certificate does not match."))
            ),
            currentVersionCode = 2
        )

        val available = sigMismatchManager.checkForUpdates("https://shareflow.sd/version.json") as CloudUpdateState.UpdateAvailable
        val sigResult = sigMismatchManager.downloadAndVerifyUpdate(available.manifest)
        assertTrue(sigResult is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.SIGNATURE_MISMATCH, (sigResult as CloudUpdateState.Error).reason)

        val pkgMismatchManager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(
                manifestResponse = AppResult.Success(validManifestJson(versionCode = 3)),
                apkBytes = sampleApkBytes
            ),
            inspector = FakeApkSecurityInspector(
                AppResult.Failure(AppError.Security("APK package ID (com.evil.app) does not match ShareFlow."))
            ),
            currentVersionCode = 2
        )
        val pkgResult = pkgMismatchManager.downloadAndVerifyUpdate(available.manifest)
        assertTrue(pkgResult is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.PACKAGE_IDENTITY_MISMATCH, (pkgResult as CloudUpdateState.Error).reason)
    }

    @Test
    fun `downloadAndVerifyUpdate fails fast before downloading when advertised signingCertSha256 mismatches installed app`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val trackingHttp = FakeCloudUpdateHttpClient(
            manifestResponse = AppResult.Success(
                """
                {
                  "versionCode": 12,
                  "versionName": "1.0.11",
                  "apkDownloadUrl": "https://github.com/ow7ab/Share-Flow/releases/download/v1.0.11/Share-Flow.apk",
                  "sha256": "$sampleApkSha256",
                  "fileSizeBytes": ${sampleApkBytes.size},
                  "signingCertSha256": "$sampleApkSha256"
                }
                """.trimIndent()
            ),
            apkBytes = sampleApkBytes
        )
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = trackingHttp,
            inspector = FakeApkSecurityInspector(
                result = AppResult.Success(Unit),
                preDownloadSignatureCompatible = false
            ),
            currentVersionCode = 11
        )

        val available = manager.checkForUpdates("https://shareflow.sd/version.json") as CloudUpdateState.UpdateAvailable
        val result = manager.downloadAndVerifyUpdate(available.manifest)

        assertTrue(result is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.SIGNATURE_MISMATCH, (result as CloudUpdateState.Error).reason)
        assertEquals("APK should not be downloaded when signing cert already mismatches", 0, trackingHttp.downloadCount)
    }

    private fun createManager(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        httpClient: CloudUpdateHttpClient,
        inspector: ApkPackageSecurityInspector,
        currentVersionCode: Int = 2,
        defaultManifestUrl: String = BuildConfig.CLOUD_UPDATE_MANIFEST_URL
    ): CloudUpdateManager = CloudUpdateManager(
        context = context,
        httpClient = httpClient,
        checksumVerifier = Sha256ChecksumVerifier(),
        packageInspector = inspector,
        permissionManager = PermissionManager(context),
        dispatchers = TestDispatcherProvider(dispatcher),
        currentVersionCode = currentVersionCode,
        currentVersionName = "1.0.2",
        currentSdkInt = 36,
        expectedPackageName = context.packageName,
        defaultManifestUrl = defaultManifestUrl
    )

    private class FakeCloudUpdateHttpClient(
        private val manifestResponse: AppResult<String> = AppResult.Failure(AppError.Network("unused")),
        private val apkBytes: ByteArray = ByteArray(0),
        private val manifestResolver: ((String) -> AppResult<String>)? = null
    ) : CloudUpdateHttpClient {
        var downloadCount: Int = 0
            private set

        override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> =
            manifestResolver?.invoke(manifestUrl) ?: manifestResponse

        override suspend fun downloadApkToFile(
            apkUrl: String,
            destinationFile: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
        ): AppResult<File> {
            downloadCount++
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(apkBytes)
            onProgress(apkBytes.size.toLong(), apkBytes.size.toLong())
            return AppResult.Success(destinationFile)
        }
    }

    private class FakeApkSecurityInspector(
        private val result: AppResult<Unit>,
        private val preDownloadSignatureCompatible: Boolean = true
    ) : ApkPackageSecurityInspector {
        override fun inspectUpdateApk(
            apkFile: File,
            expectedPackageName: String,
            expectedMinVersionCode: Int,
            currentInstalledVersionCode: Int
        ): AppResult<Unit> = result

        override fun isInstalledSignatureCompatible(
            expectedPackageName: String,
            expectedCertSha256: String
        ): Boolean = preDownloadSignatureCompatible
    }
}
