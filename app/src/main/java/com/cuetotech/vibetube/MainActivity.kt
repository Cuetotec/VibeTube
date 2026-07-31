package com.cuetotech.vibetube

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.ui.auth.AuthScreen
import com.cuetotech.vibetube.ui.auth.AuthViewModel
import com.cuetotech.vibetube.ui.home.HomeScreen
import com.cuetotech.vibetube.ui.playlists.PlaylistsViewModel
import com.cuetotech.vibetube.ui.playlists.PlaylistsScreen
import com.cuetotech.vibetube.ui.theme.VibeTubeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            VibeTubeTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val authViewModel: AuthViewModel = viewModel()
    val playlistsViewModel: PlaylistsViewModel = viewModel()
    val user by authViewModel.user.collectAsState()

    if (user == null) {
        AuthScreen(modifier = Modifier.fillMaxSize())
        return
    }

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes),
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (selectedTab) {
            AppTab.HOME -> HomeScreen(modifier = contentModifier)
            AppTab.PLAYLISTS -> PlaylistsScreen(
                modifier = contentModifier,
                onBrowse = { selectedTab = AppTab.HOME },
            )
        }
    }

    val pendingSong by playlistsViewModel.pendingSong.collectAsState()
    val showCreateDialog by playlistsViewModel.showCreateDialog.collectAsState()
    val showUrlDialog by playlistsViewModel.showUrlDialog.collectAsState()
    val isUrlProcessing by playlistsViewModel.isUrlProcessing.collectAsState()
    val urlError by playlistsViewModel.urlError.collectAsState()
    val playlistsUiState by playlistsViewModel.uiState.collectAsState()

    pendingSong?.let { song ->
        AddToPlaylistDialog(
            song = song,
            playlists = playlistsUiState.playlists,
            onAdd = playlistsViewModel::addPendingSongToPlaylist,
            onCreateNew = playlistsViewModel::openCreateDialog,
            onDismiss = playlistsViewModel::dismissAddSongDialog,
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onConfirm = playlistsViewModel::createPlaylist,
            onDismiss = playlistsViewModel::dismissCreateDialog,
        )
    }

    if (showUrlDialog) {
        UrlDialog(
            isProcessing = isUrlProcessing,
            error = urlError,
            onConfirm = playlistsViewModel::processUrl,
            onDismiss = playlistsViewModel::dismissUrlDialog,
        )
    }
}

@Composable
private fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    onAdd: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist_title)) },
        text = {
            Column {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.add_to_playlist_no_lists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.playlist_tracks_count,
                                            playlist.tracks.size,
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onAdd(playlist.id) }) {
                                    Text(stringResource(R.string.add_to_playlist_add))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCreateNew) {
                Text(stringResource(R.string.add_to_playlist_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun CreatePlaylistDialog(
    onConfirm: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_playlist_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.create_playlist_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.create_playlist_description_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.create_playlist_public),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title, description, isPublic) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.create_playlist_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun UrlDialog(
    isProcessing: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.url_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.url_dialog_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(modifier = Modifier.padding(top = 8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank() && !isProcessing,
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(stringResource(R.string.url_dialog_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isProcessing) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private enum class AppTab(
    val icon: ImageVector,
    val labelRes: Int,
) {
    HOME(Icons.Filled.Home, R.string.tab_home),
    PLAYLISTS(Icons.Filled.Star, R.string.tab_playlists),
}
