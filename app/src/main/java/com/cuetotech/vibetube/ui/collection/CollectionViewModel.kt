package com.cuetotech.vibetube.ui.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.FavoritesRepository
import com.cuetotech.vibetube.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CollectionUiState {
    data object Loading : CollectionUiState
    data class Success(val songs: List<Song>) : CollectionUiState
    data class Error(val message: String) : CollectionUiState
}

class CollectionViewModel(
    private val repository: FavoritesRepository = FavoritesRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<CollectionUiState>(CollectionUiState.Loading)
    val uiState: StateFlow<CollectionUiState> = _uiState.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favoriteIds.asStateFlow()

    init {
        observeFavorites()
    }

    fun retry() {
        observeFavorites()
    }

    fun toggleFavorite(song: Song) {
        val isFavorite = _favoriteIds.value.contains(song.id)
        viewModelScope.launch {
            runCatching {
                if (isFavorite) {
                    repository.removeFavorite(song.id)
                } else {
                    repository.addFavorite(song)
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            _uiState.value = CollectionUiState.Loading
            try {
                val userId = repository.ensureSignedIn()
                repository.observeFavorites(userId).collect { songs ->
                    _favoriteIds.value = songs.mapTo(mutableSetOf()) { it.id }
                    _uiState.value = CollectionUiState.Success(songs)
                }
            } catch (exception: Exception) {
                _uiState.value = CollectionUiState.Error(
                    exception.message ?: "No se pudieron cargar los favoritos",
                )
            }
        }
    }
}
