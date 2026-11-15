package com.personal.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.personal.music.db.SongEntity
import java.io.File

/** Turns a song from the library into something the player can queue. */
object MediaItems {
    /** [localFile] defaults to where the song says it is; [coverFile] is its cover image, if it has one. */
    fun from(song: SongEntity, localFile: File?, coverFile: File?): MediaItem {
        val uri = Uri.fromFile(localFile ?: File(song.file))
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setArtworkUri(coverFile?.let { Uri.fromFile(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}
