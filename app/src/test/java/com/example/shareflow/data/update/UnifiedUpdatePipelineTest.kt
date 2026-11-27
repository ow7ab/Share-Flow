package com.example.shareflow.data.update

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.common.DispatcherProvider
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.ui.update.shouldShowUpdateErrorDetail
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end verification of ShareFlow's unified update pipeline:
 * 1. Primary unified `version.json` schema (`versionCode`, `versionName`, `downloadUrl`, `releaseNotes`, `isMandatory`).
 * 2. Real binary APK `AndroidManifest.xml` (AXML) extraction via HTTP Range requests when `version.json`
 *    and release body Build numbers are absent (directly fixing "Release V1.1.2 does not provide a readable
 *    numeric versionCode").
 * 3. Tag/version normalization across `V1.1.2`, `v1.1.2`, and `1.1.2` without ever fabricating `versionCode`.
 * 4. Pre-download & post-download security guards (downgrade, wrong package, mismatch, minSdk, HTTPS, SHA-256).
 * 5. Actionable diagnostics on failures, GitHub API rate-limit resilience, official repo enforcement, and
 *    full download -> verify -> ReadyToInstall -> installer handoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UnifiedUpdatePipelineTest {

    private lateinit var context: Context

    private val testDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private val fixedClock = object : Clock {
        override fun nowEpochMillis(): Long = 1_795_700_000_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, CloudUpdateManager.UPDATES_CACHE_DIR).deleteRecursively()
    }

    @Test
    fun buildConfigPointsToOfficialShareFlowRepoNotLegacy() {
        assertTrue(
            "GITHUB_RELEASES_API_URL must point to ow7ab/Share-Flow",
            BuildConfig.GITHUB_RELEASES_API_URL.equals(
                "https://api.github.com/repos/ow7ab/Share-Flow/releases",
                ignoreCase = true
            )
        )
        assertTrue(
            "CLOUD_UPDATE_MANIFEST_URL must point to ow7ab.github.io/Share-Flow/version.json",
            BuildConfig.CLOUD_UPDATE_MANIFEST_URL.equals(
                "https://ow7ab.github.io/Share-Flow/version.json",
                ignoreCase = true
            )
        )
        assertFalse(BuildConfig.GITHUB_RELEASES_API_URL.contains("Share-Flow-Releases", ignoreCase = true))
        assertFalse(BuildConfig.CLOUD_UPDATE_MANIFEST_URL.contains("Share-Flow-Releases", ignoreCase = true))
    }

    @Test
    fun remoteApkManifestReader_extractsRealVersionCodeAndPackageFromDeflatedApkViaHttpRange() = runBlocking {
        val apkBytes = buildSyntheticRealApkBytes(
            packageName = "com.shareflow",
            versionCode = 25,
            versionName = "1.1.2",
            minSdkVersion = 24
        )
        val rangeClient = RangeBackedHttpClient(
            textResponses = emptyMap(),
            apkByUrl = mapOf("https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk" to apkBytes)
        )

        val result = RemoteApkManifestReader.readFromUrl(
            apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk",
            httpClient = rangeClient
        )

        assertTrue("Expected Success, got $result", result is RemoteApkManifestResult.Success)
        val meta = (result as RemoteApkManifestResult.Success).metadata
        assertEquals("com.shareflow", meta.packageName)
        assertEquals(25, meta.versionCode)
        assertEquals("1.1.2", meta.versionName)
        assertEquals(24, meta.minSdkVersion)
        assertTrue("Expected bounded HTTP Range requests", rangeClient.rangeCallCount in 1..3)
    }

    @Test
    fun releaseV112WithoutVersionJsonOrBuildText_discoversRealVersionCodeFromApkManifest() = runBlocking {
        val apkBytes = buildSyntheticRealApkBytes(
            packageName = "com.shareflow",
            versionCode = 25,
            versionName = "1.1.2",
            minSdkVersion = 24
        )
        val apkSha = sha256Hex(apkBytes)
        val apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk"

        // Reproduces exact production bug: GitHub Release tag "V1.1.2" with empty body,
        // NO version.json asset, and 404 on static portal version.json.
        val releasesJson = """
            [
              {
                "id": 9102,
                "tag_name": "V1.1.2",
                "name": "V1.1.2",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-27T20:00:00Z",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.1.2",
                "body": "",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "browser_download_url": "$apkUrl",
                    "size": ${apkBytes.size},
                    "digest": "sha256:$apkSha"
                  }
                ]
              }
            ]
        """.trimIndent()

        val prefs = InMemoryUpdatePreferencesStore()
        val httpClient = RangeBackedHttpClient(
            textResponses = mapOf(
                "api.github.com" to AppResult.Success(releasesJson)
            ),
            apkByUrl = mapOf(apkUrl to apkBytes)
        )

        val manager = createManager(
            httpClient = httpClient,
            currentVersionCode = 23,
            currentVersionName = "1.1.0",
            expectedPackageName = "com.shareflow",
            preferencesStore = prefs
        )

        val state = manager.checkForUpdates()
        assertTrue("Expected UpdateAvailable for V1.1.2, got $state", state is CloudUpdateState.UpdateAvailable)
        val manifest = (state as CloudUpdateState.UpdateAvailable).manifest
        assertEquals(25, manifest.versionCode)
        assertEquals("1.1.2", manifest.versionName)
        assertEquals(apkUrl, manifest.downloadUrl)
        assertEquals(apkSha, manifest.sha256)
        assertEquals(CloudUpdateManager.APK_MANIFEST_SOURCE_LABEL, prefs.getSnapshot().activeSourceLabel)
        assertEquals(UpdateServerConnectionStatus.CONNECTED, prefs.getSnapshot().serverStatus)
    }

    @Test
    fun unifiedVersionJsonPrimary_succeedsEvenWhenGitHubApiIsRateLimited() = runBlocking {
        val apkSha = "a".repeat(64)
        val unifiedVersionJson = """
            {
              "versionCode": 26,
              "versionName": "V1.1.3",
              "downloadUrl": "https://github.com/ow7ab/Share-Flow/releases/download/v1.1.3/Share-Flow.apk",
              "releaseNotes": "Performance boost and instant update discovery",
              "isMandatory": true,
              "sha256": "$apkSha",
              "fileSizeBytes": 28500000,
              "minSupportedAndroidSdk": 24
            }
        """.trimIndent()

        val httpClient = RangeBackedHttpClient(
            textResponses = mapOf(
                "version.json" to AppResult.Success(unifiedVersionJson),
                "api.github.com" to AppResult.Failure(
                    AppError.Network(
                        message = "GitHub API rate limit reached (HTTP 403).",
                        cause = UpdateNetworkException(
                            kind = UpdateNetworkFailureKind.RATE_LIMITED,
                            httpCode = 403,
                            message = "Rate limit exceeded"
                        )
                    )
                )
            )
        )

        val manager = createManager(
            httpClient = httpClient,
            currentVersionCode = 25,
            currentVersionName = "1.1.2"
        )

        val state = manager.checkForUpdates()
        assertTrue("Unified version.json must succeed despite GitHub 403 rate limit, got $state", state is CloudUpdateState.UpdateAvailable)
        val manifest = (state as CloudUpdateState.UpdateAvailable).manifest
        assertEquals(26, manifest.versionCode)
        assertEquals("1.1.3", manifest.versionName) // V1.1.3 normalized cleanly to 1.1.3
        assertEquals("https://github.com/ow7ab/Share-Flow/releases/download/v1.1.3/Share-Flow.apk", manifest.downloadUrl)
        assertTrue(manifest.isMandatory)
        assertTrue(manifest.releaseNotes.contains("Performance boost"))
    }

    @Test
    fun versionNameTagVariants_allNormalizeConsistentlyAcrossUppercaseLowercaseAndPlain() {
        assertEquals("1.1.2", GitHubReleasesParser.normalizeVersionName("V1.1.2"))
        assertEquals("1.1.2", GitHubReleasesParser.normalizeVersionName("v1.1.2"))
        assertEquals("1.1.2", GitHubReleasesParser.normalizeVersionName("1.1.2"))
        assertEquals("1.1.2", GitHubReleasesParser.normalizeVersionName("release-v1.1.2"))
        assertEquals("1.2.0", GitHubReleasesParser.normalizeVersionName("V1.2"))
    }

    @Test
    fun remoteApkWrongPackageId_isRejectedBeforeDownloadingApk() = runBlocking {
        val spoofApkBytes = buildSyntheticRealApkBytes(
            packageName = "com.malicious.fakeapp",
            versionCode = 25,
            versionName = "1.1.2",
            minSdkVersion = 24
        )
        val apkSha = sha256Hex(spoofApkBytes)
        val apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk"

        val releasesJson = """
            [
              {
                "id": 9103,
                "tag_name": "V1.1.2",
                "name": "V1.1.2",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-27T20:00:00Z",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.1.2",
                "body": "",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "browser_download_url": "$apkUrl",
                    "size": ${spoofApkBytes.size},
                    "digest": "sha256:$apkSha"
                  }
                ]
              }
            ]
        """.trimIndent()

        val httpClient = RangeBackedHttpClient(
            textResponses = mapOf("api.github.com" to AppResult.Success(releasesJson)),
            apkByUrl = mapOf(apkUrl to spoofApkBytes)
        )
        val manager = createManager(
            httpClient = httpClient,
            currentVersionCode = 23,
            currentVersionName = "1.1.0",
            expectedPackageName = "com.shareflow"
        )

        val state = manager.checkForUpdates()
        assertTrue("Expected Error for mismatched package ID, got $state", state is CloudUpdateState.Error)
        val err = state as CloudUpdateState.Error
        assertEquals(CloudUpdateErrorReason.VERSION_METADATA_MISMATCH, err.reason)
        assertTrue(err.detail.contains("com.malicious.fakeapp"))
        assertEquals(0, httpClient.fullDownloadCount)
    }

    @Test
    fun incompatibleMinSdkAndDowngrade_blockedBeforeDownloadStarts() = runBlocking {
        val apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/v1.2.0/Share-Flow.apk"
        val httpClient = RangeBackedHttpClient(textResponses = emptyMap())
        val manager = createManager(
            httpClient = httpClient,
            currentVersionCode = 25,
            currentVersionName = "1.1.2",
            currentSdkInt = 28
        )

        val incompatibleManifest = CloudUpdateManifest(
            versionCode = 26,
            versionName = "1.2.0",
            apkDownloadUrl = apkUrl,
            sha256 = "b".repeat(64),
            fileSizeBytes = 1024L,
            minSupportedAndroidSdk = 33
        )
        val sdkResult = manager.downloadAndVerifyUpdate(incompatibleManifest)
        assertTrue(sdkResult is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.INCOMPATIBLE_DEVICE, (sdkResult as CloudUpdateState.Error).reason)
        assertEquals(0, httpClient.fullDownloadCount)

        val downgradeManifest = incompatibleManifest.copy(
            versionCode = 24,
            versionName = "1.1.1",
            minSupportedAndroidSdk = 24
        )
        val downgradeResult = manager.downloadAndVerifyUpdate(downgradeManifest)
        assertTrue(downgradeResult is CloudUpdateState.Error)
        assertEquals(CloudUpdateErrorReason.DOWNGRADE_BLOCKED, (downgradeResult as CloudUpdateState.Error).reason)
        assertEquals(0, httpClient.fullDownloadCount)
    }

    @Test
    fun diagnosticsIncludePerSourceFailureDetailsAndAreVisibleInUi() = runBlocking {
        val releasesJson = """
            [
              {
                "id": 9104,
                "tag_name": "V1.1.2",
                "name": "V1.1.2",
                "draft": false,
                "prerelease": false,
                "published_at": "2026-11-27T20:00:00Z",
                "html_url": "https://github.com/ow7ab/Share-Flow/releases/tag/V1.1.2",
                "body": "",
                "assets": [
                  {
                    "name": "Share-Flow.apk",
                    "browser_download_url": "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk",
                    "size": 1000,
                    "digest": "sha256:${"c".repeat(64)}"
                  }
                ]
              }
            ]
        """.trimIndent()

        val httpClient = RangeBackedHttpClient(
            textResponses = mapOf("api.github.com" to AppResult.Success(releasesJson)),
            apkByUrl = emptyMap() // Range probe returns 404
        )
        val manager = createManager(
            httpClient = httpClient,
            currentVersionCode = 23,
            currentVersionName = "1.1.0"
        )

        val state = manager.checkForUpdates()
        assertTrue(state is CloudUpdateState.Error)
        val err = state as CloudUpdateState.Error
        assertEquals(CloudUpdateErrorReason.UNREADABLE_VERSION_METADATA, err.reason)
        assertTrue("Diagnostics should mention version.json and APK probe", err.detail.contains("version.json"))
        assertTrue("Diagnostics should mention APK manifest probe", err.detail.contains("APK manifest probe"))
        assertTrue(shouldShowUpdateErrorDetail(err.reason))
        assertTrue(shouldShowUpdateErrorDetail(CloudUpdateErrorReason.NETWORK_ERROR))
        assertTrue(shouldShowUpdateErrorDetail(CloudUpdateErrorReason.RATE_LIMITED))
        assertTrue(shouldShowUpdateErrorDetail(CloudUpdateErrorReason.INVALID_MANIFEST))
    }

    @Test
    fun fullEndToEndFlow_discoverV112_download_verifyShaAndApk_andPrepareInstall() = runBlocking {
        val apkBytes = buildSyntheticRealApkBytes(
            packageName = "com.shareflow",
            versionCode = 25,
            versionName = "1.1.2",
            minSdkVersion = 24
        )
        val apkSha = sha256Hex(apkBytes)
        val apkUrl = "https://github.com/ow7ab/Share-Flow/releases/download/V1.1.2/Share-Flow.apk"
        val unifiedJson = """
            {
              "versionCode": 25,
              "versionName": "V1.1.2",
              "downloadUrl": "$apkUrl",
              "releaseNotes": "Fixed update metadata discovery",
              "isMandatory": false,
              "sha256": "$apkSha",
              "fileSizeBytes": ${apkBytes.size},
              "minSupportedAndroidSdk": 24
            }
        """.trimIndent()

        val httpClient = RangeBackedHttpClient(
            textResponses = mapOf("version.json" to AppResult.Success(unifiedJson)),
            apkByUrl = mapOf(apkUrl to apkBytes)
        )
        val approvingInspector = object : ApkPackageSecurityInspector {
            override fun inspectUpdateApk(
                apkFile: File,
                expectedPackageName: String,
                expectedMinVersionCode: Int,
                currentInstalledVersionCode: Int
            ): AppResult<Unit> = AppResult.Success(Unit)

            override fun inspectUpdateApkDetailed(
                apkFile: File,
                expectedPackageName: String,
                expectedVersionCode: Int,
                expectedVersionName: String,
                currentInstalledVersionCode: Int,
                currentInstalledVersionName: String,
                expectedSigningCertSha256: String?
            ): ApkInspectionResult {
                // Parse real binary APK manifest from the downloaded file to verify full integrity!
                val parsed = RemoteApkManifestReader.parseApkBytes(apkFile.readBytes())
                require(parsed is RemoteApkManifestResult.Success) { "Expected valid APK bytes" }
                assertEquals(expectedPackageName, parsed.metadata.packageName)
                assertEquals(expectedVersionCode, parsed.metadata.versionCode)
                assertEquals(expectedVersionName, parsed.metadata.versionName)
                return ApkInspectionResult.Valid(
                    InspectedApkMetadata(
                        packageName = parsed.metadata.packageName,
                        versionCode = parsed.metadata.versionCode,
                        versionName = parsed.metadata.versionName,
                        signingCertSha256Digests = setOf("d".repeat(64))
                    )
                )
            }
        }

        val manager = createManager(
            httpClient = httpClient,
            inspector = approvingInspector,
            canInstallPackages = false,
            currentVersionCode = 23,
            currentVersionName = "1.1.0",
            expectedPackageName = "com.shareflow"
        )

        val checkState = manager.checkForUpdates()
        assertTrue(checkState is CloudUpdateState.UpdateAvailable)
        val manifest = (checkState as CloudUpdateState.UpdateAvailable).manifest
        assertEquals(25, manifest.versionCode)
        assertEquals("1.1.2", manifest.versionName)

        val dlState = manager.downloadAndVerifyUpdate(manifest)
        assertTrue("Expected ReadyToInstall, got $dlState", dlState is CloudUpdateState.ReadyToInstall)
        val apkPath = (dlState as CloudUpdateState.ReadyToInstall).verifiedApkPath
        assertTrue(File(apkPath).exists())

        // Verify installer handoff checks permission gate before launching PackageInstaller
        val launchRes = manager.launchVerifiedUpdateInstaller(apkPath)
        assertEquals(UpdateInstallLaunchResult.PERMISSION_REQUIRED, launchRes)
    }

    // ---- Helpers ----

    private fun createManager(
        httpClient: CloudUpdateHttpClient,
        inspector: ApkPackageSecurityInspector = object : ApkPackageSecurityInspector {
            override fun inspectUpdateApk(
                apkFile: File,
                expectedPackageName: String,
                expectedMinVersionCode: Int,
                currentInstalledVersionCode: Int
            ): AppResult<Unit> = AppResult.Success(Unit)
        },
        canInstallPackages: Boolean = true,
        currentVersionCode: Int = 23,
        currentVersionName: String = "1.1.0",
        currentSdkInt: Int = 34,
        expectedPackageName: String = "com.shareflow",
        preferencesStore: UpdatePreferencesStore = InMemoryUpdatePreferencesStore()
    ): CloudUpdateManager {
        val permManager = PermissionManager(
            context = context,
            sdkInt = currentSdkInt,
            permissionChecker = { true },
            canInstallPackagesChecker = { canInstallPackages }
        )
        return CloudUpdateManager(
            context = context,
            httpClient = httpClient,
            checksumVerifier = Sha256ChecksumVerifier(),
            packageInspector = inspector,
            permissionManager = permManager,
            dispatchers = testDispatchers,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName,
            currentSdkInt = currentSdkInt,
            expectedPackageName = expectedPackageName,
            enableGitHubReleasesPrimary = true,
            preferencesStore = preferencesStore,
            clock = fixedClock
        )
    }

    private class RangeBackedHttpClient(
        private val textResponses: Map<String, AppResult<String>>,
        private val apkByUrl: Map<String, ByteArray> = emptyMap()
    ) : CloudUpdateHttpClient {
        var rangeCallCount = 0
        var fullDownloadCount = 0

        override suspend fun fetchManifestText(manifestUrl: String): AppResult<String> {
            val matched = textResponses.entries.firstOrNull { (key, _) ->
                manifestUrl.contains(key, ignoreCase = true)
            }
            return matched?.value ?: AppResult.Failure(
                AppError.Network(
                    message = "HTTP 404 Not Found",
                    cause = UpdateNetworkException(
                        kind = UpdateNetworkFailureKind.RELEASE_NOT_FOUND,
                        httpCode = 404,
                        message = "HTTP 404 Not Found"
                    )
                )
            )
        }

        override suspend fun fetchByteRange(
            url: String,
            startByte: Long?,
            endByte: Long?,
            suffixBytes: Long?
        ): AppResult<RemoteByteRangeResponse> {
            rangeCallCount++
            val bytes = apkByUrl[url] ?: return AppResult.Failure(
                AppError.Network(
                    message = "HTTP 404 Not Found",
                    cause = UpdateNetworkException(
                        kind = UpdateNetworkFailureKind.RELEASE_NOT_FOUND,
                        httpCode = 404,
                        message = "APK not found"
                    )
                )
            )
            val total = bytes.size.toLong()
            val start = when {
                suffixBytes != null -> (total - suffixBytes).coerceAtLeast(0L)
                startByte != null -> startByte.coerceIn(0L, total - 1L)
                else -> 0L
            }
            val endInclusive = when {
                suffixBytes != null -> total - 1L
                endByte != null -> endByte.coerceIn(start, total - 1L)
                else -> total - 1L
            }
            val slice = bytes.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
            return AppResult.Success(
                RemoteByteRangeResponse(
                    bytes = slice,
                    totalLength = total,
                    rangeStart = start,
                    isPartialContent = true
                )
            )
        }

        override suspend fun downloadApkToFile(
            apkUrl: String,
            destinationFile: File,
            onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
        ): AppResult<File> {
            fullDownloadCount++
            val bytes = apkByUrl[apkUrl] ?: return AppResult.Failure(AppError.Network("APK 404"))
            destinationFile.parentFile?.mkdirs()
            destinationFile.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
            return AppResult.Success(destinationFile)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Generates a genuine ZIP-based Android APK containing a real binary AXML `AndroidManifest.xml`
     * (DEFLATED) and extra padding entries so HTTP Range tail/CD/local-header probing is exercised
     * identically to production ~28 MB APKs.
     */
    private fun buildSyntheticRealApkBytes(
        packageName: String,
        versionCode: Int,
        versionName: String,
        minSdkVersion: Int
    ): ByteArray {
        val axml = buildBinaryAndroidManifestAxml(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            minSdkVersion = minSdkVersion
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(axml)
            zos.closeEntry()

            // Add a dummy classes.dex entry to mirror real APK layout
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(4096) { 0x42 })
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    private fun buildBinaryAndroidManifestAxml(
        packageName: String,
        versionCode: Int,
        versionName: String,
        minSdkVersion: Int
    ): ByteArray {
        // String pool indices:
        // 0: versionCode (resId 0x0101021b)
        // 1: versionName (resId 0x0101021c)
        // 2: minSdkVersion (resId 0x0101020c)
        // 3: package
        // 4: manifest
        // 5: uses-sdk
        // 6: http://schemas.android.com/apk/res/android
        // 7: <packageName>
        // 8: <versionName>
        val strings = listOf(
            "versionCode",
            "versionName",
            "minSdkVersion",
            "package",
            "manifest",
            "uses-sdk",
            "http://schemas.android.com/apk/res/android",
            packageName,
            versionName
        )
        val stringPoolChunk = buildUtf8StringPoolChunk(strings)
        val resMapChunk = buildResourceMapChunk(intArrayOf(0x0101021b, 0x0101021c, 0x0101020c))
        val manifestElemChunk = buildStartElementChunk(
            tagNameIdx = 4,
            attrs = listOf(
                AxmlAttr(nsIdx = -1, nameIdx = 3, rawValueIdx = 7, valueType = 0x03, valueData = 7),
                AxmlAttr(nsIdx = 6, nameIdx = 0, rawValueIdx = -1, valueType = 0x10, valueData = versionCode),
                AxmlAttr(nsIdx = 6, nameIdx = 1, rawValueIdx = 8, valueType = 0x03, valueData = 8)
            )
        )
        val usesSdkElemChunk = buildStartElementChunk(
            tagNameIdx = 5,
            attrs = listOf(
                AxmlAttr(nsIdx = 6, nameIdx = 2, rawValueIdx = -1, valueType = 0x10, valueData = minSdkVersion)
            )
        )

        val totalSize = 8 + stringPoolChunk.size + resMapChunk.size + manifestElemChunk.size + usesSdkElemChunk.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0003) // RES_XML_TYPE
        buf.putShort(8)      // headerSize
        buf.putInt(totalSize)
        buf.put(stringPoolChunk)
        buf.put(resMapChunk)
        buf.put(manifestElemChunk)
        buf.put(usesSdkElemChunk)
        return buf.array()
    }

    private data class AxmlAttr(
        val nsIdx: Int,
        val nameIdx: Int,
        val rawValueIdx: Int,
        val valueType: Int,
        val valueData: Int
    )

    private fun buildUtf8StringPoolChunk(strings: List<String>): ByteArray {
        val offsets = IntArray(strings.size)
        val stringData = ByteArrayOutputStream()
        for (i in strings.indices) {
            offsets[i] = stringData.size()
            val utf8 = strings[i].toByteArray(Charsets.UTF_8)
            stringData.write(strings[i].length and 0x7F)
            stringData.write(utf8.size and 0x7F)
            stringData.write(utf8)
            stringData.write(0)
        }
        // Pad string data to 4-byte boundary
        while (stringData.size() % 4 != 0) stringData.write(0)

        val headerSize = 28
        val offsetsSize = strings.size * 4
        val stringsStart = headerSize + offsetsSize
        val chunkSize = stringsStart + stringData.size()

        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0001) // RES_STRING_POOL_TYPE
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(strings.size)
        buf.putInt(0) // styleCount
        buf.putInt(1 shl 8) // UTF-8 flag
        buf.putInt(stringsStart)
        buf.putInt(0) // stylesStart
        offsets.forEach { buf.putInt(it) }
        buf.put(stringData.toByteArray())
        return buf.array()
    }

    private fun buildResourceMapChunk(resIds: IntArray): ByteArray {
        val chunkSize = 8 + resIds.size * 4
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0180) // RES_XML_RESOURCE_MAP_TYPE
        buf.putShort(8)
        buf.putInt(chunkSize)
        resIds.forEach { buf.putInt(it) }
        return buf.array()
    }

    private fun buildStartElementChunk(tagNameIdx: Int, attrs: List<AxmlAttr>): ByteArray {
        val headerSize = 16
        val extFixedSize = 20
        val attrSize = 20
        val chunkSize = headerSize + extFixedSize + attrs.size * attrSize
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(0x0102) // RES_XML_START_ELEMENT_TYPE
        buf.putShort(headerSize.toShort())
        buf.putInt(chunkSize)
        buf.putInt(1) // lineNumber
        buf.putInt(-1) // comment
        buf.putInt(-1) // nsIdx
        buf.putInt(tagNameIdx)
        buf.putShort(extFixedSize.toShort()) // attrStart
        buf.putShort(attrSize.toShort())     // attrSize
        buf.putShort(attrs.size.toShort())   // attrCount
        buf.putShort(0) // idIndex
        buf.putShort(0) // classIndex
        buf.putShort(0) // styleIndex
        attrs.forEach { a ->
            buf.putInt(a.nsIdx)
            buf.putInt(a.nameIdx)
            buf.putInt(a.rawValueIdx)
            buf.putShort(8) // Res_value.size
            buf.put(0)      // Res_value.res0
            buf.put(a.valueType.toByte())
            buf.putInt(a.valueData)
        }
        return buf.array()
    }
}
