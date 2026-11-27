package com.example.shareflow.security

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppError
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.update.CloudUpdateValidator
import com.example.shareflow.data.update.fcm.FcmUpdatePayload
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.SmartConnectionErrorCode
import com.example.shareflow.domain.model.SmartStrategyType
import com.example.shareflow.network.TransferProtocol
import com.example.shareflow.network.qr.SmartQrPairingProtocol
import com.example.shareflow.network.qr.SmartQrParseResult
import com.example.shareflow.network.security.AndroidDeviceIdentityManager
import com.example.shareflow.network.security.IncomingApprovalCallback
import com.example.shareflow.network.security.PairingTokenManager
import com.example.shareflow.network.security.PeerHandshakeProtocol
import com.example.shareflow.network.security.ReplayCheckResult
import com.example.shareflow.network.security.ReplayProtectionCache
import com.example.shareflow.network.security.TrustedFingerprintLookup
import com.example.shareflow.network.transfer.ChunkProcessor
import com.example.shareflow.testutil.TestDispatcherProvider
import com.example.shareflow.ui.viewer.ApkInstallerIntentHelper
import com.example.shareflow.vault.AesGcmBox
import com.example.shareflow.vault.KeystoreUnwrapResult
import com.example.shareflow.vault.VaultCryptoEngine
import com.example.shareflow.vault.VaultKeystoreProvider
import com.example.shareflow.vault.VaultManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pre-deployment security verification suite proving that every remediated vulnerability
 * blocks realistic attack payloads while preserving legitimate application behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PreDeploymentSecurityAuditTest {

    private lateinit var context: Context
    private lateinit var db: ShareFlowDatabase
    private lateinit var vaultRootDir: File
    private lateinit var vaultBlobDir: File
    private lateinit var clock: MutableClock
    private lateinit var cryptoEngine: VaultCryptoEngine
    private lateinit var keystoreProvider: FakeVaultKeystoreProvider

    private class MutableClock(var now: Long = 1_730_000_000_000L) : Clock {
        override fun nowEpochMillis(): Long = now
    }

    private class FakeVaultKeystoreProvider(
        private val crypto: VaultCryptoEngine
    ) : VaultKeystoreProvider {
        private val keys = mutableMapOf<String, ByteArray>()

        override fun isAvailable(): Boolean = true

        override fun ensureKeyExists(alias: String): AppResult<Unit> {
            keys.getOrPut(alias) { crypto.generateAes256Key() }
            return AppResult.Success(Unit)
        }

        override fun wrapKey(alias: String, plainBytes: ByteArray, aad: ByteArray): AppResult<AesGcmBox> {
            val key = keys.getOrPut(alias) { crypto.generateAes256Key() }
            return crypto.encryptAes256Gcm(key, plainBytes, aad)
        }

        override fun unwrapKey(alias: String, box: AesGcmBox, aad: ByteArray): KeystoreUnwrapResult {
            val key = keys[alias] ?: return KeystoreUnwrapResult.KeyInvalidated("Missing key")
            return when (val res = crypto.decryptAes256Gcm(key, box, aad)) {
                is AppResult.Success -> KeystoreUnwrapResult.Success(res.data)
                is AppResult.Failure -> KeystoreUnwrapResult.Failure(res.error)
            }
        }

        override fun deleteKey(alias: String): AppResult<Unit> {
            keys.remove(alias)
            return AppResult.Success(Unit)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vaultRootDir = File(context.cacheDir, "sec_audit_${UUID.randomUUID()}").apply { mkdirs() }
        vaultBlobDir = File(vaultRootDir, "blobs").apply { mkdirs() }
        clock = MutableClock()
        cryptoEngine = VaultCryptoEngine(pinKdfIterations = 2_000)
        keystoreProvider = FakeVaultKeystoreProvider(cryptoEngine)
    }

    @After
    fun tearDown() {
        db.close()
        vaultRootDir.deleteRecursively()
    }

    private fun newVaultManager(): VaultManager = VaultManager(
        context = context,
        vaultConfigDao = db.vaultConfigDao(),
        vaultItemDao = db.vaultItemDao(),
        cryptoEngine = cryptoEngine,
        keystoreProvider = keystoreProvider,
        dispatchers = TestDispatcherProvider(Dispatchers.Unconfined),
        clock = clock,
        vaultBlobDirProvider = { vaultBlobDir }
    )

    private fun createMinimalValidApkZip(target: File) {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(byteArrayOf(0x03, 0x00, 0x08, 0x00, 0x10, 0x00, 0x00, 0x00))
            zos.closeEntry()
        }
    }

    // =========================================================================
    // 1. Exported ApkInstallIntentActivity URI Confinement & Forwarding Guards
    // =========================================================================

    @Test
    fun `ApkInstallerIntentHelper blocks private file URIs, self-FileProvider abuse, and raw URI forwarding`() {
        // Attack A: file:// pointing at ShareFlow's private app storage
        val privateSecret = File(context.filesDir, "private_db_backup.apk")
        createMinimalValidApkZip(privateSecret)
        try {
            val stagedPrivate = ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = Uri.fromFile(privateSecret),
                checksumVerifier = Sha256ChecksumVerifier()
            )
            assertNull("Private app storage file:// URI must never be staged", stagedPrivate)
            assertFalse(
                "Rejected URI must never allow external system installer forwarding",
                ApkInstallerIntentHelper.canForwardToExternalSystemInstaller(stagedPrivate)
            )
        } finally {
            privateSecret.delete()
        }

        // Attack B: self-FileProvider content:// targeting non-received internal paths or traversal
        val selfAuthority = "${context.packageName}.fileprovider"
        val forbiddenSelfUris = listOf(
            Uri.parse("content://$selfAuthority/shareflow_updates/shareflow-v24.apk"),
            Uri.parse("content://$selfAuthority/shareflow_qr_shares/qr.apk"),
            Uri.parse("content://$selfAuthority/shareflow_apk_exports/export.apk"),
            Uri.parse("content://$selfAuthority/received_files/../databases/shareflow.db"),
            Uri.parse("content://$selfAuthority/received_files/notes.txt"),
            Uri.parse("content://$selfAuthority/")
        )
        forbiddenSelfUris.forEach { maliciousUri ->
            assertFalse(
                "Self-FileProvider URI should be blocked: $maliciousUri",
                ApkInstallerIntentHelper.isAllowedContentUri(context, maliciousUri)
            )
        }

        // Legitimate paths: external provider content:// and confined received_files/*.apk
        assertTrue(
            ApkInstallerIntentHelper.isAllowedContentUri(
                context,
                Uri.parse("content://com.android.providers.downloads.documents/document/42")
            )
        )
        assertTrue(
            ApkInstallerIntentHelper.isAllowedContentUri(
                context,
                Uri.parse("content://$selfAuthority/received_files/legit_app.apk")
            )
        )

        // Legitimate staged APK inside receive root IS allowed to forward cleanly
        val receiveRoot = ChunkProcessor.defaultReceiveRoot(context).apply { mkdirs() }
        val legitApk = File(receiveRoot, "legit_verified_${UUID.randomUUID()}.apk")
        createMinimalValidApkZip(legitApk)
        try {
            val staged = ApkInstallerIntentHelper.inspectAndStageExternalApk(
                context = context,
                incomingUri = Uri.fromFile(legitApk),
                checksumVerifier = Sha256ChecksumVerifier()
            )
            assertNotNull("Valid received APK should stage cleanly", staged)
            assertTrue(
                "Validated staged APK should allow scoped system installer handoff",
                ApkInstallerIntentHelper.canForwardToExternalSystemInstaller(staged)
            )
        } finally {
            legitApk.delete()
        }
    }

    // =========================================================================
    // 2. Private Vault Blob Path Confinement & Biometric Lockout Enforcement
    // =========================================================================

    @Test
    fun `VaultManager blocks path traversal on blob resolution, decryption, export, and secure deletion`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("739204", enableBiometric = true) is AppResult.Success)

        val outsideFile = File(vaultRootDir, "outside_sensitive.txt").apply {
            writeText("DO_NOT_DELETE_OR_READ")
        }

        // Direct path traversal via getEncryptedBlobFile must throw SecurityException
        listOf(
            "../outside_sensitive.txt",
            "../../databases/shareflow.db",
            "nested/evil.sfv",
            "evil\\blob.sfv",
            "blob\u0000.sfv",
            ""
        ).forEach { badName ->
            var threw = false
            try {
                manager.getEncryptedBlobFile(badName)
            } catch (_: SecurityException) {
                threw = true
            }
            assertTrue("Expected SecurityException for unsafe blob name '$badName'", threw)
        }

        // Import legitimate file then tamper its Room row encryptedFileName to escape blobDir
        val imported = (
            manager.importFile("secret.txt", "text/plain") {
                ByteArrayInputStream("top-secret-payload".toByteArray())
            } as AppResult.Success
        ).data

        val originalEntity = db.vaultItemDao().findById(imported.id)!!
        db.vaultItemDao().upsert(originalEntity.copy(encryptedFileName = "../outside_sensitive.txt"))

        val decRes = manager.decryptToMemory(imported.id)
        assertTrue(decRes is AppResult.Failure && decRes.error is AppError.Security)

        val exportRes = manager.exportFile(imported.id) { ByteArrayOutputStream() }
        assertTrue(exportRes is AppResult.Failure && exportRes.error is AppError.Security)

        val delRes = manager.secureDeleteItem(imported.id)
        assertTrue(delRes is AppResult.Failure && delRes.error is AppError.Security)
        assertTrue("External file outside vault must remain intact", outsideFile.exists())
        assertEquals("DO_NOT_DELETE_OR_READ", outsideFile.readText())
    }

    @Test
    fun `VaultManager enforces active brute-force lockout during biometric unlock`() = runTest {
        val manager = newVaultManager()
        assertTrue(manager.createVault("618429", enableBiometric = true) is AppResult.Success)
        manager.lock()

        // Trigger lockout via 5 incorrect PIN attempts
        repeat(5) {
            val attempt = manager.unlockWithPin("000000")
            assertTrue(attempt is AppResult.Failure)
        }

        // Biometric unlock must NOT bypass active lockout
        val biometricDuringLockout = manager.unlockWithBiometricAfterPromptSuccess()
        assertTrue(
            "Biometric unlock must be blocked during active lockout",
            biometricDuringLockout is AppResult.Failure && biometricDuringLockout.error is AppError.Security
        )

        // Once lockout window expires, biometric unlock succeeds normally
        clock.now += 60_000L
        val biometricAfterLockout = manager.unlockWithBiometricAfterPromptSuccess()
        assertTrue("Biometric unlock should succeed after lockout expires", biometricAfterLockout is AppResult.Success)
    }

    // =========================================================================
    // 3. PeerHandshakeProtocol Pre-Auth Replay Cache Pollution Protection
    // =========================================================================

    @Test
    fun `PeerHandshakeProtocol rejects forged signature without polluting ReplayProtectionCache`() = runTest {
        val serverIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val clientIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false)
        val clientId = clientIdentity.getLocalIdentity()

        val serverReplayCache = ReplayProtectionCache(clock = clock)
        val serverProtocol = PeerHandshakeProtocol(
            identityManager = serverIdentity,
            pairingTokenManager = PairingTokenManager(clock = clock),
            replayCache = serverReplayCache,
            clock = clock
        )

        val targetNonceB64 = AndroidDeviceIdentityManager.encodeBase64Url(ByteArray(16) { (it + 7).toByte() })
        val now = clock.nowEpochMillis()
        val forgedBody = listOf(
            "CLIENT_HELLO",
            TransferProtocol.PROTOCOL_VERSION.toString(),
            "0123456789abcdef0123456789abcdef",
            clientId.deviceId,
            "SpoofedPeer",
            clientId.publicKeyBase64,
            clientId.publicKeyBase64,
            targetNonceB64,
            now.toString(),
            "",
            ConnectionType.LOCAL_LAN_NSD.name
        ).joinToString("|")
        val invalidSigB64 = AndroidDeviceIdentityManager.encodeBase64Url(ByteArray(64) { 0x5A })
        val wirePayload = "$forgedBody\nSIG=$invalidSigB64".toByteArray(Charsets.UTF_8)

        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { serverSocket ->
            val serverJob = async(Dispatchers.IO) {
                serverSocket.accept().use { accepted ->
                    serverProtocol.performServerHandshake(
                        socket = accepted,
                        localDisplayName = "Receiver",
                        requirePairingToken = false,
                        trustedLookup = TrustedFingerprintLookup { _, _ -> false },
                        approvalCallback = IncomingApprovalCallback { true }
                    )
                }
            }

            Socket(InetAddress.getLoopbackAddress(), serverSocket.localPort).use { clientSock ->
                val out = DataOutputStream(clientSock.getOutputStream())
                out.write(TransferProtocol.MAGIC_HEADER.toByteArray(Charsets.US_ASCII))
                out.writeInt(wirePayload.size)
                out.write(wirePayload)
                out.flush()
                // Drain server reject frame cleanly
                runCatching {
                    val input = DataInputStream(clientSock.getInputStream())
                    val magic = ByteArray(4)
                    input.readFully(magic)
                    val len = input.readInt()
                    if (len in 1..4096) input.readFully(ByteArray(len))
                }
            }

            val serverRes = serverJob.await()
            assertTrue("Forged signature must be rejected", serverRes is AppResult.Failure)
            assertTrue(
                "Error should report signature failure, not replay",
                (serverRes as AppResult.Failure).error.message.contains("signature", ignoreCase = true)
            )
        }

        // Because signature verification ran BEFORE replay cache recording, the legitimate
        // (clientId.deviceId, targetNonceB64) pair must NOT have been poisoned in replayCache!
        assertEquals(
            ReplayCheckResult.Accepted,
            serverReplayCache.recordAndValidate(clientId.deviceId, targetNonceB64, now)
        )
        // And a genuine second replay of that same nonce MUST be blocked
        assertEquals(
            ReplayCheckResult.ReplayedNonce,
            serverReplayCache.recordAndValidate(clientId.deviceId, targetNonceB64, now)
        )
    }

    // =========================================================================
    // 4. CloudUpdateValidator SSRF / Internal Host & IPv6 Prefix Hardening
    // =========================================================================

    @Test
    fun `CloudUpdateValidator blocks SSRF targets and allows legitimate fc or fd public domains`() {
        val blockedUrls = listOf(
            "http://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
            "https://user:secret@github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
            "https://localhost/version.json",
            "https://api.localhost/version.json",
            "https://printer.local/version.json",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://router.home.arpa/version.json",
            "https://127.0.0.1/version.json",
            "https://10.0.0.1/version.json",
            "https://172.16.0.5/version.json",
            "https://192.168.1.1/version.json",
            "https://169.254.169.254/latest/meta-data/",
            "https://100.64.0.1/version.json",
            "https://192.0.0.1/version.json",
            "https://198.18.0.1/version.json",
            "https://224.0.0.1/version.json",
            "https://255.255.255.255/version.json",
            "https://[::1]/version.json",
            "https://[fe80::1]/version.json",
            "https://[febf::1]/version.json",
            "https://[fc00::1]/version.json",
            "https://[::ffff:127.0.0.1]/version.json",
            "https://github.com/ow7ab/Share-Flow/version.json\r\nX-Injected: 1"
        )
        blockedUrls.forEach { badUrl ->
            assertFalse("SSRF / insecure URL must be rejected: $badUrl", CloudUpdateValidator.isValidPublicHttpsUrl(badUrl))
        }

        // Legitimate public HTTPS hosts — including domains starting with "fc" or "fd"
        val allowedUrls = listOf(
            "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
            "https://ow7ab.github.io/Share-Flow/version.json",
            "https://fdroid.org/repo/index.json",
            "https://fcdn.example.com/releases/version.json"
        )
        allowedUrls.forEach { okUrl ->
            assertTrue("Valid public HTTPS URL must be allowed: $okUrl", CloudUpdateValidator.isValidPublicHttpsUrl(okUrl))
        }
    }

    // =========================================================================
    // 5. FcmUpdatePayload Official Release Origin Allowlist
    // =========================================================================

    @Test
    fun `FcmUpdatePayload rejects untrusted third-party APK domains and accepts official ShareFlow origins`() {
        val validSha = "a".repeat(64)

        val spoofedUrls = listOf(
            "https://evil-attacker.example.com/Share-Flow.apk",
            "https://github.com/attacker/Fake-Share-Flow/releases/download/v2.0.0/Share-Flow.apk",
            "https://ow7ab.github.io/other-project/Share-Flow.apk",
            "https://github.com/ow7ab/Share-Flow/releases/download/v1.1.1/not-an-apk.zip",
            "http://github.com/ow7ab/Share-Flow/releases/download/v1.1.1/Share-Flow.apk"
        )
        spoofedUrls.forEach { badApkUrl ->
            val parsed = FcmUpdatePayload.fromMap(
                mapOf(
                    "type" to "app_update",
                    "versionCode" to "25",
                    "versionName" to "1.1.2",
                    "apkUrl" to badApkUrl,
                    "sha256" to validSha
                )
            )
            assertNull("Untrusted FCM APK URL must be rejected: $badApkUrl", parsed)
        }

        val trustedUrls = listOf(
            "https://github.com/ow7ab/Share-Flow/releases/download/v1.1.1/Share-Flow.apk",
            "https://github.com/ow7ab/Share-Flow/releases/latest/download/Share-Flow.apk",
            "https://objects.githubusercontent.com/github-production-release-asset/12345/Share-Flow.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/12345/Share-Flow.apk",
            "https://ow7ab.github.io/Share-Flow/Share-Flow.apk"
        )
        trustedUrls.forEach { trustedUrl ->
            val parsed = FcmUpdatePayload.fromMap(
                mapOf(
                    "type" to "app_update",
                    "versionCode" to "25",
                    "versionName" to "1.1.2",
                    "apkUrl" to trustedUrl,
                    "sha256" to validSha,
                    "title" to "Update\u0007Available"
                )
            )
            assertNotNull("Official ShareFlow FCM APK URL must be accepted: $trustedUrl", parsed)
            assertEquals("UpdateAvailable", parsed!!.notificationTitle)
        }
    }

    // =========================================================================
    // 6. QR Pairing Input Bounds & Malformed Percent-Encoding Hardening
    // =========================================================================

    @Test
    fun `PairingTokenManager and SmartQrPairingProtocol reject oversized inputs, bad URL escapes, and control chars`() {
        val tokenManager = PairingTokenManager(clock = clock)
        val oversized = "shareflow://pair?v=2&name=" + "A".repeat(PairingTokenManager.MAX_QR_INPUT_LENGTH + 100)
        val oversizedRes = tokenManager.parseFromUri(oversized)
        assertTrue(oversizedRes is AppResult.Failure && oversizedRes.error is AppError.Validation)

        // Malformed %ZZ encoding must return Validation Failure instead of throwing IllegalArgumentException
        val badEncodingRes = tokenManager.parseFromUri("shareflow://pair?v=2&name=%ZZinvalid")
        assertTrue(badEncodingRes is AppResult.Failure && badEncodingRes.error is AppError.Validation)

        // Control characters in host field must be rejected
        val localIdentity = AndroidDeviceIdentityManager(preferAndroidKeystore = false).getLocalIdentity()
        val payload = tokenManager.createPairingPayload(
            localIdentity = localIdentity,
            displayName = "Pixel",
            hostAddress = "192.168.1.5",
            port = 49152,
            preferredConnectionType = ConnectionType.LOCAL_LAN_NSD
        )
        val validUri = tokenManager.encodeToUri(payload)
        assertTrue(tokenManager.parseFromUri(validUri) is AppResult.Success)
        val badHostUri = validUri.replace("host=192.168.1.5", "host=192.168.1.5%0D%0Aevil")
        assertTrue(tokenManager.parseFromUri(badHostUri) is AppResult.Failure)

        // SmartQrPairingProtocol oversized payload & control chars
        val smartProtocol = SmartQrPairingProtocol(
            identityManager = AndroidDeviceIdentityManager(preferAndroidKeystore = false),
            clock = clock
        )
        val oversizedSmart = smartProtocol.parseAndValidate(
            rawInput = "shareflow://pair?v=3&name=" + "B".repeat(SmartQrPairingProtocol.MAX_QR_INPUT_LENGTH + 100),
            localDeviceId = "otherdevice12345"
        )
        assertTrue(
            oversizedSmart is SmartQrParseResult.Rejected &&
                oversizedSmart.errorCode == SmartConnectionErrorCode.INVALID_QR
        )

        // Valid signed Smart QR v3 payload still parses cleanly
        val signedSession = smartProtocol.createSessionPayload(
            displayName = "ReceiverPhone",
            serviceName = "ShareFlow-Test",
            servicePort = 51234,
            preferredStrategy = SmartStrategyType.LOCAL_ONLY_HOTSPOT
        )
        val smartUri = smartProtocol.encodeToUri(signedSession)
        val validSmart = smartProtocol.parseAndValidate(smartUri, localDeviceId = "differentdevice999")
        assertTrue(validSmart is SmartQrParseResult.Valid)
    }
}
