package com.personal.music.listening

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListeningRepositoryTest {
    private lateinit var db: MusicDatabase
    private lateinit var repo: ListeningRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ListeningRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun addSongs(vararg ids: String) {
        db.songDao().insertOrUpdate(ids.map {
            SongEntity(it, "Title $it", "A", "Al", "A", 1, 1, 2024, 1000, 1000, "opus", "$it.opus", null, true)
        })
    }

    @Test
    fun likeAndUnlike() = runTest {
        addSongs("a", "b")
        repo.setLiked("a", true)
        assertEquals(setOf("a"), repo.likedIds().first())
        repo.setLiked("a", false)
        assertTrue(repo.likedIds().first().isEmpty())
    }

    @Test
    fun likingTwiceKeepsOneRow() = runTest {
        addSongs("a")
        repo.setLiked("a", true)
        repo.setLiked("a", true)
        assertEquals(1, repo.likedSongs().first().size)
    }

    @Test
    fun likedSongsListsNewestLikeFirst() = runTest {
        addSongs("a", "b", "c")
        repo.setLiked("a", true, nowMillis = 1)
        repo.setLiked("b", true, nowMillis = 3)
        repo.setLiked("c", true, nowMillis = 2)
        assertEquals(listOf("b", "c", "a"), repo.likedSongs().first().map { it.id })
    }

    @Test
    fun toggleFlipsTheState() = runTest {
        addSongs("a")
        repo.toggleLiked("a")
        assertTrue("a" in repo.likedIds().first())
        repo.toggleLiked("a")
        assertFalse("a" in repo.likedIds().first())
    }

    @Test
    fun recentPlaysListNewestFirstWithoutDuplicates() = runTest {
        addSongs("a", "b")
        repo.recordPlay("a", nowMillis = 1)
        repo.recordPlay("b", nowMillis = 2)
        repo.recordPlay("a", nowMillis = 3)
        assertEquals(listOf("a", "b"), repo.recentSongs().first().map { it.id })
    }

    @Test
    fun recentPlaysKeepOnlyTheLatest30() = runTest {
        val ids = (1..35).map { "s$it" }
        addSongs(*ids.toTypedArray())
        ids.forEachIndexed { i, id -> repo.recordPlay(id, nowMillis = i.toLong()) }
        val recent = repo.recentSongs().first()
        assertEquals(30, recent.size)
        assertEquals("s35", recent.first().id)
        assertEquals("s6", recent.last().id)
    }
}
