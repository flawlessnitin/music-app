package com.personal.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EqPresetTest {
    // A typical 5 band equalizer: -15 dB to +15 dB, in millibels.
    private val min = -1500
    private val max = 1500
    private val centres = listOf(60, 230, 910, 3600, 14000)

    private fun levels(p: EqPreset) = centres.map { p.levelFor(it, min, max) }

    @Test
    fun `off changes nothing`() {
        assertEquals(listOf(0, 0, 0, 0, 0), levels(EqPreset.OFF))
    }

    @Test
    fun `bass boost lifts the low bands most and leaves the highs alone`() {
        val l = levels(EqPreset.BASS)
        assertTrue(l[0] > l[1])
        assertTrue(l[1] > 0)
        assertEquals(0, l[3])
        assertEquals(0, l[4])
    }

    @Test
    fun `treble boost lifts the high bands and leaves the lows alone`() {
        val l = levels(EqPreset.TREBLE)
        assertTrue(l[4] > l[3])
        assertTrue(l[3] > 0)
        assertEquals(0, l[0])
        assertEquals(0, l[1])
    }

    @Test
    fun `vocal lifts the middle and trims the deepest bass`() {
        val l = levels(EqPreset.VOCAL)
        assertTrue(l[2] > 0)
        assertTrue(l[3] > 0)
        assertTrue(l[0] < 0)
    }

    @Test
    fun `levels never go outside what the device supports`() {
        for (p in EqPreset.entries) {
            for (f in centres) {
                val v = p.levelFor(f, -300, 300)
                assertTrue("$p at $f gave $v", v in -300..300)
            }
        }
    }

    @Test
    fun `an unknown name falls back to off`() {
        assertEquals(EqPreset.OFF, EqPreset.fromName("nonsense"))
        assertEquals(EqPreset.OFF, EqPreset.fromName(null))
        assertEquals(EqPreset.BASS, EqPreset.fromName("BASS"))
    }
}
