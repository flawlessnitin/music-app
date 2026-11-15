package com.personal.music.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.personal.music.db.Album
import com.personal.music.db.Artist
import com.personal.music.db.SongEntity
import com.personal.music.playback.PlayerControls
import com.personal.music.ui.player.MiniPlayer
import java.io.File

enum class LibraryTab {
    Songs, Moods, Albums, Artists, Liked, Recent
}

fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    modifier: Modifier = Modifier,
    player: PlayerControls = viewModel.player,
    onAlbumClick: (albumArtist: String, album: String) -> Unit = { _, _ -> },
    onArtistClick: (name: String) -> Unit = {},
    onMoodClick: (name: String) -> Unit = {},
    onSetMood: (SongEntity) -> Unit = {},
    onOpenPlayer: () -> Unit = {},
    coverFile: (SongEntity) -> File? = { null },
    onAddToPlaylist: (SongEntity) -> Unit = {},
    likedIds: Set<String> = emptySet(),
    likedSongs: List<SongEntity> = emptyList(),
    recentSongs: List<SongEntity> = emptyList(),
    onToggleLike: (SongEntity) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    showMiniPlayer: Boolean = true,
) {
    val query by viewModel.query.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val moods by viewModel.moods.collectAsState()

    // Saved, so coming back from a mood, album or artist lands on the same tab and not on Songs.
    var selectedTab by rememberSaveable { mutableStateOf(LibraryTab.Songs) }

    Scaffold(
        bottomBar = {
            Column {
                if (showMiniPlayer) {
                    MiniPlayer(
                        player = player,
                        onOpenPlayer = onOpenPlayer,
                    )
                }
                bottomBar()
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Text(
                text = "Your Library",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp),
            )

            // Search field
            TextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("search_field"),
                placeholder = { Text("Search…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )

            // Tabs, as pills
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.name, fontWeight = FontWeight.SemiBold) },
                        shape = RoundedCornerShape(50),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            // Tab Content
            when (selectedTab) {
                LibraryTab.Songs -> {
                    if (songs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No songs yet. Send music from your PC with: musictool add <folder>",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { ShuffleButton(onClick = { player.playShuffled(songs) }, label = "Shuffle all") }
                            itemsIndexed(
                                items = songs,
                                key = { _, song -> song.id },
                            ) { index, song ->
                                SongRow(
                                    song = song,
                                    onClick = { viewModel.playSong(songs, index) },
                                    onPlayNext = { viewModel.playNext(song) },
                                    onAddToQueue = { viewModel.addToQueue(song) },
                                    onAddToPlaylist = { onAddToPlaylist(song) },
                                    coverFile = coverFile,
                                    liked = song.id in likedIds,
                                    onToggleLike = { onToggleLike(song) },
                                    onSetMood = { onSetMood(song) },
                                )
                            }
                        }
                    }
                }

                LibraryTab.Moods -> {
                    if (moods.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No moods yet. Put songs in folders like phonk or sad, or press and hold a song and choose Set mood.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(items = moods, key = { it.name }) { mood ->
                                MoodRow(mood = mood, onClick = { onMoodClick(mood.name) })
                            }
                        }
                    }
                }

                LibraryTab.Albums -> {
                    if (albums.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No albums found",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = albums,
                                key = { "${it.albumArtist}_${it.album}" },
                            ) { album ->
                                AlbumRow(
                                    album = album,
                                    onClick = { onAlbumClick(album.albumArtist, album.album) },
                                )
                            }
                        }
                    }
                }

                LibraryTab.Liked -> SongListTab(
                    songs = likedSongs,
                    emptyText = "Songs you like with the heart show up here.",
                    likedIds = likedIds,
                    player = player,
                    coverFile = coverFile,
                    onAddToPlaylist = onAddToPlaylist,
                    onToggleLike = onToggleLike,
                    onSetMood = onSetMood,
                )

                LibraryTab.Recent -> SongListTab(
                    songs = recentSongs,
                    emptyText = "Songs you play show up here.",
                    likedIds = likedIds,
                    player = player,
                    coverFile = coverFile,
                    onAddToPlaylist = onAddToPlaylist,
                    onToggleLike = onToggleLike,
                    onSetMood = onSetMood,
                )

                LibraryTab.Artists -> {
                    if (artists.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No artists found",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = artists,
                                key = { it.name },
                            ) { artist ->
                                ArtistRow(
                                    artist = artist,
                                    onClick = { onArtistClick(artist.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: SongEntity,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    coverFile: (SongEntity) -> File?,
    modifier: Modifier = Modifier,
    liked: Boolean = false,
    onToggleLike: (() -> Unit)? = null,
    onSetMood: (() -> Unit)? = null,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Cover. The note icon is always drawn underneath, so a missing or not yet
            // downloaded image simply leaves the icon showing. Keyed on `downloaded` so the
            // image is requested again once the song (and its cover) has arrived.
            val file = remember(song.id, song.cover, song.downloaded) { coverFile(song) }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (song.format.equals("flac", ignoreCase = true)) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(2.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = "FLAC",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                    .testTag("flac_label"),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "${song.artist} · ${song.album}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = formatDuration(song.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (liked) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Liked",
                        modifier = Modifier
                            .size(14.dp)
                            .testTag("liked_mark"),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text("Play next") },
                onClick = {
                    showMenu = false
                    onPlayNext()
                },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    showMenu = false
                    onAddToQueue()
                },
            )
            if (onToggleLike != null) {
                DropdownMenuItem(
                    text = { Text(if (liked) "Remove from Liked Songs" else "Add to Liked Songs") },
                    onClick = {
                        showMenu = false
                        onToggleLike()
                    },
                )
            }
            if (onSetMood != null) {
                DropdownMenuItem(
                    text = { Text("Set mood") },
                    onClick = {
                        showMenu = false
                        onSetMood()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                onClick = {
                    showMenu = false
                    onAddToPlaylist()
                },
            )
        }
    }
}

@Composable
fun AlbumRow(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Album,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.album,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(album.albumArtist, album.year?.toString()).joinToString(" · ")
            Text(
                text = "$sub · ${album.songCount} ${if (album.songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artist.songCount} ${if (artist.songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SongListTab(
    songs: List<SongEntity>,
    emptyText: String,
    likedIds: Set<String>,
    player: PlayerControls,
    coverFile: (SongEntity) -> File?,
    onAddToPlaylist: (SongEntity) -> Unit,
    onToggleLike: (SongEntity) -> Unit,
    onSetMood: (SongEntity) -> Unit,
) {
    if (songs.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { ShuffleButton(onClick = { player.playShuffled(songs) }) }
            itemsIndexed(items = songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    onClick = { player.play(songs, index) },
                    onPlayNext = { player.playNext(song) },
                    onAddToQueue = { player.addToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    coverFile = coverFile,
                    liked = song.id in likedIds,
                    onToggleLike = { onToggleLike(song) },
                    onSetMood = { onSetMood(song) },
                )
            }
        }
    }
}

@Composable
fun MoodRow(
    mood: com.personal.music.db.Mood,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mood.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${mood.songCount} ${if (mood.songCount == 1) "song" else "songs"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
