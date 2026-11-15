package com.personal.music.catalog

import kotlinx.serialization.Serializable

@Serializable
data class RemoteSong(
    val id: String,
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
    val cover: String?
)

@Serializable
data class RemoteCatalog(
    val version: Int,
    val updatedAt: String,
    val songs: List<RemoteSong>
)
