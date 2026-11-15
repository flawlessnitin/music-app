package com.personal.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.personal.music.AppContainer
import com.personal.music.MusicApp
import com.personal.music.sync.SyncScheduler
import com.personal.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Plays music in the background and owns the media session (notification, lock screen, headset). */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as MusicApp).container

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val dataSources = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this, http),
            BucketResolver(container),
        )

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // A failed stream may mean the download token expired; drop it so the next try gets a new one.
        player.addListener(SkipOnError(player) { container.b2.invalidate() })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // A song that is being streamed is also saved to the phone.
                if (mediaItem != null && MediaItems.isRemote(mediaItem)) {
                    SyncScheduler.syncNow(this@PlaybackService, prioritySongId = mediaItem.mediaId)
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(ResolveItems(container))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music going; if nothing is playing, shut down.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Items sent by the app arrive with only their id and labels (the address is not
     * transferred), so look each song up and attach where to play it from.
     */
    private inner class ResolveItems(private val container: AppContainer) : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val result = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                try {
                    val resolved = mediaItems.mapNotNull { item ->
                        // A song that sync has removed from the library is dropped from the queue.
                        val song = container.library.song(item.mediaId) ?: return@mapNotNull null
                        val local = container.downloader.localFile(song).takeIf { song.downloaded && it.isFile }
                        val cover = song.cover?.let { container.downloader.coverFile(it) }?.takeIf { it.isFile }
                        MediaItems.from(song, local, cover)
                    }
                    result.set(resolved.toMutableList())
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }
    }

    /** Turns a `b2://` address into a real download address with its authorization, at open time. */
    private class BucketResolver(private val container: AppContainer) : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            if (dataSpec.uri.scheme != MediaItems.B2_SCHEME) return dataSpec
            // Called on a loading thread, so waiting here is fine.
            return runBlocking {
                if (!container.network.connected) throw IOException("No internet connection")
                if (container.network.metered && container.settings.wifiOnly.first()) {
                    throw IOException("This song is not on your phone yet, and streaming on mobile data is off")
                }
                val path = MediaItems.bucketPath(dataSpec.uri)
                dataSpec.buildUpon()
                    .setUri(container.b2.streamUrl(path))
                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders + ("Authorization" to container.b2.authHeader()))
                    .build()
            }
        }
    }
}
