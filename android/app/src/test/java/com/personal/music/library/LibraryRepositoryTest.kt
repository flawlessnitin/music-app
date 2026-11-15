package com.personal.music.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryRepositoryTest {

    private lateinit var db: MusicDatabase
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(db)
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
        cover: String? = "covers/$id.jpg"
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
        cover = cover
    )

    @Test
    fun `albums are grouped by albumArtist and album with a song count`() = runTest {
        val songs = listOf(
            sampleSong(id = "1", albumArtist = "Artist A", album = "Album 1", year = 2020, cover = "covers/1.jpg"),
            sampleSong(id = "2", albumArtist = "Artist A", album = "Album 1", year = 2020, cover = "covers/1.jpg"),
            sampleSong(id = "3", albumArtist = "Artist A", album = "Album 2", year = 2021, cover = "covers/2.jpg"),
            sampleSong(id = "4", albumArtist = "Artist B", album = "Album 3", year = 2022, cover = null)
        )
        repository.replaceSongs(songs)

        val albums = repository.albums().first()
        assertEquals(3, albums.size)

        val a1 = albums.first { it.album == "Album 1" && it.albumArtist == "Artist A" }
        assertEquals(2, a1.songCount)
        assertEquals("covers/1.jpg", a1.cover)
        assertEquals(2020, a1.year)

        val a2 = albums.first { it.album == "Album 2" && it.albumArtist == "Artist A" }
        assertEquals(1, a2.songCount)
        assertEquals("covers/2.jpg", a2.cover)
        assertEquals(2021, a2.year)

        val a3 = albums.first { it.album == "Album 3" && it.albumArtist == "Artist B" }
        assertEquals(1, a3.songCount)
        assertNull(a3.cover)
        assertEquals(2022, a3.year)

        val artists = repository.artists().first()
        assertEquals(2, artists.size)
        val artA = artists.first { it.name == "Artist A" }
        assertEquals(3, artA.songCount)
        val artB = artists.first { it.name == "Artist B" }
        assertEquals(1, artB.songCount)
    }

    @Test
    fun `albumSongs are ordered by disc then track with nulls first`() = runTest {
        val songs = listOf(
            sampleSong(id = "s1", albumArtist = "Artist A", album = "Album 1", disc = 2, track = 1),
            sampleSong(id = "s2", albumArtist = "Artist A", album = "Album 1", disc = 1, track = 2),
            sampleSong(id = "s3", albumArtist = "Artist A", album = "Album 1", disc = null, track = null),
            sampleSong(id = "s4", albumArtist = "Artist A", album = "Album 1", disc = 1, track = 1),
            sampleSong(id = "s5", albumArtist = "Artist A", album = "Album 1", disc = null, track = 1),
            sampleSong(id = "s6", albumArtist = "Artist A", album = "Album 1", disc = 1, track = null)
        )
        repository.replaceSongs(songs)

        val albumSongs = repository.albumSongs("Artist A", "Album 1").first()
        val orderedIds = albumSongs.map { it.id }
        assertEquals(listOf("s3", "s5", "s6", "s4", "s2", "s1"), orderedIds)
    }

    @Test
    fun `search matches title artist and album ignoring case`() = runTest {
        val songs = listOf(
            sampleSong(id = "1", title = "तुम ही हो", artist = "Arijit Singh", album = "Aashiqui 2", albumArtist = "Mithoon"),
            sampleSong(id = "2", title = "Fix You", artist = "Coldplay", album = "X&Y", albumArtist = "Coldplay"),
            sampleSong(id = "3", title = "Yellow", artist = "Coldplay", album = "Parachutes", albumArtist = "Coldplay")
        )
        repository.replaceSongs(songs)

        // Match Hindi title
        val hindiResults = repository.search("तुम").first()
        assertEquals(1, hindiResults.size)
        assertEquals("1", hindiResults[0].id)

        // Match album case-insensitively
        val albumResults = repository.search("aashiqui").first()
        assertEquals(1, albumResults.size)
        assertEquals("1", albumResults[0].id)

        // Match artist case-insensitively
        val artistResults = repository.search("arijit").first()
        assertEquals(1, artistResults.size)
        assertEquals("1", artistResults[0].id)

        // Match multiple songs
        val coldResults = repository.search("COLD").first()
        assertEquals(2, coldResults.size)
        assertEquals(setOf("2", "3"), coldResults.map { it.id }.toSet())
    }

    @Test
    fun `blank search returns every song`() = runTest {
        val songs = listOf(
            sampleSong(id = "1", title = "Song A"),
            sampleSong(id = "2", title = "Song B"),
            sampleSong(id = "3", title = "Song C")
        )
        repository.replaceSongs(songs)

        val allSongs = repository.songs().first()
        assertEquals(3, allSongs.size)

        val emptySearch = repository.search("").first()
        assertEquals(allSongs, emptySearch)

        val whitespaceSearch = repository.search("   ").first()
        assertEquals(allSongs, whitespaceSearch)
    }

}
