package com.personal.music.ui.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.FakePlayerControls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LibraryViewModelTest {

    private lateinit var db: MusicDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var player: FakePlayerControls

    private fun sampleSong(id: String, title: String, artist: String = "Artist", album: String = "Album") = SongEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumArtist = artist,
        track = 1,
        disc = 1,
        year = 2024,
        durationMs = 180000L,
        sizeBytes = 5000000L,
        format = "opus",
        file = "songs/$id.opus",
        cover = "covers/$id.jpg",
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(db)
        player = FakePlayerControls()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `songs follow the search query`() = runTest {
        repository.replaceSongs(
            listOf(
                sampleSong("1", "Yellow Submarine", "The Beatles"),
                sampleSong("2", "Paint It Black", "The Rolling Stones"),
            ),
        )

        val viewModel = LibraryViewModel(repository, player, coroutineScope = backgroundScope)

        val initial = viewModel.songs.filter { it.isNotEmpty() }.first()
        assertEquals(2, initial.size)

        viewModel.setQuery("Yellow")
        val yellow = viewModel.songs.filter { it.size == 1 && it[0].title == "Yellow Submarine" }.first()
        assertEquals("1", yellow[0].id)

        viewModel.setQuery("Stones")
        val stones = viewModel.songs.filter { it.size == 1 && it[0].title == "Paint It Black" }.first()
        assertEquals("2", stones[0].id)

        viewModel.setQuery("")
        val all = viewModel.songs.filter { it.size == 2 }.first()
        assertEquals(2, all.size)
    }

    @Test
    fun `playSong sends the visible list and index to the player`() {
        val viewModel = LibraryViewModel(repository, player)
        val song1 = SongEntity("1", "Title 1", "Artist 1", "Album 1", "Artist 1", null, null, null, 1000, 1000, "opus", "songs/1.opus", null, false)
        val song2 = SongEntity("2", "Title 2", "Artist 2", "Album 2", "Artist 2", null, null, null, 1000, 1000, "opus", "songs/2.opus", null, false)
        val list = listOf(song1, song2)

        viewModel.playSong(list, 1)

        assertEquals(1, player.playCalls.size)
        assertEquals(list to 1, player.playCalls.first())
    }
}
