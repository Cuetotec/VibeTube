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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val TAG = "VibeTubePlayback"

// Tiempo máximo de espera para que el MediaController conecte con el
// PlaybackService; pasado este tiempo se libera el future y se puede reintentar.
private const val CONNECT_TIMEOUT_MS = 15_000L

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

    // Serializa las conexiones al PlaybackService: solo una corrutina espera a
    // la vez, de modo que liberar el future en awaitController (cancelación o
    // timeout) no rompe a otra corrutina que estuviera esperando el mismo.
    private val connectMutex = Mutex()

    private var connectFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    // Mapa índice(ViewModel) -> índice(servicio) para las pistas resueltas.
    private var vmToServiceIndex: Map<Int, Int> = emptyMap()

    private val _isActive = MutableStateFlow(false)

    /** true cuando el servicio está conectado y reproduciendo la lista actual. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private suspend fun controller(): MediaController? = connectMutex.withLock {
        mediaController?.let { return@withLock it }
        Log.d(TAG, "Conectando MediaController al PlaybackService...")
        val future = connectFuture ?: MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).buildAsync().also { connectFuture = it }
        val controller = awaitController(future)
        if (controller != null) {
            mediaController = controller
            _isActive.value = true
            Log.d(TAG, "MediaController conectado al PlaybackService")
        } else {
            Log.e(TAG, "No se pudo conectar el MediaController al PlaybackService")
            // Permite reintentar con un future nuevo la próxima vez (el anterior
            // se liberó por timeout o cancelación, o falló al conectar).
            connectFuture = null
        }
        controller
    }

    /**
     * Fuerza la conexión con el [PlaybackService] (y, con ello, el arranque del
     * servicio Media3) tan pronto como el usuario pulsa en reproducir, ANTES de
     * resolver las URLs de audio. Devuelve true si el servicio quedó conectado.
     */
    suspend fun ensureConnected(): Boolean {
        val connected = controller() != null
        Log.d(TAG, "ensureConnected: servicio ${if (connected) "conectado" else "NO disponible"}")
        return connected
    }

    /**
     * Envía toda la lista (en el orden activo del ViewModel) al servicio y
     * empieza a reproducir desde [startIndex]. Si [startPlaying] es false, el
     * servicio queda preparado y pausado (sin pedir foco de audio): se usa en
     * primer plano, donde el audio lo aporta el WebView y el ExoPlayer no debe
     * arrebatarle el foco. Devuelve false si no se pudo conectar o si ninguna
     * pista pudo resolver su URL de audio.
     */
    suspend fun syncPlaylist(
        tracks: List<Song>,
        startIndex: Int,
        repeatMode: Int,
        startPlaying: Boolean = true,
    ): Boolean {
        val controller = controller() ?: return false
        if (tracks.isEmpty()) {
            stop()
            return false
        }

        // Extrae las URLs de audio en paralelo (el resolver limita la
        // concurrencia y aísla el fallo de cada canción).
        val urls = YouTubeStreamResolver.resolveAudioUrls(tracks.map { it.youtubeId })
        val playable = tracks.indices.filter { urls[it] != null }
        Log.d(
            TAG,
            "syncPlaylist: ${playable.size}/${tracks.size} pistas con audio resuelto",
        )
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

        // Si la pista que se quería reproducir no resolvió audio, se arranca
        // desde la primera pista válida de la lista (en orden).
        val serviceStart = vmToServiceIndex[startIndex]
        if (serviceStart == null) {
            Log.w(
                TAG,
                "syncPlaylist: la pista inicial $startIndex no resolvió audio; " +
                    "reproduciendo desde la primera pista válida (${playable.first()})",
            )
        }

        withContext(Dispatchers.Main) {
            controller.setMediaItems(items, serviceStart ?: 0, 0L)
            controller.repeatMode = repeatMode
            controller.prepare()
            if (startPlaying) controller.play()
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

    /** Reanuda la reproducción del servicio (sin cambiar la pista actual). */
    suspend fun play() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main) {
            controller.play()
        }
    }

    /**
     * Pausa el servicio conservando la posición y la cola (usado por el handoff
     * para que el WebView sea la única fuente de audio en primer plano).
     */
    suspend fun pause() {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main) {
            controller.pause()
        }
    }

    /** Posición de reproducción actual del ExoPlayer (ms); null si no hay servicio conectado. */
    suspend fun currentPosition(): Long? {
        val controller = mediaController ?: return null
        return withContext(Dispatchers.Main) {
            controller.currentPosition
        }
    }

    /**
     * Reanuda el servicio buscando a [positionMs] dentro de la pista actual si
     * es una posición válida (> 0); si es null o inválida, solo reanuda.
     */
    suspend fun playFromPosition(positionMs: Long?) {
        val controller = mediaController ?: return
        withContext(Dispatchers.Main) {
            if (positionMs != null && positionMs > 0) {
                controller.seekTo(controller.currentMediaItemIndex, positionMs)
            }
            controller.play()
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
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                // Si la corrutina se cancela (navegación, timeout, ViewModel
                // destruido), se libera el future para no dejar la conexión
                // huérfana esperando. Solo hay un awaiter a la vez (connectMutex).
                continuation.invokeOnCancellation {
                    MediaController.releaseFuture(future)
                }
                future.addListener(
                    {
                        val controller = try {
                            future.get()
                        } catch (exception: Exception) {
                            Log.e(TAG, "Fallo al conectar el MediaController", exception)
                            null
                        }
                        if (controller == null) {
                            Log.e(TAG, "El future del MediaController no devolvió un controlador")
                        }
                        // Si la corrutina ya fue cancelada (timeout), resume() es
                        // un no-op y el future ya se liberó en invokeOnCancellation.
                        continuation.resume(controller)
                    },
                    mainExecutor,
                )
            }
        }
}
