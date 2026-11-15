package com.personal.music.ui.playlists

import android.content.Context
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.playback.FakePlayerControls
import com.personal.music.playlists.PlaylistRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class PlaylistScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: MusicDatabase
    private lateinit var repository: PlaylistRepository
    private lateinit var player: FakePlayerControls

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
        repository = PlaylistRepository(db)
        player = FakePlayerControls()
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
    ) = SongEntity(
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
        cover = null,
        downloaded = true,
    )

    @Test
    fun `shows songs and plays from the tapped one`() {
        var playlistId: Long = 0
        runBlocking {
            db.songDao().insertOrUpdate(
                listOf(
                    sampleSong("s1", title = "Song A", artist = "Artist A"),
                    sampleSong("s2", title = "Song B", artist = "Artist B"),
                )
            )
            playlistId = repository.create("My Playlist")
            repository.addSong(playlistId, "s1")
            repository.addSong(playlistId, "s2")
        }

        composeTestRule.setContent {
            PlaylistScreen(
                playlistId = playlistId,
                repository = repository,
                player = player,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Song A").assertIsDisplayed()
        composeTestRule.onNodeWithText("Song B").assertIsDisplayed()

        composeTestRule.onNodeWithText("Song B").performClick()
        assertEquals(1, player.playCalls.size)
        val (songs, startIndex) = player.playCalls.first()
        assertEquals(1, startIndex)
        assertEquals(2, songs.size)
        assertEquals("s2", songs[1].id)
    }

    @Test
    fun `empty playlist shows a hint`() {
        var playlistId: Long = 0
        runBlocking {
            playlistId = repository.create("Empty Playlist")
        }

        composeTestRule.setContent {
            PlaylistScreen(
                playlistId = playlistId,
                repository = repository,
                player = player,
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText(
            "No songs yet. Use Add to playlist on any song.",
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `add to playlist sheet lists playlists and can create one`() {
        val song = sampleSong("s1", title = "Special Song")
        runBlocking {
            db.songDao().insertOrUpdate(listOf(song))
            repository.create("Favorites")
        }

        var dismissed = false
        composeTestRule.setContent {
            AddToPlaylistSheet(
                song = song,
                repository = repository,
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Favorites").assertIsDisplayed()
        composeTestRule.onNodeWithText("New playlist").assertIsDisplayed()

        composeTestRule.onNodeWithText("New playlist").performClick()

        composeTestRule.onNodeWithTag("new_playlist_name_field").assertIsDisplayed()
        composeTestRule.onNodeWithTag("new_playlist_name_field").performTextInput("Gym")
        composeTestRule.onNodeWithText("Create").performClick()

        runBlocking {
            val playlists = repository.playlists().first()
            val gym = playlists.find { it.name == "Gym" }
            assertNotNull(gym)
            val songs = repository.songs(gym!!.id).first()
            assertEquals(1, songs.size)
            assertEquals("s1", songs[0].id)
        }
        assertEquals(true, dismissed)
    }
}
