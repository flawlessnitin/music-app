package com.personal.music.sync

import com.personal.music.b2.B2Exception
import com.personal.music.db.SongEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream

class DownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var bucket: FakeBucket
    private lateinit var songsDir: File
    private lateinit var coversDir: File
    private lateinit var downloader: Downloader

    private val bytes = ByteArray(300_000) { (it % 251).toByte() }
    private val song = SongEntity(
        id = "abc", title = "t", artist = "a", album = "b", albumArtist = "a",
        track = null, disc = null, year = null, durationMs = 1, sizeBytes = bytes.size.toLong(),
        format = "opus", file = "songs/abc.opus", cover = "covers/c1.jpg",
    )

    @Before
    fun setUp() {
        bucket = FakeBucket()
        bucket.files["songs/abc.opus"] = bytes
        bucket.files["covers/c1.jpg"] = byteArrayOf(1, 2, 3)
        songsDir = tmp.newFolder("songs")
        coversDir = tmp.newFolder("covers")
        downloader = Downloader(bucket.client(), songsDir, coversDir)
    }

    @After
    fun tearDown() = bucket.stop()

    @Test
    fun `a completed download is at its final name with the right bytes`() = runTest {
        downloader.downloadSong(song)

        val file = downloader.localFile(song)
        assertEquals(File(songsDir, "abc.opus"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf("abc.opus"), songsDir.list()!!.toList())
    }

    @Test
    fun `a connection cut mid body leaves no final file`() = runTest {
        bucket.cutBodyOf = setOf("songs/abc.opus")

        assertThrows(B2Exception.Network::class.java) {
            kotlinx.coroutines.runBlocking { downloader.downloadSong(song) }
        }

        assertFalse(downloader.localFile(song).exists())
        assertEquals(emptyList<String>(), songsDir.list()!!.toList())
    }

    @Test
    fun `a failed write leaves no partial file and reports a local write error`() = runTest {
        val full = Downloader(bucket.client(), songsDir, coversDir, openOutput = { file ->
            file.createNewFile()
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("write failed: ENOSPC (No space left on device)")
            }
        })

        assertThrows(LocalWriteException::class.java) {
            kotlinx.coroutines.runBlocking { full.downloadSong(song) }
        }

        assertEquals(emptyList<String>(), songsDir.list()!!.toList())
    }

    @Test
    fun `clearPartials removes part files and keeps finished songs`() = runTest {
        downloader.downloadSong(song)
        File(songsDir, "zzz.opus.part").writeBytes(byteArrayOf(1))
        File(coversDir, "c9.jpg.part").writeBytes(byteArrayOf(1))

        downloader.clearPartials()

        assertEquals(listOf("abc.opus"), songsDir.list()!!.toList())
        assertEquals(emptyList<String>(), coversDir.list()!!.toList())
    }

    @Test
    fun `a cover already on disk is not fetched again`() = runTest {
        downloader.downloadCover("covers/c1.jpg")
        downloader.downloadCover("covers/c1.jpg")

        assertEquals(1, bucket.requested.count { it == "covers/c1.jpg" })
        assertArrayEquals(byteArrayOf(1, 2, 3), downloader.coverFile("covers/c1.jpg").readBytes())
    }

    @Test
    fun `sweepOrphans deletes files no song refers to`() = runTest {
        downloader.downloadSong(song)
        File(songsDir, "old.opus").writeBytes(byteArrayOf(1))
        File(coversDir, "old.jpg").writeBytes(byteArrayOf(1))
        File(coversDir, "c1.jpg").writeBytes(byteArrayOf(1))

        downloader.sweepOrphans(songFileNames = setOf("abc.opus"), coverFileNames = setOf("c1.jpg"))

        assertEquals(listOf("abc.opus"), songsDir.list()!!.toList())
        assertEquals(listOf("c1.jpg"), coversDir.list()!!.toList())
    }

    @Test
    fun `delete removes the song file and an unused cover only`() = runTest {
        downloader.downloadSong(song)
        downloader.downloadCover("covers/c1.jpg")

        downloader.deleteCoverIfUnused("covers/c1.jpg", stillUsed = true)
        assertTrue(downloader.coverFile("covers/c1.jpg").exists())

        downloader.delete(song)
        downloader.deleteCoverIfUnused("covers/c1.jpg", stillUsed = false)
        assertFalse(downloader.localFile(song).exists())
        assertFalse(downloader.coverFile("covers/c1.jpg").exists())
    }
}
