package com.personal.music.db

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
    val downloaded: Boolean = false
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
