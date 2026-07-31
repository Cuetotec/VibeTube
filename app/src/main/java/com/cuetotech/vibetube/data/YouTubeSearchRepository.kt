package com.cuetotech.vibetube.data

import com.cuetotech.vibetube.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class YouTubeSearchRepository {

    suspend fun search(query: String, maxResults: Int = 25): List<Song> =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.YOUTUBE_API_KEY
            if (apiKey.isBlank()) {
                error("Falta configurar la API key de YouTube (YOUTUBE_API_KEY)")
            }
            val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

            val searchJson = get(
                "https://www.googleapis.com/youtube/v3/search" +
                    "?part=snippet&type=video&maxResults=$maxResults&q=$encodedQuery&key=$apiKey",
            )

            val items = searchJson.optJSONArray("items") ?: org.json.JSONArray()
            val videoIds = mutableListOf<String>()
            val baseSongs = mutableListOf<Song>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val snippet = item.optJSONObject("snippet") ?: continue
                val videoId = item.optJSONObject("id")?.optString("videoId") ?: continue
                videoIds.add(videoId)
                baseSongs.add(
                    Song(
                        id = videoId,
                        youtubeId = videoId,
                        title = snippet.optString("title").ifBlank { "Sin título" },
                        artist = snippet.optString("channelTitle").ifBlank { "YouTube" },
                        durationSeconds = 0L,
                    ),
                )
            }

            if (videoIds.isNotEmpty()) {
                val durations = runCatching { fetchDurations(videoIds, apiKey) }
                    .getOrDefault(emptyMap())
                return@withContext baseSongs.map { song ->
                    song.copy(durationSeconds = durations[song.youtubeId] ?: 0L)
                }
            }
            baseSongs
        }

    private fun fetchDurations(videoIds: List<String>, apiKey: String): Map<String, Long> {
        val idsParam = URLEncoder.encode(videoIds.joinToString(","), "UTF-8")
        val json = get(
            "https://www.googleapis.com/youtube/v3/videos" +
                "?part=contentDetails&id=$idsParam&key=$apiKey",
        )
        val items = json.optJSONArray("items") ?: org.json.JSONArray()
        val durations = mutableMapOf<String, Long>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val videoId = item.optString("id")
            val duration = item.optJSONObject("contentDetails")?.optString("duration") ?: continue
            durations[videoId] = parseIso8601Duration(duration)
        }
        return durations
    }

    private fun parseIso8601Duration(duration: String): Long {
        val match = DURATION_PATTERN.matchEntire(duration) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toLongOrNull() ?: 0L
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun get(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val message = runCatching {
                JSONObject(body)
                    .optJSONArray("error")
                    ?.optJSONObject(0)
                    ?.optString("message")
            }.getOrNull() ?: "Error de la API de YouTube (código $code)"
            error(message)
        }
        return JSONObject(body)
    }

    private companion object {
        val DURATION_PATTERN = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
    }
}
