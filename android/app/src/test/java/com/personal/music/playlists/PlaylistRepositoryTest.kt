package com.personal.music.playlists

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.personal.music.catalog.RemoteCatalog
import com.personal.music.catalog.RemoteSong
import com.personal.music.db.MIGRATION_1_2
import com.personal.music.db.MIGRATION_2_3
import com.personal.music.db.MIGRATION_3_4
import com.personal.music.listening.ListeningRepository
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongDao
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaylistRepositoryTest {

    private lateinit var db: MusicDatabase
    private lateinit var songDao: SongDao
    private lateinit var repository: PlaylistRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            })
            .allowMainThreadQueries()
            .build()
        songDao = db.songDao()
        repository = PlaylistRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleSong(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
        albumArtist: String = "Artist $id",
        track: Int? = 1,
        disc: Int? = 1,
        year: Int? = 2024,
        durationMs: Long = 180000L,
        sizeBytes: Long = 5000000L,
        format: String = "opus",
        file: String = "songs/$id.opus",
        cover: String? = "covers/$id.jpg",
        downloaded: Boolean = false,
    ) = SongEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        track = track,
        disc = disc,
        year = year,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        format = format,
        file = file,
        cover = cover,
        downloaded = downloaded,
    )

    private fun remoteSong(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
        format: String = "opus",
        file: String = "songs/$id.$format",
    ) = RemoteSong(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        track = 1,
        disc = 1,
        year = 2024,
        durationMs = 215000L,
        sizeBytes = 5000000L,
        format = format,
        file = file,
        cover = null,
    )

    @Test
    fun `create rename delete`() = runTest {
        val id = repository.create("Road Trip")
        var playlists = repository.playlists().first()
        assertEquals(1, playlists.size)
        assertEquals("Road Trip", playlists[0].name)
        assertEquals(0, playlists[0].songCount)

        repository.rename(id, "Road Trip 2026")
        playlists = repository.playlists().first()
        assertEquals(1, playlists.size)
        assertEquals("Road Trip 2026", playlists[0].name)

        repository.delete(id)
        playlists = repository.playlists().first()
        assertTrue(playlists.isEmpty())
    }

    @Test
    fun `blank name is rejected`() = runTest {
        try {
            repository.create("")
            fail("Expected IllegalArgumentException for empty name")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        try {
            repository.create("   ")
            fail("Expected IllegalArgumentException for whitespace-only name")
        } catch (e: IllegalArgumentException) {
            // Expected
        }

        val id = repository.create("Valid")
        try {
            repository.rename(id, "   ")
            fail("Expected IllegalArgumentException for whitespace-only rename")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `songs come back in the order added`() = runTest {
        songDao.insertOrUpdate(
            listOf(
                sampleSong("s1", title = "Song One"),
                sampleSong("s2", title = "Song Two"),
                sampleSong("s3", title = "Song Three"),
            )
        )
        val playlistId = repository.create("My Mix")
        repository.addSong(playlistId, "s2")
        repository.addSong(playlistId, "s1")
        repository.addSong(playlistId, "s3")

        val songs = repository.songs(playlistId).first()
        assertEquals(listOf("s2", "s1", "s3"), songs.map { it.id })
    }

    @Test
    fun `adding the same song twice keeps one entry`() = runTest {
        songDao.insertOrUpdate(listOf(sampleSong("s1")))
        val playlistId = repository.create("Single Song")
        repository.addSong(playlistId, "s1")
        repository.addSong(playlistId, "s1")

        val songs = repository.songs(playlistId).first()
        assertEquals(1, songs.size)
        assertEquals("s1", songs[0].id)

        val summaries = repository.playlists().first()
        assertEquals(1, summaries[0].songCount)
    }

    @Test
    fun `move reorders and positions stay contiguous`() = runTest {
        songDao.insertOrUpdate(
            listOf(
                sampleSong("s1"),
                sampleSong("s2"),
                sampleSong("s3"),
                sampleSong("s4"),
            )
        )
        val playlistId = repository.create("Order Test")
        repository.addSong(playlistId, "s1")
        repository.addSong(playlistId, "s2")
        repository.addSong(playlistId, "s3")
        repository.addSong(playlistId, "s4")

        // Move s2 from index 1 to index 3 -> [s1, s3, s4, s2]
        repository.move(playlistId, from = 1, to = 3)
        val afterMove1 = repository.songs(playlistId).first()
        assertEquals(listOf("s1", "s3", "s4", "s2"), afterMove1.map { it.id })

        // Move s2 from index 3 to index 0 -> [s2, s1, s3, s4]
        repository.move(playlistId, from = 3, to = 0)
        val afterMove2 = repository.songs(playlistId).first()
        assertEquals(listOf("s2", "s1", "s3", "s4"), afterMove2.map { it.id })
    }

    @Test
    fun `removing a song closes the gap`() = runTest {
        songDao.insertOrUpdate(
            listOf(
                sampleSong("s1"),
                sampleSong("s2"),
                sampleSong("s3"),
                sampleSong("s4"),
            )
        )
        val playlistId = repository.create("Remove Test")
        repository.addSong(playlistId, "s1")
        repository.addSong(playlistId, "s2")
        repository.addSong(playlistId, "s3")

        repository.removeSong(playlistId, "s2")
        val songs = repository.songs(playlistId).first()
        assertEquals(listOf("s1", "s3"), songs.map { it.id })

        // Adding s4 should append contiguous at position 2
        repository.addSong(playlistId, "s4")
        val songsAfterAdd = repository.songs(playlistId).first()
        assertEquals(listOf("s1", "s3", "s4"), songsAfterAdd.map { it.id })
    }

    @Test
    fun `a song deleted by sync disappears from playlists`() = runTest {
        val libRepo = LibraryRepository(db)
        libRepo.applyCatalog(
            RemoteCatalog(
                version = 1,
                updatedAt = "",
                songs = listOf(
                    remoteSong("s1"),
                    remoteSong("s2"),
                    remoteSong("s3"),
                ),
            )
        )
        val playlistId = repository.create("Sync Cascade")
        repository.addSong(playlistId, "s1")
        repository.addSong(playlistId, "s2")
        repository.addSong(playlistId, "s3")

        var songs = repository.songs(playlistId).first()
        assertEquals(3, songs.size)

        // Sync arrives with catalog missing s2
        libRepo.applyCatalog(
            RemoteCatalog(
                version = 2,
                updatedAt = "",
                songs = listOf(
                    remoteSong("s1"),
                    remoteSong("s3"),
                ),
            )
        )

        songs = repository.songs(playlistId).first()
        assertEquals(listOf("s1", "s3"), songs.map { it.id })

        val summaries = repository.playlists().first()
        assertEquals(2, summaries[0].songCount)
    }

    @Test
    fun `deleting a playlist keeps the songs in the library`() = runTest {
        val libRepo = LibraryRepository(db)
        libRepo.applyCatalog(
            RemoteCatalog(
                version = 1,
                updatedAt = "",
                songs = listOf(remoteSong("s1")),
            )
        )
        val playlistId = repository.create("Temp Playlist")
        repository.addSong(playlistId, "s1")

        repository.delete(playlistId)

        val libSongs = libRepo.songs().first()
        assertEquals(1, libSongs.size)
        assertEquals("s1", libSongs[0].id)
    }

    @Test
    fun `migration from version 1 keeps existing songs and adds liked and recent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_test.db"
        context.deleteDatabase(dbName)
        val dbFile = context.getDatabasePath(dbName)
        dbFile.parentFile?.mkdirs()

        val sqliteDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `songs` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `artist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `albumArtist` TEXT NOT NULL,
                `track` INTEGER,
                `disc` INTEGER,
                `year` INTEGER,
                `durationMs` INTEGER NOT NULL,
                `sizeBytes` INTEGER NOT NULL,
                `format` TEXT NOT NULL,
                `file` TEXT NOT NULL,
                `cover` TEXT,
                `downloaded` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `meta` (
                `id` INTEGER NOT NULL,
                `catalogVersion` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        sqliteDb.execSQL("PRAGMA user_version = 1")
        sqliteDb.execSQL(
            """
            INSERT INTO `songs` (`id`, `title`, `artist`, `album`, `albumArtist`, `track`, `disc`, `year`, `durationMs`, `sizeBytes`, `format`, `file`, `cover`, `downloaded`)
            VALUES ('migrated_1', 'Old Song', 'Old Artist', 'Old Album', 'Old Artist', 1, 1, 2020, 180000, 4000000, 'mp3', 'songs/migrated_1.mp3', null, 1)
            """.trimIndent()
        )
        sqliteDb.close()

        val migratedDb = Room.databaseBuilder(context, MusicDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

        try {
            runBlocking {
                val song = migratedDb.songDao().getSong("migrated_1")
                assertNotNull(song)
                assertEquals("Old Song", song?.title)
                assertEquals(true, song?.downloaded)
                assertEquals(false, song?.local)

                val pRepo = PlaylistRepository(migratedDb)
                val pId = pRepo.create("Migrated Playlist")
                pRepo.addSong(pId, "migrated_1")
                val pSongs = pRepo.songs(pId).first()
                assertEquals(1, pSongs.size)
                assertEquals("migrated_1", pSongs[0].id)

                val listening = ListeningRepository(migratedDb)
                listening.setLiked("migrated_1", true)
                listening.recordPlay("migrated_1")
                assertEquals(setOf("migrated_1"), listening.likedIds().first())
                assertEquals(listOf("migrated_1"), listening.recentSongs().first().map { it.id })
            }
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
