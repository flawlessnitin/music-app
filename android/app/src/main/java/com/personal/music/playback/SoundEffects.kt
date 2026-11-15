package com.personal.music.playback

import android.media.audiofx.Equalizer

/**
 * Applies an [EqPreset] to the player's audio through the phone's equalizer.
 * If the phone has no equalizer, or it fails, music still plays; the preset just has no effect.
 */
class SoundEffects {
    private var equalizer: Equalizer? = null
    private var preset = EqPreset.OFF

    fun onAudioSession(sessionId: Int) {
        release()
        // 0 means no audio session yet.
        if (sessionId == 0) return
        equalizer = try {
            Equalizer(0, sessionId)
        } catch (e: Exception) {
            null
        }
        apply()
    }

    fun setPreset(preset: EqPreset) {
        this.preset = preset
        apply()
    }

    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            // Nothing to clean up.
        }
        equalizer = null
    }

    private fun apply() {
        val eq = equalizer ?: return
        try {
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val centreHz = eq.getCenterFreq(band.toShort()) / 1000
                val level = preset.levelFor(centreHz, range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), level.toShort())
            }
            eq.enabled = preset != EqPreset.OFF
        } catch (e: Exception) {
            // The effect went away (for example the audio session ended); the next session sets it up again.
        }
    }
}
