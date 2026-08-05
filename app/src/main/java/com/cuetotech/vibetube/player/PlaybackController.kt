package com.cuetotech.vibetube.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeStreamResolver
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val TAG = "VibeTubePlayback"

// Semáforo para no lanzar demasiadas extracciones de NewPipeExtractor a la vez
// (YouTube puede limitar si se disparan en paralelo).
private const val MAX_CONCURRENT_EXTRACTIONS = 4

private const val ARTWORK_URL_TEMPLATE = "https://i.ytimg.com/vi/%s/hqdefault.jpg"

/**
 * Controlador de la reproducción en segundo plano. Se conecta al
 * [PlaybackService] mediante un [MediaController] (vía [SessionToken])
 * y le envía las [MediaItem] construidas con la URL de audio real de YouTube
 * (resuelta con [YouTubeStreamResolver]) y metadatos básicos (título, artista y
 * portada) para que Android muestre la notificación en el centro de control y
 * en la pantalla de bloqueo.
 *
 * La lista que se envía al servicio mantiene el MISMO orden que la lista activa
 * del ViewModel (incluido el orden aleatorio), de modo que el índice que ve la
 * app coincide con el índice del reproductor. Las pistas cuya extracción falla
 * se omiten del servicio; [seekTo] se encarga de mapear el índice de la app al
 * índice del servicio.
 */
class PlaybackController(private val appContext: Context) {

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val resolutionSemaphore = Semaphore(MAX_CONCURRENT_EXTRACTIONS)

    private var connectFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Mapa índice(ViewModel) -> índice(servicio) para las pistas resueltas.
    private var vmToServiceIndex: Map<Int, Int> = emptyMap()

    private val _isActive = MutableStateFlow(false)

    /** true cuando el servicio está conectado y reproduciendo la lista actual. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private suspend fun controller(): MediaController? {
        mediaController?.let { return it }
        val future = connectFuture ?: MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync().also { connectFuture = it }
        val controller = awaitController(future)
        if (controller != null) {
            mediaController = controller
            _isActive.value = true
        }
        return controller
    }

    /**
     * Envía toda la lista (en el orden activo del ViewModel) al servicio y
     * empieza a reproducir desde [startIndex]. Devuelve false si no se pudo
     * conectar o si ninguna pista pudo resolver su URL de audio.
     */
    suspend fun syncPlaylist(
        tracks: List<Song>,
        startIndex: Int,
        repeatMode: Int,
    ): Boolean {
        val controller = controller() ?: return false
        if (tracks.isEmpty()) {
            stop()
            return false
        }

        // Extrae las URLs de audio en paralelo (con límite de concurrencia).
        val urls = resolveAudioUrls(tracks)
        val playable = tracks.indices.filter { urls[it] != null }
        if (playable.isEmpty()) {
            Log.w(TAG, "syncPlaylist: ninguna pista pudo resolver su audio")
            _isActive.value = false
            return false
        }

        vmToServiceIndex = playable
            .mapIndexed { serviceIndex, vmIndex -> vmIndex to serviceIndex }
            .toMap()

        val items = playable.map { vmIndex ->
            buildMediaItem(tracks[vmIndex], urls[vmIndex]!!)
        }
        val serviceStart = vmToServiceIndex[startIndex] ?: 0

        withContext(Dispatchers.Main) {
            controller.setMediaItems(items, serviceStart, 0L)
            controller.repeatMode = repeatMode
            controller.prepare()
            controller.play()
        }
        Log.d(
            TAG,
            "syncPlaylist: ${playable.size}/${tracks.size} pistas sincronizadas, " +
                "inicio en índice $startIndex (servicio $serviceStart)",
        )
        _isActive.value = true
        return true
    }

    /** Reproduce (o solo busca, si [play] es false) la pista en el índice del ViewModel. */
    suspend fun seekTo(vmIndex: Int, play: Boolean) {
        val controller = mediaController ?: return
        val serviceIndex = vmToServiceIndex[vmIndex] ?: return
        withContext(Dispatchers.Main) {
            controller.seekTo(serviceIndex, 0L)
            if (play) controller.play()
        }
    }

    suspend fun setRepeatMode(repeatMode: Int) {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main) {
            controller.repeatMode = repeatMode
        }
    }

    /** Detiene la reproducción en el servicio y limpia la cola. */
    fun stop() {
        val controller = mediaController ?: return
        runCatching {
            controller.stop()
            controller.clearMediaItems()
        }
        _isActive.value = false
    }

    /** Libera la conexión con el servicio (al destruirse el ViewModel). */
    fun release() {
        connectFuture?.let { future ->
            MediaController.releaseFuture(future)
        }
        connectFuture = null
        mediaController = null
        _isActive.value = false
    }

    private suspend fun resolveAudioUrls(tracks: List<Song>): Map<Int, String?> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                tracks.indices.map { vmIndex ->
                    async {
                        resolutionSemaphore.withPermit {
                            vmIndex to withTimeoutOrNull(20_000) {
                                runCatching {
                                    YouTubeStreamResolver.resolveAudioUrl(tracks[vmIndex].youtubeId)
                                }.getOrNull()
                            }
                        }
                    }
                }.awaitAll().toMap()
            }
        }

    private fun buildMediaItem(song: Song, url: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(Uri.parse(String.format(ARTWORK_URL_TEMPLATE, song.youtubeId)))
                    .build(),
            )
            .build()

    private suspend fun awaitController(future: ListenableFuture<MediaController>): MediaController? =
        suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    continuation.resume(runCatching { future.get() }.getOrNull())
                },
                mainExecutor,
            )
        }
}
