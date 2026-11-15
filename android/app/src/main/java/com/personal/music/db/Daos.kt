package com.personal.music.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    // Upsert, not REPLACE: REPLACE deletes the row first, which would cascade into playlists.
    @Upsert
    suspend fun insertOrUpdate(songs: List<SongEntity>)

    @Delete
    suspend fun delete(songs: List<SongEntity>)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSong(id: String): SongEntity?

    @Query("SELECT * FROM songs")
    suspend fun getAllSongsList(): List<SongEntity>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE ASC")
    fun getAllSongsFlow(): Flow<List<SongEntity>>

    @Query("""
        SELECT albumArtist, album, MAX(cover) AS cover, MIN(year) AS year, COUNT(*) AS songCount 
        FROM songs 
        GROUP BY albumArtist, album 
        ORDER BY albumArtist COLLATE NOCASE ASC, album COLLATE NOCASE ASC
    """)
    fun getAlbumsFlow(): Flow<List<Album>>

    @Query("""
        SELECT albumArtist AS name, COUNT(*) AS songCount 
        FROM songs 
        GROUP BY albumArtist 
        ORDER BY albumArtist COLLATE NOCASE ASC
    """)
    fun getArtistsFlow(): Flow<List<Artist>>

    @Query("""
        SELECT * FROM songs 
        WHERE albumArtist = :albumArtist AND album = :album 
        ORDER BY CASE WHEN disc IS NULL THEN 0 ELSE 1 END ASC, disc ASC, 
                 CASE WHEN track IS NULL THEN 0 ELSE 1 END ASC, track ASC, 
                 title COLLATE NOCASE ASC
    """)
    fun getAlbumSongsFlow(albumArtist: String, album: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE albumArtist = :name 
        ORDER BY year ASC, album COLLATE NOCASE ASC, 
                 CASE WHEN disc IS NULL THEN 0 ELSE 1 END ASC, disc ASC, 
                 CASE WHEN track IS NULL THEN 0 ELSE 1 END ASC, track ASC, 
                 title COLLATE NOCASE ASC
    """)
    fun getArtistSongsFlow(name: String): Flow<List<SongEntity>>

    @Query("""
        SELECT * FROM songs 
        WHERE (
            LOWER(title) LIKE '%' || LOWER(:query) || '%' 
            OR LOWER(artist) LIKE '%' || LOWER(:query) || '%' 
            OR LOWER(album) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(albumArtist) LIKE '%' || LOWER(:query) || '%'
        ) 
        ORDER BY title COLLATE NOCASE ASC
    """)
    fun searchSongsFlow(query: String): Flow<List<SongEntity>>
}

@Dao
interface MetaDao {
    @Query("SELECT catalogVersion FROM meta WHERE id = 1")
    suspend fun getCatalogVersion(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCatalogVersion(meta: MetaEntity)
}

@Dao
interface PlaylistDao {
    @Query("""
        SELECT p.id, p.name, COUNT(ps.songId) AS songCount
        FROM playlists p
        LEFT JOIN playlist_songs ps ON p.id = ps.playlistId
        GROUP BY p.id, p.name
        ORDER BY p.name COLLATE NOCASE ASC, p.id ASC
    """)
    fun getPlaylistsFlow(): Flow<List<PlaylistSummary>>

    @Query("""
        SELECT s.*
        FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
    """)
    fun getPlaylistSongsFlow(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun getPlaylistFlow(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun updatePlaylistName(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistSongsList(playlistId: Long): List<PlaylistSongEntity>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun countSongInPlaylist(playlistId: Long, songId: String): Int

    suspend fun containsSong(playlistId: Long, songId: String): Boolean =
        countSongInPlaylist(playlistId, songId) > 0

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSong(playlistSong: PlaylistSongEntity): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deletePlaylistSong(playlistId: Long, songId: String)

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updatePosition(playlistId: Long, songId: String, position: Int)
}

@Dao
interface ListeningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLiked(liked: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE songId = :songId")
    suspend fun deleteLiked(songId: String)

    @Query("SELECT COUNT(*) FROM liked_songs WHERE songId = :songId")
    suspend fun countLiked(songId: String): Int

    @Query("SELECT songId FROM liked_songs")
    fun likedIdsFlow(): Flow<List<String>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN liked_songs l ON s.id = l.songId
        ORDER BY l.likedAtMillis DESC, s.title COLLATE NOCASE ASC
    """)
    fun likedSongsFlow(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(recent: RecentPlayEntity)

    @Query("""
        DELETE FROM recent_plays WHERE songId NOT IN (
            SELECT songId FROM recent_plays ORDER BY playedAtMillis DESC LIMIT :keep
        )
    """)
    suspend fun trimRecent(keep: Int)

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN recent_plays r ON s.id = r.songId
        ORDER BY r.playedAtMillis DESC
    """)
    fun recentSongsFlow(): Flow<List<SongEntity>>
}
