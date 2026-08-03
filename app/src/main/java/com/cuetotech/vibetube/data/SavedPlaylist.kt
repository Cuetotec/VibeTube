package com.cuetotech.vibetube.data

data class SavedPlaylist(
    val playlist: Playlist,
    val ownerDisplayName: String,
    val ownerPhotoUrl: String? = null,
    val savedAt: Long = 0L,
)
