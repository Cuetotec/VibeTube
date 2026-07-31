package com.cuetotech.vibetube.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.player.YouTubePlayerView
import com.cuetotech.vibetube.ui.collection.CollectionViewModel
import com.cuetotech.vibetube.ui.home.components.SongItem
import com.cuetotech.vibetube.ui.song.SongUiState
import com.cuetotech.vibetube.ui.song.SongViewModel

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: SongViewModel = viewModel(),
    collectionViewModel: CollectionViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteIds by collectionViewModel.favoriteIds.collectAsState()

    when (val state = uiState) {
        is SongUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is SongUiState.Error -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Button(
                onClick = viewModel::loadSongs,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.home_retry))
            }
        }

        is SongUiState.Success -> {
            if (state.songs.isEmpty()) {
                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_empty_songs),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                SongList(
                    songs = state.songs,
                    favoriteIds = favoriteIds,
                    onToggleFavorite = collectionViewModel::toggleFavorite,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    favoriteIds: Set<String>,
    onToggleFavorite: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedSongId by rememberSaveable { mutableStateOf(songs.firstOrNull()?.id) }
    val selectedSong = songs.find { it.id == selectedSongId } ?: songs.firstOrNull()

    var searchQuery by rememberSaveable { mutableStateOf("") }
    val query = searchQuery.trim()
    val filteredSongs = songs.filter { song ->
        query.isEmpty() ||
            song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true)
    }

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onClear = { searchQuery = "" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        selectedSong?.let { song ->
            YouTubePlayerView(
                videoId = song.youtubeId,
                isFavorite = favoriteIds.contains(song.id),
                onToggleFavorite = { onToggleFavorite(song) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }

        if (filteredSongs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.home_no_results, query),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredSongs, key = { it.id }) { song ->
                    SongItem(
                        song = song,
                        onClick = { selectedSongId = song.id },
                        trailingContent = {
                            IconButton(onClick = { onToggleFavorite(song) }) {
                                Icon(
                                    imageVector = if (favoriteIds.contains(song.id)) {
                                        Icons.Filled.Favorite
                                    } else {
                                        Icons.Filled.FavoriteBorder
                                    },
                                    contentDescription = stringResource(
                                        if (favoriteIds.contains(song.id)) {
                                            R.string.player_remove_favorite
                                        } else {
                                            R.string.player_add_favorite
                                        },
                                    ),
                                    tint = if (favoriteIds.contains(song.id)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.home_search_clear),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
    )
}
