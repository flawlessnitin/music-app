package com.personal.music

import android.content.Context
import android.os.Environment
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.music.db.MIGRATION_1_2
import com.personal.music.db.MIGRATION_2_3
import com.personal.music.db.MIGRATION_3_4
import com.personal.music.db.MIGRATION_4_5
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.listening.ListeningRepository
import com.personal.music.local.AndroidTagReader
import com.personal.music.local.LocalMusicScanner
import com.personal.music.playback.MediaItems
import com.personal.music.playback.PlayerConnection
import com.personal.music.playlists.PlaylistRepository
import com.personal.music.settings.SavedSession
import com.personal.music.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The app's long-lived objects, built once. No dependency-injection framework. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)

    val db: MusicDatabase =
        Room.databaseBuilder(appContext, MusicDatabase::class.java, "music.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            })
            .build()

    val library = LibraryRepository(db)
    val playlists = PlaylistRepository(db)
    val listening = ListeningRepository(db)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Songs are copied into this folder from the PC (musictool) or by hand; the app finds them here. */
    val musicDir: File =
        appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(appContext.filesDir, "Music")

    private val coversDir = File(appContext.filesDir, "covers")

    /** The song's file on the phone, or null if it is no longer there. */
    fun localFile(song: SongEntity): File? = File(song.file).takeIf { it.isFile }

    /** The song's cover image, or null if it has none. */
    fun coverFile(song: SongEntity): File? =
        song.cover?.let { File(coversDir, it) }?.takeIf { it.isFile }

    /** The cover image with this name, whether or not it exists yet; for screens that load it lazily. */
    fun coverFile(name: String): File = File(coversDir, name)

    val player = PlayerConnection(appScope) { song ->
        MediaItems.from(song, localFile(song), coverFile(song))
    }

    private val scanLock = Mutex()

    /** Looks in [musicDir] and makes the library match what is there; returns how many songs were found. */
    suspend fun scanMusic(): Int = scanLock.withLock {
        withContext(Dispatchers.IO) {
            val found = LocalMusicScanner(musicDir, coversDir, AndroidTagReader()).scan()
            library.replaceSongs(found)
            found.size
        }
    }

    /** Called whenever the app comes to the front, so songs sent from the PC show up. */
    fun rescanInBackground() {
        appScope.launch { runCatching { scanMusic() } }
    }

    private var playerConnected = false

    /** Connects [player] to the playback service; called when the app's screen opens. */
    fun ensurePlayerConnected() {
        if (playerConnected) return
        playerConnected = true
        PlayerConnection.connect(appContext, player)
        appScope.launch { runCatching { settings.forgetBackblaze() } }
        restoreLastSession()
        rememberListening()
    }

    /** Puts the queue from the last time back, paused, so the app opens where it was left. */
    private fun restoreLastSession() {
        appScope.launch {
            val saved = settings.session() ?: return@launch
            val songs = saved.songIds.mapNotNull { library.song(it) }.filter { localFile(it) != null }
            if (songs.isEmpty()) return@launch
            val wanted = saved.songIds.getOrNull(saved.index)
            val index = songs.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
            player.restore(songs, index, if (songs[index].id == wanted) saved.positionMs else 0L)
        }
    }

    /** Keeps "recently played" and the saved session up to date while the app is used. */
    private fun rememberListening() {
        appScope.launch {
            player.state.map { it.current?.songId to it.isPlaying }
                .distinctUntilChanged()
                .collect { (songId, playing) -> if (playing && songId != null) listening.recordPlay(songId) }
        }
        appScope.launch {
            player.state
                .map { s -> Triple(s.queue.map { it.songId }, s.index, s.isPlaying) }
                .distinctUntilChanged()
                .collect { saveSession() }
        }
        appScope.launch {
            while (true) {
                delay(10_000)
                if (player.state.value.isPlaying) saveSession()
            }
        }
    }

    private suspend fun saveSession() {
        val s = player.state.value
        if (s.queue.isEmpty()) return
        settings.saveSession(SavedSession(s.queue.map { it.songId }, s.index, s.positionMs))
    }
}
