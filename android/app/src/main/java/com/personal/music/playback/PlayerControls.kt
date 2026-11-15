package com.personal.music.playback

import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.StateFlow

interface PlayerControls {
    val state: StateFlow<PlayerState>

    fun play(songs: List<SongEntity>, startIndex: Int = 0)
    fun playNext(song: SongEntity)
    fun addToQueue(song: SongEntity)
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(positionMs: Long)
    fun setShuffle(on: Boolean)
    fun cycleRepeat()
    fun moveQueueItem(from: Int, to: Int)
    fun removeQueueItem(index: Int)
    fun jumpTo(index: Int)
    fun clearError()
    fun playShuffled(songs: List<SongEntity>)
    /** Pauses playback after [minutes]; null cancels the timer. */
    fun setSleepTimer(minutes: Int?)
    fun setSpeed(speed: Float)
}
