package com.cuetotech.vibetube.ui.song

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.SongRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SongUiState {
    data object Loading : SongUiState
    data class Success(val songs: List<Song>) : SongUiState
    data class Error(val message: String) : SongUiState
}

class SongViewModel(
    private val repository: SongRepository = SongRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<SongUiState>(SongUiState.Loading)
    val uiState: StateFlow<SongUiState> = _uiState.asStateFlow()

    init {
        loadSongs()
    }

    fun loadSongs() {
        viewModelScope.launch {
            _uiState.value = SongUiState.Loading
            _uiState.value = repository.getSongs().fold(
                onSuccess = { songs -> SongUiState.Success(songs) },
                onFailure = { exception ->
                    SongUiState.Error(exception.message ?: "No se pudieron cargar las canciones")
                },
            )
        }
    }
}
