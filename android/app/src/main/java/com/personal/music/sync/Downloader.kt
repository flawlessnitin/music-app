package com.personal.music.sync

import com.personal.music.b2.B2Client
import com.personal.music.b2.B2Exception
import com.personal.music.db.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/** Writing to the phone's storage failed (usually: no space left). */
class LocalWriteException(message: String, cause: Throwable) : IOException(message, cause)

/**
 * Copies files from the bucket into app storage.
 *
 * A file is written as `<name>.part` and renamed only when complete, so a file at its
 * final name is always whole.
 */
class Downloader(
    private val b2: B2Client,
    private val songsDir: File,
    private val coversDir: File,
    private val openOutput: (File) -> OutputStream = { it.outputStream() },
) {
    fun localFile(song: SongEntity): File = File(songsDir, song.file.substringAfterLast('/'))

    fun coverFile(name: String): File = File(coversDir, name.substringAfterLast('/'))

    suspend fun downloadSong(song: SongEntity) = fetch(song.file, localFile(song))

    suspend fun downloadCover(name: String) {
        val target = coverFile(name)
        if (target.isFile && target.length() > 0) return
        fetch(name, target)
    }

    fun delete(song: SongEntity) {
        localFile(song).delete()
    }

    fun deleteCoverIfUnused(name: String, stillUsed: Boolean) {
        if (!stillUsed) coverFile(name).delete()
    }

    fun clearPartials() {
        for (dir in listOf(songsDir, coversDir)) {
            dir.listFiles { f -> f.name.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
        }
    }

    /** Deletes finished files that no song in the library refers to any more. */
    fun sweepOrphans(songFileNames: Set<String>, coverFileNames: Set<String>) {
        songsDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(PART_SUFFIX) && f.name !in songFileNames) f.delete()
        }
        coversDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(PART_SUFFIX) && f.name !in coverFileNames) f.delete()
        }
    }

    private suspend fun fetch(name: String, target: File) = withContext(Dispatchers.IO) {
        val dir = target.parentFile ?: throw IOException("No folder for $name")
        dir.mkdirs()
        val part = File(dir, target.name + PART_SUFFIX)
        try {
            b2.open(name).use { response ->
                val expected = response.body.contentLength()
                val input = response.body.byteStream()
                val output = try {
                    openOutput(part)
                } catch (e: IOException) {
                    throw LocalWriteException("Could not create ${part.name}", e)
                }
                var written = 0L
                output.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            throw B2Exception.Network(e)
                        }
                        if (read < 0) break
                        try {
                            out.write(buffer, 0, read)
                        } catch (e: IOException) {
                            throw LocalWriteException("Could not write ${part.name}", e)
                        }
                        written += read
                    }
                    try {
                        out.flush()
                    } catch (e: IOException) {
                        throw LocalWriteException("Could not write ${part.name}", e)
                    }
                }
                if (expected >= 0 && written != expected) {
                    throw B2Exception.Network(IOException("Incomplete download of $name: $written of $expected bytes"))
                }
            }
            if (!part.renameTo(target)) {
                target.delete()
                if (!part.renameTo(target)) throw LocalWriteException("Could not save ${target.name}", IOException("rename failed"))
            }
        } finally {
            // After a successful rename the part file no longer exists; otherwise drop it.
            part.delete()
        }
    }

    private companion object {
        const val PART_SUFFIX = ".part"
    }
}
