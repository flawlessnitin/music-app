package com.personal.music.local

import com.personal.music.db.SongEntity
import java.io.File
import java.security.MessageDigest

/** What a song file says about itself. Every field may be missing. */
class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val disc: Int? = null,
    val year: Int? = null,
    val durationMs: Long = 0,
    val cover: ByteArray? = null,
)

/** Reads the tags of one audio file; null when the file cannot be read as audio. */
fun interface TagReader {
    fun read(file: File): AudioTags?
}

/**
 * Finds songs that were put on the phone by hand (not synced from the bucket) in [dir].
 * Those songs stay on the phone only; they are never uploaded.
 */
class LocalMusicScanner(
    private val dir: File,
    private val coversDir: File,
    private val reader: TagReader,
) {
    fun scan(): List<SongEntity> {
        if (!dir.isDirectory) return emptyList()
        coversDir.mkdirs()
        return dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
            .sortedBy { it.absolutePath }
            .mapNotNull { toSong(it) }
            .toList()
    }

    private fun toSong(file: File): SongEntity? {
        val tags = try {
            reader.read(file)
        } catch (e: Exception) {
            null
        } ?: return null

        val id = "local-" + sha1(file.relativeTo(dir).path.replace('\\', '/')).take(16)
        val artist = tags.artist?.takeIf { it.isNotBlank() } ?: UNKNOWN_ARTIST
        val folder = file.parentFile
        val album = tags.album?.takeIf { it.isNotBlank() }
            ?: folder?.takeIf { it != dir }?.name
            ?: DEFAULT_ALBUM

        return SongEntity(
            id = id,
            title = tags.title?.takeIf { it.isNotBlank() } ?: titleFromName(file.nameWithoutExtension),
            artist = artist,
            album = album,
            albumArtist = tags.albumArtist?.takeIf { it.isNotBlank() } ?: artist,
            track = tags.track,
            disc = tags.disc,
            year = tags.year,
            durationMs = tags.durationMs,
            sizeBytes = file.length(),
            format = file.extension.lowercase(),
            file = file.absolutePath,
            cover = saveCover(id, tags.cover, folder),
            downloaded = true,
            local = true,
        )
    }

    private fun saveCover(id: String, embedded: ByteArray?, folder: File?): String? {
        val bytes = embedded?.takeIf { it.isNotEmpty() }
            ?: folder?.let { f -> COVER_NAMES.firstNotNullOfOrNull { n -> File(f, n).takeIf { it.isFile } } }?.readBytes()
            ?: return null
        val name = "$id.jpg"
        val target = File(coversDir, name)
        if (!target.isFile || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        return name
    }

    private fun titleFromName(name: String): String =
        name.replace(NUMBER_PREFIX, "").trim().ifEmpty { name }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "opus", "ogg", "m4a", "aac", "wav")
        val COVER_NAMES = listOf("Folder.jpg", "folder.jpg", "cover.jpg", "Cover.jpg")
        val NUMBER_PREFIX = Regex("^\\d{1,3}\\s*[-._]\\s*")
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val DEFAULT_ALBUM = "Local music"
    }
}
