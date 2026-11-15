package com.personal.music.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuroraTest {
    @Test
    fun `the palette wraps around so there is no jump`() {
        assertEquals(paletteAt(0f), paletteAt(1f))
        assertEquals(paletteAt(0.25f), paletteAt(1.25f))
        assertEquals(paletteAt(0.4f), paletteAt(-0.6f))
    }

    @Test
    fun `the palette changes along the way`() {
        assertNotEquals(paletteAt(0f), paletteAt(0.3f))
        assertNotEquals(paletteAt(0.3f), paletteAt(0.6f))
    }

    @Test
    fun `curtain heights stay between zero and one all along the screen and all through the animation`() {
        for (step in 0..40) {
            val phase = step * 0.157f
            for (x in 0..30) {
                val h = curtainHeight(x / 30f, phase)
                assertTrue("height $h", h in 0f..1f)
            }
        }
    }

    @Test
    fun `the curtains move as time passes`() {
        val before = (0..30).map { curtainHeight(it / 30f, 0f) }
        val after = (0..30).map { curtainHeight(it / 30f, 1.5f) }
        assertNotEquals(before, after)
    }
}
