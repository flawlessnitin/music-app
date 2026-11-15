package com.personal.music.playback

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class PlayerConnectionTest {
    private lateinit var player: ExoPlayer
    private lateinit var scope: CoroutineScope
    private lateinit var connection: PlayerConnection

    private fun song(id: String) = SongEntity(
        id = id, title = "Song $id", artist = "Artist $id", album = "b", albumArtist = "a",
        track = null, disc = null, year = null, durationMs = 1_000, sizeBytes = 1,
        format = "opus", file = "songs/$id.opus", cover = null,
    )

    private val a = song("a")
    private val b = song("b")
    private val c = song("c")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        player = TestExoPlayerBuilder(context).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        connection = PlayerConnection(scope) { MediaItems.from(it, null, null) }
        connection.attach(player)
    }

    @After
    fun tearDown() {
        scope.cancel()
        player.release()
    }

    /**
     * The test player hands messages between its playback thread and the main thread one at
     * a time, so a single pass over the main looper can return before the last update has
     * arrived. Keep draining for a short while.
     */
    private fun settle() = repeat(30) {
        ShadowLooper.idleMainLooper()
        Thread.sleep(5)
    }
    private fun ids() = connection.state.value.queue.map { it.songId }

    /** Loads the queue straight into the player without starting playback. */
    private fun queue(startIndex: Int, vararg songs: SongEntity) {
        player.setMediaItems(songs.map { MediaItems.from(it, null, null) }, startIndex, 0L)
        settle()
    }

    @Test
    fun `play sets the queue and starts at the chosen index`() {
        connection.play(listOf(a, b, c), startIndex = 1)
        settle()

        val state = connection.state.value
        assertEquals(listOf("a", "b", "c"), ids())
        assertEquals(1, state.index)
        assertEquals("Song b", state.current!!.title)
        assertEquals("Artist b", state.current!!.artist)
        assertTrue(player.playWhenReady)
    }

    @Test
    fun `playNext inserts after the current item`() {
        connection.play(listOf(a, b), startIndex = 0)
        connection.playNext(c)
        settle()

        assertEquals(listOf("a", "c", "b"), ids())
        assertEquals(0, connection.state.value.index)
    }

    @Test
    fun `addToQueue appends`() {
        connection.play(listOf(a), startIndex = 0)
        connection.addToQueue(b)
        connection.addToQueue(c)
        settle()

        assertEquals(listOf("a", "b", "c"), ids())
    }

    @Test
    fun `adding to an empty queue makes that song current`() {
        connection.playNext(a)
        settle()

        assertEquals(listOf("a"), ids())
        assertEquals("a", connection.state.value.current!!.songId)
    }

    @Test
    fun `moving and removing queue items updates state`() {
        connection.play(listOf(a, b, c), startIndex = 0)
        connection.moveQueueItem(from = 2, to = 1)
        settle()
        assertEquals(listOf("a", "c", "b"), ids())

        connection.removeQueueItem(1)
        settle()
        assertEquals(listOf("a", "b"), ids())
    }

    @Test
    fun `out of range queue positions are ignored`() {
        connection.play(listOf(a, b), startIndex = 0)
        connection.moveQueueItem(from = 5, to = 0)
        connection.removeQueueItem(9)
        connection.jumpTo(-1)
        settle()

        assertEquals(listOf("a", "b"), ids())
        assertEquals(0, connection.state.value.index)
    }

    @Test
    fun `removing the current item advances to the next`() {
        connection.play(listOf(a, b, c), startIndex = 1)
        connection.removeQueueItem(1)
        settle()

        assertEquals(listOf("a", "c"), ids())
        assertEquals("c", connection.state.value.current!!.songId)
    }

    @Test
    fun `removing the last remaining item leaves nothing current`() {
        connection.play(listOf(a), startIndex = 0)
        connection.removeQueueItem(0)
        settle()

        assertEquals(emptyList<String>(), ids())
        assertNull(connection.state.value.current)
    }

    @Test
    fun `jumpTo selects that item`() {
        connection.play(listOf(a, b, c), startIndex = 0)
        connection.jumpTo(2)
        settle()

        assertEquals("c", connection.state.value.current!!.songId)
    }

    @Test
    fun `cycleRepeat goes OFF ALL ONE OFF`() {
        val seen = mutableListOf(connection.state.value.repeat)
        repeat(3) {
            connection.cycleRepeat()
            settle()
            seen += connection.state.value.repeat
        }

        assertEquals(listOf(RepeatMode.OFF, RepeatMode.ALL, RepeatMode.ONE, RepeatMode.OFF), seen)
    }

    @Test
    fun `shuffle is reflected in state`() {
        connection.setShuffle(true)
        settle()

        assertTrue(connection.state.value.shuffle)
    }

    @Test
    fun `commands issued before the player is attached run once it is`() {
        val late = PlayerConnection(scope) { MediaItems.from(it, null, null) }
        late.play(listOf(a, b), startIndex = 1)
        assertEquals(emptyList<QueueItem>(), late.state.value.queue)

        late.attach(player)
        settle()

        assertEquals(listOf("a", "b"), late.state.value.queue.map { it.songId })
        assertEquals(1, late.state.value.index)
    }

    @Test
    fun `a failing item is skipped and the error is reported`() {
        // Queue without preparing, so the only error in play is the one this test delivers.
        val skipper = SkipOnError(player)
        queue(0, a, b)

        val error = PlaybackException("source error", java.io.IOException("No internet connection"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        connection.onPlayerError(error)
        assertEquals("Could not play Song a: No internet connection", connection.state.value.error)
        connection.clearError()
        assertNull(connection.state.value.error)

        skipper.onPlayerError(error)
        settle()
        assertEquals("b", connection.state.value.current!!.songId)
    }

    @Test
    fun `skipping stops after five failures in a row`() {
        val skipper = SkipOnError(player)
        queue(0, a, b)
        player.repeatMode = Player.REPEAT_MODE_ALL
        val error = PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

        var moves = 0
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) moves++
            }
        })
        repeat(8) { skipper.onPlayerError(error); settle() }

        assertEquals(SkipOnError.MAX_IN_A_ROW - 1, moves)
    }

    @Test
    fun `the last item failing does not wrap around`() {
        val skipper = SkipOnError(player)
        queue(1, a, b)

        skipper.onPlayerError(PlaybackException("x", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        settle()

        assertEquals("b", connection.state.value.current!!.songId)
    }

    @Test
    fun `playShuffled queues every song with shuffle on`() {
        connection.playShuffled(listOf(a, b, c))
        settle()

        assertEquals(setOf("a", "b", "c"), ids().toSet())
        assertEquals(3, ids().size)
        assertTrue(connection.state.value.shuffle)
        assertTrue(connection.state.value.current!!.songId in setOf("a", "b", "c"))
    }

    @Test
    fun `playShuffled with no songs changes nothing`() {
        connection.playShuffled(emptyList())
        settle()

        assertTrue(ids().isEmpty())
    }

    @Test
    fun `restore loads the queue at the saved spot without playing`() {
        connection.restore(listOf(a, b, c), index = 2, positionMs = 500)
        settle()

        assertEquals(listOf("a", "b", "c"), ids())
        assertEquals(2, connection.state.value.index)
        assertTrue(!player.playWhenReady)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun timerConnection(testScope: TestScope): PlayerConnection {
        val timed = PlayerConnection(testScope) { MediaItems.from(it, null, null) }
        timed.attach(player)
        timed.play(listOf(a, b), 0)
        settle()
        assertTrue(player.playWhenReady)
        return timed
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the sleep timer pauses playback when it runs out`() {
        val testScope = TestScope()
        val timed = timerConnection(testScope)

        timed.setSleepTimerMillis(1_000)
        assertTrue(timed.state.value.sleepTimerEndsAtMillis != null)
        testScope.advanceTimeBy(999); testScope.runCurrent()
        assertTrue(player.playWhenReady)

        testScope.advanceTimeBy(2); testScope.runCurrent()
        settle()

        assertTrue(!player.playWhenReady)
        assertNull(timed.state.value.sleepTimerEndsAtMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling the sleep timer keeps playback going`() {
        val testScope = TestScope()
        val timed = timerConnection(testScope)

        timed.setSleepTimerMillis(1_000)
        timed.setSleepTimerMillis(null)
        testScope.advanceTimeBy(5_000); testScope.runCurrent()
        settle()

        assertTrue(player.playWhenReady)
        assertNull(timed.state.value.sleepTimerEndsAtMillis)
    }

    @Test
    fun `setSpeed changes the playback speed and shows in state`() {
        connection.play(listOf(a, b), 0)
        settle()

        connection.setSpeed(1.5f)
        settle()

        assertEquals(1.5f, player.playbackParameters.speed, 0.001f)
        assertEquals(1.5f, connection.state.value.speed, 0.001f)
    }

    @Test
    fun `speed is kept within a sensible range`() {
        connection.play(listOf(a, b), 0)
        settle()

        connection.setSpeed(10f)
        settle()

        assertEquals(2f, connection.state.value.speed, 0.001f)
    }
}
