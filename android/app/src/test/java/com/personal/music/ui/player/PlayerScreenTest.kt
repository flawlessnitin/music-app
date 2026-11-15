package com.personal.music.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.personal.music.playback.FakePlayerControls
import com.personal.music.playback.QueueItem
import com.personal.music.playback.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var player: FakePlayerControls

    @Before
    fun setUp() {
        player = FakePlayerControls()
    }

    @Test
    fun `shows the current title artist and times`() {
        val song = QueueItem(
            songId = "song_1",
            title = "Starman",
            artist = "David Bowie",
            artworkUri = null,
        )
        player.updateState {
            it.copy(
                current = song,
                queue = listOf(song),
                positionMs = 65_000L,
                durationMs = 215_000L,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("Starman").assertIsDisplayed()
        composeTestRule.onNodeWithText("David Bowie").assertIsDisplayed()
        composeTestRule.onNodeWithText("1:05").assertIsDisplayed()
        composeTestRule.onNodeWithText("3:35").assertIsDisplayed()
    }

    @Test
    fun `shows nothing is playing when queue is empty`() {
        player.updateState {
            it.copy(current = null, queue = emptyList())
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("Nothing is playing").assertIsDisplayed()
    }

    @Test
    fun `play pause next previous call the player`() {
        val song = QueueItem("1", "Song 1", "Artist 1", null)
        player.updateState {
            it.copy(
                current = song,
                queue = listOf(song),
                isPlaying = false,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithTag("play_pause_button").performClick()
        assertEquals(1, player.togglePlayCallCount)

        composeTestRule.onNodeWithTag("next_button").performClick()
        assertEquals(1, player.nextCallCount)

        composeTestRule.onNodeWithTag("previous_button").performClick()
        assertEquals(1, player.previousCallCount)
    }

    @Test
    fun `dragging the seek bar seeks on release`() {
        val song = QueueItem("1", "Song 1", "Artist 1", null)
        player.updateState {
            it.copy(
                current = song,
                queue = listOf(song),
                positionMs = 0L,
                durationMs = 100_000L,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        assertEquals(emptyList<Long>(), player.seekToCalls)

        composeTestRule.onNodeWithTag("seek_slider").performTouchInput {
            val start = Offset(width * 0.1f, centerY)
            val end = Offset(width * 0.5f, centerY)
            down(start)
            advanceEventTime(100)
            moveTo(end, delayMillis = 100)
            assertEquals(0, player.seekToCalls.size)
            up()
        }

        assertEquals(1, player.seekToCalls.size)
        assertTrue(player.seekToCalls.first() > 0L)
    }

    @Test
    fun `repeat button shows OFF ALL ONE in turn`() {
        val song = QueueItem("1", "Song 1", "Artist 1", null)
        player.updateState {
            it.copy(
                current = song,
                queue = listOf(song),
                repeat = RepeatMode.OFF,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("OFF").assertIsDisplayed()

        composeTestRule.onNodeWithTag("repeat_button").performClick()
        assertEquals(1, player.cycleRepeatCallCount)

        player.updateState { it.copy(repeat = RepeatMode.ALL) }
        composeTestRule.onNodeWithText("ALL").assertIsDisplayed()

        composeTestRule.onNodeWithTag("repeat_button").performClick()
        assertEquals(2, player.cycleRepeatCallCount)

        player.updateState { it.copy(repeat = RepeatMode.ONE) }
        composeTestRule.onNodeWithText("ONE").assertIsDisplayed()
    }

    @Test
    fun `queue sheet lists items and highlights the current one`() {
        val song1 = QueueItem("1", "First Song", "First Artist", null)
        val song2 = QueueItem("2", "Second Song", "Second Artist", null)
        player.updateState {
            it.copy(
                current = song1,
                queue = listOf(song1, song2),
                index = 0,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithTag("open_queue_button").performClick()

        composeTestRule.onNodeWithTag("queue_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("current_queue_item")
            .assertIsDisplayed()
            .assertTextContains("First Song")
        composeTestRule.onNodeWithText("Second Song").assertIsDisplayed()
    }

    @Test
    fun `swiping a queue item removes it`() {
        val song1 = QueueItem("1", "First Song", "First Artist", null)
        val song2 = QueueItem("2", "Second Song", "Second Artist", null)
        player.updateState {
            it.copy(
                current = song1,
                queue = listOf(song1, song2),
                index = 0,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithTag("open_queue_button").performClick()

        composeTestRule.onNodeWithTag("queue_item_1").performTouchInput {
            swipeLeft()
        }

        assertEquals(listOf(1), player.removeCalls)
    }

    @Test
    fun `tapping a queue item jumps to it`() {
        val song1 = QueueItem("1", "First Song", "First Artist", null)
        val song2 = QueueItem("2", "Second Song", "Second Artist", null)
        val song3 = QueueItem("3", "Third Song", "Third Artist", null)
        player.updateState {
            it.copy(
                current = song1,
                queue = listOf(song1, song2, song3),
                index = 0,
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithTag("open_queue_button").performClick()

        composeTestRule.onNodeWithText("Third Song").performClick()

        assertEquals(listOf(2), player.jumpToCalls)
    }

    @Test
    fun `a player error is shown as a snackbar`() {
        val song = QueueItem("1", "Song 1", "Artist 1", null)
        player.updateState {
            it.copy(
                current = song,
                queue = listOf(song),
                error = "Could not stream audio",
            )
        }

        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {})
        }

        composeTestRule.onNodeWithText("Could not stream audio").assertIsDisplayed()
        assertEquals(1, player.clearErrorCallCount)
    }

    private val song = QueueItem(songId = "s1", title = "Starman", artist = "David Bowie", artworkUri = null)

    @Test
    fun `the heart button toggles like and reflects the liked state`() {
        player.updateState { it.copy(current = song, queue = listOf(song)) }
        var toggles = 0
        composeTestRule.setContent {
            PlayerScreen(player = player, onBack = {}, liked = true, onToggleLike = { toggles++ })
        }

        composeTestRule.onNodeWithTag("like_button").performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `picking a sleep timer length starts the timer`() {
        player.updateState { it.copy(current = song, queue = listOf(song)) }
        composeTestRule.setContent { PlayerScreen(player = player, onBack = {}) }

        composeTestRule.onNodeWithTag("sleep_timer_button").performClick()
        composeTestRule.onNodeWithTag("sleep_option_30").performClick()

        assertEquals(listOf<Int?>(30), player.sleepTimerCalls)
    }

    @Test
    fun `a running sleep timer can be turned off`() {
        player.updateState {
            it.copy(current = song, queue = listOf(song), sleepTimerEndsAtMillis = System.currentTimeMillis() + 10 * 60_000L)
        }
        composeTestRule.setContent { PlayerScreen(player = player, onBack = {}) }

        composeTestRule.onNodeWithTag("sleep_timer_button").performClick()
        composeTestRule.onNodeWithTag("sleep_timer_remaining").assertIsDisplayed()
        composeTestRule.onNodeWithTag("sleep_timer_off").performClick()

        assertEquals(listOf<Int?>(null), player.sleepTimerCalls)
    }
}
