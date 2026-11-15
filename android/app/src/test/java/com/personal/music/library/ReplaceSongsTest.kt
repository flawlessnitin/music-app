package com.personal.music.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.listening.ListeningRepository
import com.personal.music.playlists.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplaceSongsTest {
    private lateinit var db: MusicDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON;")
        repo = LibraryRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun song(id: String, title: String = "Title $id") = SongEntity(
        id = id, title = title, artist = "A", album = "Al", albumArtist = "A",
        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 10,
        format = "opus", file = "/music/$id.opus", cover = null,
    )

    private suspend fun ids() = repo.songs().first().map { it.id }.toSet()

    @Test
    fun `songs found on the phone are added`() = runTest {
        repo.replaceSongs(listOf(song("a"), song("b")))

        assertEquals(setOf("a", "b"), ids())
    }

    @Test
    fun `songs that are gone from the phone leave the library`() = runTest {
        repo.replaceSongs(listOf(song("a"), song("b")))
        repo.replaceSongs(listOf(song("b")))

        assertEquals(setOf("b"), ids())
    }

    @Test
    fun `an empty phone empties the library`() = runTest {
        repo.replaceSongs(listOf(song("a")))
        repo.replaceSongs(emptyList())

        assertTrue(ids().isEmpty())
    }

    @Test
    fun `a changed tag updates the song`() = runTest {
        repo.replaceSongs(listOf(song("a", title = "Old")))
        repo.replaceSongs(listOf(song("a", title = "New")))

        assertEquals(listOf("New"), repo.songs().first().map { it.title })
    }

    @Test
    fun `a rescan keeps playlists and likes of songs that are still there`() = runTest {
        val playlists = PlaylistRepository(db)
        val listening = ListeningRepository(db)
        repo.replaceSongs(listOf(song("a"), song("b")))
        val id = playlists.create("Mine")
        playlists.addSong(id, "a")
        listening.setLiked("a", true)

        repo.replaceSongs(listOf(song("a"), song("b")))

        assertEquals(listOf("a"), playlists.songs(id).first().map { it.id })
        assertEquals(setOf("a"), listening.likedIds().first())
    }

    @Test
    fun `a song that is gone also leaves its playlists and likes`() = runTest {
        val playlists = PlaylistRepository(db)
        val listening = ListeningRepository(db)
        repo.replaceSongs(listOf(song("a"), song("b")))
        val id = playlists.create("Mine")
        playlists.addSong(id, "a")
        playlists.addSong(id, "b")
        listening.setLiked("a", true)

        repo.replaceSongs(listOf(song("b")))

        assertEquals(listOf("b"), playlists.songs(id).first().map { it.id })
        assertTrue(listening.likedIds().first().isEmpty())
    }
}
