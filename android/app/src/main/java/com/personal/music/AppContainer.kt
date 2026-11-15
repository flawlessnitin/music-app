package com.personal.music

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import com.personal.music.b2.B2Client
import com.personal.music.db.MusicDatabase
import com.personal.music.db.SongEntity
import com.personal.music.playback.MediaItems
import com.personal.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.personal.music.library.LibraryRepository
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
        Room.databaseBuilder(appContext, MusicDatabase::class.java, "music.db").build()

    val library = LibraryRepository(db)

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

    private var playerConnected = false

    /** Connects [player] to the playback service; called when the app's screen opens. */
    fun ensurePlayerConnected() {
        if (playerConnected) return
        playerConnected = true
        PlayerConnection.connect(appContext, player)
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
