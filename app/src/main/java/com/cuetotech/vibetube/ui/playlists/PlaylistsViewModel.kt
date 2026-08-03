package com.cuetotech.vibetube.ui.playlists

import android.util.Log
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

private const val TAG = "VibeTubePlayer"

// Devuelve el id de la siguiente canción según el índice actual (index + 1).
// Decisión de fin de lista: en la última canción se reinicia desde la 0 (bucle),
// para que el autoplay nunca se detenga en pantalla negra.
internal fun nextTrackId(currentTrackId: String?, tracks: List<Song>): String? {
    if (tracks.isEmpty()) return null
    val index = tracks.indexOfFirst { it.id == currentTrackId }
    if (index < 0) return null
    return tracks[(index + 1) % tracks.size].id
}

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

    private val _savedPlaylists = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val savedPlaylists: StateFlow<List<SavedPlaylist>> = _savedPlaylists.asStateFlow()

    private val _selectedSavedId = MutableStateFlow<String?>(null)
    val selectedSavedId: StateFlow<String?> = _selectedSavedId.asStateFlow()

    private val _selectedSavedTrackId = MutableStateFlow<String?>(null)

    // Canción actualmente en reproducción, derivada de la selección activa
    // (lista propia o guardada) y de las tracks que el ViewModel mantiene
    // durante toda la sesión. playNextTrack()/playNextSavedTrack() avanzan el
    // índice (currentIndex + 1) sobre la lista en memoria y actualizan la
    // selección; currentSong se actualiza automáticamente con el objeto de la
    // siguiente canción (bucle: al final reinicia desde la 0).
    val currentSong: StateFlow<Song?> = combine(
        combine(_uiState, _selectedPlaylistId, _selectedTrackId) { ui, playlistId, trackId ->
            ui.playlists.find { it.id == playlistId }?.tracks?.find { it.id == trackId }
        },
        combine(_savedPlaylists, _selectedSavedId, _selectedSavedTrackId) { saved, savedId, savedTrackId ->
            saved.find { it.playlist.id == savedId }?.playlist?.tracks?.find { it.id == savedTrackId }
        },
    ) { own, saved -> own ?: saved }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _editingPlaylist = MutableStateFlow<Playlist?>(null)
    val editingPlaylist: StateFlow<Playlist?> = _editingPlaylist.asStateFlow()

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

    // Mensaje de error para Toast directo en pantalla (evento one-shot: se
    // muestra una vez y MainActivity lo limpia con clearToastMessage()).
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var observeJob: Job? = null

    // Última lista confirmada por Firestore (sin el overlay optimista).
    private var serverPlaylists: List<Playlist> = emptyList()

    // Canciones añadidas localmente y aún sin confirmar por un snapshot. Se
    // fusionan con los datos del servidor para que un snapshot desactualizado
    // no revierta la lista recién modificada (2 -> 3 -> 2).
    private val pendingTrackAdds = mutableMapOf<String, MutableList<Song>>()

    init {
        observePlaylists()
        observeSavedPlaylists()
    }

    private fun mergePendingTrackAdds(playlists: List<Playlist>): List<Playlist> {
        if (pendingTrackAdds.isEmpty()) return playlists
        return playlists.map { playlist ->
            val pending = pendingTrackAdds[playlist.id].orEmpty()
            if (pending.isEmpty()) {
                playlist
            } else {
                playlist.copy(
                    tracks = playlist.tracks + pending.filter { song ->
                        playlist.tracks.none { it.id == song.id }
                    },
                )
            }
        }
    }

    private fun confirmPendingTrackAdds(playlists: List<Playlist>) {
        pendingTrackAdds.forEach { (playlistId, pending) ->
            val server = playlists.find { it.id == playlistId }
            pending.removeAll { song ->
                server?.tracks?.any { it.id == song.id } == true
            }
        }
        pendingTrackAdds.entries.removeIf { it.value.isEmpty() }
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

    fun playNextTrack() {
        val playlist = _uiState.value.playlists.find { it.id == _selectedPlaylistId.value }
            ?: run {
                Log.w(TAG, "playNextTrack: no hay lista seleccionada, no se avanza")
                return
            }
        val tracks = playlist.tracks
        val currentId = _selectedTrackId.value
        val currentIndex = tracks.indexOfFirst { it.id == currentId }
        val nextId = nextTrackId(currentId, tracks)
        Log.d(
            TAG,
            "playNextTrack: índice actual=$currentIndex (canción=$currentId) -> " +
                "siguiente=${nextId ?: "ninguna"} de ${tracks.size} canciones en memoria",
        )
        if (nextId != null) {
            _selectedTrackId.value = nextId
            // currentSong es derivado de la selección y se actualiza
            // automáticamente con el objeto de la siguiente canción.
            Log.d(TAG, "playNextTrack: canción activa avanzada a $nextId")
        }
    }

    fun playNextSavedTrack() {
        val saved = _savedPlaylists.value.find { it.playlist.id == _selectedSavedId.value }
            ?: run {
                Log.w(TAG, "playNextSavedTrack: no hay lista guardada seleccionada, no se avanza")
                return
            }
        val tracks = saved.playlist.tracks
        val currentId = _selectedSavedTrackId.value
        val currentIndex = tracks.indexOfFirst { it.id == currentId }
        val nextId = nextTrackId(currentId, tracks)
        Log.d(
            TAG,
            "playNextSavedTrack: índice actual=$currentIndex (canción=$currentId) -> " +
                "siguiente=${nextId ?: "ninguna"} de ${tracks.size} canciones en memoria",
        )
        if (nextId != null) {
            _selectedSavedTrackId.value = nextId
            // currentSong es derivado de la selección y se actualiza
            // automáticamente con el objeto de la siguiente canción.
            Log.d(TAG, "playNextSavedTrack: canción activa avanzada a $nextId")
        }
    }

    fun openEditDialog(playlist: Playlist) {
        _editingPlaylist.value = playlist
    }

    fun dismissEditDialog() {
        _editingPlaylist.value = null
    }

    fun updatePlaylist(title: String, description: String, isPublic: Boolean) {
        val playlist = _editingPlaylist.value ?: return
        viewModelScope.launch {
            runCatching {
                playlistRepository.updatePlaylist(playlist.id, title, description, isPublic)
            }.onFailure { exception ->
                _uiState.value = _uiState.value.copy(
                    error = exception.message ?: "No se pudo guardar la lista",
                )
            }
            _editingPlaylist.value = null
        }
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
                _toastMessage.value = "Error: ${exception.message ?: "No se pudo crear la lista"}"
            }
            _showCreateDialog.value = false
            _pendingSong.value = null
        }
    }

    fun addPendingSongToPlaylist(playlistId: String) {
        val song = _pendingSong.value ?: return
        val alreadyPresent = serverPlaylists
            .find { it.id == playlistId }
            ?.tracks
            ?.any { it.id == song.id } == true
        if (!alreadyPresent) {
            pendingTrackAdds.getOrPut(playlistId) { mutableListOf() }.add(song)
            _uiState.value = _uiState.value.copy(playlists = mergePendingTrackAdds(serverPlaylists))
        }
        viewModelScope.launch {
            runCatching { playlistRepository.addTrack(playlistId, song) }
                .onSuccess { _pendingSong.value = null }
                .onFailure { exception ->
                    pendingTrackAdds[playlistId]?.remove(song)
                    pendingTrackAdds.entries.removeIf { it.value.isEmpty() }
                    _uiState.value = _uiState.value.copy(
                        playlists = mergePendingTrackAdds(serverPlaylists),
                        error = exception.message ?: "No se pudo añadir la canción",
                    )
                    _toastMessage.value = "Error: ${exception.message ?: "No se pudo añadir la canción"}"
                }
        }
    }

    fun clearToastMessage() {
        _toastMessage.value = null
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
                        serverPlaylists = playlists
                        confirmPendingTrackAdds(playlists)
                        _uiState.value = PlaylistsUiState(
                            playlists = mergePendingTrackAdds(playlists),
                        )
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
