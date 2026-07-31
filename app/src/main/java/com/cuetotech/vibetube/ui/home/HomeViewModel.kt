package com.cuetotech.vibetube.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
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
) : ViewModel() {

    private val _profileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

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
