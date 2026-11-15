package com.personal.music.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoodsRepositoryTest {
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

    private fun song(id: String, mood: String? = null, title: String = "T$id") = SongEntity(
        id = id, title = title, artist = "A", album = "Al", albumArtist = "A",
        track = 1, disc = 1, year = 2024, durationMs = 1000, sizeBytes = 10,
        format = "mp3", file = "/m/$id.mp3", cover = null, mood = mood,
    )

    @Test
    fun `moods are listed with how many songs each has`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk"), song("2", "phonk"), song("3", "sad"), song("4")))

        assertEquals(listOf("phonk" to 2, "sad" to 1), repo.moods().first().map { it.name to it.songCount })
    }

    @Test
    fun `songs without a mood do not make a mood`() = runTest {
        repo.replaceSongs(listOf(song("1"), song("2")))

        assertTrue(repo.moods().first().isEmpty())
    }

    @Test
    fun `moods are sorted ignoring case`() = runTest {
        repo.replaceSongs(listOf(song("1", "sad"), song("2", "Chill"), song("3", "energetic")))

        assertEquals(listOf("Chill", "energetic", "sad"), repo.moods().first().map { it.name })
    }

    @Test
    fun `a mood lists only its own songs`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk", "B"), song("2", "sad"), song("3", "phonk", "A")))

        assertEquals(listOf("A", "B"), repo.moodSongs("phonk").first().map { it.title })
    }

    @Test
    fun `setting a mood moves the song into it`() = runTest {
        repo.replaceSongs(listOf(song("1")))

        repo.setMood("1", "chill")

        assertEquals(listOf("1"), repo.moodSongs("chill").first().map { it.id })
    }

    @Test
    fun `clearing the mood takes the song out of its mood`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk")))

        repo.setMood("1", null)

        assertTrue(repo.moodSongs("phonk").first().isEmpty())
        assertNull(repo.song("1")!!.mood)
    }

    @Test
    fun `a mood you set survives a rescan that says otherwise`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk")))
        repo.setMood("1", "sad")

        repo.replaceSongs(listOf(song("1", "phonk")))

        assertEquals("sad", repo.song("1")!!.mood)
    }

    @Test
    fun `a mood you cleared stays cleared after a rescan`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk")))
        repo.setMood("1", null)

        repo.replaceSongs(listOf(song("1", "phonk")))

        assertNull(repo.song("1")!!.mood)
    }

    @Test
    fun `a song that was never changed follows its folder mood on a rescan`() = runTest {
        repo.replaceSongs(listOf(song("1", "phonk")))

        repo.replaceSongs(listOf(song("1", "chill")))

        assertEquals("chill", repo.song("1")!!.mood)
    }
}
