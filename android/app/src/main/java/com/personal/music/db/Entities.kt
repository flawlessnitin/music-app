package com.personal.music.db

import androidx.room.Entity
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
