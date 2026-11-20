package com.example.shareflow.media

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.shareflow.core.common.AppResult
import com.example.shareflow.core.common.Clock
import com.example.shareflow.core.security.Sha256ChecksumVerifier
import com.example.shareflow.data.local.database.ShareFlowDatabase
import com.example.shareflow.data.repository.AndroidFileManagerRepository
import com.example.shareflow.domain.model.MediaAudioTrack
import com.example.shareflow.domain.model.MusicRepeatMode
import com.example.shareflow.media.music.MusicPlaybackController
import com.example.shareflow.media.music.MusicPlaybackController.Companion.toMedia3Item
import com.example.shareflow.testutil.TestDispatcherProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
 * Tests real local media file inspection (PNG image + RIFF/WAV audio), Media3 MediaItem construction,
 * playback resume persistence, persistent Music Playlists in Room, missing media error handling,
 * and non-destructive Room v2 -> v3 database migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaCenterAndMigrationTest {

    private lateinit var context: Context
    private lateinit var db: ShareFlowDatabase
    private lateinit var tempMediaDir: File
    private lateinit var repository: MediaCenterRepository

    private val testClock = object : Clock {
        override fun nowEpochMillis(): Long = 1_750_000_000_000L
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, ShareFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempMediaDir = File(context.cacheDir, "media_test_${UUID.randomUUID()}").apply { mkdirs() }

        val dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        val fileManager = AndroidFileManagerRepository(
            context = context,
            checksumVerifier = Sha256ChecksumVerifier(),
            dispatchers = dispatchers
        )
        repository = MediaCenterRepository(
            context = context,
            fileManagerRepository = fileManager,
            mediaPlaybackDao = db.mediaPlaybackDao(),
            musicPlaylistDao = db.musicPlaylistDao(),
            dispatchers = dispatchers,
            clock = testClock
        )
    }

    @After
    fun tearDown() {
        db.close()
        tempMediaDir.deleteRecursively()
    }

    @Test
    fun `real local PNG image file is inspected accurately for dimensions size and album`() = runTest {
        val albumDir = File(tempMediaDir, "Vacation").apply { mkdirs() }
        val pngFile = File(albumDir, "sunset.png")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 118, 224))
        FileOutputStream(pngFile).use { out ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        }
        bitmap.recycle()

        val uriStr = Uri.fromFile(pngFile).toString()
        val inspected = repository.inspectImageUri(uriStr)
        assertNotNull(inspected)
        assertEquals("sunset.png", inspected!!.displayName)
        assertEquals("Vacation", inspected.albumName)
        assertEquals("image/png", inspected.mimeType)
        assertEquals(pngFile.length(), inspected.sizeBytes)
        assertEquals(120, inspected.widthPx)
        assertEquals(80, inspected.heightPx)

        val galleryRes = repository.loadImages(extraUris = listOf(uriStr))
        assertTrue(galleryRes is AppResult.Success)
        assertTrue((galleryRes as AppResult.Success).data.any { it.uriString == uriStr })
    }

    @Test
    fun `real local WAV audio file builds Media3 item persists resume position and playlists`() = runTest {
        val wavFile = File(tempMediaDir, "tone_440hz.wav")
        writeRealPcmWavFile(wavFile, durationSeconds = 1, sampleRate = 8_000)
        assertTrue(wavFile.length() > 44L)

        val uriStr = Uri.fromFile(wavFile).toString()
        assertTrue(repository.verifyUriReadable(uriStr))

        val audioTrack = repository.inspectAudioUri(uriStr)
        assertNotNull(audioTrack)
        assertEquals("tone_440hz", audioTrack!!.title)
        assertEquals("audio/wav", audioTrack.mimeType)
        assertEquals(wavFile.length(), audioTrack.sizeBytes)

        // Verify AndroidX Media3 MediaItem metadata mapping
        val media3Item = audioTrack.toMedia3Item()
        assertEquals(uriStr, media3Item.mediaId)
        assertEquals("tone_440hz", media3Item.mediaMetadata.title.toString())

        // Save & restore resume playback position
        repository.savePlaybackPosition(
            mediaUri = uriStr,
            mediaType = MediaCenterRepository.MEDIA_TYPE_AUDIO,
            positionMs = 45_200L,
            durationMs = 180_000L,
            playbackSpeed = 1.25f
        )
        val restored = repository.getPlaybackPosition(uriStr)
        assertNotNull(restored)
        assertEquals(45_200L, restored!!.positionMs)
        assertEquals(180_000L, restored.durationMs)
        assertEquals(1.25f, restored.playbackSpeed, 0.01f)

        // Playlist CRUD in Room
        val created = (repository.createPlaylist("Road Trip") as AppResult.Success).data
        assertTrue(repository.addTrackToPlaylist(created.id, audioTrack) is AppResult.Success)
        val playlistsAfterAdd = repository.observePlaylists().first()
        assertEquals(1, playlistsAfterAdd.size)
        assertEquals("Road Trip", playlistsAfterAdd.first().name)
        assertEquals(1, playlistsAfterAdd.first().tracks.size)

        assertTrue(repository.removeTrackFromPlaylist(created.id, uriStr) is AppResult.Success)
        assertTrue(repository.observePlaylists().first().first().tracks.isEmpty())

        assertTrue(repository.deletePlaylist(created.id) is AppResult.Success)
        assertTrue(repository.observePlaylists().first().isEmpty())
    }

    @Test
    fun `missing media file is handled gracefully without crashing and reports playback error`() = runTest {
        val missingUri = Uri.fromFile(File(tempMediaDir, "does_not_exist.mp3")).toString()
        assertFalse(repository.verifyUriReadable(missingUri))
        assertNull(repository.inspectImageUri(missingUri))
        assertNull(repository.inspectAudioUri(missingUri))

        val controller = MusicPlaybackController(
            context = context,
            mediaCenterRepository = repository,
            dispatchers = TestDispatcherProvider(Dispatchers.Unconfined)
        )
        controller.playQueue(
            tracks = listOf(
                MediaAudioTrack(
                    uriString = missingUri,
                    title = "Missing Song",
                    artist = "Unknown",
                    album = "None",
                    mimeType = "audio/mpeg"
                )
            ),
            startIndex = 0
        )
        assertNotNull(controller.state.value.errorMessage)
        assertTrue(controller.state.value.errorMessage!!.contains("missing", ignoreCase = true))

        // Repeat mode cycling and shuffle toggles work deterministically
        assertEquals(MusicRepeatMode.OFF, controller.state.value.repeatMode)
        controller.cycleRepeatMode()
        assertEquals(MusicRepeatMode.ALL, controller.state.value.repeatMode)
        controller.cycleRepeatMode()
        assertEquals(MusicRepeatMode.ONE, controller.state.value.repeatMode)
        controller.cycleRepeatMode()
        assertEquals(MusicRepeatMode.OFF, controller.state.value.repeatMode)
        controller.toggleShuffle()
        assertTrue(controller.state.value.shuffleEnabled)
    }

    @Test
    fun `Room v2 to v3 migration preserves existing records and creates media and vault tables`() {
        val dbFile = File(tempMediaDir, "migration_v2_v3.db")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        // Create v2 schema matching existing ShareFlowDatabase v2 tables
        sqlite.execSQL(
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
        sqlite.execSQL(
            """
            INSERT INTO `vault_items`
            (`id`,`display_name`,`encrypted_file_name`,`original_mime_type`,`original_size_bytes`,`sha256_checksum`,`key_alias`,`created_at_epoch_millis`)
            VALUES ('legacy-1','legacy.txt','legacy.sfv','text/plain',128,'abc123','alias',1700000000000)
            """.trimIndent()
        )
        sqlite.version = 2
        sqlite.close()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(
                    object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            ShareFlowDatabase.MIGRATION_2_3.migrate(db)
                        }
                    }
                )
                .build()
        )
        val migratedDb = helper.writableDatabase
        // Existing v2 row preserved cleanly with default v3 columns
        migratedDb.query("SELECT id, category, chunk_count FROM vault_items WHERE id = 'legacy-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy-1", cursor.getString(0))
            assertEquals("DOCUMENTS", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        // New v3 tables exist and are queryable
        listOf("media_playback_state", "music_playlists", "music_playlist_tracks", "vault_config").forEach { table ->
            migratedDb.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { cursor ->
                assertTrue("Expected table $table to exist after MIGRATION_2_3", cursor.moveToFirst())
            }
        }
        helper.close()
    }

    private fun writeRealPcmWavFile(target: File, durationSeconds: Int, sampleRate: Int) {
        val numSamples = durationSeconds * sampleRate
        val dataSize = numSamples * 2 // 16-bit mono
        val out = ByteArrayOutputStream(44 + dataSize)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1) // AudioFormat = PCM
            putShort(1) // NumChannels = Mono
            putInt(sampleRate)
            putInt(sampleRate * 2) // ByteRate
            putShort(2) // BlockAlign
            putShort(16) // BitsPerSample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }
        out.write(header.array())
        val sampleBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * 440.0 * i / sampleRate
            val sample = (sin(angle) * 12_000).toInt().toShort()
            sampleBuf.clear()
            sampleBuf.putShort(sample)
            out.write(sampleBuf.array())
        }
        target.writeBytes(out.toByteArray())
    }
}
