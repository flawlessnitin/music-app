package com.personal.music.local

import android.media.MediaMetadataRetriever
import java.io.File

/** Reads song tags with the phone's own media framework. */
class AndroidTagReader : TagReader {
    override fun read(file: File): AudioTags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            // No duration means this is not something the phone can play.
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            AudioTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                track = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                disc = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                durationMs = durationMs,
                cover = retriever.embeddedPicture,
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /** Track numbers often look like "3/12". */
    private fun number(text: String?): Int? = text?.substringBefore('/')?.trim()?.toIntOrNull()
}
