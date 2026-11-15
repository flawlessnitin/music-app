package com.personal.music.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverFitTest {
    private fun fit(aspect: Float, w: Float = 300f, h: Float = 400f) = fitCover(aspect, w, h)

    @Test
    fun `a wide 16 by 9 cover keeps its shape and fills the width`() {
        val (w, h) = fit(16f / 9f)

        assertEquals(300f, w, 0.01f)
        assertEquals(300f * 9f / 16f, h, 0.01f)
    }

    @Test
    fun `a square cover fills the width when there is room`() {
        val (w, h) = fit(1f)

        assertEquals(300f, w, 0.01f)
        assertEquals(300f, h, 0.01f)
    }

    @Test
    fun `a tall cover is limited by the height so it stays on the screen`() {
        val (w, h) = fit(aspect = 9f / 16f, w = 300f, h = 400f)

        assertEquals(400f, h, 0.01f)
        assertEquals(400f * 9f / 16f, w, 0.01f)
    }

    @Test
    fun `a cover never goes outside the space it is given`() {
        for (aspect in listOf(0.3f, 0.56f, 1f, 1.33f, 1.78f, 2.4f, 4f)) {
            for ((mw, mh) in listOf(300f to 400f, 300f to 150f, 500f to 500f)) {
                val (w, h) = fitCover(aspect, mw, mh)
                assertTrue("$aspect in $mw x $mh gave $w x $h", w <= mw + 0.01f && h <= mh + 0.01f)
                assertEquals(aspect, w / h, 0.01f)
            }
        }
    }

    @Test
    fun `a nonsense ratio falls back to a square`() {
        for (bad in listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val (w, h) = fit(bad)
            assertEquals(w, h, 0.01f)
        }
    }
}
