package com.cuetotech.vibetube.data

data class Playlist(
    val id: String,
    val ownerId: String,
    val title: String,
    val description: String,
    val isPublic: Boolean,
    val tracks: List<Song>,
    val createdAt: Long,
)
