package com.personal.music

import android.content.Context
import android.os.Environment
import com.personal.music.local.AndroidTagReader
import com.personal.music.local.LocalMusicScanner
import kotlinx.coroutines.withContext
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.personal.music.b2.B2Client
import com.personal.music.db.MIGRATION_1_2
import com.personal.music.db.MIGRATION_2_3
import com.personal.music.db.MIGRATION_3_4
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.listening.ListeningRepository
import com.personal.music.playback.MediaItems
import com.personal.music.playback.PlayerConnection
import com.personal.music.playlists.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.personal.music.settings.SavedSession
import com.personal.music.settings.SettingsStore
import com.personal.music.sync.Downloader
import com.personal.music.sync.FreeSpace
import com.personal.music.sync.NetworkState
import com.personal.music.sync.SyncRunner
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's long-lived objects, built once. No dependency-injection framework. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settings = SettingsStore(appContext)

    val db: MusicDatabase =
        Room.databaseBuilder(appContext, MusicDatabase::class.java, "music.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }
            })
            .build()

    val library = LibraryRepository(db)
    val playlists = PlaylistRepository(db)
    val playlistRepository = playlists
    val listening = ListeningRepository(db)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val b2 = B2Client(http, credentials = { settings.credentials() })

    val downloader = Downloader(
        b2 = b2,
        songsDir = File(appContext.filesDir, "songs"),
        coversDir = File(appContext.filesDir, "covers"),
    )

    val network: NetworkState = AndroidNetworkState(appContext)

    val syncRunner = SyncRunner(
        settings = settings,
        library = library,
        b2 = b2,
        downloader = downloader,
        network = network,
        freeSpace = FreeSpace { appContext.filesDir.usableSpace },
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The song's file on the phone, or null if it is not downloaded. */
    fun localFile(song: SongEntity): File? =
        downloader.localFile(song).takeIf { song.downloaded && it.isFile }

    /** The song's cover image on the phone, or null if there is none yet. */
    fun coverFile(song: SongEntity): File? =
        song.cover?.let { downloader.coverFile(it) }?.takeIf { it.isFile }

    val player = PlayerConnection(appScope) { song ->
        MediaItems.from(song, localFile(song), coverFile(song))
    }

    /** Songs put here by hand (for example with adb or a file manager) are added to the library, phone only. */
    val localMusicDir: File =
        appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(appContext.filesDir, "Music")

    /** Looks in [localMusicDir] and makes the library's by-hand songs match; returns how many were found. */
    suspend fun scanLocalMusic(): Int = withContext(Dispatchers.IO) {
        val found = LocalMusicScanner(localMusicDir, File(appContext.filesDir, "covers"), AndroidTagReader()).scan()
        library.replaceLocalSongs(found)
        found.size
    }

    private var playerConnected = false

    /** Connects [player] to the playback service; called when the app's screen opens. */
    fun ensurePlayerConnected() {
        if (playerConnected) return
        playerConnected = true
        PlayerConnection.connect(appContext, player)
        appScope.launch { runCatching { scanLocalMusic() } }
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

private class AndroidNetworkState(context: Context) : NetworkState {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    override val connected: Boolean
        get() {
            val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    override val metered: Boolean
        get() = manager.isActiveNetworkMetered
}
