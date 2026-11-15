package com.personal.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.personal.music.playback.PlayerControls
import com.personal.music.playback.RepeatMode
import kotlinx.coroutines.launch

fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L)) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    player: PlayerControls,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    liked: Boolean = false,
    onToggleLike: () -> Unit = {},
) {
    val state by player.state.collectAsState()
    val current = state.current
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        scope.launch {
            snackbarHostState.showSnackbar(error)
        }
        player.clearError()
    }

    if (showSpeedDialog) {
        SpeedDialog(
            current = state.speed,
            onPick = { speed ->
                player.setSpeed(speed)
                showSpeedDialog = false
            },
            onDismiss = { showSpeedDialog = false },
        )
    }

    if (showSleepDialog) {
        SleepTimerDialog(
            endsAtMillis = state.sleepTimerEndsAtMillis,
            onPick = { minutes ->
                player.setSleepTimer(minutes)
                showSleepDialog = false
            },
            onDismiss = { showSleepDialog = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(
                            onClick = onToggleLike,
                            modifier = Modifier.testTag("like_button"),
                        ) {
                            Icon(
                                imageVector = if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (liked) "Remove from Liked Songs" else "Add to Liked Songs",
                                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    TextButton(
                        onClick = { showSpeedDialog = true },
                        modifier = Modifier.testTag("speed_button"),
                    ) {
                        Text(
                            text = speedLabel(state.speed),
                            color = if (state.speed != 1f) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(
                        onClick = { showSleepDialog = true },
                        modifier = Modifier.testTag("sleep_timer_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = if (state.sleepTimerEndsAtMillis != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(
                        onClick = { showQueueSheet = true },
                        modifier = Modifier.testTag("open_queue_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                            contentDescription = "Queue",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { innerPadding ->
        if (current == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Nothing is playing",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                // Large Cover
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!current.artworkUri.isNullOrBlank()) {
                        AsyncImage(
                            model = current.artworkUri,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                // Title & Artist
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = current.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("player_title"),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = current.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("player_artist"),
                    )
                }

                // Seek bar & times
                var draggingPosition by remember { mutableStateOf<Float?>(null) }
                val position = draggingPosition?.toLong() ?: state.positionMs
                val duration = state.durationMs.coerceAtLeast(0L)

                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = position.coerceIn(0L, duration).toFloat(),
                        onValueChange = { draggingPosition = it },
                        onValueChangeFinished = {
                            val target = draggingPosition ?: position.toFloat()
                            player.seekTo(target.toLong())
                            draggingPosition = null
                        },
                        valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                        enabled = duration > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("seek_slider"),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatPlayerTime(position),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("elapsed_time"),
                        )
                        Text(
                            text = formatPlayerTime(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("total_time"),
                        )
                    }
                }

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Shuffle
                    IconButton(
                        onClick = { player.setShuffle(!state.shuffle) },
                        modifier = Modifier.testTag("shuffle_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = if (state.shuffle) "Shuffle: On" else "Shuffle: Off",
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Previous
                    IconButton(
                        onClick = { player.previous() },
                        modifier = Modifier.testTag("previous_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Play/Pause
                    FilledIconButton(
                        onClick = { player.togglePlay() },
                        modifier = Modifier
                            .size(64.dp)
                            .testTag("play_pause_button"),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { player.next() },
                        modifier = Modifier.testTag("next_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            modifier = Modifier.size(36.dp),
                        )
                    }

                    // Repeat
                    IconButton(
                        onClick = { player.cycleRepeat() },
                        // Wide enough for the icon plus its OFF / ALL / ONE label on a narrow phone.
                        modifier = Modifier
                            .width(56.dp)
                            .testTag("repeat_button"),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (state.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat: ${state.repeat.name}",
                                tint = if (state.repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = state.repeat.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQueueSheet) {
        QueueSheet(
            player = player,
            onDismiss = { showQueueSheet = false },
        )
    }
}

@Composable
private fun SleepTimerDialog(
    endsAtMillis: Long?,
    onPick: (minutes: Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                if (endsAtMillis != null) {
                    val left = ((endsAtMillis - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                    Text(
                        text = "Music stops in about $left min.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(bottom = 8.dp)
                            .testTag("sleep_timer_remaining"),
                    )
                }
                listOf(10, 20, 30, 45, 60).forEach { minutes ->
                    Text(
                        text = "$minutes minutes",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(minutes) }
                            .padding(vertical = 12.dp)
                            .testTag("sleep_option_$minutes"),
                    )
                }
            }
        },
        confirmButton = {
            if (endsAtMillis != null) {
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.testTag("sleep_timer_off"),
                ) { Text("Turn off timer") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

fun speedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "x"

@Composable
private fun SpeedDialog(
    current: Float,
    onPick: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed") },
        text = {
            Column {
                SPEEDS.forEach { speed ->
                    Text(
                        text = speedLabel(speed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (speed == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(speed) }
                            .padding(vertical = 12.dp)
                            .testTag("speed_option_${speedLabel(speed)}"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
