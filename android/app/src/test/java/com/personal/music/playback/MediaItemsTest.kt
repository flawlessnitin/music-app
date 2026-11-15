package com.personal.music.playback

import android.net.Uri
import com.personal.music.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MediaItemsTest {
    private val song = SongEntity(
        id = "9f2c1a7b3d4e5f60", title = "तुम ही हो", artist = "Artist", album = "Album",
        albumArtist = "AA", track = 3, disc = 1, year = 2019, durationMs = 215_000,
        sizeBytes = 7_000_000, format = "opus", file = "songs/9f2c1a7b3d4e5f60.opus",
        cover = "covers/1b2c.jpg",
    )

    @Test
    fun `downloaded song uses the local file uri`() {
        val file = File("/data/songs/9f2c1a7b3d4e5f60.opus")
        val item = MediaItems.from(song, file, null)

        assertEquals(Uri.fromFile(file), item.localConfiguration!!.uri)
        assertEquals("9f2c1a7b3d4e5f60", item.mediaId)
        assertFalse(MediaItems.isRemote(item))
    }

    @Test
    fun `song not downloaded uses a b2 uri with the bucket path`() {
        val item = MediaItems.from(song, null, null)

        assertEquals("b2://songs/9f2c1a7b3d4e5f60.opus", item.localConfiguration!!.uri.toString())
        assertTrue(MediaItems.isRemote(item))
        assertEquals("songs/9f2c1a7b3d4e5f60.opus", MediaItems.bucketPath(item.localConfiguration!!.uri))
    }

    @Test
    fun `metadata carries title artist album and artwork`() {
        val cover = File("/data/covers/1b2c.jpg")
        val meta = MediaItems.from(song, null, cover).mediaMetadata

        assertEquals("तुम ही हो", meta.title.toString())
        assertEquals("Artist", meta.artist.toString())
        assertEquals("Album", meta.albumTitle.toString())
        assertEquals(Uri.fromFile(cover), meta.artworkUri)
    }

    @Test
    fun `missing cover gives no artwork uri`() {
        assertNull(MediaItems.from(song, null, null).mediaMetadata.artworkUri)
    }
}
