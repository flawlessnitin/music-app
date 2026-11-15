package com.personal.music.playback

import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakePlayerControls(
    initialState: PlayerState = PlayerState(),
) : PlayerControls {
    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun updateState(transform: (PlayerState) -> PlayerState) {
        _state.update(transform)
    }

    val playCalls = mutableListOf<Pair<List<SongEntity>, Int>>()
    val playNextCalls = mutableListOf<SongEntity>()
    val addToQueueCalls = mutableListOf<SongEntity>()
    var togglePlayCallCount = 0
    var nextCallCount = 0
    var previousCallCount = 0
    val seekToCalls = mutableListOf<Long>()
    val shuffleCalls = mutableListOf<Boolean>()
    var cycleRepeatCallCount = 0
    val moveCalls = mutableListOf<Pair<Int, Int>>()
    val removeCalls = mutableListOf<Int>()
    val jumpToCalls = mutableListOf<Int>()
    var clearErrorCallCount = 0
    val shuffledPlayCalls = mutableListOf<List<SongEntity>>()
    val sleepTimerCalls = mutableListOf<Int?>()
    val speedCalls = mutableListOf<Float>()

    override fun play(songs: List<SongEntity>, startIndex: Int) {
        playCalls += songs to startIndex
    }

    override fun playNext(song: SongEntity) {
        playNextCalls += song
    }

    override fun addToQueue(song: SongEntity) {
        addToQueueCalls += song
    }

    override fun togglePlay() {
        togglePlayCallCount++
    }

    override fun next() {
        nextCallCount++
    }

    override fun previous() {
        previousCallCount++
    }

    override fun seekTo(positionMs: Long) {
        seekToCalls += positionMs
    }

    override fun setShuffle(on: Boolean) {
        shuffleCalls += on
    }

    override fun cycleRepeat() {
        cycleRepeatCallCount++
    }

    override fun moveQueueItem(from: Int, to: Int) {
        moveCalls += from to to
    }

    override fun removeQueueItem(index: Int) {
        removeCalls += index
    }

    override fun jumpTo(index: Int) {
        jumpToCalls += index
    }

    override fun clearError() {
        clearErrorCallCount++
    }

    override fun playShuffled(songs: List<SongEntity>) {
        shuffledPlayCalls += songs
    }

    override fun setSleepTimer(minutes: Int?) {
        sleepTimerCalls += minutes
    }

    override fun setSpeed(speed: Float) {
        speedCalls += speed
    }
}
