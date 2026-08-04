package com.cuetotech.vibetube.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.ProfileStorageRepository
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.UserProfile
import com.cuetotech.vibetube.data.UserProfileRepository
import com.cuetotech.vibetube.data.YouTubeSearchRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Success(val profile: UserProfile) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<Song> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class HomeViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val profileRepository: UserProfileRepository = UserProfileRepository(),
    private val searchRepository: YouTubeSearchRepository = YouTubeSearchRepository(),
    private val storageRepository: ProfileStorageRepository = ProfileStorageRepository(),
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _isUploadingAvatar = MutableStateFlow(false)
    val isUploadingAvatar: StateFlow<Boolean> = _isUploadingAvatar.asStateFlow()

    private val _isUploadingBanner = MutableStateFlow(false)
    val isUploadingBanner: StateFlow<Boolean> = _isUploadingBanner.asStateFlow()

    // Error de subida de imagen (evento one-shot: se muestra con Toast y se
    // limpia con clearUploadError()).
    private val _uploadError = MutableStateFlow<String?>(null)
    val uploadError: StateFlow<String?> = _uploadError.asStateFlow()

    private val _query = MutableStateFlow("")

    init {
        observeProfile()
        observeSearch()
    }

    fun retryProfile() {
        _profileState.value = ProfileUiState.Loading
        observeProfile()
    }

    fun retrySearch() {
        viewModelScope.launch {
            searchFlow(_query.value).collect {}
        }
    }

    fun onQueryChange(value: String) {
        _searchState.update { it.copy(query = value) }
        _query.value = value
    }

    fun signOut() {
        authRepository.signOut()
    }

    // Sube una nueva foto de avatar a Firebase Storage, guarda la URL en el
    // perfil (avatarUrl + photoUrl para que se propague a amigos/perfiles
    // públicos) y actualiza el estado local.
    fun uploadAvatar(uri: Uri) {
        val uid = authRepository.currentUser()?.uid ?: return
        viewModelScope.launch {
            _isUploadingAvatar.value = true
            _uploadError.value = null
            runCatching {
                val url = storageRepository.uploadAvatar(uid, uri)
                val current = (profileState.value as? ProfileUiState.Success)?.profile
                    ?: UserProfile(
                        uid = uid,
                        displayName = "Usuario",
                        email = authRepository.currentUser()?.email.orEmpty(),
                    )
                val updated = current.copy(avatarUrl = url, photoUrl = url)
                profileRepository.saveUserProfile(updated)
                updated
            }.onSuccess { updated ->
                _profileState.value = ProfileUiState.Success(updated)
            }.onFailure { exception ->
                _uploadError.value = exception.message ?: "No se pudo subir la imagen"
            }
            _isUploadingAvatar.value = false
        }
    }

    // Sube una nueva imagen de portada (banner) y guarda su URL en el perfil.
    fun uploadBanner(uri: Uri) {
        val uid = authRepository.currentUser()?.uid ?: return
        viewModelScope.launch {
            _isUploadingBanner.value = true
            _uploadError.value = null
            runCatching {
                val url = storageRepository.uploadBanner(uid, uri)
                val current = (profileState.value as? ProfileUiState.Success)?.profile
                    ?: UserProfile(
                        uid = uid,
                        displayName = "Usuario",
                        email = authRepository.currentUser()?.email.orEmpty(),
                    )
                val updated = current.copy(bannerUrl = url)
                profileRepository.saveUserProfile(updated)
                updated
            }.onSuccess { updated ->
                _profileState.value = ProfileUiState.Success(updated)
            }.onFailure { exception ->
                _uploadError.value = exception.message ?: "No se pudo subir la imagen"
            }
            _isUploadingBanner.value = false
        }
    }

    fun clearUploadError() {
        _uploadError.value = null
    }

    private fun observeProfile() {
        viewModelScope.launch {
            authRepository.authState()
                .mapNotNull { it?.uid }
                .distinctUntilChanged()
                .flatMapLatest { uid -> profileFlow(uid) }
                .catch { exception ->
                    _profileState.value = ProfileUiState.Error(
                        exception.message ?: "No se pudo cargar el perfil",
                    )
                }
                .collect { profile ->
                    _profileState.value = ProfileUiState.Success(profile)
                }
        }
    }

    private fun profileFlow(uid: String): Flow<UserProfile> = flow {
        val existing = profileRepository.getUserProfile(uid)
        val profile = existing ?: UserProfile(
            uid = uid,
            displayName = "Usuario",
            email = authRepository.currentUser()?.email ?: "",
        ).also { profileRepository.saveUserProfile(it) }
        emit(profile)
    }

    private fun observeSearch() {
        viewModelScope.launch {
            _query
                .debounce(350)
                .flatMapLatest { query -> searchFlow(query) }
                .collect {}
        }
    }

    private fun searchFlow(query: String): Flow<Unit> = flow {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchState.value = SearchUiState(query = _query.value)
            return@flow
        }
        _searchState.value = SearchUiState(query = _query.value, isLoading = true)
        runCatching { searchRepository.search(trimmed) }
            .onSuccess { results ->
                _searchState.value = SearchUiState(query = _query.value, results = results)
            }
            .onFailure { exception ->
                _searchState.value = SearchUiState(
                    query = _query.value,
                    error = exception.message ?: "No se pudieron obtener resultados",
                )
            }
        emit(Unit)
    }
}
