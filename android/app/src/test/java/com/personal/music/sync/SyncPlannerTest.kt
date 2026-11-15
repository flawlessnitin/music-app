package com.personal.music.sync

import com.personal.music.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {
    private val gb = 1_000_000_000L

    private fun song(id: String, size: Long) = SongEntity(
        id = id, title = id, artist = "a", album = "b", albumArtist = "a",
        track = null, disc = null, year = null, durationMs = 1, sizeBytes = size,
        format = "opus", file = "songs/$id.opus", cover = null,
    )

    @Test
    fun `everything fits`() {
        val pending = listOf(song("1", 7_000_000), song("2", 28_000_000))
        val plan = SyncPlanner.plan(pending, downloadedBytes = 0, capBytes = 20 * gb, freeBytes = 40 * gb)
        assertEquals(pending, plan.toDownload)
        assertEquals(0, plan.blockedByCap)
        assertEquals(0, plan.blockedByFreeSpace)
    }

    @Test
    fun `songs past the cap are blocked not planned`() {
        val pending = listOf(song("1", 60), song("2", 60), song("3", 30))
        val plan = SyncPlanner.plan(pending, downloadedBytes = 900, capBytes = 1_000, freeBytes = 40 * gb)
        // 900 + 60 fits; a second 60 would pass 1,000; the 30 still fits afterwards.
        assertEquals(listOf("1", "3"), plan.toDownload.map { it.id })
        assertEquals(1, plan.blockedByCap)
        assertEquals(0, plan.blockedByFreeSpace)
    }

    @Test
    fun `free space keeps a 500 MB reserve`() {
        val pending = listOf(song("1", 100_000_000), song("2", 100_000_000))
        // 650 MB free minus the 500 MB reserve leaves room for one 100 MB song only.
        val plan = SyncPlanner.plan(pending, downloadedBytes = 0, capBytes = 20 * gb, freeBytes = 650_000_000)
        assertEquals(listOf("1"), plan.toDownload.map { it.id })
        assertEquals(1, plan.blockedByFreeSpace)
        assertEquals(0, plan.blockedByCap)
    }

    @Test
    fun `less free space than the reserve plans nothing`() {
        val plan = SyncPlanner.plan(listOf(song("1", 1)), 0, 20 * gb, freeBytes = 400_000_000)
        assertEquals(emptyList<SongEntity>(), plan.toDownload)
        assertEquals(1, plan.blockedByFreeSpace)
    }

    @Test
    fun `nothing pending gives an empty plan`() {
        val plan = SyncPlanner.plan(emptyList(), 0, 20 * gb, 40 * gb)
        assertEquals(SyncPlan(emptyList(), 0, 0), plan)
    }
}
