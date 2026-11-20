package com.example.shareflow.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.shareflow.data.local.database.dao.FavoriteFileDao
import com.example.shareflow.data.local.database.dao.MediaPlaybackDao
import com.example.shareflow.data.local.database.dao.MusicPlaylistDao
import com.example.shareflow.data.local.database.dao.StorageSnapshotDao
import com.example.shareflow.data.local.database.dao.TransferHistoryDao
import com.example.shareflow.data.local.database.dao.TransferSessionDao
import com.example.shareflow.data.local.database.dao.TrustedDeviceDao
import com.example.shareflow.data.local.database.dao.VaultConfigDao
import com.example.shareflow.data.local.database.dao.VaultItemDao
import com.example.shareflow.data.local.database.entity.FavoriteFileEntity
import com.example.shareflow.data.local.database.entity.MediaPlaybackStateEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistTrackEntity
import com.example.shareflow.data.local.database.entity.StorageSnapshotEntity
import com.example.shareflow.data.local.database.entity.TransferHistoryEntity
import com.example.shareflow.data.local.database.entity.TransferItemEntity
import com.example.shareflow.data.local.database.entity.TransferSessionEntity
import com.example.shareflow.data.local.database.entity.TrustedDeviceEntity
import com.example.shareflow.data.local.database.entity.VaultConfigEntity
import com.example.shareflow.data.local.database.entity.VaultItemEntity

/**
 * ShareFlow offline-first Room database.
 * Destructive migrations are intentionally disallowed to protect user history, trusted peers,
 * transfer recovery state, media resume/playlists, and encrypted vault metadata.
 */
@Database(
    entities = [
        TransferHistoryEntity::class,
        TrustedDeviceEntity::class,
        VaultItemEntity::class,
        TransferSessionEntity::class,
        TransferItemEntity::class,
        MediaPlaybackStateEntity::class,
        MusicPlaylistEntity::class,
        MusicPlaylistTrackEntity::class,
        VaultConfigEntity::class,
        FavoriteFileEntity::class,
        StorageSnapshotEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class ShareFlowDatabase : RoomDatabase() {
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun trustedDeviceDao(): TrustedDeviceDao
    abstract fun vaultItemDao(): VaultItemDao
    abstract fun transferSessionDao(): TransferSessionDao
    abstract fun mediaPlaybackDao(): MediaPlaybackDao
    abstract fun musicPlaylistDao(): MusicPlaylistDao
    abstract fun vaultConfigDao(): VaultConfigDao
    abstract fun favoriteFileDao(): FavoriteFileDao
    abstract fun storageSnapshotDao(): StorageSnapshotDao

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

        /**
         * Safe non-destructive migration from v2 to v3:
         * - Adds media_playback_state, music_playlists, music_playlist_tracks, and vault_config tables.
         * - Extends vault_items with encrypted metadata & per-file DEK envelope columns without dropping any existing rows.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_playback_state` (
                        `media_uri` TEXT NOT NULL,
                        `media_type` TEXT NOT NULL,
                        `position_ms` INTEGER NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `playback_speed` REAL NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`media_uri`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_playback_state_updated_at_epoch_millis` ON `media_playback_state` (`updated_at_epoch_millis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `music_playlists` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_music_playlists_created_at_epoch_millis` ON `music_playlists` (`created_at_epoch_millis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `music_playlist_tracks` (
                        `playlist_id` TEXT NOT NULL,
                        `track_uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `duration_ms` INTEGER NOT NULL,
                        `sort_order` INTEGER NOT NULL,
                        PRIMARY KEY(`playlist_id`, `track_uri`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `music_playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_music_playlist_tracks_playlist_id_sort_order` ON `music_playlist_tracks` (`playlist_id`, `sort_order`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vault_config` (
                        `id` INTEGER NOT NULL,
                        `pin_salt_b64` TEXT NOT NULL,
                        `pin_verifier_b64` TEXT NOT NULL,
                        `pin_iterations` INTEGER NOT NULL,
                        `wrapped_vmk_pin_iv_b64` TEXT NOT NULL,
                        `wrapped_vmk_pin_ct_b64` TEXT NOT NULL,
                        `wrapped_vmk_keystore_iv_b64` TEXT,
                        `wrapped_vmk_keystore_ct_b64` TEXT,
                        `recovery_salt_b64` TEXT NOT NULL,
                        `wrapped_vmk_recovery_iv_b64` TEXT NOT NULL,
                        `wrapped_vmk_recovery_ct_b64` TEXT NOT NULL,
                        `biometric_enabled` INTEGER NOT NULL,
                        `auto_lock_timeout_seconds` INTEGER NOT NULL,
                        `failed_attempts` INTEGER NOT NULL,
                        `lockout_until_epoch_millis` INTEGER NOT NULL,
                        `keystore_key_alias` TEXT NOT NULL,
                        `created_at_epoch_millis` INTEGER NOT NULL,
                        `updated_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                // Preserve all existing vault_items rows by appending new envelope columns with safe defaults
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `category` TEXT NOT NULL DEFAULT 'DOCUMENTS'")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `encrypted_metadata_b64` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `metadata_iv_b64` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `wrapped_dek_b64` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `dek_iv_b64` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `file_nonce_prefix_b64` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `encrypted_size_bytes` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `vault_items` ADD COLUMN `chunk_count` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Safe non-destructive migration from v3 to v4:
         * - Adds favorite_files and storage_snapshots tables for Favorites and Storage Analyzer trends.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_files` (
                        `uri_string` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL,
                        `category` TEXT NOT NULL,
                        `added_at_epoch_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`uri_string`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_favorite_files_added_at_epoch_millis` ON `favorite_files` (`added_at_epoch_millis`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `storage_snapshots` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `captured_at_epoch_millis` INTEGER NOT NULL,
                        `total_bytes` INTEGER NOT NULL,
                        `used_bytes` INTEGER NOT NULL,
                        `available_bytes` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_storage_snapshots_captured_at_epoch_millis` ON `storage_snapshots` (`captured_at_epoch_millis`)"
                )
            }
        }

        fun create(context: Context): ShareFlowDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ShareFlowDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
