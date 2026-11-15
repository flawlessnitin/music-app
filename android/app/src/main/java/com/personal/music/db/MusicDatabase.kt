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
        LikedSongEntity::class,
        RecentPlayEntity::class,
        MoodOverrideEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun metaDao(): MetaDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun listeningDao(): ListeningDao
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

private const val CREATE_LIKED_TABLE = """
    CREATE TABLE IF NOT EXISTS `liked_songs` (
        `songId` TEXT NOT NULL,
        `likedAtMillis` INTEGER NOT NULL,
        PRIMARY KEY(`songId`),
        FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

private const val CREATE_RECENT_TABLE = """
    CREATE TABLE IF NOT EXISTS `recent_plays` (
        `songId` TEXT NOT NULL,
        `playedAtMillis` INTEGER NOT NULL,
        PRIMARY KEY(`songId`),
        FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_LIKED_TABLE)
        db.execSQL(CREATE_RECENT_TABLE)
    }

    override fun migrate(connection: SQLiteConnection) {
        if (connection is SupportSQLiteConnection) {
            super.migrate(connection)
        } else {
            connection.execSQL(CREATE_LIKED_TABLE)
            connection.execSQL(CREATE_RECENT_TABLE)
        }
    }
}

val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_LOCAL_COLUMN)
    }

    override fun migrate(connection: SQLiteConnection) {
        if (connection is SupportSQLiteConnection) {
            super.migrate(connection)
        } else {
            connection.execSQL(ADD_LOCAL_COLUMN)
        }
    }
}

private const val ADD_LOCAL_COLUMN = "ALTER TABLE `songs` ADD COLUMN `local` INTEGER NOT NULL DEFAULT 0"

private const val ADD_MOOD_COLUMN = "ALTER TABLE `songs` ADD COLUMN `mood` TEXT"

private const val CREATE_MOOD_OVERRIDES_TABLE = """
    CREATE TABLE IF NOT EXISTS `mood_overrides` (
        `songId` TEXT NOT NULL,
        `mood` TEXT NOT NULL,
        PRIMARY KEY(`songId`),
        FOREIGN KEY(`songId`) REFERENCES `songs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
    )
"""

val MIGRATION_4_5: Migration = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(ADD_MOOD_COLUMN)
        db.execSQL(CREATE_MOOD_OVERRIDES_TABLE)
    }

    override fun migrate(connection: SQLiteConnection) {
        if (connection is SupportSQLiteConnection) {
            super.migrate(connection)
        } else {
            connection.execSQL(ADD_MOOD_COLUMN)
            connection.execSQL(CREATE_MOOD_OVERRIDES_TABLE)
        }
    }
}
