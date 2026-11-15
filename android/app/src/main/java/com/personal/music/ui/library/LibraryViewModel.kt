package com.personal.music.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.music.db.Album
import com.personal.music.db.Artist
import com.personal.music.db.Mood
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.PlayerControls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: LibraryRepository,
    val player: PlayerControls,
    coroutineScope: CoroutineScope? = null,
) : ViewModel() {

    private val scope = coroutineScope ?: viewModelScope

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val songs: StateFlow<List<SongEntity>> = _query
        .flatMapLatest { q -> repo.search(q) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val albums: StateFlow<List<Album>> = repo.albums()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val artists: StateFlow<List<Artist>> = repo.artists()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val moods: StateFlow<List<Mood>> = repo.moods()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    fun setQuery(q: String) {
        _query.value = q
    }

    fun playSong(list: List<SongEntity>, index: Int) {
        player.play(list, index)
    }

    fun playNext(song: SongEntity) {
        player.playNext(song)
    }

    fun addToQueue(song: SongEntity) {
        player.addToQueue(song)
    }
}
