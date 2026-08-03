package com.cuetotech.vibetube.ui.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.Friend
import com.cuetotech.vibetube.data.FriendshipRepository
import com.cuetotech.vibetube.data.IncomingFriendRequest
import com.cuetotech.vibetube.data.UserProfile
import com.cuetotech.vibetube.data.UserProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FriendsUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<UserProfile> = emptyList(),
    val searchError: String? = null,
    val sentRequestUids: Set<String> = emptySet(),
    val friends: List<Friend> = emptyList(),
    val incomingRequests: List<IncomingFriendRequest> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class FriendsViewModel(
    private val authRepository: AuthRepository = AuthRepository(),
    private val profileRepository: UserProfileRepository = UserProfileRepository(),
    private val friendshipRepository: FriendshipRepository = FriendshipRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(FriendsUiState())
    val uiState: StateFlow<FriendsUiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")

    private var observeJob: Job? = null

    init {
        observeFriendsAndRequests()
        observeSearch()
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value, searchError = null) }
        _query.value = value
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun retry() {
        observeFriendsAndRequests()
    }

    fun sendFriendRequest(target: UserProfile) {
        viewModelScope.launch {
            runCatching {
                val me = myProfile()
                friendshipRepository.sendFriendRequest(me, target)
            }.onSuccess { result ->
                _uiState.update { state ->
                    state.copy(
                        sentRequestUids = state.sentRequestUids + target.uid,
                        searchResults = state.searchResults.filterNot { it.uid == target.uid },
                        message = when (result) {
                            FriendshipRepository.SendRequestResult.Sent ->
                                "Solicitud enviada a ${target.displayName}"
                            FriendshipRepository.SendRequestResult.AcceptedAutomatically ->
                                "Ahora eres amigo de ${target.displayName}"
                            FriendshipRepository.SendRequestResult.AlreadyPending ->
                                "Ya tienes una solicitud pendiente con ${target.displayName}"
                            FriendshipRepository.SendRequestResult.AlreadyFriends ->
                                "Ya eres amigo de ${target.displayName}"
                        },
                    )
                }
            }.onFailure { exception ->
                _uiState.update {
                    it.copy(message = exception.message ?: "No se pudo enviar la solicitud")
                }
            }
        }
    }

    fun acceptRequest(request: IncomingFriendRequest) {
        viewModelScope.launch {
            runCatching {
                val me = myProfile()
                friendshipRepository.acceptRequest(me, request)
            }.onSuccess {
                _uiState.update { it.copy(message = "Ahora eres amigo de ${request.fromDisplayName}") }
            }.onFailure { exception ->
                _uiState.update { it.copy(message = exception.message ?: "No se pudo aceptar la solicitud") }
            }
        }
    }

    fun rejectRequest(request: IncomingFriendRequest) {
        viewModelScope.launch {
            runCatching { friendshipRepository.rejectRequest(request.requestId) }
                .onFailure { exception ->
                    _uiState.update { it.copy(message = exception.message ?: "No se pudo rechazar la solicitud") }
                }
        }
    }

    fun removeFriend(friend: Friend) {
        viewModelScope.launch {
            runCatching {
                val meUid = authRepository.currentUser()?.uid ?: error("Sesión no iniciada")
                friendshipRepository.removeFriend(meUid, friend.uid)
            }.onFailure { exception ->
                _uiState.update { it.copy(message = exception.message ?: "No se pudo eliminar el amigo") }
            }
        }
    }

    private suspend fun myProfile(): UserProfile {
        val uid = authRepository.currentUser()?.uid ?: error("Sesión no iniciada")
        return profileRepository.getUserProfile(uid) ?: UserProfile(
            uid = uid,
            displayName = authRepository.currentUser()?.displayName ?: "Usuario",
            email = authRepository.currentUser()?.email ?: "",
        )
    }

    private fun observeFriendsAndRequests() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                authRepository.authState()
                    .mapNotNull { it?.uid }
                    .distinctUntilChanged()
                    .flatMapLatest { uid ->
                        combineFriendsAndRequests(uid)
                    }
                    .collect { (friends, requests) ->
                        _uiState.update {
                            it.copy(
                                friends = friends,
                                incomingRequests = requests,
                                isLoading = false,
                            )
                        }
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = exception.message ?: "No se pudieron cargar los amigos")
                }
            }
        }
    }

    private fun combineFriendsAndRequests(
        uid: String,
    ): Flow<Pair<List<Friend>, List<IncomingFriendRequest>>> {
        val friendsFlow = friendshipRepository.observeFriends(uid)
        val requestsFlow = friendshipRepository.observeIncomingRequests(uid)
        return combine(friendsFlow, requestsFlow) { friends, requests ->
            friends to requests
        }
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
            _uiState.update { it.copy(isSearching = false, searchResults = emptyList(), searchError = null) }
            return@flow
        }
        val meUid = authRepository.currentUser()?.uid
        _uiState.update { it.copy(isSearching = true, searchError = null) }
        runCatching { profileRepository.searchUsers(trimmed, excludeUid = meUid) }
            .onSuccess { results ->
                val state = _uiState.value
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchResults = results.filterNot { user ->
                            user.uid in state.friends.map { friend -> friend.uid }
                        },
                    )
                }
            }
            .onFailure { exception ->
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        searchError = exception.message ?: "No se pudieron buscar usuarios",
                    )
                }
            }
        emit(Unit)
    }
}
