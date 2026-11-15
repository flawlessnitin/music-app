package com.personal.music.library

import androidx.room.withTransaction
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.Flow

typealias Album = com.personal.music.db.Album
typealias Artist = com.personal.music.db.Artist

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
        if (found.isNotEmpty()) songDao.insertOrUpdate(found)
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
