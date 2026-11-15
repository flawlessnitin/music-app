package com.personal.music.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val track: Int?,
    val disc: Int?,
    val year: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val format: String,
    val file: String,
    val cover: String?,
    // Both are always true now: every song is a file on the phone. The columns stay so the database
    // does not need another upgrade.
    val downloaded: Boolean = true,
    @ColumnInfo(defaultValue = "0") val local: Boolean = true,
    /** The kind of music this is (for example phonk, sad, chill); null when it has none. */
    val mood: String? = null
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val id: Int = 1,
    val catalogVersion: Int
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("playlistId"),
        Index("songId")
    ]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Int
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val songCount: Int
)

data class Album(
    val albumArtist: String,
    val album: String,
    val cover: String?,
    val year: Int?,
    val songCount: Int
)

data class Artist(
    val name: String,
    val songCount: Int
)

@Entity(
    tableName = "liked_songs",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LikedSongEntity(
    @PrimaryKey val songId: String,
    val likedAtMillis: Long
)

@Entity(
    tableName = "recent_plays",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class RecentPlayEntity(
    @PrimaryKey val songId: String,
    val playedAtMillis: Long
)

/** A mood chosen in the app. It is kept apart from the song so a rescan of the folders cannot undo it. */
@Entity(
    tableName = "mood_overrides",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MoodOverrideEntity(
    @PrimaryKey val songId: String,
    /** The chosen mood; empty means "no mood" was chosen on purpose. */
    val mood: String
)

data class Mood(
    val name: String,
    val songCount: Int
)
