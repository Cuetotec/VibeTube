package com.cuetotech.vibetube.data

data class Song(
    val id: String,
    val youtubeId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
) {
    // Portada del vídeo en YouTube (miniatura hqdefault); se usa como carátula
    // del MediaItem para la notificación y Android Auto.
    val imageUrl: String
        get() = "https://i.ytimg.com/vi/$youtubeId/hqdefault.jpg"
}
