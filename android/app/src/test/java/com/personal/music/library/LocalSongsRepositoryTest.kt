package com.personal.music.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.catalog.RemoteCatalog
import com.personal.music.catalog.RemoteSong
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalSongsRepositoryTest {
    private lateinit var db: MusicDatabase
    private lateinit var repo: LibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = LibraryRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun local(id: String) = SongEntity(
        id = id, title = "Local $id", artist = "A", album = "Mix", albumArtist = "A",
        track = null, disc = null, year = null, durationMs = 1000, sizeBytes = 10,
        format = "mp3", file = "/data/$id.mp3", cover = null, downloaded = true, local = true,
    )

    private fun remote(id: String) = RemoteSong(
        id = id, title = "Remote $id", artist = "B", album = "Al", albumArtist = "B",
        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 10,
        format = "opus", file = "songs/$id.opus", cover = null,
    )

    private suspend fun ids() = repo.songs().first().map { it.id }.toSet()

    @Test
    fun `local songs are added and flagged`() = runTest {
        repo.replaceLocalSongs(listOf(local("local-1"), local("local-2")))

        assertEquals(setOf("local-1", "local-2"), ids())
        assertTrue(repo.songs().first().all { it.local && it.downloaded })
    }

    @Test
    fun `local songs that are gone from the phone are removed`() = runTest {
        repo.replaceLocalSongs(listOf(local("local-1"), local("local-2")))
        repo.replaceLocalSongs(listOf(local("local-2")))

        assertEquals(setOf("local-2"), ids())
    }

    @Test
    fun `a new catalog does not remove local songs`() = runTest {
        repo.replaceLocalSongs(listOf(local("local-1")))

        repo.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = listOf(remote("r1"))))
        assertEquals(setOf("local-1", "r1"), ids())

        repo.applyCatalog(RemoteCatalog(version = 2, updatedAt = "", songs = emptyList()))
        assertEquals(setOf("local-1"), ids())
    }

    @Test
    fun `rescanning local songs does not touch synced songs`() = runTest {
        repo.applyCatalog(RemoteCatalog(version = 1, updatedAt = "", songs = listOf(remote("r1"))))

        repo.replaceLocalSongs(emptyList())

        assertEquals(setOf("r1"), ids())
    }

    @Test
    fun `local songs are never listed as pending downloads`() = runTest {
        repo.replaceLocalSongs(listOf(local("local-1")))

        assertTrue(repo.pending().isEmpty())
    }
}
