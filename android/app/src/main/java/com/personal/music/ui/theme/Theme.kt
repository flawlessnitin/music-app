package com.personal.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SpotifyLikeColors = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    secondary = SpotifyGreen,
    onSecondary = Color.Black,
    tertiary = SpotifyGreen,
    background = Background,
    onBackground = TextPrimary,
    surface = Background,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceDark,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHigh,
    surfaceContainerLow = SurfaceDark,
    surfaceContainerLowest = Background,
    outline = Color(0xFF727272),
    outlineVariant = SurfaceHigh,
)

/** Always dark, like the music apps people are used to. The phone's own colours are ignored. */
@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SpotifyLikeColors,
        typography = Typography,
        content = content,
    )
}
