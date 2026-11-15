package com.personal.music.ui.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.PlayerControls
import java.io.File

/** The songs of one mood. Playing or shuffling here never leaves the mood. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoodScreen(
    moodName: String,
    repository: LibraryRepository,
    player: PlayerControls,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    coverFile: (SongEntity) -> File? = { null },
    onAddToPlaylist: (SongEntity) -> Unit = {},
    onSetMood: (SongEntity) -> Unit = {},
) {
    val songs by repository.moodSongs(moodName).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = moodName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (songs.isNotEmpty()) {
                item { ShuffleButton(onClick = { player.playShuffled(songs) }, label = "Shuffle $moodName") }
            }
            itemsIndexed(
                items = songs,
                key = { _, song -> song.id },
            ) { index, song ->
                SongRow(
                    song = song,
                    onClick = { player.play(songs, index) },
                    onPlayNext = { player.playNext(song) },
                    onAddToQueue = { player.addToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    coverFile = coverFile,
                    onSetMood = { onSetMood(song) },
                )
            }
        }
    }
}
