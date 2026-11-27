package com.example.shareflow.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.testutil.TestDispatcherProvider
import com.example.shareflow.ui.update.cloudUpdateErrorReasonStringRes
import com.example.shareflow.ui.update.shouldShowUpdateErrorDetail
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite for ShareFlow version precedence & pre-install APK inspection.
 *
 * Covers all 5 mandatory production update scenarios plus edge cases:
 * - Case 1: Installed 1.0.19 (20), available 1.0.20 (21) with matching APK & signature -> Allow update.
 * - Case 2: Installed 1.0.19 (20), available 1.0.20 (20) -> Reject as DUPLICATE_VERSION_CONFLICT (NOT downgrade!).
 * - Case 3: Installed 1.0.20 (21), available 1.0.19 (20) -> Block as DOWNGRADE_BLOCKED.
 * - Case 4: GitHub Release claims versionCode 21, downloaded APK actually has versionCode 20 ->
 *           Stop installation with VERSION_METADATA_MISMATCH (NOT downgrade!) & delete APK.
 * - Case 5: Downloaded APK has versionCode 21 (> 20) but different signing certificate ->
 *           Reject installation with SIGNATURE_MISMATCH & delete APK.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VersionPrecedenceAndApkVerificationTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val sampleApkBytes = "PK\u0003\u0004shareflow-real-update-apk-payload-2026".toByteArray(Charsets.UTF_8)
    private val sampleApkSha256: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(sampleApkBytes)
            .joinToString("") { "%02x".format(it) }
    }

    private val prodSignature = Signature("30820122300d06092a864886f70d01010105000382010f003082010a0282010100aa11bb22")
    private val rogueSignature = Signature("30820122300d06092a864886f70d01010105000382010f003082010a0282010100ff99ee88")

    private val prodCertSha256: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest(prodSignature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun makePackageInfo(
        packageName: String = "com.shareflow",
        versionCode: Int,
        versionName: String?,
        signature: Signature? = prodSignature
    ): PackageInfo = PackageInfo().apply {
        this.packageName = packageName
        this.longVersionCode = versionCode.toLong()
        this.versionCode = versionCode
        this.versionName = versionName
        if (signature != null) {
            this.signatures = arrayOf(signature)
        }
    }

    private fun githubReleaseJson(
        tag: String,
        releaseName: String,
        body: String = "",
        includeVersionJsonAsset: Boolean = true
    ): String {
        val versionJsonAsset = if (includeVersionJsonAsset) {
            """,
                  {
                    "name": "version.json",
                    "size": 420,
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/$tag/version.json"
                  }"""
        } else {
            ""
        }
        return """
            [
              {
                "id": 2026,
                "tag_name": "$tag",
                "name": "$releaseName",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-26T00:00:00Z",
                "body": "$body",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/$tag",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "size": ${sampleApkBytes.size},
                    "digest": "sha256:$sampleApkSha256",
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/$tag/Share-Flow.apk"
                  }$versionJsonAsset
                ]
              }
            ]
        """.trimIndent()
    }

    private fun sidecarVersionJson(
        versionCode: Int,
        versionName: String,
        signingCertSha256: String? = prodCertSha256
    ): String = """
        {
          "versionCode": $versionCode,
          "versionName": "$versionName",
          "apkDownloadUrl": "https://github.com/ow7ab/Share-Flow/releases/download/v$versionName/Share-Flow.apk",
          "sha256": "$sampleApkSha256",
          "fileSizeBytes": ${sampleApkBytes.size},
          "minSupportedAndroidSdk": 24,
          "mandatory": false,
          "releaseNotesAr": "تحديث رسمي موثّق",
          "releaseNotesEn": "Verified official release",
          "portalUrl": "https://github.com/ow7ab/Share-Flow/releases",
          "publishedAt": "2026-11-26T00:00:00Z"
          ${if (signingCertSha256 != null) """, "signingCertSha256": "$signingCertSha256"""" else ""}
        }
    """.trimIndent()

    private fun createMockHttpClient(
        releasesJson: String,
        versionJson: String? = null,
        apkPayload: ByteArray = sampleApkBytes
    ): CloudUpdateHttpClient = object : CloudUpdateHttpClient {
        override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> = when {
            manifestUrl.contains("api.github.com") -> AppResult.Success(releasesJson)
            manifestUrl.endsWith("version.json") && versionJson != null -> AppResult.Success(versionJson)
            else -> AppResult.Failure(AppError.Network("Not found: $manifestUrl"))
        }

        override suspend fun downloadApkToFile(
            apkUrl: String,
            destinationFile: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
        ): AppResult<File> {
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(apkPayload)
            onProgress(apkPayload.size.toLong(), apkPayload.size.toLong())
            return AppResult.Success(destinationFile)
        }
    }

    @Test
    fun `Case 1 - installed 1_0_19 (20) and available 1_0_20 (21) with matching APK and cert allows update`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installedPkg = makePackageInfo(versionCode = 20, versionName = "1.0.19", signature = prodSignature)
        val archivePkg = makePackageInfo(versionCode = 21, versionName = "1.0.20", signature = prodSignature)

        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> archivePkg },
            installedInfoProvider = { _, _ -> installedPkg }
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = githubReleaseJson(tag = "v1.0.20", releaseName = "ShareFlow v1.0.20"),
                versionJson = sidecarVersionJson(versionCode = 21, versionName = "1.0.20")
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val checkState = manager.checkForUpdates("")
        assertTrue("Case 1 must offer update, got $checkState", checkState is CloudUpdateState.UpdateAvailable)
        val manifest = (checkState as CloudUpdateState.UpdateAvailable).manifest
        assertEquals(21, manifest.versionCode)
        assertEquals("1.0.20", manifest.versionName)

        val downloadState = manager.downloadAndVerifyUpdate(manifest)
        assertTrue("Case 1 must pass APK & cert verification, got $downloadState", downloadState is CloudUpdateState.ReadyToInstall)

        val apkFile = File((downloadState as CloudUpdateState.ReadyToInstall).verifiedApkPath)
        assertTrue(apkFile.exists())
        apkFile.delete()
    }

    @Test
    fun `Case 2 - installed 1_0_19 (20) and available 1_0_20 (20) rejects duplicate versionCode with conflict error and NOT downgrade`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        // Direct precedence rule verification
        assertEquals(
            VersionPrecedenceDecision.DUPLICATE_VERSION_CONFLICT,
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = 20,
                remoteVersionName = "1.0.20",
                installedVersionCode = 20,
                installedVersionName = "1.0.19"
            )
        )
        assertFalse(
            "versionName alone must NEVER override equal versionCode to return true",
            GitHubReleasesParser.isRemoteReleaseNewer(
                remoteVersionCode = 20,
                remoteVersionName = "1.0.20",
                installedVersionCode = 20,
                installedVersionName = "1.0.19"
            )
        )

        val installedPkg = makePackageInfo(versionCode = 20, versionName = "1.0.19", signature = prodSignature)
        val archivePkg = makePackageInfo(versionCode = 20, versionName = "1.0.20", signature = prodSignature)
        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> archivePkg },
            installedInfoProvider = { _, _ -> installedPkg }
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = githubReleaseJson(tag = "v1.0.20", releaseName = "ShareFlow v1.0.20"),
                versionJson = sidecarVersionJson(versionCode = 20, versionName = "1.0.20")
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val checkState = manager.checkForUpdates("")
        assertTrue("Case 2 must reject with error state, got $checkState", checkState is CloudUpdateState.Error)
        val err = checkState as CloudUpdateState.Error
        assertEquals(CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT, err.reason)
        assertNotEquals("Case 2 must NOT show downgrade error", CloudUpdateErrorReason.DOWNGRADE_BLOCKED, err.reason)
        assertTrue(err.detail.contains("20"))
        assertTrue(err.detail.contains("1.0.20"))
    }

    @Test
    fun `Case 3 - installed 1_0_20 (21) and available 1_0_19 (20) blocks downgrade`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)

        assertEquals(
            VersionPrecedenceDecision.DOWNGRADE_BLOCKED,
            GitHubReleasesParser.evaluateVersionPrecedence(
                remoteVersionCode = 20,
                remoteVersionName = "1.0.19",
                installedVersionCode = 21,
                installedVersionName = "1.0.20"
            )
        )

        val installedPkg = makePackageInfo(versionCode = 21, versionName = "1.0.20", signature = prodSignature)
        val archivePkg = makePackageInfo(versionCode = 20, versionName = "1.0.19", signature = prodSignature)
        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> archivePkg },
            installedInfoProvider = { _, _ -> installedPkg }
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = githubReleaseJson(tag = "v1.0.19", releaseName = "ShareFlow v1.0.19"),
                versionJson = sidecarVersionJson(versionCode = 20, versionName = "1.0.19")
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 21,
            currentVersionName = "1.0.20",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val checkState = manager.checkForUpdates("")
        assertTrue("Case 3 must block downgrade, got $checkState", checkState is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.DOWNGRADE_BLOCKED, (checkState as CloudUpdateState.Error).reason)

        // Direct APK inspection must also strictly block downgrade if invoked directly
        val tempApk = File(context.cacheDir, "case3_downgrade.apk").apply { writeBytes(sampleApkBytes) }
        try {
            val inspect = inspector.inspectUpdateApkDetailed(
                apkFile = tempApk,
                expectedPackageName = "com.shareflow",
                expectedVersionCode = 20,
                expectedVersionName = "1.0.19",
                currentInstalledVersionCode = 21,
                currentInstalledVersionName = "1.0.20"
            )
            assertTrue(inspect is ApkInspectionResult.Rejected)
            assertEquals(CloudUpdateErrorReason.DOWNGRADE_BLOCKED, (inspect as ApkInspectionResult.Rejected).reason)
        } finally {
            tempApk.delete()
        }
    }

    @Test
    fun `Case 4 - GitHub Release advertises versionCode 21 but downloaded APK actually has versionCode 20 stops install with metadata conflict and NOT downgrade`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installedPkg = makePackageInfo(versionCode = 20, versionName = "1.0.19", signature = prodSignature)
        // Actual APK was mistakenly built with versionCode 20 even though GitHub Release declared 21
        val mismatchedArchivePkg = makePackageInfo(versionCode = 20, versionName = "1.0.20", signature = prodSignature)

        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> mismatchedArchivePkg },
            installedInfoProvider = { _, _ -> installedPkg }
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = githubReleaseJson(tag = "v1.0.20", releaseName = "ShareFlow v1.0.20"),
                versionJson = sidecarVersionJson(versionCode = 21, versionName = "1.0.20")
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val available = manager.checkForUpdates("") as CloudUpdateState.UpdateAvailable
        assertEquals(21, available.manifest.versionCode)

        val verifyState = manager.downloadAndVerifyUpdate(available.manifest)
        assertTrue("Case 4 must reject installation, got $verifyState", verifyState is CloudUpdateState.Error)
        val err = verifyState as CloudUpdateState.Error
        assertEquals(
            "Case 4 must report VERSION_METADATA_MISMATCH, NOT DOWNGRADE_BLOCKED",
            CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
            err.reason
        )
        assertTrue("Detail must explain advertised 21 vs actual 20", err.detail.contains("21") && err.detail.contains("20"))

        // Downloaded mismatched APK must be cleaned up immediately
        val updatesDir = File(context.cacheDir, CloudUpdateManager.UPDATES_CACHE_DIR)
        assertFalse(File(updatesDir, "shareflow-v21.apk").exists())
    }

    @Test
    fun `Case 5 - available APK has higher versionCode 21 but different signing certificate rejects installation with SIGNATURE_MISMATCH`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val installedPkg = makePackageInfo(versionCode = 20, versionName = "1.0.19", signature = prodSignature)
        val differentlySignedApk = makePackageInfo(versionCode = 21, versionName = "1.0.20", signature = rogueSignature)

        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> differentlySignedApk },
            installedInfoProvider = { _, _ -> installedPkg }
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = githubReleaseJson(tag = "v1.0.20", releaseName = "ShareFlow v1.0.20"),
                // No pre-download cert pin in manifest so download proceeds and actual APK cert check fires
                versionJson = sidecarVersionJson(versionCode = 21, versionName = "1.0.20", signingCertSha256 = null)
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val available = manager.checkForUpdates("") as CloudUpdateState.UpdateAvailable
        val verifyState = manager.downloadAndVerifyUpdate(available.manifest)

        assertTrue("Case 5 must reject differently signed APK, got $verifyState", verifyState is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.SIGNATURE_MISMATCH, (verifyState as CloudUpdateState.Error).reason)

        val updatesDir = File(context.cacheDir, CloudUpdateManager.UPDATES_CACHE_DIR)
        assertFalse(File(updatesDir, "shareflow-v21.apk").exists())
    }

    @Test
    fun `GitHub release without real versionCode is rejected as UNREADABLE_VERSION_METADATA instead of fabricating synthetic code`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val releaseWithoutCode = githubReleaseJson(
            tag = "v1.0.20",
            releaseName = "ShareFlow v1.0.20",
            body = "General improvements without build number",
            includeVersionJsonAsset = false
        )

        val parsed = GitHubReleasesParser.parseReleasesArray(releaseWithoutCode).first()
        assertNull(
            "Parser must NOT fabricate a fake versionCode when version.json and body lack numeric versionCode",
            GitHubReleasesParser.toCloudUpdateManifest(
                release = parsed,
                currentVersionCode = 20,
                currentVersionName = "1.0.19"
            )
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(releasesJson = releaseWithoutCode, versionJson = null),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = AndroidApkPackageSecurityInspector(context),
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 20,
            currentVersionName = "1.0.19",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val state = manager.checkForUpdates("")
        assertTrue("Missing versionCode must surface UNREADABLE_VERSION_METADATA, got $state", state is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA, (state as CloudUpdateState.Error).reason)
    }

    @Test
    fun `legacy GitHub release V1_0_21 without versionCode resolves as UpToDate when user already runs 1_0_21`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val legacyReleaseWithoutCode = githubReleaseJson(
            tag = "V1.0.21",
            releaseName = "ShareFlow V1.0.21",
            body = "Manual release without version.json or Build number",
            includeVersionJsonAsset = false
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(releasesJson = legacyReleaseWithoutCode, versionJson = null),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = AndroidApkPackageSecurityInspector(context),
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 21,
            currentVersionName = "1.0.21",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val state = manager.checkForUpdates("")
        assertTrue("Already-installed legacy release V1.0.21 must return UpToDate, got $state", state is CloudUpdateState.UpToDate)
        val upToDate = state as CloudUpdateState.UpToDate
        assertEquals(21, upToDate.currentVersionCode)
        assertEquals("1.0.21", upToDate.currentVersionName)
    }

    @Test
    fun `GitHub release lacking version_json asset falls back to official portal version_json for verified versionCode`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val releaseWithoutCode = githubReleaseJson(
            tag = "v1.1.0",
            releaseName = "ShareFlow v1.1.0",
            body = "Performance & security release",
            includeVersionJsonAsset = false
        )

        val manager = CloudUpdateManager(
            context = context,
            httpClient = createMockHttpClient(
                releasesJson = releaseWithoutCode,
                versionJson = sidecarVersionJson(versionCode = 23, versionName = "1.1.0")
            ),
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = AndroidApkPackageSecurityInspector(context),
            permissionManager = PermissionManager(context),
            dispatchers = TestDispatcherProvider(dispatcher),
            currentVersionCode = 21,
            currentVersionName = "1.0.21",
            currentSdkInt = 34,
            expectedPackageName = "com.shareflow",
            enableGitHubReleasesPrimary = true
        )

        val state = manager.checkForUpdates("")
        assertTrue("Official portal version.json fallback must resolve UpdateAvailable, got $state", state is CloudUpdateState.UpdateAvailable)
        val manifest = (state as CloudUpdateState.UpdateAvailable).manifest
        assertEquals(23, manifest.versionCode)
        assertEquals("1.1.0", manifest.versionName)
    }

    @Test
    fun `corrupt APK file returns INVALID_APK and all distinct error strings resolve cleanly`() {
        val inspector = AndroidApkPackageSecurityInspector(
            context = context,
            archiveInfoProvider = { _, _ -> null },
            installedInfoProvider = { _, _ -> makePackageInfo(versionCode = 20, versionName = "1.0.19") }
        )
        val corruptFile = File(context.cacheDir, "corrupt_test.apk").apply { writeBytes("not-a-zip".toByteArray()) }
        try {
            val result = inspector.inspectUpdateApkDetailed(
                apkFile = corruptFile,
                expectedPackageName = "com.shareflow",
                expectedVersionCode = 21,
                expectedVersionName = "1.0.20",
                currentInstalledVersionCode = 20,
                currentInstalledVersionName = "1.0.19"
            )
            assertTrue(result is ApkInspectionResult.Rejected)
            assertEquals(CloudUpdateErrorReason.INVALID_APK, (result as ApkInspectionResult.Rejected).reason)
        } finally {
            corruptFile.delete()
        }

        // Verify all new error reasons map to distinct non-empty string resources
        val distinctReasons = listOf(
            CloudUpdateErrorReason.DOWNGRADE_BLOCKED,
            CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT,
            CloudUpdateErrorReason.VERSION_METADATA_MISMATCH,
            CloudUpdateErrorReason.SIGNATURE_MISMATCH,
            CloudUpdateErrorReason.INVALID_APK,
            CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA
        )
        val resolvedIds = distinctReasons.map { cloudUpdateErrorReasonStringRes(it) }
        assertEquals("Every failure reason must have a unique user-facing message", distinctReasons.size, resolvedIds.toSet().size)
        resolvedIds.forEach { resId ->
            assertTrue(context.getString(resId).isNotBlank())
        }
        assertEquals(R.string.settings_update_err_downgrade, cloudUpdateErrorReasonStringRes(CloudUpdateErrorReason.DOWNGRADE_BLOCKED))
        assertTrue(shouldShowUpdateErrorDetail(CloudUpdateErrorReason.VERSION_METADATA_MISMATCH))
        assertTrue(shouldShowUpdateErrorDetail(CloudUpdateErrorReason.DUPLICATE_VERSION_CONFLICT))
    }
}
