package com.cuetotech.vibetube.data

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

private const val TAG = "VibeTubeSearch"

class YouTubeSearchRepository {

    suspend fun search(query: String, maxResults: Int = 25): List<Song> =
        withContext(Dispatchers.IO) {
            try {
                // Inicialización centralizada (preselección de versión de
                // cliente WEB incluida) para no fallar con ytInitialData.
                NewPipeInitializer.ensureInitialized()
                val service = ServiceList.YouTube
                val extractor = service.getSearchExtractor(query.trim())
                extractor.fetchPage()
                val searchInfo = SearchInfo.getInfo(extractor)
                val relatedItems = searchInfo.relatedItems

                Log.d(TAG, "Búsqueda '${query.trim()}': ${relatedItems.size} resultados")

                relatedItems
                    .filterIsInstance<StreamInfoItem>()
                    .take(maxResults)
                    .mapNotNull { item ->
                        val videoId = extractVideoId(item.url) ?: return@mapNotNull null
                        Song(
                            id = videoId,
                            youtubeId = videoId,
                            title = item.name.ifBlank { "Sin título" },
                            artist = item.uploaderName.ifBlank { "YouTube" },
                            durationSeconds = item.duration.coerceAtLeast(0L),
                        )
                    }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReCaptchaException) {
                Log.e(TAG, "YouTube pide CAPTCHA para la búsqueda", exception)
                error("YouTube pide CAPTCHA; intenta de nuevo más tarde")
            } catch (exception: ExtractionException) {
                Log.e(TAG, "Error de extracción en la búsqueda", exception)
                error("No se pudieron obtener resultados: ${exception.message}")
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado en la búsqueda", exception)
                error("Error de búsqueda: ${exception.message}")
            }
        }

    /**
     * Extrae el videoId de una URL de YouTube.
     * Soporta formato `https://www.youtube.com/watch?v=VIDEO_ID` (el que
     * devuelve NewPipeExtractor) y variantes con /shorts/, /embed/, etc.
     */
    private fun extractVideoId(url: String): String? {
        val uri = Uri.parse(url)
        // Formato estándar: ?v=VIDEO_ID
        uri.getQueryParameter("v")?.let { return it }
        // Formato /shorts/VIDEO_ID o /embed/VIDEO_ID
        val path = uri.path.orEmpty()
        val slashIndex = path.lastIndexOf('/')
        if (slashIndex >= 0) {
            val id = path.substring(slashIndex + 1)
            if (id.isNotBlank()) return id
        }
        return null
    }
}
