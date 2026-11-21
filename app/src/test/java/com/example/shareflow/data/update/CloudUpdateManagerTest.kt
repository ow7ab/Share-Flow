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
    fun `version metadata is incremented to 1_0_1 versionCode 2`() {
        assertEquals(2, BuildConfig.VERSION_CODE)
        assertEquals("1.0.1", BuildConfig.VERSION_NAME)
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
    fun `checkForUpdates returns PortalNotConfigured when no portal URL is set`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(),
            inspector = FakeApkSecurityInspector(AppResult.Success(Unit))
        )

        val state = manager.checkForUpdates("")
        assertEquals(CloudUpdateState.PortalNotConfigured, state)
    }

    @Test
    fun `checkForUpdates reports UpToDate when remote version is not newer`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = createManager(
            dispatcher = dispatcher,
            httpClient = FakeCloudUpdateHttpClient(manifestResponse = AppResult.Success(validManifestJson(versionCode = 2, versionName = "1.0.1"))),
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

    private fun createManager(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        httpClient: CloudUpdateHttpClient,
        inspector: ApkPackageSecurityInspector,
        currentVersionCode: Int = 2
    ): CloudUpdateManager = CloudUpdateManager(
        context = context,
        httpClient = httpClient,
        checksumVerifier = Sha256ChecksumVerifier(),
        packageInspector = inspector,
        permissionManager = PermissionManager(context),
        dispatchers = TestDispatcherProvider(dispatcher),
        currentVersionCode = currentVersionCode,
        currentVersionName = "1.0.1",
        currentSdkInt = 36,
        expectedPackageName = context.packageName
    )

    private class FakeCloudUpdateHttpClient(
        private val manifestResponse: AppResult<String> = AppResult.Failure(AppError.Network("unused")),
        private val apkBytes: ByteArray = ByteArray(0)
    ) : CloudUpdateHttpClient {
        override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> = manifestResponse

        override suspend fun downloadApkToFile(
            apkUrl: String,
            destinationFile: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
        ): AppResult<File> {
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(apkBytes)
            onProgress(apkBytes.size.toLong(), apkBytes.size.toLong())
            return AppResult.Success(destinationFile)
        }
    }

    private class FakeApkSecurityInspector(
        private val result: AppResult<Unit>
    ) : ApkPackageSecurityInspector {
        override fun inspectUpdateApk(
            apkFile: File,
            expectedPackageName: String,
            expectedMinVersionCode: Int,
            currentInstalledVersionCode: Int
        ): AppResult<Unit> = result
    }
}
