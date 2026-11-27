package com.example.shareflow.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.permissions.PermissionManager
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.repository.ApkArchiveInspector
import com.example.shareflow.data.repository.ApkArchiveMetadata
import com.example.shareflow.domain.model.ShareFlowApkPartManifest
import com.example.shareflow.domain.model.ShareFlowAppBundleManifest
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApplicationInstallationManagerTest {

    private lateinit var context: Context
    private lateinit var tempReceiveRoot: File
    private val verifier = Sha256ChecksumVerifier()

    private class FakeApkInspector(
        private val packageMap: Map<String, ApkArchiveMetadata?>
    ) : ApkArchiveInspector {
        override fun inspectApkArchive(apkFile: File): ApkArchiveMetadata? =
            packageMap[apkFile.name]
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempReceiveRoot = File(context.cacheDir, "test_install_root_${System.nanoTime()}").apply {
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempReceiveRoot.deleteRecursively()
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `validates single APK inside verified root and rejects tampered hash or path traversal`() = runTest {
        val apkBytes = "PK\u0003\u0004valid_single_apk_payload".toByteArray()
        val apkFile = File(tempReceiveRoot, "sample_app.apk").apply { writeBytes(apkBytes) }
        val outsideFile = File(context.cacheDir, "outside_app_${System.nanoTime()}.apk").apply {
            writeBytes(apkBytes)
        }
        val expectedHash = sha256Of(apkBytes)

        val inspector = FakeApkInspector(
            mapOf(
                apkFile.name to ApkArchiveMetadata("com.sample.app", "Sample App", "2.1.0", 21L),
                outsideFile.name to ApkArchiveMetadata("com.sample.app", "Sample App", "2.1.0", 21L)
            )
        )
        val permManager = PermissionManager(context, canInstallPackagesChecker = { true })
        val manager = ApplicationInstallationManager(
            context = context,
            checksumVerifier = verifier,
            permissionManager = permManager,
            apkArchiveInspector = inspector,
            receiveRootProvider = { tempReceiveRoot },
            availableStorageBytesProvider = { 500L * 1024L * 1024L }
        )

        // 1. Valid single APK succeeds
        val okRes = manager.validateApkFiles(apkFile, expectedPrimarySha256 = expectedHash)
        assertTrue(okRes is AppResult.Success)
        val bundle = (okRes as AppResult.Success).data
        assertEquals("com.sample.app", bundle.packageName)
        assertEquals("Sample App", bundle.appName)
        assertFalse(bundle.isSplitBundle)
        assertEquals(1, bundle.apkFiles.size)

        // 2. Tampered SHA-256 is rejected with IntegrityMismatch
        val badHashRes = manager.validateApkFiles(apkFile, expectedPrimarySha256 = "0".repeat(64))
        assertTrue(badHashRes is AppResult.Failure)
        assertTrue((badHashRes as AppResult.Failure).error is AppError.IntegrityMismatch)

        // 3. Outside-root APK path traversal is rejected with Security error
        val outsideRes = manager.validateApkFiles(outsideFile, expectedPrimarySha256 = expectedHash)
        assertTrue(outsideRes is AppResult.Failure)
        assertTrue((outsideRes as AppResult.Failure).error is AppError.Security)

        outsideFile.delete()
        manager.dispose()
    }

    @Test
    fun `validates complete split APK bundle and blocks missing splits or package mismatch`() = runTest {
        val bundleDir = File(tempReceiveRoot, "DemoSplit_split_apk").apply { mkdirs() }
        val baseBytes = "PK\u0003\u0004base_apk_data".toByteArray()
        val splitBytes = "PK\u0003\u0004split_arm64_data".toByteArray()
        val baseFile = File(bundleDir, "base.apk").apply { writeBytes(baseBytes) }
        val splitFile = File(bundleDir, "split_config.arm64_v8a.apk").apply { writeBytes(splitBytes) }

        val manifest = ShareFlowAppBundleManifest(
            packageName = "com.example.splitgame",
            appName = "Split Game",
            versionName = "3.4.0",
            versionCode = 340L,
            baseApkFileName = "base.apk",
            parts = listOf(
                ShareFlowApkPartManifest("base.apk", baseBytes.size.toLong(), sha256Of(baseBytes), isBase = true),
                ShareFlowApkPartManifest("split_config.arm64_v8a.apk", splitBytes.size.toLong(), sha256Of(splitBytes), isBase = false)
            )
        )
        val manifestFile = File(bundleDir, "shareflow_app_manifest.json").apply {
            writeText(manifest.toJsonString(), Charsets.UTF_8)
        }

        val matchingInspector = FakeApkInspector(
            mapOf(
                "base.apk" to ApkArchiveMetadata("com.example.splitgame", "Split Game", "3.4.0", 340L),
                "split_config.arm64_v8a.apk" to ApkArchiveMetadata("com.example.splitgame", "Split Game", "3.4.0", 340L)
            )
        )
        val permManager = PermissionManager(context, canInstallPackagesChecker = { false })
        val manager = ApplicationInstallationManager(
            context = context,
            checksumVerifier = verifier,
            permissionManager = permManager,
            apkArchiveInspector = matchingInspector,
            receiveRootProvider = { tempReceiveRoot },
            availableStorageBytesProvider = { 500L * 1024L * 1024L }
        )

        // 1. Valid split APK bundle succeeds with base.apk ordered first
        val valid = manager.validateApkFiles(baseFile, manifestFile = manifestFile)
        assertTrue(valid is AppResult.Success)
        val splitBundle = (valid as AppResult.Success).data
        assertTrue(splitBundle.isSplitBundle)
        assertEquals(2, splitBundle.apkFiles.size)
        assertEquals("base.apk", splitBundle.apkFiles.first().name)

        // 2. When unknown-sources permission is not granted, staging halts cleanly with NeedsUnknownSourcesPermission
        val installGate = manager.installValidatedBundle(splitBundle)
        assertTrue(installGate is AppResult.Success)
        assertTrue(manager.installState.value is AppInstallSessionState.NeedsUnknownSourcesPermission)

        // 3. Mismatched packageName in split APK is strictly blocked
        val mismatchedInspector = FakeApkInspector(
            mapOf(
                "base.apk" to ApkArchiveMetadata("com.example.splitgame", "Split Game", "3.4.0", 340L),
                "split_config.arm64_v8a.apk" to ApkArchiveMetadata("com.evil.otherpkg", "Evil", "1.0", 1L)
            )
        )
        val mismatchManager = ApplicationInstallationManager(
            context = context,
            checksumVerifier = verifier,
            permissionManager = permManager,
            apkArchiveInspector = mismatchedInspector,
            receiveRootProvider = { tempReceiveRoot },
            availableStorageBytesProvider = { 500L * 1024L * 1024L }
        )
        val mismatchRes = mismatchManager.validateApkFiles(baseFile, manifestFile = manifestFile)
        assertTrue(mismatchRes is AppResult.Failure)
        assertTrue((mismatchRes as AppResult.Failure).error is AppError.Security)

        // 4. Deleting a required split APK makes bundle incomplete & un-installable
        splitFile.delete()
        val missingSplitRes = manager.validateApkFiles(baseFile, manifestFile = manifestFile)
        assertTrue(missingSplitRes is AppResult.Failure)

        manager.dispose()
        mismatchManager.dispose()
    }

    @Test
    fun `handles PackageInstaller pending user confirmation, success, and cancellation callbacks`() {
        val permManager = PermissionManager(context, canInstallPackagesChecker = { true })
        val manager = ApplicationInstallationManager(
            context = context,
            checksumVerifier = verifier,
            permissionManager = permManager,
            receiveRootProvider = { tempReceiveRoot }
        )

        val confirmIntent = Intent("android.content.pm.action.CONFIRM_INSTALL")
        manager.handleInstallCallback(
            PackageInstallCallbackEvent(
                sessionId = 42,
                packageName = "com.example.demo",
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                statusMessage = "",
                confirmationIntent = confirmIntent
            )
        )
        assertTrue(manager.installState.value is AppInstallSessionState.AwaitingUserConfirmation)

        manager.handleInstallCallback(
            PackageInstallCallbackEvent(
                sessionId = 42,
                packageName = "com.example.demo",
                status = PackageInstaller.STATUS_SUCCESS,
                statusMessage = "",
                confirmationIntent = null
            )
        )
        assertTrue(manager.installState.value is AppInstallSessionState.Succeeded)

        manager.handleInstallCallback(
            PackageInstallCallbackEvent(
                sessionId = 43,
                packageName = "com.example.demo",
                status = PackageInstaller.STATUS_FAILURE_ABORTED,
                statusMessage = "User cancelled",
                confirmationIntent = null
            )
        )
        val failed = manager.installState.value as AppInstallSessionState.Failed
        assertTrue(failed.wasCancelledByUser)

        manager.dispose()
    }

    @Test
    fun `accepts valid config split APK ZIP when PackageManager returns null metadata and rejects corrupt split`() = runTest {
        val bundleDir = File(tempReceiveRoot, "ConfigSplit_split_apk").apply { mkdirs() }
        val baseBytes = createMinimalApkZipBytes("base")
        val configSplitBytes = createMinimalApkZipBytes("split_config_xxhdpi")
        val baseFile = File(bundleDir, "base.apk").apply { writeBytes(baseBytes) }
        val configSplitFile = File(bundleDir, "split_config.xxhdpi.apk").apply { writeBytes(configSplitBytes) }

        val manifest = ShareFlowAppBundleManifest(
            packageName = "com.example.configsplit",
            appName = "Config Split App",
            versionName = "1.2.0",
            versionCode = 12L,
            baseApkFileName = "base.apk",
            parts = listOf(
                ShareFlowApkPartManifest("base.apk", baseBytes.size.toLong(), sha256Of(baseBytes), isBase = true),
                ShareFlowApkPartManifest("split_config.xxhdpi.apk", configSplitBytes.size.toLong(), sha256Of(configSplitBytes), isBase = false)
            )
        )
        val manifestFile = File(bundleDir, "shareflow_app_manifest.json").apply {
            writeText(manifest.toJsonString(), Charsets.UTF_8)
        }

        // Real Android PackageManager.getPackageArchiveInfo often returns null for resource/ABI config splits
        val inspectorWithNullSplit = FakeApkInspector(
            mapOf(
                "base.apk" to ApkArchiveMetadata("com.example.configsplit", "Config Split App", "1.2.0", 12L),
                "split_config.xxhdpi.apk" to null
            )
        )
        val permManager = PermissionManager(context, canInstallPackagesChecker = { true })
        val manager = ApplicationInstallationManager(
            context = context,
            checksumVerifier = verifier,
            permissionManager = permManager,
            apkArchiveInspector = inspectorWithNullSplit,
            receiveRootProvider = { tempReceiveRoot },
            availableStorageBytesProvider = { 500L * 1024L * 1024L }
        )

        val validResult = manager.validateApkFiles(baseFile, manifestFile = manifestFile)
        assertTrue("Valid config split ZIP must pass validation", validResult is AppResult.Success)
        val validated = (validResult as AppResult.Success).data
        assertEquals(2, validated.apkFiles.size)
        assertEquals("base.apk", validated.apkFiles.first().name)

        // Now corrupt the config split with non-ZIP bytes and update manifest hash to match corrupt bytes
        val corruptBytes = "NOT_A_VALID_ZIP_ARCHIVE".toByteArray()
        configSplitFile.writeBytes(corruptBytes)
        val corruptManifest = manifest.copy(
            parts = listOf(
                ShareFlowApkPartManifest("base.apk", baseBytes.size.toLong(), sha256Of(baseBytes), isBase = true),
                ShareFlowApkPartManifest("split_config.xxhdpi.apk", corruptBytes.size.toLong(), sha256Of(corruptBytes), isBase = false)
            )
        )
        manifestFile.writeText(corruptManifest.toJsonString(), Charsets.UTF_8)

        val corruptResult = manager.validateApkFiles(baseFile, manifestFile = manifestFile)
        assertTrue("Corrupt non-ZIP split must still be rejected", corruptResult is AppResult.Failure)

        manager.dispose()
    }

    private fun createMinimalApkZipBytes(marker: String): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zos.write("binary_manifest_$marker".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
