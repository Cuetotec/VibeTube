package com.cuetotech.vibetube.ui.home

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.UserProfile
import com.cuetotech.vibetube.ui.components.SongItem
import com.cuetotech.vibetube.ui.playlists.PlaylistsViewModel

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
    playlistsViewModel: PlaylistsViewModel = viewModel(),
) {
    val profileState by viewModel.profileState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val isUploadingAvatar by viewModel.isUploadingAvatar.collectAsState()
    val isUploadingBanner by viewModel.isUploadingBanner.collectAsState()
    val uploadError by viewModel.uploadError.collectAsState()

    // Indica qué imagen se está seleccionando (avatar o banner) cuando el
    // selector de galería devuelve el Uri elegido.
    var pendingImageTarget by remember { mutableStateOf<ImageTarget?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = pendingImageTarget
        pendingImageTarget = null
        if (uri != null) {
            when (target) {
                ImageTarget.Avatar -> viewModel.uploadAvatar(uri)
                ImageTarget.Banner -> viewModel.uploadBanner(uri)
                null -> Unit
            }
        }
    }

    val context = LocalContext.current
    LaunchedEffect(uploadError) {
        uploadError?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.clearUploadError()
        }
    }

    when (val state = profileState) {
        is ProfileUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is ProfileUiState.Error -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Button(
                    onClick = viewModel::retryProfile,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.home_retry))
                }
            }
        }

        is ProfileUiState.Success -> HomeContent(
            profile = state.profile,
            searchState = searchState,
            isUploadingAvatar = isUploadingAvatar,
            isUploadingBanner = isUploadingBanner,
            onPickAvatar = {
                pendingImageTarget = ImageTarget.Avatar
                imagePicker.launch("image/*")
            },
            onPickBanner = {
                pendingImageTarget = ImageTarget.Banner
                imagePicker.launch("image/*")
            },
            onQueryChange = viewModel::onQueryChange,
            onRetrySearch = viewModel::retrySearch,
            onAddSong = playlistsViewModel::openAddSongDialog,
            onAddByUrl = playlistsViewModel::openUrlDialog,
            onSignOut = viewModel::signOut,
            modifier = modifier,
        )
    }
}

private enum class ImageTarget { Avatar, Banner }

@Composable
private fun HomeContent(
    profile: UserProfile,
    searchState: SearchUiState,
    isUploadingAvatar: Boolean,
    isUploadingBanner: Boolean,
    onPickAvatar: () -> Unit,
    onPickBanner: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRetrySearch: () -> Unit,
    onAddSong: (com.cuetotech.vibetube.data.Song) -> Unit,
    onAddByUrl: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item(key = "banner") {
            ProfileBanner(
                profile = profile,
                isUploading = isUploadingBanner,
                onPickImage = onPickBanner,
                onSignOut = onSignOut,
            )
        }

        item(key = "profile_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    profile = profile,
                    isUploading = isUploadingAvatar,
                    onPickImage = onPickAvatar,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                ) {
                    Text(
                        text = profile.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = profile.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "search_bar") {
            OutlinedTextField(
                value = searchState.query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(stringResource(R.string.home_search_placeholder))
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (searchState.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.home_search_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
        }

        item(key = "search_meta") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_search_results),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddByUrl) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Text(stringResource(R.string.profile_add_by_url))
                }
            }
        }

        when {
            searchState.isLoading && searchState.results.isEmpty() -> {
                item(key = "search_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            searchState.error != null -> {
                item(key = "search_error") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = searchState.error,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(
                            onClick = onRetrySearch,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.home_retry))
                        }
                    }
                }
            }

            searchState.query.isBlank() -> {
                item(key = "search_hint") {
                    Text(
                        text = stringResource(R.string.home_search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }

            searchState.results.isEmpty() -> {
                item(key = "search_empty") {
                    Text(
                        text = stringResource(R.string.home_search_no_results, searchState.query.trim()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }

            else -> {
                items(searchState.results, key = { it.id }) { song ->
                    SongItem(
                        song = song,
                        trailingContent = {
                            IconButton(onClick = { onAddSong(song) }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.player_add_to_playlist),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                    )
                }
                item(key = "search_spacer") { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun ProfileBanner(
    profile: UserProfile,
    isUploading: Boolean,
    onPickImage: () -> Unit,
    onSignOut: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        val bannerUrl = profile.bannerUrl
        if (bannerUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                    ),
            )
        } else {
            AsyncImage(
                model = bannerUrl,
                contentDescription = stringResource(R.string.profile_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
        }
        // La portada es tocable para cambiarla: muestra un indicador de subida
        // mientras se procesa la imagen elegida.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clickable(enabled = !isUploading, onClick = onPickImage)
                .background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isUploading) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.profile_banner_change),
                    tint = Color.White,
                )
            }
        }
        IconButton(
            onClick = onSignOut,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = stringResource(R.string.profile_sign_out),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Avatar(
    profile: UserProfile,
    isUploading: Boolean,
    onPickImage: () -> Unit,
) {
    val avatarUrl = profile.avatarUrl
    val avatarChangeDescription = stringResource(R.string.profile_avatar_change)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = avatarChangeDescription }
            .clickable(enabled = !isUploading, onClick = onPickImage),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl.isNullOrBlank()) {
            Text(
                text = profile.displayName.initials(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.profile_avatar),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isUploading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
            }
        }
    }
}

private fun String.initials(): String {
    val parts = trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    return parts.take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
}
