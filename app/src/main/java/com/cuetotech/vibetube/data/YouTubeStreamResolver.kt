package com.cuetotech.vibetube.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "VibeTubeStream"

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * Implementación del [Downloader] de NewPipeExtractor usando OkHttp. NewPipe
 * exige que la app proporcione el cliente HTTP que usará para llamar a los
 * endpoints de YouTube (innerTube, etc.) durante la extracción del stream.
 */
private class NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .build()

    override fun execute(request: Request): Response {
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .header("User-Agent", USER_AGENT)

        // Se añaden las cabeceras que pide NewPipe (p. ej. Content-Type de los
        // POST JSON de innerTube), sin duplicar las existentes.
        request.headers().forEach { (name, values) ->
            requestBuilder.removeHeader(name)
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }
}

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
        NewPipe.init(
            NewPipeDownloader(),
            Localization.fromLocale(Locale.getDefault()),
            ContentCountry("US"),
        )
        initialized = true
    }

    suspend fun resolveAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.isBlank()) return@withContext null
        try {
            ensureInitialized()
            // ServiceList.YouTube ya es una instancia de StreamingService en
            // NewPipe v0.26.x; getService() solo acepta id o nombre.
            val service = ServiceList.YouTube
            val url = "https://www.youtube.com/watch?v=$videoId"
            val linkHandler = service.streamLHFactory.fromUrl(url)
            val extractor = service.getStreamExtractor(linkHandler)
            extractor.fetchPage()

            // Preferencia: audio solo (m4a/webm/opus); se elige el de mayor
            // bitrate medio. Si el vídeo no expone audio puro, se cae al stream
            // progresivo de menor resolución (que lleva el audio multiplexado).
            val audioStreams = extractor.audioStreams
            val bestAudio = audioStreams
                .filter {
                    it.format == MediaFormat.M4A ||
                        it.format == MediaFormat.WEBMA ||
                        it.format == MediaFormat.WEBMA_OPUS
                }
                .maxByOrNull { it.averageBitrate }
                ?: audioStreams.maxByOrNull { it.averageBitrate }
            if (bestAudio != null) {
                return@withContext bestAudio.content
            }

            extractor.videoStreams
                .minByOrNull { it.height ?: Int.MAX_VALUE }
                ?.content
        } catch (exception: Exception) {
            Log.e(TAG, "No se pudo resolver el stream de $videoId", exception)
            null
        }
    }
}
