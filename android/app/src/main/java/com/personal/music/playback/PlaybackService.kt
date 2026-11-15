package com.personal.music.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.personal.music.AppContainer
import com.personal.music.MusicApp
import com.personal.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Plays music in the background and owns the media session (notification, lock screen, headset). */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var soundEffects: SoundEffects? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as MusicApp).container

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        val effects = SoundEffects()
        soundEffects = effects
        effects.onAudioSession(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.onAudioSession(audioSessionId)
        })
        scope.launch { container.settings.eqPreset.collect { effects.setPreset(it) } }

        // A song that cannot be played (for example its file was removed) is skipped.
        player.addListener(SkipOnError(player))
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
        soundEffects?.release()
        soundEffects = null
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
                        // A song that is no longer in the library or on the phone is dropped from the queue.
                        val song = container.library.song(item.mediaId) ?: return@mapNotNull null
                        val file = container.localFile(song) ?: return@mapNotNull null
                        MediaItems.from(song, file, container.coverFile(song))
                    }
                    result.set(resolved.toMutableList())
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }
    }
}
