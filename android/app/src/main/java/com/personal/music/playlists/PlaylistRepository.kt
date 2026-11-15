package com.personal.music.playlists

import androidx.room.withTransaction
import com.personal.music.db.MusicDatabase
import com.personal.music.db.PlaylistEntity
import com.personal.music.db.PlaylistSongEntity
import com.personal.music.db.PlaylistSummary
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.Flow

class PlaylistRepository(private val db: MusicDatabase) {
    private val playlistDao = db.playlistDao()

    fun playlists(): Flow<List<PlaylistSummary>> {
        return playlistDao.getPlaylistsFlow()
    }

    fun songs(playlistId: Long): Flow<List<SongEntity>> {
        return playlistDao.getPlaylistSongsFlow(playlistId)
    }

    fun playlist(playlistId: Long): Flow<PlaylistEntity?> {
        return playlistDao.getPlaylistFlow(playlistId)
    }

    suspend fun getPlaylist(playlistId: Long): PlaylistEntity? {
        return playlistDao.getPlaylist(playlistId)
    }

    suspend fun create(name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Playlist name cannot be blank")
        }
        return playlistDao.insertPlaylist(PlaylistEntity(name = trimmed))
    }

    suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Playlist name cannot be blank")
        }
        playlistDao.updatePlaylistName(id, trimmed)
    }

    suspend fun delete(id: Long) {
        playlistDao.deletePlaylist(id)
    }

    suspend fun addSong(playlistId: Long, songId: String) = db.withTransaction {
        if (!playlistDao.containsSong(playlistId, songId)) {
            val nextPosition = playlistDao.getMaxPosition(playlistId) + 1
            playlistDao.insertPlaylistSong(
                PlaylistSongEntity(
                    playlistId = playlistId,
                    songId = songId,
                    position = nextPosition,
                )
            )
        }
    }

    suspend fun removeSong(playlistId: Long, songId: String) = db.withTransaction {
        playlistDao.deletePlaylistSong(playlistId, songId)
        val remaining = playlistDao.getPlaylistSongsList(playlistId)
        remaining.forEachIndexed { newIndex, song ->
            if (song.position != newIndex) {
                playlistDao.updatePosition(playlistId, song.songId, newIndex)
            }
        }
    }

    suspend fun move(playlistId: Long, from: Int, to: Int) = db.withTransaction {
        val songs = playlistDao.getPlaylistSongsList(playlistId).toMutableList()
        if (from in songs.indices && to in songs.indices && from != to) {
            val moved = songs.removeAt(from)
            songs.add(to, moved)
            songs.forEachIndexed { newIndex, song ->
                if (song.position != newIndex) {
                    playlistDao.updatePosition(playlistId, song.songId, newIndex)
                }
            }
        }
    }
}
