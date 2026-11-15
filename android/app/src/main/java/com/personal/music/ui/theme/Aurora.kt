package com.personal.music.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** The colours of a soft blue, purple and pink glow, one after the other and back to the start. */
private val GlowPalette = listOf(
    Color(0xFF4285F4),
    Color(0xFF9B72CB),
    Color(0xFFD96570),
    Color(0xFF4FC3F7),
)

/** A colour along the palette. [t] runs 0..1 and wraps, so the last colour blends back into the first. */
fun paletteAt(t: Float): Color {
    val wrapped = t - floor(t)
    val scaled = wrapped * GlowPalette.size
    val from = scaled.toInt().coerceAtMost(GlowPalette.lastIndex)
    val to = (from + 1) % GlowPalette.size
    return lerp(GlowPalette[from], GlowPalette[to], scaled - from)
}

/** How tall, from 0 to 1, a curtain fold is at position [u] (0..1 across) at animation [phase] (radians). */
fun curtainHeight(u: Float, phase: Float): Float =
    ((sin(u * 9f + phase) + sin(u * 5f - phase * 1.3f) + 2f) / 4f).coerceIn(0f, 1f)

/** Soft glowing curtains that drift slowly along the bottom of the screen, a little like the glow at the bottom of Gemini. */
@Composable
fun AuroraGlow(
    modifier: Modifier = Modifier,
    strength: Float = 1f,
) {
    val transition = rememberInfiniteTransition(label = "aurora")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 22_000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift",
    )

    // The state is read inside the draw block, so each frame only redraws; nothing is recomposed.
    Canvas(modifier = modifier) {
        // Many thin strips, each starting exactly where the last one ended, so no seams or overlaps show.
        val strips = 90
        for (i in 0 until strips) {
            val left = (size.width * i / strips).roundToInt().toFloat()
            val right = (size.width * (i + 1) / strips).roundToInt().toFloat()
            val u = i / (strips - 1f)
            val wave = curtainHeight(u, phase.value)
            val colour = paletteAt(drift.value + u * 0.8f)
            val top = size.height * (1f - (0.3f + 0.7f * wave))
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, colour.copy(alpha = (0.5f * strength * (0.45f + 0.55f * wave)).coerceIn(0f, 1f))),
                    startY = top,
                    endY = size.height,
                ),
                topLeft = Offset(left, top),
                size = Size(right - left, size.height - top),
            )
        }
    }
}
