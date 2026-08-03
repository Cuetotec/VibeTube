package com.cuetotech.vibetube.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.SavedCollection
import com.cuetotech.vibetube.data.SavedCollectionsRepository
import com.cuetotech.vibetube.data.SavedPlaylist
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlaylistsViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val playlistRepository: PlaylistRepository = PlaylistRepository(),
    private val savedCollectionsRepository: SavedCollectionsRepository = SavedCollectionsRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistsUiState(isLoading = true))
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<String?>(null)
    val selectedTrackId: StateFlow<String?> = _selectedTrackId.asStateFlow()

    private val _savedPlaylists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val savedPlaylists: StateFlow<List<SavedPlaylist>> = _savedPlaylists.asStateFlow()

    private val _selectedSavedId = MutableStateFlow<String?>(null)
    val selectedSavedId: StateFlow<String?> = _selectedSavedId.asStateFlow()

    private val _selectedSavedTrackId = MutableStateFlow<String?>(null)
    val selectedSavedTrackId: StateFlow<String?> = _selectedSavedTrackId.asStateFlow()

    private val _pendingSong = MutableStateFlow<Song?>(null)
    val pendingSong: StateFlow<Song?> = _pendingSong.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _showUrlDialog = MutableStateFlow(false)
    val showUrlDialog: StateFlow<Boolean> = _showUrlDialog.asStateFlow()

    private val _isUrlProcessing = MutableStateFlow(false)
    val isUrlProcessing: StateFlow<Boolean> = _isUrlProcessing.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    private var observeJob: Job? = null

    init {
        observePlaylists()
        observeSavedPlaylists()
    }

    fun retry() {
        observePlaylists()
        observeSavedPlaylists()
    }

    fun openPlaylist(playlistId: String) {
        _selectedPlaylistId.value = playlistId
        val playlist = _uiState.value.playlists.find { it.id == playlistId }
        _selectedTrackId.value = playlist?.tracks?.firstOrNull()?.id
    }

    fun closePlaylist() {
        _selectedPlaylistId.value = null
        _selectedTrackId.value = null
    }

    fun selectTrack(songId: String) {
        _selectedTrackId.value = songId
    }

    fun openSavedPlaylist(savedPlaylistId: String) {
        _selectedSavedId.value = savedPlaylistId
        val saved = _savedPlaylists.value.find { it.playlist.id == savedPlaylistId }
        _selectedSavedTrackId.value = saved?.playlist?.tracks?.firstOrNull()?.id
    }

    fun closeSavedPlaylist() {
        _selectedSavedId.value = null
        _selectedSavedTrackId.value = null
    }

    fun selectSavedTrack(songId: String) {
        _selectedSavedTrackId.value = songId
    }

    fun unsavePlaylist(savedPlaylistId: String) {
        viewModelScope.launch {
            runCatching {
                val myUid = authRepository.currentUser()?.uid ?: error("Sesión no iniciada")
                savedCollectionsRepository.removeCollection(myUid, savedPlaylistId)
            }.onSuccess {
                if (_selectedSavedId.value == savedPlaylistId) {
                    closeSavedPlaylist()
                }
            }
        }
    }

    fun openAddSongDialog(song: Song) {
        _pendingSong.value = song
    }

    fun dismissAddSongDialog() {
        _pendingSong.value = null
    }

    fun openCreateDialog() {
        _showCreateDialog.value = true
    }

    fun dismissCreateDialog() {
        _showCreateDialog.value = false
    }

    fun openUrlDialog() {
        _urlError.value = null
        _showUrlDialog.value = true
    }

    fun dismissUrlDialog() {
        _showUrlDialog.value = false
    }

    fun createPlaylist(title: String, description: String, isPublic: Boolean) {
        viewModelScope.launch {
            runCatching {
                val ownerId = authRepository.currentUser()?.uid
                    ?: error("Sesión no iniciada")
                val newId = playlistRepository.createPlaylist(ownerId, title, description, isPublic)
                _pendingSong.value?.let { song ->
                    playlistRepository.addTrack(newId, song)
                }
            }.onFailure { exception ->
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "No se pudo crear la lista",
                )
            }
            _showCreateDialog.value = false
            _pendingSong.value = null
        }
    }

    fun addPendingSongToPlaylist(playlistId: String) {
        val song = _pendingSong.value ?: return
        viewModelScope.launch {
            runCatching { playlistRepository.addTrack(playlistId, song) }
                .onSuccess { _pendingSong.value = null }
                .onFailure { exception ->
                    _uiState.value = _uiState.value.copy(
                        error = exception.message ?: "No se pudo añadir la canción",
                    )
                }
        }
    }

    fun processUrl(url: String) {
        viewModelScope.launch {
            _isUrlProcessing.value = true
            _urlError.value = null
            val videoId = YouTubeLinkParser.extractVideoId(url)
            if (videoId == null) {
                _isUrlProcessing.value = false
                _urlError.value = "La URL no es un enlace de YouTube válido"
                return@launch
            }
            val song = YouTubeLinkParser.fetchVideoInfo(videoId)
                ?: Song(
                    id = videoId,
                    youtubeId = videoId,
                    title = "Vídeo de YouTube",
                    artist = "YouTube",
                    durationSeconds = 0L,
                )
            _isUrlProcessing.value = false
            _showUrlDialog.value = false
            _pendingSong.value = song
        }
    }

    fun removeTrack(playlistId: String, songId: String) {
        viewModelScope.launch {
            runCatching { playlistRepository.removeTrack(playlistId, songId) }
                .onSuccess {
                    if (_selectedTrackId.value == songId) {
                        val playlist = _uiState.value.playlists.find { it.id == playlistId }
                        _selectedTrackId.value = playlist?.tracks
                            ?.firstOrNull { it.id != songId }
                            ?.id
                    }
                }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            runCatching { playlistRepository.deletePlaylist(playlistId) }
                .onSuccess {
                    if (_selectedPlaylistId.value == playlistId) {
                        closePlaylist()
                    }
                }
        }
    }

    private fun observePlaylists() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                authRepository.authState()
                    .mapNotNull { it?.uid }
                    .distinctUntilChanged()
                    .flatMapLatest { uid -> playlistRepository.observeUserPlaylists(uid) }
                    .collect { playlists ->
                        _uiState.value = PlaylistsUiState(playlists = playlists)
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.value = PlaylistsUiState(
                    isLoading = false,
                    error = exception.message ?: "No se pudieron cargar las listas",
                )
            }
        }
    }

    private fun observeSavedPlaylists() {
        viewModelScope.launch {
            try {
                authRepository.authState()
                    .mapNotNull { it?.uid }
                    .distinctUntilChanged()
                    .flatMapLatest { uid ->
                        savedCollectionsRepository.observeSavedCollections(uid)
                            .flatMapLatest { collections -> savedPlaylistsFlow(collections) }
                    }
                    .collect { saved -> _savedPlaylists.value = saved }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // Las colecciones guardadas son complementarias; se ignora el error de observación.
            }
        }
    }

    private fun savedPlaylistsFlow(collections: List<SavedCollection>) = if (collections.isEmpty()) {
        flowOf(emptyList())
    } else {
        val flows = collections.map { collection ->
            playlistRepository.observePublicPlaylist(collection.playlistId)
                .map { playlist ->
                    if (playlist == null) {
                        null
                    } else {
                        SavedPlaylist(
                            playlist = playlist,
                            ownerDisplayName = collection.ownerDisplayName,
                            ownerPhotoUrl = collection.ownerPhotoUrl,
                            savedAt = collection.savedAt,
                        )
                    }
                }
        }
        combine(flows) { savedList -> savedList.filterNotNull() }
    }
}
