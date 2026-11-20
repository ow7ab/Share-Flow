package com.example.shareflow.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.shareflow.data.local.database.entity.MediaPlaybackStateEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistEntity
import com.example.shareflow.data.local.database.entity.MusicPlaylistTrackEntity
import com.example.shareflow.data.local.database.entity.VaultConfigEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaPlaybackDao {
    @Query("SELECT * FROM media_playback_state WHERE media_uri = :mediaUri LIMIT 1")
    suspend fun getByUri(mediaUri: String): MediaPlaybackStateEntity?

    @Query("SELECT * FROM media_playback_state ORDER BY updated_at_epoch_millis DESC")
    suspend fun getAll(): List<MediaPlaybackStateEntity>

    @Query("SELECT * FROM media_playback_state ORDER BY updated_at_epoch_millis DESC")
    fun observeAll(): Flow<List<MediaPlaybackStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: MediaPlaybackStateEntity)

    @Query("DELETE FROM media_playback_state WHERE media_uri = :mediaUri")
    suspend fun deleteByUri(mediaUri: String)
}

@Dao
interface MusicPlaylistDao {
    @Query("SELECT * FROM music_playlists ORDER BY created_at_epoch_millis DESC")
    fun observePlaylists(): Flow<List<MusicPlaylistEntity>>

    @Query("SELECT * FROM music_playlist_tracks ORDER BY sort_order ASC")
    fun observeAllPlaylistTracks(): Flow<List<MusicPlaylistTrackEntity>>

    @Query("SELECT * FROM music_playlist_tracks WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    suspend fun getTracksForPlaylist(playlistId: String): List<MusicPlaylistTrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: MusicPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: MusicPlaylistTrackEntity)

    @Query("DELETE FROM music_playlist_tracks WHERE playlist_id = :playlistId AND track_uri = :trackUri")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackUri: String)

    @Query("DELETE FROM music_playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}

@Dao
interface VaultConfigDao {
    @Query("SELECT * FROM vault_config WHERE id = 1 LIMIT 1")
    fun observeConfig(): Flow<VaultConfigEntity?>

    @Query("SELECT * FROM vault_config WHERE id = 1 LIMIT 1")
    suspend fun getConfig(): VaultConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: VaultConfigEntity)

    @Query("DELETE FROM vault_config")
    suspend fun clearConfig()
}
