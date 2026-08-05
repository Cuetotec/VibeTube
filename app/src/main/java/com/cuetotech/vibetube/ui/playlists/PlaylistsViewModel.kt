package com.cuetotech.vibetube.ui.playlists

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.SavedCollection
import com.cuetotech.vibetube.data.SavedCollectionsRepository
import com.cuetotech.vibetube.data.SavedPlaylist
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeLinkParser
import com.cuetotech.vibetube.player.PlaybackController
import com.cuetotech.vibetube.player.PlaybackHandoff
import com.cuetotech.vibetube.player.WebPlayerControlHandle
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

// Modo de repetición del reproductor: OFF (sin repetición), ALL (repetición de
// toda la lista) y ONE (repetición de la canción actual).
enum class RepeatMode { OFF, ALL, ONE }

// Devuelve el id de la siguiente canción según la combinación activa de
// RepeatMode e isShuffleEnabled:
//  - RepeatMode.ONE: mantiene la canción actual (el reproductor la reinicia).
//  - Shuffle activo: avanza por el orden permutado (shuffleOrder) sin repetir
//    canciones hasta agotar la lista.
//  - RepeatMode.OFF: en la última canción no avanza más (null).
//  - RepeatMode.ALL: en la última canción vuelve al inicio (0 o inicio del
//    orden aleatorio).
internal fun nextTrack(
    currentTrackId: String?,
    tracks: List<Song>,
    repeatMode: RepeatMode,
    isShuffleEnabled: Boolean,
    shuffleOrder: List<String>,
): String? {
    if (tracks.isEmpty() || currentTrackId == null) return null
    if (repeatMode == RepeatMode.ONE) return currentTrackId
    val size = tracks.size
    if (isShuffleEnabled && shuffleOrder.isNotEmpty()) {
        val position = shuffleOrder.indexOf(currentTrackId)
        if (position >= 0) {
            val nextPosition = position + 1
            return when {
                nextPosition < size -> shuffleOrder[nextPosition]
                repeatMode == RepeatMode.ALL -> shuffleOrder[0]
                else -> null
            }
        }
    }
    val index = tracks.indexOfFirst { it.id == currentTrackId }
    if (index < 0) return null
    val nextIndex = index + 1
    return when {
        nextIndex < size -> tracks[nextIndex].id
        repeatMode == RepeatMode.ALL -> tracks[0].id
        else -> null
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class PlaylistsViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val authRepository = AuthRepository()
    private val playlistRepository = PlaylistRepository()
    private val savedCollectionsRepository = SavedCollectionsRepository()

    // Controlador de la reproducción en segundo plano (MediaController conectado
    // al PlaybackService). El audio real se reproduce en el servicio (ExoPlayer)
    // para que siga sonando con la pantalla apagada; el reproductor WebView solo
    // muestra el vídeo (silenciado mientras el servicio reproduce la pista).
    private val playbackController = PlaybackController(application.applicationContext)

    // Coordinador del handoff entre el WebView (primer plano) y el servicio
    // (segundo plano): observa pantalla/proceso y conmuta el audio entre ambos.
    private val playbackHandoff = PlaybackHandoff(
        appContext = application.applicationContext,
        playbackController = playbackController,
        scope = viewModelScope,
    )

    /** true cuando el servicio está reproduciendo la lista actual. */
    val backgroundAudioActive: StateFlow<Boolean> = playbackController.isActive

    /** true cuando la pantalla está encendida y la app en primer plano (controla el handoff). */
    val isForeground: StateFlow<Boolean> = playbackHandoff.isForeground

    /** Control del reproductor WebView para el handoff (lo registra YouTubePlayerView). */
    val webPlayerControl: WebPlayerControlHandle = playbackHandoff.webPlayer

    // Clave de la "sesión de reproducción" ya sincronizada con el servicio:
    // lista seleccionada + orden activo. Si coincide, un cambio de pista solo
    // hace seekTo; si cambia, se re-envía la lista completa.
    private var syncedPlaylistKey: String? = null

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

    // Estado del modo de reproducción. Con el aleatorio activado se mantiene un
    // orden permutado (shuffleOrder) de las pistas activas que garantiza no
    // repetir ninguna canción hasta haber escuchado todas.
    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // Token de reproducción: se incrementa cuando la canción actual debe
    // reiniciarse (RepeatMode.ONE). El reproductor observa este valor para
    // volver a ejecutar loadVideoById sobre la misma pista.
    private val _playbackTick = MutableStateFlow(0)
    val playbackTick: StateFlow<Int> = _playbackTick.asStateFlow()

    // Orden aleatorio vigente: ids de las pistas activas permutados, con la
    // canción actual en primera posición.
    private var shuffleOrder: List<String> = emptyList()

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
        playbackHandoff.start()
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
        // No se selecciona ni se reproduce ninguna canción al abrir la lista: la
        // reproducción solo comienza con una acción explícita del usuario
        // (tocar una canción o pulsar Play). currentSong se queda en null.
        _selectedTrackId.value = null
        rebuildShuffleOrder()
    }

    fun closePlaylist() {
        _selectedPlaylistId.value = null
        _selectedTrackId.value = null
    }

    fun selectTrack(songId: String) {
        _selectedTrackId.value = songId
        rebuildShuffleOrder()
        syncBackgroundPlayback()
    }

    fun openSavedPlaylist(savedPlaylistId: String) {
        _selectedSavedId.value = savedPlaylistId
        // Igual que openPlaylist: sin reproducción automática de la primera
        // canción; el usuario debe elegir o pulsar Play.
        _selectedSavedTrackId.value = null
        rebuildShuffleOrder()
    }

    fun closeSavedPlaylist() {
        _selectedSavedId.value = null
        _selectedSavedTrackId.value = null
    }

    fun selectSavedTrack(songId: String) {
        _selectedSavedTrackId.value = songId
        rebuildShuffleOrder()
        syncBackgroundPlayback()
    }

    // Inicia la reproducción desde el principio de la lista propia seleccionada:
    // la primera canción (secuencial) o una al azar si el shuffle está activado.
    fun startPlayback() {
        val playlist = _uiState.value.playlists.find { it.id == _selectedPlaylistId.value }
            ?: return
        val tracks = playlist.tracks
        if (tracks.isEmpty()) return
        if (_isShuffleEnabled.value) {
            rebuildShuffleOrder()
            _selectedTrackId.value = shuffleOrder.first()
        } else {
            _selectedTrackId.value = tracks.first().id
        }
        syncBackgroundPlayback()
    }

    // Versión de startPlayback para listas guardadas.
    fun startSavedPlayback() {
        val saved = _savedPlaylists.value.find { it.playlist.id == _selectedSavedId.value }
            ?: return
        val tracks = saved.playlist.tracks
        if (tracks.isEmpty()) return
        if (_isShuffleEnabled.value) {
            rebuildShuffleOrder()
            _selectedSavedTrackId.value = shuffleOrder.first()
        } else {
            _selectedSavedTrackId.value = tracks.first().id
        }
        syncBackgroundPlayback()
    }

    // Alterna el modo aleatorio. Al activarlo se genera un nuevo orden permutado
    // de las pistas activas; al desactivarlo se descarta el orden.
    fun toggleShuffle() {
        _isShuffleEnabled.value = !_isShuffleEnabled.value
        if (_isShuffleEnabled.value) {
            rebuildShuffleOrder()
        } else {
            shuffleOrder = emptyList()
        }
        // El orden activo cambió: se re-envía la lista al servicio (o se salta a
        // la pista actual si aún no había sesión sincronizada).
        syncBackgroundPlayback()
    }

    // Cicla el modo de repetición: OFF -> ALL -> ONE -> OFF.
    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        val mediaRepeat = repeatModeToMedia(_repeatMode.value)
        viewModelScope.launch { playbackController.setRepeatMode(mediaRepeat) }
    }

    // Reconstruye el orden aleatorio sobre las pistas activas (lista propia o
    // guardada seleccionada). La canción actual queda en primera posición para
    // continuar desde la pista elegida; el resto se permuta sin repeticiones.
    private fun rebuildShuffleOrder() {
        if (!_isShuffleEnabled.value) return
        val currentId = when {
            _selectedPlaylistId.value != null -> _selectedTrackId.value
            else -> _selectedSavedTrackId.value
        }
        val ids = activeTracks().map { it.id }.distinct()
        shuffleOrder = listOfNotNull(currentId) + ids.filterNot { it == currentId }.shuffled()
    }

    private fun activeTracks(): List<Song> {
        val playlistId = _selectedPlaylistId.value
        if (playlistId != null) {
            return _uiState.value.playlists.find { it.id == playlistId }?.tracks.orEmpty()
        }
        val savedId = _selectedSavedId.value
        if (savedId != null) {
            return _savedPlaylists.value.find { it.playlist.id == savedId }?.playlist?.tracks.orEmpty()
        }
        return emptyList()
    }

    // Pistas activas en el ORDEN en que se deben reproducir: si el shuffle está
    // activado se usa el orden permutado (shuffleOrder), si no, el orden de la
    // lista. Este mismo orden es el que se envía al servicio de reproducción en
    // segundo plano, para que ambos índices coincidan.
    private fun orderedActiveTracks(): List<Song> {
        val tracks = activeTracks()
        if (_isShuffleEnabled.value && shuffleOrder.isNotEmpty()) {
            val byId = tracks.associateBy { it.id }
            return shuffleOrder.mapNotNull { byId[it] }
        }
        return tracks
    }

    // Identifica la sesión de reproducción ya sincronizada con el servicio:
    // lista seleccionada + orden activo. Si no cambia, un cambio de pista solo
    // requiere un seekTo; si cambia, hay que re-enviar la lista completa.
    private fun playbackSessionKey(tracks: List<Song>): String {
        val listId = _selectedPlaylistId.value ?: _selectedSavedId.value ?: return ""
        return "$listId|" + tracks.joinToString(",") { it.id }
    }

    private fun repeatModeToMedia(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    }

    // Mantiene el servicio de reproducción en segundo plano sincronizado con la
    // pista activa del ViewModel: si la sesión (lista + orden) ya se sincronizó,
    // solo se salta a la pista nueva; si no, se envía la lista completa con los
    // metadatos y se empieza a reproducir desde la pista actual.
    private fun syncBackgroundPlayback() {
        val selectedId = when {
            _selectedPlaylistId.value != null -> _selectedTrackId.value
            else -> _selectedSavedTrackId.value
        } ?: return
        val tracks = orderedActiveTracks()
        val index = tracks.indexOfFirst { it.id == selectedId }
        if (index < 0) return
        val key = playbackSessionKey(tracks)
        viewModelScope.launch {
            // En primer plano el audio lo aporta el WebView: el servicio se
            // prepara pero NO reproduce (startPlaying=false), evitando que el
            // ExoPlayer arrebate el foco de audio y ponga en pausa el WebView.
            val startPlaying = !playbackHandoff.isForeground.value
            // Arranca el servicio Media3 (PlaybackService) en cuanto el usuario
            // pulsa reproducir, ANTES de resolver las URLs de audio: si la
            // extracción tardara o fallara, el servicio ya está en marcha y el
            // MediaController conectado.
            if (!playbackController.isActive.value) {
                playbackController.ensureConnected()
            }
            if (syncedPlaylistKey == key && playbackController.isActive.value) {
                playbackController.seekTo(index, play = startPlaying)
                playbackHandoff.onPlaybackSynced()
            } else {
                syncedPlaylistKey = key
                val synced = playbackController.syncPlaylist(
                    tracks = tracks,
                    startIndex = index,
                    repeatMode = repeatModeToMedia(_repeatMode.value),
                    startPlaying = startPlaying,
                )
                // Aplica la política de audio según el estado de la pantalla:
                // en primer plano el servicio se pausa (el sonido lo aporta el
                // WebView); en segundo plano se mantiene/reanuda.
                playbackHandoff.onPlaybackSynced()
                if (!synced) {
                    Log.w(TAG, "No se pudo sincronizar la reproducción en segundo plano")
                    Toast.makeText(
                        getApplication<Application>(),
                        R.string.playback_audio_extraction_failed,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // Aplica el resultado de nextTrack sobre la selección activa:
    //  - nextId == null: fin de lista (RepeatMode.OFF), no se avanza.
    //  - nextId distinta: avanza la selección a la siguiente canción.
    //  - nextId == actual (RepeatMode.ONE): incrementa el token para que el
    //    reproductor reinicie la canción actual.
    private fun advanceSelection(
        nextId: String?,
        currentId: String?,
        label: String,
        onSelect: (String) -> Unit,
    ) {
        when {
            nextId == null -> Log.d(TAG, "$label: fin de lista, no se avanza")
            nextId != currentId -> {
                onSelect(nextId)
                Log.d(TAG, "$label: canción activa avanzada a $nextId")
            }
            else -> {
                _playbackTick.value += 1
                Log.d(TAG, "$label: RepeatMode.ONE, reiniciando canción $nextId")
            }
        }
    }

    fun playNextTrack() {
        val playlist = _uiState.value.playlists.find { it.id == _selectedPlaylistId.value }
            ?: run {
                Log.w(TAG, "playNextTrack: no hay lista seleccionada, no se avanza")
                return
            }
        val tracks = playlist.tracks
        val currentId = _selectedTrackId.value
        val nextId = nextTrack(
            currentId,
            tracks,
            _repeatMode.value,
            _isShuffleEnabled.value,
            shuffleOrder,
        )
        Log.d(
            TAG,
            "playNextTrack: (canción=$currentId) -> siguiente=${nextId ?: "ninguna"} de " +
                "${tracks.size} canciones (repeat=${_repeatMode.value}, " +
                "shuffle=${_isShuffleEnabled.value})",
        )
        // currentSong es derivado de la selección y se actualiza automáticamente
        // con el objeto de la siguiente canción.
        advanceSelection(nextId, currentId, "playNextTrack") { _selectedTrackId.value = it }
        syncBackgroundPlayback()
    }

    fun playNextSavedTrack() {
        val saved = _savedPlaylists.value.find { it.playlist.id == _selectedSavedId.value }
            ?: run {
                Log.w(TAG, "playNextSavedTrack: no hay lista guardada seleccionada, no se avanza")
                return
            }
        val tracks = saved.playlist.tracks
        val currentId = _selectedSavedTrackId.value
        val nextId = nextTrack(
            currentId,
            tracks,
            _repeatMode.value,
            _isShuffleEnabled.value,
            shuffleOrder,
        )
        Log.d(
            TAG,
            "playNextSavedTrack: (canción=$currentId) -> siguiente=${nextId ?: "ninguna"} de " +
                "${tracks.size} canciones (repeat=${_repeatMode.value}, " +
                "shuffle=${_isShuffleEnabled.value})",
        )
        // currentSong es derivado de la selección y se actualiza automáticamente
        // con el objeto de la siguiente canción.
        advanceSelection(nextId, currentId, "playNextSavedTrack") {
            _selectedSavedTrackId.value = it
        }
        syncBackgroundPlayback()
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

    // Procesa un texto con uno o varios enlaces de YouTube y añade las canciones
    // de forma agrupada a la lista indicada. Extrae y valida los IDs, descarta
    // los que ya están en la lista, aplica el overlay optimista (para que un
    // snapshot desactualizado no revierta la escritura) y confirma con un Toast
    // con el número de canciones importadas.
    fun addMultipleTracksByUrls(urlsText: String, playlistId: String) {
        viewModelScope.launch {
            _isUrlProcessing.value = true
            _urlError.value = null
            val videoIds = YouTubeLinkParser.extractVideoIds(urlsText)
            if (videoIds.isEmpty()) {
                _isUrlProcessing.value = false
                _urlError.value = "No se encontraron enlaces de YouTube válidos"
                return@launch
            }
            val songs = videoIds.map { videoId ->
                YouTubeLinkParser.fetchVideoInfo(videoId)
                    ?: Song(
                        id = videoId,
                        youtubeId = videoId,
                        title = "Vídeo de YouTube",
                        artist = "YouTube",
                        durationSeconds = 0L,
                    )
            }
            val playlist = serverPlaylists.find { it.id == playlistId }
            val newSongs = songs.filter { song ->
                playlist?.tracks?.any { it.id == song.id } != true &&
                    pendingTrackAdds[playlistId].orEmpty().none { it.id == song.id }
            }
            if (newSongs.isEmpty()) {
                _isUrlProcessing.value = false
                _urlError.value = "Las canciones ya están en la lista"
                return@launch
            }
            pendingTrackAdds.getOrPut(playlistId) { mutableListOf() }.addAll(newSongs)
            _uiState.value = _uiState.value.copy(playlists = mergePendingTrackAdds(serverPlaylists))
            runCatching { playlistRepository.addMultipleTracks(playlistId, newSongs) }
                .onSuccess {
                    _showUrlDialog.value = false
                    _toastMessage.value = if (newSongs.size == 1) {
                        "Se ha añadido 1 canción a la lista"
                    } else {
                        "Se han añadido ${newSongs.size} canciones a la lista"
                    }
                }
                .onFailure { exception ->
                    pendingTrackAdds[playlistId]?.removeAll(newSongs.toSet())
                    pendingTrackAdds.entries.removeIf { it.value.isEmpty() }
                    _uiState.value = _uiState.value.copy(
                        playlists = mergePendingTrackAdds(serverPlaylists),
                        error = exception.message ?: "No se pudieron añadir las canciones",
                    )
                    _urlError.value = exception.message ?: "No se pudieron añadir las canciones"
                    _toastMessage.value = "Error: ${exception.message ?: "No se pudieron añadir las canciones"}"
                }
            _isUrlProcessing.value = false
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

    override fun onCleared() {
        playbackHandoff.stop()
        playbackController.release()
        super.onCleared()
    }
}
