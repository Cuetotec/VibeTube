package com.cuetotech.vibetube.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.ProfileMediaRepository
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
    private val mediaRepository: ProfileMediaRepository,
) : ViewModel() {

    // Fábrica explícita: AndroidViewModelFactory NO puede instanciar este
    // ViewModel porque su constructor no tiene un solo parámetro Application
    // (Kotlin solo genera el constructor reducido público cuando TODOS los
    // parámetros tienen valor por defecto). Se pasa manualmente al crear el
    // ViewModel en la pantalla: viewModel(factory = HomeViewModel.factory(app)).
    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    HomeViewModel(
                        authRepository = AuthRepository(),
                        profileRepository = UserProfileRepository(),
                        searchRepository = YouTubeSearchRepository(),
                        mediaRepository = ProfileMediaRepository(application),
                    )
                }
            }
    }

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

    // Copia la nueva foto de avatar desde la galería local a la carpeta privada
    // de la app (filesDir, "avatar_{uid}.jpg"), guarda su ruta "file:///..." en
    // el perfil (avatarUrl + photoUrl para que se propague a amigos/perfiles
    // públicos) y actualiza el estado local. Sin Firebase Storage.
    fun uploadAvatar(uri: Uri) {
        val uid = authRepository.currentUser()?.uid ?: return
        viewModelScope.launch {
            _isUploadingAvatar.value = true
            _uploadError.value = null
            runCatching {
                val path = mediaRepository.copyImageToPrivateStorage(uri, "avatar_$uid.jpg")
                val current = (profileState.value as? ProfileUiState.Success)?.profile
                    ?: UserProfile(
                        uid = uid,
                        displayName = "Usuario",
                        email = authRepository.currentUser()?.email.orEmpty(),
                    )
                val previous = current.avatarUrl
                val updated = current.copy(avatarUrl = path, photoUrl = path)
                profileRepository.saveUserProfile(updated)
                mediaRepository.deleteLocalImage(previous)
                updated
            }.onSuccess { updated ->
                _profileState.value = ProfileUiState.Success(updated)
            }.onFailure { exception ->
                _uploadError.value = exception.message ?: "No se pudo guardar la imagen"
            }
            _isUploadingAvatar.value = false
        }
    }

    // Copia la nueva imagen de fondo (portada) desde la galería local a la
    // carpeta privada de la app (filesDir, "banner_{uid}.jpg") y guarda su ruta
    // "file:///..." en el perfil. Sin Firebase Storage.
    fun uploadBanner(uri: Uri) {
        val uid = authRepository.currentUser()?.uid ?: return
        viewModelScope.launch {
            _isUploadingBanner.value = true
            _uploadError.value = null
            runCatching {
                val path = mediaRepository.copyImageToPrivateStorage(uri, "banner_$uid.jpg")
                val current = (profileState.value as? ProfileUiState.Success)?.profile
                    ?: UserProfile(
                        uid = uid,
                        displayName = "Usuario",
                        email = authRepository.currentUser()?.email.orEmpty(),
                    )
                val previous = current.bannerUrl
                val updated = current.copy(bannerUrl = path)
                profileRepository.saveUserProfile(updated)
                mediaRepository.deleteLocalImage(previous)
                updated
            }.onSuccess { updated ->
                _profileState.value = ProfileUiState.Success(updated)
            }.onFailure { exception ->
                _uploadError.value = exception.message ?: "No se pudo guardar la imagen"
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

    // Carga el perfil del usuario desde Firestore de forma defensiva: si la
    // lectura falla (permisos, red o campos de imagen avatarUrl/bannerUrl
    // ausentes o inválidos), se continúa con un perfil por defecto SIN imágenes,
    // para que el inicio de sesión complete siempre el flujo hacia la pantalla
    // principal. Las rutas locales "file://..." que ya no existen en disco se
    // descartan (null) para que la UI muestre la imagen por defecto. La
    // escritura del perfil por defecto también es tolerante a fallos.
    private fun profileFlow(uid: String): Flow<UserProfile> = flow {
        val existing = runCatching { profileRepository.getUserProfile(uid) }.getOrNull()
        val profile = existing
            ?.copy(
                avatarUrl = mediaRepository.existingLocalImage(existing.avatarUrl),
                bannerUrl = mediaRepository.existingLocalImage(existing.bannerUrl),
                photoUrl = mediaRepository.existingLocalImage(existing.photoUrl),
            )
            ?: UserProfile(
                uid = uid,
                displayName = authRepository.currentUser()?.displayName ?: "Usuario",
                email = authRepository.currentUser()?.email ?: "",
            )
        if (existing == null) {
            runCatching { profileRepository.saveUserProfile(profile) }
        }
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
