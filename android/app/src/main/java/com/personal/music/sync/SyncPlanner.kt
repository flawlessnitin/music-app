package com.personal.music.sync

import com.personal.music.db.SongEntity

data class SyncPlan(
    val toDownload: List<SongEntity>,
    val blockedByCap: Int,
    val blockedByFreeSpace: Int,
)

/** Decides which pending songs fit on the phone. Pure logic, no I/O. */
object SyncPlanner {
    /** Space always left free on the phone, so a sync can never fill it completely. */
    const val FREE_SPACE_RESERVE_BYTES = 500_000_000L

    fun plan(
        pending: List<SongEntity>,
        downloadedBytes: Long,
        capBytes: Long,
        freeBytes: Long,
    ): SyncPlan {
        val usableFree = (freeBytes - FREE_SPACE_RESERVE_BYTES).coerceAtLeast(0)
        val toDownload = mutableListOf<SongEntity>()
        var planned = 0L
        var blockedByCap = 0
        var blockedByFreeSpace = 0

        for (song in pending) {
            val after = planned + song.sizeBytes
            when {
                downloadedBytes + after > capBytes -> blockedByCap++
                after > usableFree -> blockedByFreeSpace++
                else -> {
                    toDownload += song
                    planned = after
                }
            }
        }
        return SyncPlan(toDownload, blockedByCap, blockedByFreeSpace)
    }
}
