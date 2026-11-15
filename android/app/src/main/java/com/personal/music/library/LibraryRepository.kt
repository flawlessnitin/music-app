package com.personal.music.library

import androidx.room.withTransaction
import com.personal.music.db.MoodOverrideEntity
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.Flow

typealias Album = com.personal.music.db.Album
typealias Artist = com.personal.music.db.Artist
typealias Mood = com.personal.music.db.Mood

class LibraryRepository(private val db: MusicDatabase) {

    private val songDao = db.songDao()

    /**
     * Makes the library match [found], the songs that are on the phone right now.
     * Songs that are gone from the phone leave the library (and the playlists, likes and history that held them).
     */
    suspend fun replaceSongs(found: List<SongEntity>) = db.withTransaction {
        val keep = found.map { it.id }.toSet()
        val gone = songDao.getAllSongsList().filter { it.id !in keep }
        if (gone.isNotEmpty()) songDao.delete(gone)
        // A mood chosen in the app wins over the one the folders say.
        val chosen = songDao.getMoodOverrides().associate { it.songId to it.mood }
        val withMoods = found.map { song ->
            val pick = chosen[song.id]
            if (pick == null) song else song.copy(mood = pick.ifEmpty { null })
        }
        if (withMoods.isNotEmpty()) songDao.insertOrUpdate(withMoods)
    }

    fun moods(): Flow<List<Mood>> = songDao.getMoodsFlow()

    fun moodSongs(mood: String): Flow<List<SongEntity>> = songDao.getMoodSongsFlow(mood)

    /** Puts a song in [mood] (or none), and remembers the choice so a rescan keeps it. */
    suspend fun setMood(songId: String, mood: String?) = db.withTransaction {
        val cleaned = mood?.trim()?.takeIf { it.isNotEmpty() }
        songDao.upsertMoodOverride(MoodOverrideEntity(songId, cleaned.orEmpty()))
        songDao.updateMood(songId, cleaned)
    }

    fun songs(): Flow<List<SongEntity>> {
        return songDao.getAllSongsFlow()
    }

    fun albums(): Flow<List<Album>> {
        return songDao.getAlbumsFlow()
    }

    fun artists(): Flow<List<Artist>> {
        return songDao.getArtistsFlow()
    }

    fun albumSongs(albumArtist: String, album: String): Flow<List<SongEntity>> {
        return songDao.getAlbumSongsFlow(albumArtist, album)
    }

    fun artistSongs(name: String): Flow<List<SongEntity>> {
        return songDao.getArtistSongsFlow(name)
    }

    fun search(query: String): Flow<List<SongEntity>> {
        val trimmed = query.trim()
        return if (trimmed.isBlank()) {
            songs()
        } else {
            songDao.searchSongsFlow(trimmed)
        }
    }

    suspend fun song(id: String): SongEntity? {
        return songDao.getSong(id)
    }
}
