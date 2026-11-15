package com.personal.music.ui.library

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.FakePlayerControls
import com.personal.music.playback.QueueItem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: MusicDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var player: FakePlayerControls
    private lateinit var viewModel: LibraryViewModel

    private fun remoteSong(
        id: String,
        title: String = "Title $id",
        artist: String = "Artist $id",
        album: String = "Album $id",
        format: String = "opus",
        file: String = "songs/$id.$format",
    ) = SongEntity(
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

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LibraryRepository(db)
        player = FakePlayerControls()
        viewModel = LibraryViewModel(repository, player)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `shows song rows with title and artist`() {
        runBlocking {
            repository.replaceSongs(listOf(
                        remoteSong("1", title = "Bohemian Rhapsody", artist = "Queen", album = "A Night at the Opera"),
                    ))
        }

        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithText("Bohemian Rhapsody").assertIsDisplayed()
        composeTestRule.onNodeWithText("Queen · A Night at the Opera", substring = true).assertIsDisplayed()
    }

    @Test
    fun `flac song shows the FLAC label`() {
        runBlocking {
            repository.replaceSongs(listOf(
                        remoteSong("1", title = "Flac Song", format = "flac"),
                        remoteSong("2", title = "Opus Song", format = "opus"),
                    ))
        }

        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithText("FLAC").assertIsDisplayed()
    }

    @Test
    fun `empty library shows the setup hint`() {
        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithText("No songs yet. Send music from your PC with: musictool add <folder>").assertIsDisplayed()
    }

    @Test
    fun `typing in search filters the list`() {
        runBlocking {
            repository.replaceSongs(listOf(
                        remoteSong("1", title = "Karma Police", artist = "Radiohead"),
                        remoteSong("2", title = "No Surprises", artist = "Radiohead"),
                    ))
        }

        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithText("Karma Police").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Surprises").assertIsDisplayed()

        composeTestRule.onNodeWithTag("search_field").performTextInput("Karma")

        composeTestRule.onNodeWithText("Karma Police").assertIsDisplayed()
        composeTestRule.onNodeWithText("No Surprises").assertDoesNotExist()
    }

    @Test
    fun `tapping a song starts playback from it`() {
        runBlocking {
            repository.replaceSongs(listOf(
                        remoteSong("1", title = "First Song"),
                        remoteSong("2", title = "Second Song"),
                    ))
        }

        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithText("Second Song").performClick()

        assertEquals(1, player.playCalls.size)
        assertEquals(1, player.playCalls.first().second)
        assertEquals("Second Song", player.playCalls.first().first[1].title)
    }

    @Test
    fun `mini player appears only when something is queued`() {
        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player)
        }

        composeTestRule.onNodeWithTag("mini_player").assertDoesNotExist()

        val item = QueueItem(
            songId = "1",
            title = "Now Playing Song",
            artist = "Now Playing Artist",
            artworkUri = null,
        )
        player.updateState {
            it.copy(
                queue = listOf(item),
                current = item,
                isPlaying = true,
            )
        }

        composeTestRule.onNodeWithTag("mini_player").assertIsDisplayed()
        composeTestRule.onNodeWithText("Now Playing Song").assertIsDisplayed()
        composeTestRule.onNodeWithText("Now Playing Artist").assertIsDisplayed()
    }

    private fun addSongs() {
        runBlocking {
            repository.replaceSongs(listOf(remoteSong("1", title = "Alpha"), remoteSong("2", title = "Beta")))
        }
    }

    @Test
    fun `shuffle play button shuffles the whole library`() {
        addSongs()
        composeTestRule.setContent { LibraryScreen(viewModel = viewModel, player = player) }

        composeTestRule.onNodeWithTag("shuffle_play_button").performClick()

        assertEquals(1, player.shuffledPlayCalls.size)
        assertEquals(setOf("1", "2"), player.shuffledPlayCalls.single().map { it.id }.toSet())
    }

    @Test
    fun `the Liked tab shows the empty hint when nothing is liked`() {
        addSongs()
        composeTestRule.setContent { LibraryScreen(viewModel = viewModel, player = player) }

        composeTestRule.onNodeWithText("Liked").performClick()
        composeTestRule.onNodeWithText("Songs you like with the heart show up here.").assertIsDisplayed()
    }

    @Test
    fun `the Liked tab shows the liked songs`() {
        addSongs()
        val liked = runBlocking { repository.song("2")!! }
        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player, likedSongs = listOf(liked), likedIds = setOf("2"))
        }

        composeTestRule.onNodeWithText("Liked").performClick()
        composeTestRule.onNodeWithText("Beta").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("liked_mark", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `the Recent tab shows recently played songs`() {
        addSongs()
        val recent = runBlocking { repository.song("1")!! }
        composeTestRule.setContent {
            LibraryScreen(viewModel = viewModel, player = player, recentSongs = listOf(recent))
        }

        composeTestRule.onNodeWithText("Recent").performClick()
        composeTestRule.onAllNodesWithText("Alpha").assertCountEquals(1)
    }
}
