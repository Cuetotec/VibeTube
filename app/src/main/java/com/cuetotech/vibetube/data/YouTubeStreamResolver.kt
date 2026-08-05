package com.cuetotech.vibetube.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

private const val TAG = "VibeTubeStream"

// Semáforo para no lanzar demasiadas extracciones a la vez (YouTube puede
// limitar si se disparan muchas en paralelo desde la misma IP).
private const val MAX_CONCURRENT_EXTRACTIONS = 4

private const val RESOLUTION_TIMEOUT_MS = 20_000L

// Versión del cliente WEB de YouTube que usa NewPipeExtractor como respaldo
// (getClientVersion()) cuando no puede extraerla del HTML/JS de youtube.com.
// Preseleccionarla evita que el extractor tenga que visitar www.youtube.com
// (sw.js_data y la página de búsqueda con ?ucbcb=1), que en redes bloqueadas
// (IPs de datacenter) devuelve una página robot sin ytInitialData y hace
// fallar TODA la extracción con "Could not get ytInitialData" (o un bucle de
// redirecciones de consentimiento en builds sin cookie SOCS).
private const val PRESET_WEB_CLIENT_VERSION = "2.20260120.01.00"

private val extractionSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

/**
 * Resuelve la URL real de audio (m4a/opus progresivo o, en su defecto, el
 * stream de vídeo progresivo más pequeño, que incluye audio) para un vídeo de
 * YouTube usando NewPipeExtractor. Esa URL es la que recibe el ExoPlayer del
 * [com.cuetotech.vibetube.player.PlaybackService] para poder reproducir con la
 * pantalla apagada. Devuelve null si la extracción falla (vídeo restringido,
 * borrado o error de red); en ese caso la app sigue sonando vía el reproductor
 * WebView sin silenciar.
 */
object YouTubeStreamResolver {

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        Log.d(TAG, "Inicializando NewPipe (locale=${Locale.getDefault()}, country=US)")
        NewPipe.init(
            NewPipeDownloader(),
            Localization.fromLocale(Locale.getDefault()),
            ContentCountry("US"),
        )
        presetWebClientVersion()
        initialized = true
    }

    // Preselecciona la versión del cliente WEB en YoutubeParsingHelper. Al estar
    // poblado el campo estático, getClientVersion() la devuelve directamente y
    // el extractor se salta las peticiones a www.youtube.com (sw.js_data y la
    // página de búsqueda) que son las que fallan en redes bloqueadas. Si la
    // preselección fallara (minificación, cambio de campo), se ignora: el
    // extractor seguiría con su flujo normal.
    private fun presetWebClientVersion() {
        try {
            val helper = Class.forName(
                "org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper",
            )
            val field = helper.getDeclaredField("clientVersion")
            @Suppress("DEPRECATION")
            field.isAccessible = true
            val current = field.get(null) as? String
            if (current.isNullOrEmpty()) {
                field.set(null, PRESET_WEB_CLIENT_VERSION)
                Log.d(
                    TAG,
                    "Versión de cliente WEB preseleccionada: $PRESET_WEB_CLIENT_VERSION",
                )
            } else {
                Log.d(TAG, "Versión de cliente WEB ya disponible: $current")
            }
        } catch (exception: Exception) {
            Log.w(TAG, "No se pudo preseleccionar la versión de cliente WEB", exception)
        }
    }

    suspend fun resolveSingleAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) {
            Log.w(TAG, "resolveSingleAudioUrl: videoId vacío")
            return@withContext null
        }
        Log.d(TAG, "Resolviendo URL de audio de $videoId")
        try {
            ensureInitialized()
            // ServiceList.YouTube ya es una instancia de StreamingService en
            // NewPipe v0.26.x; getService() solo acepta id o nombre.
            val service = ServiceList.YouTube
            val url = "https://www.youtube.com/watch?v=$videoId"
            val linkHandler = service.streamLHFactory.fromUrl(url)
            val extractor = service.getStreamExtractor(linkHandler)
            extractor.fetchPage()
            Log.d(TAG, "fetchPage OK para $videoId")

            // Preferencia: audio solo (m4a/webm/opus); se elige el de mayor
            // bitrate medio. Si el vídeo no expone audio puro, se cae al stream
            // progresivo de menor resolución (que lleva el audio multiplexado).
            val audioStreams = extractor.audioStreams
            Log.d(TAG, "$videoId: ${audioStreams.size} streams de audio disponibles")
            val bestAudio = audioStreams
                .filter {
                    it.format == MediaFormat.M4A ||
                        it.format == MediaFormat.WEBMA ||
                        it.format == MediaFormat.WEBMA_OPUS
                }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
            if (bestAudio != null) {
                Log.d(
                    TAG,
                    "$videoId: audio elegido formato=${bestAudio.format} " +
                        "bitrate=${bestAudio.averageBitrate}",
                )
                return@withContext bestAudio.content
            }

            Log.w(TAG, "$videoId: sin streams de audio puro, probando vídeo progresivo")
            val fallback = extractor.videoStreams
                .minByOrNull { it.height ?: Int.MAX_VALUE }
                ?.content
            if (fallback != null) {
                Log.d(TAG, "$videoId: usando vídeo progresivo (fallback)")
            } else {
                Log.w(TAG, "$videoId: tampoco hay streams de vídeo progresivo")
            }
            fallback
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ReCaptchaException) {
            Log.e(TAG, "$videoId: YouTube pide CAPTCHA (posible bloqueo por IP/cookies)", exception)
            null
        } catch (exception: ParsingException) {
            Log.e(
                TAG,
                "$videoId: fallo al parsear la respuesta de YouTube (HTML/vídeo " +
                    "cambiado o restringido): ${exception.message}",
            )
            null
        } catch (exception: Exception) {
            Log.e(TAG, "$videoId: error inesperado al resolver el stream", exception)
            null
        }
    }

    /**
     * Resuelve la URL de audio de varios vídeos en paralelo usando
     * [Dispatchers.IO] y [async]/[awaitAll], con un semáforo para limitar la
     * concurrencia y un timeout por canción. El fallo de una canción (try-catch
     * → null) no rompe el resto de la lista. Devuelve una lista alineada por
     * índice con [videoIds].
     */
    suspend fun resolveAudioUrls(videoIds: List<String>): List<String?> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                videoIds.map { id ->
                    async {
                        try {
                            extractionSemaphore.withPermit {
                                withTimeoutOrNull(RESOLUTION_TIMEOUT_MS) {
                                    resolveSingleAudioUrl(id)
                                }
                            }
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            Log.w(TAG, "Fallo al resolver audio de $id", exception)
                            null
                        }
                    }
                }.awaitAll()
            }
        }
}
