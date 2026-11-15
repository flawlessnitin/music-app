package com.personal.music.playback

/** Simple sound shapes. Works on any equalizer, however many bands it has. */
enum class EqPreset(val label: String) {
    OFF("Off"),
    BASS("Bass boost"),
    TREBLE("Treble boost"),
    VOCAL("Vocal");

    /** Gain in millibels for a band centred on [freqHz], kept inside [minLevel]..[maxLevel]. */
    fun levelFor(freqHz: Int, minLevel: Int, maxLevel: Int): Int {
        val level = when (this) {
            OFF -> 0
            BASS -> when {
                freqHz <= 150 -> maxLevel * 7 / 10
                freqHz <= 500 -> maxLevel * 35 / 100
                else -> 0
            }
            TREBLE -> when {
                freqHz >= 8000 -> maxLevel * 7 / 10
                freqHz >= 3000 -> maxLevel * 4 / 10
                else -> 0
            }
            VOCAL -> when {
                freqHz in 500..4000 -> maxLevel / 2
                freqHz < 150 -> minLevel * 3 / 10
                else -> 0
            }
        }
        return level.coerceIn(minLevel, maxLevel)
    }

    companion object {
        fun fromName(name: String?): EqPreset = entries.firstOrNull { it.name == name } ?: OFF
    }
}
