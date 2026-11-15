package com.personal.music.listening

import androidx.room.withTransaction
import com.personal.music.db.LikedSongEntity
import com.personal.music.db.MusicDatabase
import com.personal.music.db.RecentPlayEntity
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Liked songs and the recently played list. */
class ListeningRepository(private val db: MusicDatabase) {
    private val dao = db.listeningDao()

    fun likedIds(): Flow<Set<String>> = dao.likedIdsFlow().map { it.toSet() }

    fun likedSongs(): Flow<List<SongEntity>> = dao.likedSongsFlow()

    fun recentSongs(): Flow<List<SongEntity>> = dao.recentSongsFlow()

    suspend fun setLiked(songId: String, liked: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        if (liked) dao.insertLiked(LikedSongEntity(songId, nowMillis)) else dao.deleteLiked(songId)
    }

    suspend fun toggleLiked(songId: String) = db.withTransaction {
        setLiked(songId, liked = dao.countLiked(songId) == 0)
    }

    suspend fun recordPlay(songId: String, nowMillis: Long = System.currentTimeMillis()) = db.withTransaction {
        dao.upsertRecent(RecentPlayEntity(songId, nowMillis))
        dao.trimRecent(MAX_RECENT)
    }

    private companion object {
        const val MAX_RECENT = 30
    }
}
