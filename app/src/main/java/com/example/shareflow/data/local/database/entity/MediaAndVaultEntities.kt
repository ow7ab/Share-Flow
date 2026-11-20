package com.example.shareflow.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_playback_state",
    indices = [Index(value = ["updated_at_epoch_millis"])]
)
data class MediaPlaybackStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,
    @ColumnInfo(name = "media_type")
    val mediaType: String,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "playback_speed")
    val playbackSpeed: Float,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long
)

@Entity(
    tableName = "music_playlists",
    indices = [Index(value = ["created_at_epoch_millis"])]
)
data class MusicPlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "music_playlist_tracks",
    primaryKeys = ["playlist_id", "track_uri"],
    foreignKeys = [
        ForeignKey(
            entity = MusicPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlist_id", "sort_order"])]
)
data class MusicPlaylistTrackEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    @ColumnInfo(name = "track_uri")
    val trackUri: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "album")
    val album: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int
)

/**
 * Persisted Private Vault cryptographic envelope and lock policy configuration.
 * NEVER stores plaintext PINs, plaintext recovery keys, or unencrypted master keys.
 */
@Entity(tableName = "vault_config")
data class VaultConfigEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "pin_salt_b64")
    val pinSaltB64: String,
    @ColumnInfo(name = "pin_verifier_b64")
    val pinVerifierB64: String,
    @ColumnInfo(name = "pin_iterations")
    val pinIterations: Int,
    @ColumnInfo(name = "wrapped_vmk_pin_iv_b64")
    val wrappedVmkPinIvB64: String,
    @ColumnInfo(name = "wrapped_vmk_pin_ct_b64")
    val wrappedVmkPinCtB64: String,
    @ColumnInfo(name = "wrapped_vmk_keystore_iv_b64")
    val wrappedVmkKeystoreIvB64: String?,
    @ColumnInfo(name = "wrapped_vmk_keystore_ct_b64")
    val wrappedVmkKeystoreCtB64: String?,
    @ColumnInfo(name = "recovery_salt_b64")
    val recoverySaltB64: String,
    @ColumnInfo(name = "wrapped_vmk_recovery_iv_b64")
    val wrappedVmkRecoveryIvB64: String,
    @ColumnInfo(name = "wrapped_vmk_recovery_ct_b64")
    val wrappedVmkRecoveryCtB64: String,
    @ColumnInfo(name = "biometric_enabled")
    val biometricEnabled: Boolean,
    @ColumnInfo(name = "auto_lock_timeout_seconds")
    val autoLockTimeoutSeconds: Long,
    @ColumnInfo(name = "failed_attempts")
    val failedAttempts: Int,
    @ColumnInfo(name = "lockout_until_epoch_millis")
    val lockoutUntilEpochMillis: Long,
    @ColumnInfo(name = "keystore_key_alias")
    val keystoreKeyAlias: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
