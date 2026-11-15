package com.personal.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.personal.music.db.SongEntity
import java.io.File

/** Turns a song from the library into something the player can queue. */
object MediaItems {
    const val B2_SCHEME = "b2"

    /**
     * [localFile] is the song's file on the phone, or null when it is not downloaded; the
     * item then gets a `b2://<path in bucket>` address that is resolved when playback opens it.
     */
    fun from(song: SongEntity, localFile: File?, coverFile: File?): MediaItem {
        val uri = if (localFile != null) Uri.fromFile(localFile) else Uri.parse("$B2_SCHEME://${song.file}")
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

    fun isRemote(item: MediaItem?): Boolean = item?.localConfiguration?.uri?.scheme == B2_SCHEME

    /** The bucket path inside a `b2://` address, for example `songs/abc.opus`. */
    fun bucketPath(uri: Uri): String = uri.toString().removePrefix("$B2_SCHEME://")
}
