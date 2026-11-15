package com.personal.music.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.personal.music.AppContainer
import com.personal.music.db.SongEntity
import com.personal.music.library.LibraryRepository
import com.personal.music.playback.PlayerControls
import com.personal.music.ui.library.AlbumScreen
import com.personal.music.ui.library.ArtistScreen
import com.personal.music.ui.library.LibraryScreen
import com.personal.music.ui.library.LibraryViewModel
import com.personal.music.ui.player.MiniPlayer
import com.personal.music.ui.player.PlayerScreen
import com.personal.music.ui.playlists.AddToPlaylistSheet
import com.personal.music.ui.playlists.PlaylistScreen
import com.personal.music.ui.playlists.PlaylistsScreen

class LibraryViewModelFactory(
    private val repository: LibraryRepository,
    private val player: PlayerControls,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            return LibraryViewModel(repository, player) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
fun AppBottomBar(
    navController: NavHostController,
    currentRoute: String,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        NavigationBarItem(
            selected = currentRoute == "library",
            onClick = {
                if (currentRoute != "library") {
                    navController.navigate("library") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = "Library",
                )
            },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = currentRoute == "playlists",
            onClick = {
                if (currentRoute != "playlists") {
                    navController.navigate("playlists") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = "Playlists",
                )
            },
            label = { Text("Playlists") },
        )
        NavigationBarItem(
            selected = currentRoute == "settings",
            onClick = {
                if (currentRoute != "settings") {
                    navController.navigate("settings") {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                )
            },
            label = { Text("Settings") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(container.library, container.player),
    )
    var songToAddToPlaylist by remember { mutableStateOf<SongEntity?>(null) }

    NavHost(
        navController = navController,
        startDestination = "library",
        modifier = modifier,
    ) {
        composable("library") {
            LibraryScreen(
                viewModel = libraryViewModel,
                player = container.player,
                onAlbumClick = { albumArtist, album ->
                    navController.navigate("album/${Uri.encode(albumArtist)}/${Uri.encode(album)}")
                },
                onArtistClick = { name ->
                    navController.navigate("artist/${Uri.encode(name)}")
                },
                onOpenPlayer = {
                    navController.navigate("player")
                },
                coverFile = { song -> song.cover?.let { container.downloader.coverFile(it) } },
                onAddToPlaylist = { song -> songToAddToPlaylist = song },
                bottomBar = {
                    AppBottomBar(
                        navController = navController,
                        currentRoute = "library",
                    )
                },
            )
        }

        composable(
            route = "album/{albumArtist}/{album}",
            arguments = listOf(
                navArgument("albumArtist") { type = NavType.StringType },
                navArgument("album") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val albumArtist = Uri.decode(backStackEntry.arguments?.getString("albumArtist").orEmpty())
            val album = Uri.decode(backStackEntry.arguments?.getString("album").orEmpty())
            AlbumScreen(
                albumArtist = albumArtist,
                album = album,
                repository = container.library,
                player = container.player,
                onBack = { navController.popBackStack() },
                coverFile = { song -> song.cover?.let { container.downloader.coverFile(it) } },
                onAddToPlaylist = { song -> songToAddToPlaylist = song },
            )
        }

        composable(
            route = "artist/{name}",
            arguments = listOf(
                navArgument("name") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val name = Uri.decode(backStackEntry.arguments?.getString("name").orEmpty())
            ArtistScreen(
                artistName = name,
                repository = container.library,
                player = container.player,
                onBack = { navController.popBackStack() },
                coverFile = { song -> song.cover?.let { container.downloader.coverFile(it) } },
                onAddToPlaylist = { song -> songToAddToPlaylist = song },
            )
        }

        composable("player") {
            PlayerScreen(
                player = container.player,
                onBack = { navController.popBackStack() },
            )
        }

        composable("playlists") {
            PlaylistsScreen(
                repository = container.playlists,
                onPlaylistClick = { id -> navController.navigate("playlist/$id") },
                bottomBar = {
                    Column {
                        MiniPlayer(
                            player = container.player,
                            onOpenPlayer = { navController.navigate("player") },
                            coverFile = { cover -> container.downloader.coverFile(cover) },
                        )
                        AppBottomBar(
                            navController = navController,
                            currentRoute = "playlists",
                        )
                    }
                },
            )
        }

        composable(
            route = "playlist/{id}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: 0L
            PlaylistScreen(
                playlistId = id,
                repository = container.playlists,
                player = container.player,
                onBack = { navController.popBackStack() },
                coverFile = { song -> song.cover?.let { container.downloader.coverFile(it) } },
            )
        }

        composable("settings") {
            Scaffold(
                bottomBar = {
                    Column {
                        MiniPlayer(
                            player = container.player,
                            onOpenPlayer = { navController.navigate("player") },
                        )
                        AppBottomBar(
                            navController = navController,
                            currentRoute = "settings",
                        )
                    }
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Settings Screen")
                }
            }
        }
    }

    songToAddToPlaylist?.let { song ->
        AddToPlaylistSheet(
            song = song,
            repository = container.playlists,
            onDismiss = { songToAddToPlaylist = null },
        )
    }
}
