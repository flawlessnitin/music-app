package com.personal.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.personal.music.db.SongEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RepeatMode { OFF, ALL, ONE }

data class QueueItem(
    val songId: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
)

data class PlayerState(
    val current: QueueItem? = null,
    val queue: List<QueueItem> = emptyList(),
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
)

/**
 * What the screens use to control playback and to observe it.
 *
 * Works against any [Player]; in the app that is a MediaController connected to
 * [PlaybackService]. Commands issued before the player is attached run once it is.
 */
class PlayerConnection(
    private val scope: CoroutineScope,
    private val toMediaItem: (SongEntity) -> MediaItem,
) : PlayerControls, Player.Listener {
    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var player: Player? = null
    private val waiting = ArrayDeque<(Player) -> Unit>()
    private var ticker: Job? = null

    fun attach(player: Player) {
        this.player = player
        player.addListener(this)
        while (waiting.isNotEmpty()) waiting.removeFirst()(player)
        refresh()
    }

    override fun play(songs: List<SongEntity>, startIndex: Int) = withPlayer { p ->
        if (songs.isEmpty()) return@withPlayer
        p.setMediaItems(songs.map(toMediaItem), startIndex.coerceIn(0, songs.lastIndex), 0L)
        p.prepare()
        p.play()
    }

    override fun playNext(song: SongEntity) = withPlayer { p ->
        val at = if (p.mediaItemCount == 0) 0 else p.currentMediaItemIndex + 1
        p.addMediaItem(at, toMediaItem(song))
        startIfIdle(p)
    }

    override fun addToQueue(song: SongEntity) = withPlayer { p ->
        p.addMediaItem(toMediaItem(song))
        startIfIdle(p)
    }

    override fun togglePlay() = withPlayer { p ->
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0L)
            p.play()
        }
    }

    override fun next() = withPlayer { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }

    /** Restarts the song when more than a few seconds in, otherwise goes to the previous one. */
    override fun previous() = withPlayer { it.seekToPrevious() }

    override fun seekTo(positionMs: Long) = withPlayer { it.seekTo(positionMs) }

    override fun setShuffle(on: Boolean) = withPlayer { it.shuffleModeEnabled = on }

    override fun cycleRepeat() = withPlayer { p ->
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun moveQueueItem(from: Int, to: Int) = withPlayer { p ->
        if (from in 0 until p.mediaItemCount && to in 0 until p.mediaItemCount) p.moveMediaItem(from, to)
    }

    override fun removeQueueItem(index: Int) = withPlayer { p ->
        if (index in 0 until p.mediaItemCount) p.removeMediaItem(index)
    }

    override fun jumpTo(index: Int) = withPlayer { p ->
        if (index in 0 until p.mediaItemCount) {
            p.seekTo(index, 0L)
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    override fun clearError() = _state.update { it.copy(error = null) }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    override fun onPlayerError(error: PlaybackException) {
        val title = player?.currentMediaItem?.mediaMetadata?.title?.toString()
        val reason = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull()
        _state.update {
            it.copy(error = listOfNotNull("Could not play", title).joinToString(" ") + (reason?.let { r -> ": $r" } ?: ""))
        }
    }

    private fun startIfIdle(p: Player) {
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    private fun withPlayer(action: (Player) -> Unit) {
        val p = player
        if (p != null) action(p) else waiting.addLast(action)
    }

    private fun refresh() {
        val p = player ?: return
        val queue = List(p.mediaItemCount) { i -> p.getMediaItemAt(i).toQueueItem() }
        val index = if (queue.isEmpty()) 0 else p.currentMediaItemIndex
        _state.update {
            it.copy(
                current = queue.getOrNull(index),
                queue = queue,
                index = index,
                isPlaying = p.isPlaying,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
                shuffle = p.shuffleModeEnabled,
                repeat = when (p.repeatMode) {
                    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                    else -> RepeatMode.OFF
                },
            )
        }
        runTicker(p.isPlaying)
    }

    /** While playing, the position moves without any player event, so poll it. */
    private fun runTicker(playing: Boolean) {
        if (!playing) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val p = player ?: break
                _state.update { it.copy(positionMs = p.currentPosition.coerceAtLeast(0)) }
            }
        }
    }

    private fun MediaItem.toQueueItem() = QueueItem(
        songId = mediaId,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        artworkUri = mediaMetadata.artworkUri?.toString(),
    )

    companion object {
        /** Connects [connection] to the playback service; safe to call once per process. */
        fun connect(context: Context, connection: PlayerConnection) {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                { runCatching { future.get() }.onSuccess { connection.attach(it) } },
                ContextCompat.getMainExecutor(context),
            )
        }
    }
}
