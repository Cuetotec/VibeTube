package com.cuetotech.vibetube.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeLinkParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val playlistRepository: PlaylistRepository = PlaylistRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistsUiState(isLoading = true))
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private val _selectedPlaylistId = MutableStateFlow<String?>(null)
    val selectedPlaylistId: StateFlow<String?> = _selectedPlaylistId.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<String?>(null)
    val selectedTrackId: StateFlow<String?> = _selectedTrackId.asStateFlow()

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
    }

    fun retry() {
        observePlaylists()
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
}
