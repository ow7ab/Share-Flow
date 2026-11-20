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

    @Test
    fun `non-destructive MIGRATION_1_2 preserves existing v1 transfer history and trusted devices`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "shareflow_migration_test_${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        // Bootstrap an authentic v1 SQLite database matching ShareFlowDatabase v1 schema
        val v1Helper = object : android.database.sqlite.SQLiteOpenHelper(context, dbName, null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_history` (
                        `id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `file_type` TEXT NOT NULL,
                        `file_size_bytes` INTEGER NOT NULL,
                        `bytes_transferred` INTEGER NOT NULL,
                        `direction` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `peer_device_id` TEXT NOT NULL,
                        `peer_device_name` TEXT NOT NULL,
                        `local_uri` TEXT,
                        `sha256_checksum` TEXT,
                        `is_checksum_verified` INTEGER NOT NULL,
                        `started_at_epoch_millis` INTEGER NOT NULL,
                        `completed_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_history_started_at_epoch_millis` ON `transfer_history` (`started_at_epoch_millis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_history_session_id` ON `transfer_history` (`session_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_history_direction` ON `transfer_history` (`direction`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `trusted_devices` (
                        `device_id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `device_type` TEXT NOT NULL,
                        `connection_type` TEXT NOT NULL,
                        `public_key_fingerprint` TEXT,
                        `last_seen_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`device_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_trusted_devices_last_seen_epoch_millis` ON `trusted_devices` (`last_seen_epoch_millis`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_items` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `encrypted_file_name` TEXT NOT NULL,
                        `original_mime_type` TEXT NOT NULL,
                        `original_size_bytes` INTEGER NOT NULL,
                        `sha256_checksum` TEXT NOT NULL,
                        `key_alias` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vault_items_created_at_epoch_millis` ON `vault_items` (`created_at_epoch_millis`)")

                db.execSQL(
                    """
                    INSERT INTO `transfer_history` VALUES (
                        'legacy-tx-1', 'legacy-sess-1', 'family_photo.jpg', 'image/jpeg', 'IMAGE',
                        4096, 4096, 'RECEIVED', 'COMPLETED_VERIFIED', 'peer-legacy', 'Old Phone',
                        NULL, '${"a".repeat(64)}', 1, 1700000000000, 1700000001000
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `trusted_devices` VALUES (
                        'peer-legacy', 'Old Phone', 'PHONE', 'LOCAL_LAN_NSD', '${"b".repeat(64)}',
                        1700000001000
                    )
                    """.trimIndent()
                )
            }

            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v1Helper.writableDatabase.close()
        v1Helper.close()

        // Open with Room v4 + MIGRATION_1_2, MIGRATION_2_3 & MIGRATION_3_4 (without destructive migration)
        val migratedDb = Room.databaseBuilder(context, ShareFlowDatabase::class.java, dbName)
            .addMigrations(
                ShareFlowDatabase.MIGRATION_1_2,
                ShareFlowDatabase.MIGRATION_2_3,
                ShareFlowDatabase.MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()

        try {
            val historyRepo = TransferHistoryRepositoryImpl(migratedDb.transferHistoryDao(), dispatchers)
            val trustedRepo = TrustedDeviceRepositoryImpl(migratedDb.trustedDeviceDao(), dispatchers)

            val preservedRecords = historyRepo.observeAllRecords().first()
            assertEquals(1, preservedRecords.size)
            assertEquals("family_photo.jpg", preservedRecords.single().fileName)

            val preservedPeer = trustedRepo.getTrustedDevice("peer-legacy")
            assertEquals("Old Phone", preservedPeer?.displayName)

            // Verify newly created v2, v3 & v4 tables are operational
            assertTrue(migratedDb.transferSessionDao().getIncompleteSessions().isEmpty())
            assertTrue(migratedDb.mediaPlaybackDao().getAll().isEmpty())
            assertNull(migratedDb.vaultConfigDao().getConfig())
            assertTrue(migratedDb.favoriteFileDao().observeAll().first().isEmpty())
            assertTrue(migratedDb.storageSnapshotDao().getRecent().isEmpty())
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}

