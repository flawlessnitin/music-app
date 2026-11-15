package com.personal.music.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * When a song cannot be played (file gone, no network for a song that is not downloaded),
 * move on to the next one instead of stopping the queue.
 *
 * Gives up after [MAX_IN_A_ROW] failures in a row, so a queue where nothing can play
 * (for example offline with repeat on) does not spin forever.
 */
class SkipOnError(
    private val player: Player,
    private val onError: (PlaybackException) -> Unit = {},
) : Player.Listener {
    private var failuresInARow = 0

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) failuresInARow = 0
    }

    override fun onPlayerError(error: PlaybackException) {
        onError(error)
        failuresInARow++
        if (failuresInARow >= MAX_IN_A_ROW || !player.hasNextMediaItem()) return
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
    }

    companion object {
        const val MAX_IN_A_ROW = 5
    }
}
