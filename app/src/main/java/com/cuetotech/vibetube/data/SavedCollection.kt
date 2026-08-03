package com.cuetotech.vibetube.data

data class SavedCollection(
    val playlistId: String,
    val ownerId: String,
    val ownerDisplayName: String,
    val ownerPhotoUrl: String? = null,
    val savedAt: Long = 0L,
)
