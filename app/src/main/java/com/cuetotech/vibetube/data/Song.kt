package com.cuetotech.vibetube.data

data class Song(
    val id: String,
    val youtubeId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
)
