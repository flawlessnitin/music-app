package com.personal.music.library

import androidx.room.withTransaction
import com.personal.music.catalog.RemoteCatalog
import com.personal.music.db.MetaEntity
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.Flow

typealias Album = com.personal.music.db.Album
typealias Artist = com.personal.music.db.Artist

class LibraryRepository(private val db: MusicDatabase) {

    private val songDao = db.songDao()
    private val metaDao = db.metaDao()

    suspend fun applyCatalog(catalog: RemoteCatalog): List<SongEntity> = db.withTransaction {
        val existingSongs = songDao.getAllSongsList()
        val existingMap = existingSongs.associateBy { it.id }
        val newIds = catalog.songs.map { it.id }.toSet()

        val toDelete = existingSongs.filter { it.id !in newIds }
        if (toDelete.isNotEmpty()) {
            songDao.delete(toDelete)
        }

        val toUpsert = catalog.songs.distinctBy { it.id }.map { remote ->
            val existing = existingMap[remote.id]
            SongEntity(
                id = remote.id,
                title = remote.title,
                artist = remote.artist,
                album = remote.album,
                albumArtist = remote.albumArtist,
                track = remote.track,
                disc = remote.disc,
                year = remote.year,
                durationMs = remote.durationMs,
                sizeBytes = remote.sizeBytes,
                format = remote.format,
                file = remote.file,
                cover = remote.cover,
                // A song re-added in another format keeps its id but points at a new file,
                // so the copy on the phone no longer counts.
                downloaded = existing != null && existing.downloaded && existing.file == remote.file
            )
        }
        if (toUpsert.isNotEmpty()) {
            songDao.insertOrUpdate(toUpsert)
        }

        metaDao.setCatalogVersion(MetaEntity(id = 1, catalogVersion = catalog.version))

        toDelete
    }

    suspend fun catalogVersion(): Int {
        return metaDao.getCatalogVersion() ?: 0
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

    suspend fun setDownloaded(id: String, downloaded: Boolean) {
        songDao.setDownloaded(id, downloaded)
    }

    suspend fun pending(): List<SongEntity> {
        return songDao.getPendingSongs()
    }

    suspend fun downloadedBytes(): Long {
        return songDao.getDownloadedBytes()
    }
}
