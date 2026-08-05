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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.cuetotech.vibetube.data.SavedPlaylist
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.player.WebPlayerControlHandle
import com.cuetotech.vibetube.player.YouTubePlayerView
import com.cuetotech.vibetube.ui.components.SongItem

@Composable
fun PlaylistsScreen(
    modifier: Modifier = Modifier,
    onBrowse: () -> Unit = {},
    onGoToFriends: () -> Unit = {},
    viewModel: PlaylistsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedPlaylistId by viewModel.selectedPlaylistId.collectAsState()
    val currentSong by viewModel.currentSong.collectAsState()
    val savedPlaylists by viewModel.savedPlaylists.collectAsState()
    val selectedSavedId by viewModel.selectedSavedId.collectAsState()
    val isShuffleEnabled by viewModel.isShuffleEnabled.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()
    val playbackTick by viewModel.playbackTick.collectAsState()
    val backgroundAudioActive by viewModel.backgroundAudioActive.collectAsState()
    val isForeground by viewModel.isForeground.collectAsState()
    val webPlayerControl = viewModel.webPlayerControl

    val selectedPlaylist = uiState.playlists.find { it.id == selectedPlaylistId }
    val selectedSaved = savedPlaylists.find { it.playlist.id == selectedSavedId }

    // Lista pendiente de confirmar antes de borrarla. Mientras sea distinta de
    // null se muestra el diálogo de confirmación de borrado.
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

    BackHandler(enabled = selectedPlaylist != null || selectedSaved != null) {
        when {
            selectedSaved != null -> viewModel.closeSavedPlaylist()
            selectedPlaylist != null -> viewModel.closePlaylist()
        }
    }

    when {
        uiState.isLoading && uiState.playlists.isEmpty() && savedPlaylists.isEmpty() -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        uiState.error != null && uiState.playlists.isEmpty() && savedPlaylists.isEmpty() -> Box(
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

        selectedSaved != null -> PlaylistDetail(
            playlist = selectedSaved.playlist,
            currentSong = currentSong,
            ownerLabel = selectedSaved.ownerDisplayName,
            isSaved = true,
            isShuffleEnabled = isShuffleEnabled,
            repeatMode = repeatMode,
            playbackTick = playbackTick,
            backgroundAudioActive = backgroundAudioActive,
            isForeground = isForeground,
            webPlayerControl = webPlayerControl,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeatMode,
            onBack = viewModel::closeSavedPlaylist,
            onSelectTrack = viewModel::selectSavedTrack,
            onStartPlayback = viewModel::startSavedPlayback,
            onEnded = viewModel::playNextSavedTrack,
            onUnsave = { viewModel.unsavePlaylist(selectedSaved.playlist.id) },
            onBrowse = onBrowse,
            modifier = modifier,
        )

        selectedPlaylist != null -> PlaylistDetail(
            playlist = selectedPlaylist,
            currentSong = currentSong,
            isSaved = false,
            isShuffleEnabled = isShuffleEnabled,
            repeatMode = repeatMode,
            playbackTick = playbackTick,
            backgroundAudioActive = backgroundAudioActive,
            isForeground = isForeground,
            webPlayerControl = webPlayerControl,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeatMode,
            onBack = viewModel::closePlaylist,
            onSelectTrack = viewModel::selectTrack,
            onStartPlayback = viewModel::startPlayback,
            onEnded = viewModel::playNextTrack,
            onRemoveTrack = { songId -> viewModel.removeTrack(selectedPlaylist.id, songId) },
            onAddSong = viewModel::openAddSongDialog,
            onBrowse = onBrowse,
            modifier = modifier,
        )

        else -> PlaylistsList(
            playlists = uiState.playlists,
            savedPlaylists = savedPlaylists,
            onCreatePlaylist = viewModel::openCreateDialog,
            onOpenPlaylist = viewModel::openPlaylist,
            onDeletePlaylist = { id -> playlistToDelete = uiState.playlists.find { it.id == id } },
            onEditPlaylist = viewModel::openEditDialog,
            onOpenSaved = viewModel::openSavedPlaylist,
            onUnsave = viewModel::unsavePlaylist,
            onBrowse = onBrowse,
            onGoToFriends = onGoToFriends,
            modifier = modifier,
        )
    }

    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.playlist_delete_confirm_title)) },
            text = { Text(stringResource(R.string.playlist_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlaylist(playlist.id)
                        playlistToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.playlist_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlaylistsList(
    playlists: List<Playlist>,
    savedPlaylists: List<SavedPlaylist>,
    onCreatePlaylist: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onEditPlaylist: (Playlist) -> Unit,
    onOpenSaved: (String) -> Unit,
    onUnsave: (String) -> Unit,
    onBrowse: () -> Unit,
    onGoToFriends: () -> Unit,
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

        if (playlists.isEmpty() && savedPlaylists.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.playlists_empty_title),
                subtitle = stringResource(R.string.playlists_empty_subtitle),
                actionLabel = stringResource(R.string.empty_browse_button),
                onAction = onBrowse,
                modifier = modifier,
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "own_header") {
                Text(
                    text = stringResource(R.string.playlists_own_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (playlists.isEmpty()) {
                item(key = "own_empty") {
                    Text(
                        text = stringResource(R.string.playlists_own_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(playlists, key = { "own_${it.id}" }) { playlist ->
                    PlaylistCard(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onEdit = { onEditPlaylist(playlist) },
                        onDelete = { onDeletePlaylist(playlist.id) },
                    )
                }
            }

            if (savedPlaylists.isNotEmpty()) {
                item(key = "saved_header") {
                    Text(
                        text = stringResource(R.string.playlists_saved_section),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(savedPlaylists, key = { "saved_${it.playlist.id}" }) { saved ->
                    SavedPlaylistCard(
                        saved = saved,
                        onClick = { onOpenSaved(saved.playlist.id) },
                        onUnsave = { onUnsave(saved.playlist.id) },
                    )
                }
                if (playlists.isEmpty()) {
                    item(key = "saved_hint") {
                        TextButton(onClick = onGoToFriends, modifier = Modifier.padding(start = 8.dp)) {
                            Text(stringResource(R.string.playlists_saved_hint))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedPlaylistCard(
    saved: SavedPlaylist,
    onClick: () -> Unit,
    onUnsave: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                    text = saved.playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.playlists_saved_owner, saved.ownerDisplayName),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.playlist_tracks_count,
                        saved.playlist.tracks.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnsave) {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = stringResource(R.string.playlist_unsave),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
    onEdit: () -> Unit,
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
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.playlist_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    currentSong: Song?,
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    playbackTick: Int,
    backgroundAudioActive: Boolean = false,
    isForeground: Boolean = true,
    webPlayerControl: WebPlayerControlHandle? = null,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onBack: () -> Unit,
    onSelectTrack: (String) -> Unit,
    onStartPlayback: () -> Unit = {},
    onEnded: () -> Unit = {},
    onRemoveTrack: ((String) -> Unit)? = null,
    onAddSong: ((Song) -> Unit)? = null,
    onUnsave: (() -> Unit)? = null,
    ownerLabel: String? = null,
    isSaved: Boolean = false,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks = playlist.tracks
    val selectedSong = currentSong
    val hasValidSong = selectedSong != null && selectedSong.youtubeId.isNotBlank()

    // ID de la canción pendiente de confirmar antes de borrarla. Mientras sea
    // distinto de null se muestra el diálogo de confirmación de borrado.
    var videoToDelete by remember { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                    text = when {
                        isSaved && ownerLabel != null -> stringResource(
                            R.string.playlists_saved_owner,
                            ownerLabel,
                        )
                        else -> stringResource(
                            if (playlist.isPublic) R.string.playlist_public
                            else R.string.playlist_private,
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isSaved && onUnsave != null) {
                IconButton(onClick = onUnsave) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(R.string.playlist_unsave),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // El reproductor solo se instancia cuando el usuario eligió
        // explícitamente una canción (tocándola o con el botón Play): al abrir la
        // lista no hay selección (currentSong == null) y no se carga ningún vídeo.
        if (hasValidSong) {
            YouTubePlayerView(
                currentSong = selectedSong,
                onAddSong = { onAddSong?.invoke(selectedSong) },
                showAddSong = !isSaved,
                onEnded = onEnded,
                playbackTick = playbackTick,
                // El WebView solo se silencia cuando el servicio está activo Y la
                // app NO está en primer plano: en primer plano el WebView es la
                // fuente de audio (el servicio queda en pausa vía el handoff).
                muted = backgroundAudioActive && !isForeground,
                webPlayerControl = webPlayerControl,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )

            PlayerModeControls(
                isShuffleEnabled = isShuffleEnabled,
                repeatMode = repeatMode,
                onToggleShuffle = onToggleShuffle,
                onCycleRepeat = onCycleRepeat,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (tracks.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // Espacio inferior extra cuando el FAB de Play está visible
                // (lista sin reproducción activa).
                contentPadding = PaddingValues(
                    start = 0.dp,
                    top = 8.dp,
                    end = 0.dp,
                    bottom = if (hasValidSong) 8.dp else 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tracks, key = { it.id }) { track ->
                    SongItem(
                        song = track,
                        highlighted = track.id == selectedSong?.id,
                        onClick = { onSelectTrack(track.id) },
                        trailingContent = {
                            if (onRemoveTrack != null) {
                                IconButton(onClick = { videoToDelete = track.id }) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.playlist_remove_track),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
                item(key = "bottom_spacer") { Spacer(modifier = Modifier.padding(24.dp)) }
            }
        } else {
            EmptyState(
                icon = Icons.Filled.LibraryMusic,
                title = stringResource(R.string.playlist_no_tracks_title),
                subtitle = stringResource(R.string.playlist_no_tracks_subtitle),
                actionLabel = stringResource(R.string.empty_browse_button),
                onAction = onBrowse,
                modifier = Modifier.fillMaxSize(),
            )
        }
        }

        // Botón flotante de Play: visible solo cuando la lista tiene canciones
        // pero aún no se está reproduciendo ninguna (currentSong == null). Inicia
        // la reproducción con la primera canción (o una al azar con shuffle).
        if (tracks.isNotEmpty() && !hasValidSong) {
            FloatingActionButton(
                onClick = onStartPlayback,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.playlist_play),
                )
            }
        }
    }

    videoToDelete?.let { songId ->
        AlertDialog(
            onDismissRequest = { videoToDelete = null },
            title = { Text(stringResource(R.string.playlist_delete_track_title)) },
            text = { Text(stringResource(R.string.playlist_delete_track_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveTrack?.invoke(songId)
                        videoToDelete = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.playlist_delete_track_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { videoToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun PlayerModeControls(
    isShuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleShuffle) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = stringResource(
                    if (isShuffleEnabled) R.string.player_shuffle_on
                    else R.string.player_shuffle_off,
                ),
                tint = if (isShuffleEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onCycleRepeat) {
            Icon(
                imageVector = if (repeatMode == RepeatMode.ONE) {
                    Icons.Filled.RepeatOne
                } else {
                    Icons.Filled.Repeat
                },
                contentDescription = stringResource(
                    when (repeatMode) {
                        RepeatMode.OFF -> R.string.player_repeat_off
                        RepeatMode.ALL -> R.string.player_repeat_all
                        RepeatMode.ONE -> R.string.player_repeat_one
                    },
                ),
                tint = if (repeatMode != RepeatMode.OFF) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
