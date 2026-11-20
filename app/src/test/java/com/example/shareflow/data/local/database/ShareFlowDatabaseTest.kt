package com.example.shareflow.data.local.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.data.repository.TransferHistoryRepositoryImpl
import com.example.shareflow.data.repository.TrustedDeviceRepositoryImpl
import com.example.shareflow.data.repository.VaultRepositoryImpl
import com.example.shareflow.domain.model.ConnectionType
import com.example.shareflow.domain.model.DeviceType
import com.example.shareflow.domain.model.PeerDevice
import com.example.shareflow.domain.model.TransferDirection
import com.example.shareflow.domain.model.TransferFileType
import com.example.shareflow.domain.model.TransferRecord
import com.example.shareflow.domain.model.TransferStatus
import com.example.shareflow.domain.model.VaultItem
import com.example.shareflow.testutil.TestDispatcherProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFlowDatabaseTest {

    private lateinit var database: ShareFlowDatabase
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestDispatcherProvider(testDispatcher)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `transfer history repository persists and clears verified records`() = runTest(testDispatcher) {
        val repo = TransferHistoryRepositoryImpl(database.transferHistoryDao(), dispatchers)
        val record = TransferRecord(
            id = "tx-1",
            sessionId = "sess-1",
            fileName = "archive.zip",
            mimeType = "application/zip",
            fileType = TransferFileType.ARCHIVE,
            fileSizeBytes = 2048L,
            bytesTransferred = 2048L,
            direction = TransferDirection.SENT,
            status = TransferStatus.COMPLETED_VERIFIED,
            peerDeviceId = "peer-1",
            peerDeviceName = "Pixel Tablet",
            localUri = "content://com.android.providers.downloads.documents/document/1",
            sha256Checksum = "a".repeat(64),
            isChecksumVerified = true,
            startedAtEpochMillis = 1_700_000_000_000L,
            completedAtEpochMillis = 1_700_000_001_000L
        )

        val saveResult = repo.upsertRecord(record)
        assertTrue(saveResult is AppResult.Success)
        assertEquals(1, repo.observeTotalCount().first())
        assertEquals("archive.zip", repo.observeAllRecords().first().single().fileName)

        repo.clearAllRecords()
        assertEquals(0, repo.observeTotalCount().first())
    }

    @Test
    fun `trusted device repository saves queries and revokes peers`() = runTest(testDispatcher) {
        val repo = TrustedDeviceRepositoryImpl(database.trustedDeviceDao(), dispatchers)
        val peer = PeerDevice(
            deviceId = "dev-99",
            displayName = "Workstation",
            deviceType = DeviceType.DESKTOP,
            connectionType = ConnectionType.LOCAL_LAN_NSD,
            publicKeyFingerprint = "fp-1234",
            isTrusted = true,
            lastSeenEpochMillis = 1_700_000_000_000L
        )

        assertTrue(repo.saveTrustedDevice(peer) is AppResult.Success)
        assertEquals("Workstation", repo.getTrustedDevice("dev-99")?.displayName)
        assertEquals(1, repo.observeTrustedCount().first())

        repo.revokeTrustedDevice("dev-99")
        assertNull(repo.getTrustedDevice("dev-99"))
    }

    @Test
    fun `vault repository registers and removes encrypted item metadata`() = runTest(testDispatcher) {
        val repo = VaultRepositoryImpl(database.vaultItemDao(), dispatchers)
        val item = VaultItem(
            id = "vault-1",
            displayName = "contract.pdf",
            encryptedFileName = "v_8923a.enc",
            originalMimeType = "application/pdf",
            originalSizeBytes = 4096L,
            sha256Checksum = "b".repeat(64),
            keyAlias = "shareflow_vault_master_aes256",
            createdAtEpochMillis = 1_700_000_000_000L
        )

        assertTrue(repo.registerEncryptedItem(item) is AppResult.Success)
        assertEquals(1, repo.observeVaultItems().first().size)
        assertEquals("v_8923a.enc", repo.getVaultItemById("vault-1")?.encryptedFileName)

        repo.removeVaultItem("vault-1")
        assertTrue(repo.observeVaultItems().first().isEmpty())
    }
}
