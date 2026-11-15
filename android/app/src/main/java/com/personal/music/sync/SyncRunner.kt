package com.personal.music.sync

import com.personal.music.b2.B2Client
import com.personal.music.b2.B2Exception
import com.personal.music.catalog.CatalogFormatException
import com.personal.music.catalog.CatalogParser
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.settings.SettingsStore
import com.personal.music.settings.SyncOutcome
import com.personal.music.settings.SyncSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

interface NetworkState {
    val connected: Boolean
    val metered: Boolean
}

fun interface FreeSpace {
    fun bytes(): Long
}

/** One full sync pass: bring the phone's library in line with the bucket's catalog. */
class SyncRunner(
    private val settings: SettingsStore,
    private val library: LibraryRepository,
    private val b2: B2Client,
    private val downloader: Downloader,
    private val network: NetworkState,
    private val freeSpace: FreeSpace,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val running = Mutex()

    suspend fun run(
        prioritySongId: String? = null,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncSummary = running.withLock {
        val (outcome, message) = try {
            pass(prioritySongId, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classify(e)
        }
        val summary = SyncSummary(
            finishedAtMillis = now(),
            outcome = outcome,
            message = message,
            pendingSongs = library.pending().size,
        )
        settings.setLastSync(summary)
        summary
    }

    private suspend fun pass(
        prioritySongId: String?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Pair<SyncOutcome, String> {
        if (settings.credentials() == null) return SyncOutcome.NOT_SET_UP to ""
        if (!network.connected) return SyncOutcome.NO_NETWORK to ""
        if (network.metered && settings.wifiOnly.first()) return SyncOutcome.NO_NETWORK to ""

        downloader.clearPartials()

        val catalog = CatalogParser.parse(b2.readText(CATALOG_NAME))
        if (catalog.version != library.catalogVersion()) {
            library.applyCatalog(catalog)
        }

        // Make the flags and the files agree, in both directions.
        val all = library.songs().first()
        for (song in all) {
            if (!song.local && song.downloaded && !downloader.localFile(song).isFile) {
                library.setDownloaded(song.id, false)
            }
        }
        downloader.sweepOrphans(
            songFileNames = all.map { downloader.localFile(it).name }.toSet(),
            coverFileNames = all.mapNotNull { it.cover }.map { downloader.coverFile(it).name }.toSet(),
        )

        val pending = library.pending().let { list ->
            val first = list.filter { it.id == prioritySongId }
            first + list.filterNot { it.id == prioritySongId }
        }
        if (pending.isEmpty()) {
            fetchMissingCovers(all)
            return SyncOutcome.UP_TO_DATE to ""
        }

        val plan = SyncPlanner.plan(
            pending = pending,
            downloadedBytes = library.downloadedBytes(),
            capBytes = settings.capBytes.first(),
            freeBytes = freeSpace.bytes(),
        )

        var done = 0
        onProgress(done, plan.toDownload.size)
        for (song in plan.toDownload) {
            // Cover first: the screen redraws the moment the song is marked downloaded,
            // and the cover must already be on disk by then.
            song.cover?.let { fetchCover(it) }
            try {
                downloader.downloadSong(song)
            } catch (e: B2Exception.NotFound) {
                // The catalog lists a file the bucket does not have; skip it, keep going.
                continue
            }
            library.setDownloaded(song.id, true)
            done++
            onProgress(done, plan.toDownload.size)
        }
        fetchMissingCovers(all)

        return when {
            plan.blockedByFreeSpace > 0 -> SyncOutcome.STORAGE_FULL to ""
            plan.blockedByCap > 0 -> SyncOutcome.CAP_REACHED to ""
            else -> SyncOutcome.DOWNLOADED to ""
        }
    }

    private suspend fun fetchMissingCovers(songs: List<SongEntity>) {
        songs.mapNotNull { it.cover }.distinct().forEach { fetchCover(it) }
    }

    private suspend fun fetchCover(name: String) {
        try {
            downloader.downloadCover(name)
        } catch (e: B2Exception.NotFound) {
            // A missing cover is not worth failing the sync for.
        }
    }

    private fun classify(e: Exception): Pair<SyncOutcome, String> = when (e) {
        is B2Exception.Refused -> SyncOutcome.WAITING to e.code
        is B2Exception.BadKey -> SyncOutcome.BAD_KEY to ""
        is B2Exception.Network -> SyncOutcome.NO_NETWORK to ""
        is LocalWriteException ->
            if (isOutOfSpace(e)) SyncOutcome.STORAGE_FULL to "" else SyncOutcome.ERROR to (e.message ?: "Could not write to storage")
        is CatalogFormatException -> SyncOutcome.ERROR to "The catalog in the bucket could not be read"
        is B2Exception.NotFound -> SyncOutcome.ERROR to "No catalog.json in the bucket yet. Add songs with musictool first."
        is IOException -> SyncOutcome.ERROR to (e.message ?: "Network error")
        else -> SyncOutcome.ERROR to (e.message ?: e.javaClass.simpleName)
    }

    private fun isOutOfSpace(e: LocalWriteException): Boolean {
        val text = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        return "ENOSPC" in text || "No space left" in text ||
            freeSpace.bytes() < SyncPlanner.FREE_SPACE_RESERVE_BYTES / 10
    }

    private companion object {
        const val CATALOG_NAME = "catalog.json"
    }
}
