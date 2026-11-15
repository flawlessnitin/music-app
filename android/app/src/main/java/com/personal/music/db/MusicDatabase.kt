package com.personal.music.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SongEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun metaDao(): MetaDao
}
