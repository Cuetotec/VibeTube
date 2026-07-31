package com.cuetotech.vibetube.ui.playlists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.player.YouTubePlayerView
import com.cuetotech.vibetube.ui.components.SongItem

@Composable
fun PlaylistsScreen(
    modifier: Modifier = Modifier,
    onBrowse: () -> Unit = {},
    viewModel: PlaylistsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsState()
    val selectedTrackId by viewModel.selectedTrackId.collectAsState()

    val selectedPlaylist = uiState.playlists.find { it.id == selectedPlaylistId }

    BackHandler(enabled = selectedPlaylist != null) {
        viewModel.closePlaylist()
    }

    when {
        uiState.isLoading && uiState.playlists.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        uiState.error != null && uiState.playlists.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = uiState.error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Button(
                    onClick = viewModel::retry,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.playlists_retry))
                }
            }
        }

        selectedPlaylist != null -> PlaylistDetail(
            playlist = selectedPlaylist,
            selectedTrackId = selectedTrackId,
            onBack = viewModel::closePlaylist,
            onSelectTrack = viewModel::selectTrack,
            onRemoveTrack = { songId -> viewModel.removeTrack(selectedPlaylist.id, songId) },
            onAddSong = viewModel::openAddSongDialog,
            onBrowse = onBrowse,
            modifier = modifier,
        )

        else -> PlaylistsList(
            playlists = uiState.playlists,
            onCreatePlaylist = viewModel::openCreateDialog,
            onOpenPlaylist = viewModel::openPlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            onBrowse = onBrowse,
            modifier = modifier,
        )
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<Playlist>,
    onCreatePlaylist: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.playlists_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onCreatePlaylist) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(stringResource(R.string.playlists_new))
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.playlists_empty_title),
                subtitle = stringResource(R.string.playlists_empty_subtitle),
                actionLabel = stringResource(R.string.empty_browse_button),
                onAction = onBrowse,
                modifier = modifier,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onDelete = { onDeletePlaylist(playlist.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlist.description.isNotBlank()) {
                    Text(
                        text = playlist.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.playlist_tracks_count,
                        playlist.tracks.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(
                    if (playlist.isPublic) R.string.playlist_public
                    else R.string.playlist_private,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.playlist_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    selectedTrackId: String?,
    onBack: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onAddSong: (Song) -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks = playlist.tracks
    val selectedSong = selectedTrackId?.let { id -> tracks.find { it.id == id } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.playlist_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (playlist.isPublic) R.string.playlist_public
                        else R.string.playlist_private,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // El reproductor solo se instancia cuando el usuario entró en el detalle
        // de una lista Y hay un vídeo válido seleccionado (selectedTrackId resuelto).
        if (selectedSong != null && selectedSong.youtubeId.isNotBlank()) {
            YouTubePlayerView(
                videoId = selectedSong.youtubeId,
                onAddSong = { onAddSong(selectedSong) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tracks, key = { it.id }) { track ->
                    SongItem(
                        song = track,
                        highlighted = track.id == selectedSong.id,
                        onClick = { onSelectTrack(track.id) },
                        trailingContent = {
                            IconButton(onClick = { onRemoveTrack(track.id) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.playlist_remove_track),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        },
                    )
                }
                item(key = "bottom_spacer") { Spacer(modifier = Modifier.padding(24.dp)) }
            }
        } else {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(
                    if (tracks.isEmpty()) R.string.playlist_no_tracks_title
                    else R.string.playlist_select_video_title,
                ),
                subtitle = stringResource(
                    if (tracks.isEmpty()) R.string.playlist_no_tracks_subtitle
                    else R.string.playlist_select_video_subtitle,
                ),
                actionLabel = stringResource(R.string.empty_browse_button),
                onAction = onBrowse,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onAction) {
            Text(actionLabel)
        }
    }
}
