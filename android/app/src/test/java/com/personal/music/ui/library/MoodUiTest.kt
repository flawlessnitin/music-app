package com.personal.music.ui.library

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.FakePlayerControls
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoodUiTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: MusicDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var player: FakePlayerControls

    private fun song(id: String, title: String, mood: String?) = SongEntity(
        id = id, title = title, artist = "A", album = "Al", albumArtist = "A",
        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 10,
        format = "mp3", file = "/m/$id.mp3", cover = null, mood = mood,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        repository = LibraryRepository(db)
        player = FakePlayerControls()
        runBlocking {
            repository.replaceSongs(
                listOf(song("1", "Drift", "phonk"), song("2", "Midnight", "phonk"), song("3", "Rain", "sad"), song("4", "Plain", null)),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the Moods tab lists each mood with its song count`() {
        composeTestRule.setContent { LibraryScreen(viewModel = LibraryViewModel(repository, player), player = player) }

        composeTestRule.onNodeWithText("Moods").performClick()

        composeTestRule.onNodeWithText("phonk").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 songs").assertIsDisplayed()
        composeTestRule.onNodeWithText("sad").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 song").assertIsDisplayed()
    }

    @Test
    fun `tapping a mood opens it`() {
        var opened: String? = null
        composeTestRule.setContent {
            LibraryScreen(viewModel = LibraryViewModel(repository, player), player = player, onMoodClick = { opened = it })
        }

        composeTestRule.onNodeWithText("Moods").performClick()
        composeTestRule.onNodeWithText("phonk").performClick()

        assertEquals("phonk", opened)
    }

    @Test
    fun `a mood screen shows only its songs and shuffles only them`() {
        composeTestRule.setContent {
            MoodScreen(moodName = "phonk", repository = repository, player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("Drift").assertIsDisplayed()
        composeTestRule.onNodeWithText("Midnight").assertIsDisplayed()
        composeTestRule.onNodeWithTag("shuffle_play_button").performClick()

        assertEquals(setOf("1", "2"), player.shuffledPlayCalls.single().map { it.id }.toSet())
    }

    @Test
    fun `tapping a song in a mood plays the queue of that mood`() {
        composeTestRule.setContent {
            MoodScreen(moodName = "phonk", repository = repository, player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("Midnight").performClick()

        val (queue, index) = player.playCalls.single()
        assertEquals(listOf("1", "2"), queue.map { it.id })
        assertEquals(1, index)
    }

    private fun showDialog(existing: List<String>, current: String? = null, onPick: (String?) -> Unit) {
        composeTestRule.setContent {
            SetMoodDialog(
                songTitle = "Drift",
                currentMood = current,
                existingMoods = existing,
                onPick = onPick,
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the mood dialog offers the usual moods and the ones you already have`() {
        showDialog(existing = listOf("phonk"), onPick = {})

        composeTestRule.onNodeWithTag("mood_option_phonk").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mood_option_Chill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mood_option_Sad").assertIsDisplayed()
    }

    @Test
    fun `a mood that exists is not listed twice`() {
        showDialog(existing = listOf("sad", "phonk"), onPick = {})

        composeTestRule.onNodeWithTag("mood_option_phonk").assertIsDisplayed()
        // "sad" already exists in lower case, so the usual "Sad" is not offered a second time.
        composeTestRule.onNodeWithTag("mood_option_sad").assertIsDisplayed()
        assertTrue(composeTestRule.onAllNodesWithTagCount("mood_option_Sad") == 0)
    }

    @Test
    fun `picking a mood reports it`() {
        var picked: String? = "unset"
        showDialog(existing = emptyList(), onPick = { picked = it })

        composeTestRule.onNodeWithTag("mood_option_Chill").performClick()

        assertEquals("Chill", picked)
    }

    @Test
    fun `no mood reports null`() {
        var picked: String? = "unset"
        showDialog(existing = listOf("phonk"), current = "phonk", onPick = { picked = it })

        composeTestRule.onNodeWithTag("mood_option_none").performScrollTo().performClick()

        assertEquals(null, picked)
    }

    @Test
    fun `a new mood can be typed in`() {
        var picked: String? = null
        showDialog(existing = emptyList(), onPick = { picked = it })

        composeTestRule.onNodeWithTag("new_mood_input").performScrollTo().performTextInput("  gym  ")
        composeTestRule.onNodeWithTag("add_mood_button").performScrollTo().performClick()

        assertEquals("gym", picked)
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
    androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.TestTag, tag).let { matcher ->
        this.onAllNodes(matcher).fetchSemanticsNodes().size
    }

@RunWith(RobolectricTestRunner::class)
class LibraryTabMemoryTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the library comes back on the same tab after leaving the screen and returning`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java).allowMainThreadQueries().build()
        val repository = LibraryRepository(db)
        val player = FakePlayerControls()
        runBlocking {
            repository.replaceSongs(
                listOf(
                    SongEntity(
                        id = "1", title = "Drift", artist = "A", album = "Al", albumArtist = "A",
                        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 10,
                        format = "mp3", file = "/m/1.mp3", cover = null, mood = "phonk",
                    ),
                ),
            )
        }
        val restoration = StateRestorationTester(composeTestRule)
        restoration.setContent { LibraryScreen(viewModel = LibraryViewModel(repository, player), player = player) }

        composeTestRule.onNodeWithText("Moods").performClick()
        composeTestRule.onNodeWithText("phonk").assertIsDisplayed()

        // This is what happens when you open a mood and press back: the screen is rebuilt from saved state.
        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("phonk").assertIsDisplayed()
        db.close()
    }
}
