package com.personal.music.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CatalogParserTest {

    private val fixture = """
        {"version": 42, "updatedAt": "2026-11-15T10:00:00Z", "songs": [
         {"id":"9f2c1a7b3d4e5f60","title":"तुम ही हो","artist":"A","album":"B","albumArtist":"A",
          "track":3,"disc":1,"year":2019,"durationMs":215000,"sizeBytes":7000000,"format":"opus",
          "file":"songs/9f2c1a7b3d4e5f60.opus","cover":"covers/1b2c3d4e5f607182.jpg"},
         {"id":"aaaaaaaaaaaaaaaa","title":"T","artist":"Unknown","album":"Unknown","albumArtist":"Unknown",
          "track":null,"disc":null,"year":null,"durationMs":1000,"sizeBytes":28000000,"format":"flac",
          "file":"songs/aaaaaaaaaaaaaaaa.flac","cover":null,"somethingNew":"ignored"}]}
    """.trimIndent()

    @Test
    fun `parses version and every field`() {
        val catalog = CatalogParser.parse(fixture)
        assertEquals(42, catalog.version)
        assertEquals("2026-11-15T10:00:00Z", catalog.updatedAt)
        assertEquals(2, catalog.songs.size)

        val first = catalog.songs[0]
        assertEquals("9f2c1a7b3d4e5f60", first.id)
        assertEquals("तुम ही हो", first.title)
        assertEquals("A", first.artist)
        assertEquals("B", first.album)
        assertEquals("A", first.albumArtist)
        assertEquals(3, first.track)
        assertEquals(1, first.disc)
        assertEquals(2019, first.year)
        assertEquals(215000L, first.durationMs)
        assertEquals(7000000L, first.sizeBytes)
        assertEquals("opus", first.format)
        assertEquals("songs/9f2c1a7b3d4e5f60.opus", first.file)
        assertEquals("covers/1b2c3d4e5f607182.jpg", first.cover)
    }

    @Test
    fun `null track disc year and cover are kept as null`() {
        val catalog = CatalogParser.parse(fixture)
        val second = catalog.songs[1]
        assertNull(second.track)
        assertNull(second.disc)
        assertNull(second.year)
        assertNull(second.cover)
    }

    @Test
    fun `unknown fields are ignored`() {
        val catalog = CatalogParser.parse(fixture)
        assertEquals(2, catalog.songs.size)
        val second = catalog.songs[1]
        assertEquals("aaaaaaaaaaaaaaaa", second.id)
        assertEquals("flac", second.format)
    }

    @Test
    fun `empty catalog parses`() {
        val json = """{"version":0,"updatedAt":"","songs":[]}"""
        val catalog = CatalogParser.parse(json)
        assertEquals(0, catalog.version)
        assertEquals("", catalog.updatedAt)
        assertTrue(catalog.songs.isEmpty())
    }

    @Test
    fun `garbage throws CatalogFormatException`() {
        listOf("<html>error</html>", "", "{invalid json").forEach { garbage ->
            try {
                CatalogParser.parse(garbage)
                fail("Expected CatalogFormatException for garbage: '$garbage'")
            } catch (e: CatalogFormatException) {
                // Expected
            }
        }
    }

    @Test
    fun `missing required field throws CatalogFormatException`() {
        val missingSongId = """
            {"version": 42, "updatedAt": "2026-11-15T10:00:00Z", "songs": [
             {"title":"Missing ID","artist":"A","album":"B","albumArtist":"A",
              "track":1,"disc":1,"year":2020,"durationMs":1000,"sizeBytes":1000,"format":"opus",
              "file":"songs/id.opus","cover":null}]}
        """.trimIndent()

        try {
            CatalogParser.parse(missingSongId)
            fail("Expected CatalogFormatException when required field 'id' is missing")
        } catch (e: CatalogFormatException) {
            // Expected
        }
    }
}
