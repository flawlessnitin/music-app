package com.personal.music.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.catalog.RemoteCatalog
import com.personal.music.catalog.RemoteSong
import com.personal.music.db.MusicDatabase
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
    ) = RemoteSong(
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
    fun `applyCatalog inserts songs and stores the version`() = runTest {
        val catalog = RemoteCatalog(
            version = 42,
            updatedAt = "2026-11-15T10:00:00Z",
            songs = listOf(
                sampleSong(id = "1", title = "Song One"),
                sampleSong(id = "2", title = "Song Two")
            )
        )

        val deleted = repository.applyCatalog(catalog)
        assertTrue(deleted.isEmpty())
        assertEquals(42, repository.catalogVersion())

        val songs = repository.songs().first()
        assertEquals(2, songs.size)
        assertEquals("Song One", songs[0].title)
        assertEquals("Song Two", songs[1].title)
        assertEquals(false, songs[0].downloaded)
        assertEquals(false, songs[1].downloaded)
    }

    @Test
    fun `applyCatalog keeps the downloaded flag when metadata changes`() = runTest {
        val catalogV1 = RemoteCatalog(
            version = 1,
            updatedAt = "2026-11-15T10:00:00Z",
            songs = listOf(sampleSong(id = "1", title = "Original Title"))
        )
        repository.applyCatalog(catalogV1)
        repository.setDownloaded("1", true)

        val before = repository.song("1")
        assertNotNull(before)
        assertTrue(before!!.downloaded)

        val catalogV2 = RemoteCatalog(
            version = 2,
            updatedAt = "2026-11-15T11:00:00Z",
            songs = listOf(sampleSong(id = "1", title = "Updated Title", sizeBytes = 8000000L))
        )
        val deleted = repository.applyCatalog(catalogV2)
        assertTrue(deleted.isEmpty())

        val after = repository.song("1")
        assertNotNull(after)
        assertEquals("Updated Title", after!!.title)
        assertEquals(8000000L, after.sizeBytes)
        assertTrue(after.downloaded)
    }

    @Test
    fun `applyCatalog clears the downloaded flag when the song points at a new file`() = runTest {
        repository.applyCatalog(RemoteCatalog(1, "", listOf(sampleSong(id = "1"))))
        repository.setDownloaded("1", true)

        repository.applyCatalog(
            RemoteCatalog(2, "", listOf(sampleSong(id = "1", format = "flac", file = "songs/1.flac")))
        )

        val after = repository.song("1")!!
        assertEquals("songs/1.flac", after.file)
        assertEquals(false, after.downloaded)
    }

    @Test
    fun `applyCatalog deletes songs missing from the new catalog and returns them`() = runTest {
        val catalogV1 = RemoteCatalog(
            version = 1,
            updatedAt = "2026-11-15T10:00:00Z",
            songs = listOf(
                sampleSong(id = "1", title = "Song 1"),
                sampleSong(id = "2", title = "Song 2"),
                sampleSong(id = "3", title = "Song 3")
            )
        )
        repository.applyCatalog(catalogV1)

        val catalogV2 = RemoteCatalog(
            version = 2,
            updatedAt = "2026-11-15T12:00:00Z",
            songs = listOf(sampleSong(id = "1", title = "Song 1"))
        )
        val deleted = repository.applyCatalog(catalogV2)

        assertEquals(2, deleted.size)
        val deletedIds = deleted.map { it.id }.toSet()
        assertEquals(setOf("2", "3"), deletedIds)

        assertNotNull(repository.song("1"))
        assertNull(repository.song("2"))
        assertNull(repository.song("3"))
        assertEquals(1, repository.songs().first().size)
    }

    @Test
    fun `catalogVersion is 0 before any sync`() = runTest {
        assertEquals(0, repository.catalogVersion())
    }

    @Test
    fun `albums are grouped by albumArtist and album with a song count`() = runTest {
        val songs = listOf(
            sampleSong(id = "1", albumArtist = "Artist A", album = "Album 1", year = 2020, cover = "covers/1.jpg"),
            sampleSong(id = "2", albumArtist = "Artist A", album = "Album 1", year = 2020, cover = "covers/1.jpg"),
            sampleSong(id = "3", albumArtist = "Artist A", album = "Album 2", year = 2021, cover = "covers/2.jpg"),
            sampleSong(id = "4", albumArtist = "Artist B", album = "Album 3", year = 2022, cover = null)
        )
        repository.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = songs))

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
        repository.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = songs))

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
        repository.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = songs))

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
        repository.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = songs))

        val allSongs = repository.songs().first()
        assertEquals(3, allSongs.size)

        val emptySearch = repository.search("").first()
        assertEquals(allSongs, emptySearch)

        val whitespaceSearch = repository.search("   ").first()
        assertEquals(allSongs, whitespaceSearch)
    }

    @Test
    fun `pending lists only songs not downloaded and downloadedBytes sums the rest`() = runTest {
        val songs = listOf(
            sampleSong(id = "s1", sizeBytes = 1000L),
            sampleSong(id = "s2", sizeBytes = 2500L),
            sampleSong(id = "s3", sizeBytes = 4000L)
        )
        repository.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = songs))

        val initialPending = repository.pending()
        assertEquals(3, initialPending.size)
        assertEquals(0L, repository.downloadedBytes())

        repository.setDownloaded("s1", true)
        repository.setDownloaded("s3", true)

        val pendingAfter = repository.pending()
        assertEquals(1, pendingAfter.size)
        assertEquals("s2", pendingAfter[0].id)

        assertEquals(5000L, repository.downloadedBytes())
    }
}
