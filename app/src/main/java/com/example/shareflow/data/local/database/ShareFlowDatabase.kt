package com.example.shareflow.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shareflow.data.local.database.dao.TransferHistoryDao
import com.example.shareflow.data.local.database.dao.TransferSessionDao
import com.example.shareflow.data.local.database.dao.TrustedDeviceDao
import com.example.shareflow.data.local.database.dao.VaultItemDao
import com.example.shareflow.data.local.database.entity.TransferHistoryEntity
import com.example.shareflow.data.local.database.entity.TransferItemEntity
import com.example.shareflow.data.local.database.entity.TransferSessionEntity
import com.example.shareflow.data.local.database.entity.TrustedDeviceEntity
import com.example.shareflow.data.local.database.entity.VaultItemEntity

/**
 * ShareFlow offline-first Room database.
 * Destructive migrations are intentionally disallowed to protect user history, trusted peers,
 * transfer recovery state, and vault metadata.
 */
@Database(
    entities = [
        TransferHistoryEntity::class,
        TrustedDeviceEntity::class,
        VaultItemEntity::class,
        TransferSessionEntity::class,
        TransferItemEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class ShareFlowDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun transferSessionDao(): TransferSessionDao

    companion object {
        const val DATABASE_NAME = "shareflow_local.db"

        /**
         * Safe non-destructive migration from v1 to v2 adding transfer_sessions and transfer_items
         * while preserving all existing transfer_history, trusted_devices, and vault_items rows.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_sessions` (
                        `session_id` TEXT NOT NULL,
                        `peer_device_id` TEXT NOT NULL,
                        `peer_display_name` TEXT NOT NULL,
                        `peer_public_key_fingerprint` TEXT,
                        `peer_connection_type` TEXT NOT NULL,
                        `direction` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `chunk_size_bytes` INTEGER NOT NULL,
                        `destination_root_path` TEXT,
                        `error_message` TEXT,
                        `is_resumable` INTEGER NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        `completed_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_sessions_state` ON `transfer_sessions` (`state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_sessions_updated_at_epoch_millis` ON `transfer_sessions` (`updated_at_epoch_millis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_sessions_peer_device_id` ON `transfer_sessions` (`peer_device_id`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_items` (
                        `item_id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `file_name` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `file_type` TEXT NOT NULL,
                        `file_size_bytes` INTEGER NOT NULL,
                        `bytes_transferred` INTEGER NOT NULL,
                        `chunk_size_bytes` INTEGER NOT NULL,
                        `total_chunks` INTEGER NOT NULL,
                        `next_chunk_index` INTEGER NOT NULL,
                        `expected_sha256` TEXT NOT NULL,
                        `verified_sha256` TEXT,
                        `source_uri` TEXT,
                        `destination_uri` TEXT,
                        `temp_file_path` TEXT,
                        `state` TEXT NOT NULL,
                        `error_message` TEXT,
                        `is_recoverable` INTEGER NOT NULL,
                        `started_at_epoch_millis` INTEGER NOT NULL,
                        `completed_at_epoch_millis` INTEGER,
                        PRIMARY KEY(`item_id`),
                        FOREIGN KEY(`session_id`) REFERENCES `transfer_sessions`(`session_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_items_session_id` ON `transfer_items` (`session_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_items_state` ON `transfer_items` (`state`)"
                )
            }
        }

        fun create(context: Context): ShareFlowDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ShareFlowDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
