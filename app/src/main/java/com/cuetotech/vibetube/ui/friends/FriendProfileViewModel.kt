package com.cuetotech.vibetube.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.Playlist
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.SavedCollection
import com.cuetotech.vibetube.data.SavedCollectionsRepository
import com.cuetotech.vibetube.data.UserProfile
import com.cuetotech.vibetube.data.UserProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val publicPlaylists: List<Playlist> = emptyList(),
    val savedPlaylistIds: Set<String> = emptySet(),
    val error: String? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class FriendProfileViewModel(
    private val friendUid: String,
    private val authRepository: AuthRepository = AuthRepository(),
    private val profileRepository: UserProfileRepository = UserProfileRepository(),
    private val playlistRepository: PlaylistRepository = PlaylistRepository(),
    private val savedCollectionsRepository: SavedCollectionsRepository = SavedCollectionsRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendProfileUiState())
    val uiState: StateFlow<FriendProfileUiState> = _uiState.asStateFlow()

    init {
        observeProfile()
        observePlaylists()
        observeSaved()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun retry() {
        observeProfile()
        observePlaylists()
    }

    fun savePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            runCatching {
                val meUid = authRepository.currentUser()?.uid ?: error("Sesión no iniciada")
                val profile = _uiState.value.profile
                savedCollectionsRepository.saveCollection(
                    meUid,
                    SavedCollection(
                        playlistId = playlist.id,
                        ownerId = friendUid,
                        ownerDisplayName = profile?.displayName ?: friendUid,
                        ownerPhotoUrl = profile?.photoUrl,
                    ),
                )
            }.onSuccess {
                _uiState.update { it.copy(message = "Lista guardada en Mis Colecciones") }
            }.onFailure { exception ->
                _uiState.update { it.copy(message = exception.message ?: "No se pudo guardar la lista") }
            }
        }
    }

    fun unsavePlaylist(playlistId: String) {
        viewModelScope.launch {
            runCatching {
                val meUid = authRepository.currentUser()?.uid ?: error("Sesión no iniciada")
                savedCollectionsRepository.removeCollection(meUid, playlistId)
            }.onFailure { exception ->
                _uiState.update { it.copy(message = exception.message ?: "No se pudo quitar la lista") }
            }
        }
    }

    private fun observeProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val profile = profileRepository.getUserProfile(friendUid)
                _uiState.update { it.copy(profile = profile, isLoading = false) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = exception.message ?: "No se pudo cargar el perfil")
                }
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            try {
                playlistRepository.observePublicPlaylists(friendUid)
                    .collect { playlists ->
                        _uiState.update { it.copy(publicPlaylists = playlists) }
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(error = exception.message) }
            }
        }
    }

    private fun observeSaved() {
        viewModelScope.launch {
            try {
                authRepository.authState()
                    .mapNotNull { it?.uid }
                    .distinctUntilChanged()
                    .flatMapLatest { meUid ->
                        savedCollectionsRepository.observeSavedCollections(meUid)
                            .map { collections -> collections.map { it.playlistId }.toSet() }
                    }
                    .collect { ids -> _uiState.update { it.copy(savedPlaylistIds = ids) } }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update { it.copy(error = exception.message) }
            }
        }
    }
}
