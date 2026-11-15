package com.personal.music.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMusicScannerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var music: File
    private lateinit var covers: File
    private val tags = mutableMapOf<String, AudioTags?>()

    private val reader = TagReader { file ->
        if (tags.containsKey(file.name)) tags[file.name] else AudioTags(durationMs = 1_000)
    }

    @Before
    fun setUp() {
        music = tmp.newFolder("Music")
        covers = tmp.newFolder("covers")
    }

    private fun file(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)): File =
        File(music, path).also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }

    private fun scan() = LocalMusicScanner(music, covers, reader).scan()

    @Test
    fun `reads tags into songs marked local and downloaded`() {
        file("a.mp3")
        tags["a.mp3"] = AudioTags("Tum Hi Ho", "Arijit", "Aashiqui 2", "Mithoon", 3, 1, 2013, 262_000)

        val song = scan().single()

        assertEquals("Tum Hi Ho", song.title)
        assertEquals("Arijit", song.artist)
        assertEquals("Aashiqui 2", song.album)
        assertEquals("Mithoon", song.albumArtist)
        assertEquals(3, song.track)
        assertEquals(2013, song.year)
        assertEquals(262_000L, song.durationMs)
        assertEquals("mp3", song.format)
        assertTrue(song.local)
        assertTrue(song.downloaded)
        assertEquals(File(music, "a.mp3").absolutePath, song.file)
        assertEquals(3L, song.sizeBytes)
    }

    @Test
    fun `without tags the title comes from the file name without its number prefix`() {
        file("03 - Aashiq Awara.mp3")

        val song = scan().single()

        assertEquals("Aashiq Awara", song.title)
        assertEquals("Unknown artist", song.artist)
        assertEquals("Unknown artist", song.albumArtist)
    }

    @Test
    fun `without an album tag the folder name is the album`() {
        file("barian mix/01 - Bairan.mp3")

        assertEquals("barian mix", scan().single().album)
    }

    @Test
    fun `files in the music folder itself get a plain album name`() {
        file("loose.mp3")

        assertEquals("Local music", scan().single().album)
    }

    @Test
    fun `ids are stable between scans and differ between files`() {
        file("a.mp3")
        file("b.mp3")

        val first = scan().map { it.id }
        val second = scan().map { it.id }

        assertEquals(first, second)
        assertNotEquals(first[0], first[1])
        assertTrue(first.all { it.startsWith("local-") })
    }

    @Test
    fun `non audio files and unreadable files are skipped`() {
        file("a.mp3")
        file("notes.txt")
        file("Folder.jpg")
        file("broken.mp3")
        tags["broken.mp3"] = null

        assertEquals(listOf("a"), scan().map { it.title })
    }

    @Test
    fun `embedded art is saved as the cover`() {
        file("a.mp3")
        tags["a.mp3"] = AudioTags(durationMs = 1, cover = byteArrayOf(9, 9, 9))

        val song = scan().single()

        assertEquals(File(covers, song.cover!!).readBytes().toList(), listOf<Byte>(9, 9, 9))
    }

    @Test
    fun `a Folder jpg next to the songs is used when there is no embedded art`() {
        file("mix/a.mp3")
        file("mix/Folder.jpg", byteArrayOf(7, 7))

        val song = scan().single()

        assertEquals(listOf<Byte>(7, 7), File(covers, song.cover!!).readBytes().toList())
    }

    @Test
    fun `a song with no art has no cover`() {
        file("a.mp3")

        assertNull(scan().single().cover)
    }

    @Test
    fun `a missing folder gives no songs`() {
        music.deleteRecursively()

        assertFalse(scan().isNotEmpty())
    }

    @Test
    fun `a folder straight under the music folder is a mood`() {
        file("phonk/01 - Drift.mp3")

        assertEquals("phonk", scan().single().mood)
    }

    @Test
    fun `artist and album folders alone make no mood`() {
        file("Arijit/Aashiqui/01 - Tum Hi Ho.mp3")

        assertNull(scan().single().mood)
    }

    @Test
    fun `mood then artist then album gives the first folder as the mood`() {
        file("sad/Arijit/Aashiqui/01 - Tum Hi Ho.mp3")

        assertEquals("sad", scan().single().mood)
    }

    @Test
    fun `songs loose in the music folder have no mood`() {
        file("loose.mp3")

        assertNull(scan().single().mood)
    }
}
