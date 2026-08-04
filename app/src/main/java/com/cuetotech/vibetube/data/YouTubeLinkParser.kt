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

    // Para extraer varios IDs de un texto arbitrario (sin anclas de inicio/fin):
    // encuentra cualquier enlace de YouTube en la línea.
    private val VIDEO_ID_FIND_PATTERN = Regex(
        """(?:youtube\.com/(?:watch\?(?:.*&)?v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    )

    // Fallback para IDs limpios de 11 caracteres sueltos en el texto.
    private val CLEAN_VIDEO_ID_PATTERN = Regex("""\b[A-Za-z0-9_-]{11}\b""")

    fun extractVideoId(url: String): String? {
        return VIDEO_ID_PATTERN.matchEntire(url.trim())?.groupValues?.get(1)
    }

    // Extrae todos los IDs de YouTube de un texto arbitrario. Soporta enlaces
    // watch/embed/shorts/live/youtu.be y IDs limpios de 11 caracteres. Limpia
    // espacios en blanco, omite líneas vacías y elimina duplicados.
    fun extractVideoIds(text: String): List<String> {
        return text.lineSequence()
            .flatMap { line ->
                val fromLinks = VIDEO_ID_FIND_PATTERN.findAll(line).map { it.groupValues[1] }.toList()
                if (fromLinks.isNotEmpty()) {
                    fromLinks.asSequence()
                } else {
                    CLEAN_VIDEO_ID_PATTERN.findAll(line).map { it.value }
                }
            }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
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
