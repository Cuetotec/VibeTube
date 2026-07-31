package com.cuetotech.vibetube.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object YouTubeLinkParser {

    private val VIDEO_ID_PATTERN = Regex(
        """.*(?:youtube\.com/(?:watch\?.*v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11}).*""",
    )

    fun extractVideoId(url: String): String? {
        return VIDEO_ID_PATTERN.matchEntire(url.trim())?.groupValues?.get(1)
    }

    suspend fun fetchVideoInfo(videoId: String): Song? = withContext(Dispatchers.IO) {
        runCatching {
            val endpoint =
                "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            Song(
                id = videoId,
                youtubeId = videoId,
                title = json.optString("title").ifBlank { "Vídeo de YouTube" },
                artist = json.optString("author_name").ifBlank { "YouTube" },
                durationSeconds = 0L,
            )
        }.getOrNull()
    }
}
