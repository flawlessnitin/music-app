package com.personal.music.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.SupportSQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        SongEntity::class,
        MetaEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun metaDao(): MetaDao
    abstract fun playlistDao(): PlaylistDao
}

private const val CREATE_PLAYLISTS_TABLE = """
    CREATE TABLE IF NOT EXISTS `playlists` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `name` TEXT NOT NULL,
        `createdAtMillis` INTEGER NOT NULL
    )
"""

private const val CREATE_PLAYLIST_SONGS_TABLE = """
    CREATE TABLE IF NOT EXISTS `playlist_songs` (
        `playlistId` INTEGER NOT NULL,
        `songId` TEXT NOT NULL,
        `position` INTEGER NOT NULL,
        PRIMARY KEY(`playlistId`, `songId`),
        FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
        FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

private const val CREATE_INDEX_PLAYLIST_ID =
    "CREATE INDEX IF NOT EXISTS `index_playlist_songs_playlistId` ON `playlist_songs` (`playlistId`)"

private const val CREATE_INDEX_SONG_ID =
    "CREATE INDEX IF NOT EXISTS `index_playlist_songs_songId` ON `playlist_songs` (`songId`)"

val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_PLAYLISTS_TABLE)
        db.execSQL(CREATE_PLAYLIST_SONGS_TABLE)
        db.execSQL(CREATE_INDEX_PLAYLIST_ID)
        db.execSQL(CREATE_INDEX_SONG_ID)
    }

    override fun migrate(connection: SQLiteConnection) {
        if (connection is SupportSQLiteConnection) {
            super.migrate(connection)
        } else {
            connection.execSQL(CREATE_PLAYLISTS_TABLE)
            connection.execSQL(CREATE_PLAYLIST_SONGS_TABLE)
            connection.execSQL(CREATE_INDEX_PLAYLIST_ID)
            connection.execSQL(CREATE_INDEX_SONG_ID)
        }
    }
}
