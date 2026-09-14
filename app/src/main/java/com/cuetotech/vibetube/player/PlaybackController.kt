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
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeStreamResolver
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.coroutines.resume

private const val TAG = "VibeTubePlayback"

// Tiempo máximo de espera para que el MediaController conecte con el
// PlaybackService; pasado este tiempo se libera el future y se puede reintentar.
private const val CONNECT_TIMEOUT_MS = 15_000L

private const val ARTWORK_URL_TEMPLATE = "https://i.ytimg.com/vi/%s/hqdefault.jpg"

// La cola del teléfono arranca con un item provisional (un WAV silencioso
// empaquetado) que YA tiene URI: así la notificación foreground aparece al
// instante con la metadata en caché (ExoPlayer lanza NPE con ítems sin URI),
// y cuando NewPipe resuelve la URL real se reemplaza en caliente.
private const val PLACEHOLDER_SCHEME = "rawresource"

/**
 * Controlador de la reproducción en segundo plano. Se conecta al
 * [PlaybackService] mediante un [MediaController] (vía [SessionToken])
 * y le envía los [MediaItem] con la URL de audio real de YouTube (resuelta
 * con [YouTubeStreamResolver]) y metadatos básicos (título, artista y portada)
 * para que Android muestre la notificación en el centro de control y en la
 * pantalla de bloqueo.
 *
 * Diseño asíncrono (no bloqueante), mismo patrón que Android Auto:
 * - Al pulsar una canción se envía INMEDIATAMENTE un item provisional con la
 *   metadata en caché (placeholder silencioso con URI válida), de modo que la
 *   notificación foreground aparece sin esperar a NewPipe.
 * - La URL real de la pista seleccionada se resuelve en `Dispatchers.IO` en
 *   background y se reemplaza el item provisional en caliente.
 * - El resto de la cola se resuelve en background y se va añadiendo en orden,
 *   actualizando el mapeo índice(ViewModel) -> índice(servicio).
 *
 * La lista que se envía al servicio mantiene el MISMO orden que la lista activa
 * del ViewModel (incluido el orden aleatorio). Las pistas cuya extracción falla
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
    // Se va ampliando a medida que la cola se infla en background.
    @Volatile
    private var vmToServiceIndex: Map<Int, Int> = emptyMap()

    // Renvía de URLs ya resueltas entre pulsaciones (segunda pulsación = instantánea).
    private val urlCache = ConcurrentHashMap<String, String>()

    // Generación de sincronización: cada syncPlaylist (o stop) incrementa la
    // generación; el backfill en background solo aplica si su generación sigue
    // siendo la actual (evita inflar la cola de una sesión ya reemplazada).
    @Volatile
    private var playbackGeneration = 0L

    // Se completa cuando el placeholder de la primera pista se reemplaza por su
    // URL real; play()/playFromPosition() esperan a ese momento para no
    // reproducir silencio al minimizar la app.
    private var realTrackDef: CompletableDeferred<Boolean?>? = null

    // Corrutina de inflado en background de la cola.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
     * Envía la lista (en el orden activo del ViewModel) al servicio y empieza a
     * reproducir desde [startIndex] SIN bloquear el arranque en la resolución
     * de streams:
     *
     * FASE 1 — Notificación inmediata: se envía al instante un item provisional
     * (placeholder silencioso con URI válida y la metadata en caché) y se
     * prepara/reproduce; la notificación foreground aparece ya. La pista real
     * del target se resuelve en `Dispatchers.IO` en background y reemplaza al
     * placeholder en caliente.
     *
     * FASE 2 — Backfill: el resto de la cola se resuelve en background y se va
     * añadiendo en orden al reproductor.
     *
     * Si [startPlaying] es false, el servicio queda preparado y pausado (sin
     * pedir foco de audio): se usa en primer plano, donde el audio lo aporta el
     * WebView y el ExoPlayer no debe arrebatarle el foco.
     *
     * Devuelve false si no se pudo conectar o si ninguna pista del target pudo
     * resolver su URL de audio.
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

        val target = startIndex.coerceIn(tracks.indices)

        // Elige la pista real desde la que arrancar: el target o, si falla, la
        // siguiente pista válida en orden. Siempre se resuelve UNA pista antes
        // de arrancar (la escuchable inmediatamente), el resto en background.
        val firstUrl = resolveFirstPlayable(tracks, target) ?: run {
            Log.w(TAG, "syncPlaylist: ninguna pista pudo resolver su audio")
            _isActive.value = false
            return false
        }
        val (firstUrlString, firstPlayable) = firstUrl

        // Nueva generación: invalida cualquier backfill anterior.
        playbackGeneration += 1
        val generation = playbackGeneration
        realTrackDef?.complete(false)
        realTrackDef = CompletableDeferred()

        // FASE 1a — Enviar el placeholder INMEDIATAMENTE (metadata en caché).
        // La notificación aparece sin esperar a NewPipe. Solo la primera pista
        // se envía así; el resto se infla en background.
        vmToServiceIndex = mapOf(firstPlayable to 0)
        val placeholderItem = buildPlaceholderItem(tracks[firstPlayable])
        withContext(Dispatchers.Main) {
            controller.setMediaItems(listOf(placeholderItem), 0, 0L)
            controller.repeatMode = repeatMode
            controller.prepare()
            if (startPlaying) controller.play()
        }
        Log.d(
            TAG,
            "syncPlaylist: placeholder enviado al instante (vmIdx=$firstPlayable), " +
            "notificación planeada sin esperar a NewPipe",
        )
        _isActive.value = true

        // FASE 1b/2 — Background: URL real del target + backfill del resto.
        backgroundScope.launch {
            inflateQueue(
                controller,
                tracks,
                firstPlayable,
                firstUrlString,
                generation,
                startPlaying,
            )
        }
        return true
    }

    /**
     * FASE 1b: resuelve la URL real del target y reemplaza el placeholder en
     * caliente; FASE 2: resuelve el resto de la cola en orden y la va añadiendo
     * al reproductor. Solo actúa si [generation] sigue siendo la actual.
     */
    private suspend fun inflateQueue(
        controller: MediaController,
        tracks: List<Song>,
        firstPlayable: Int,
        firstUrlString: String,
        generation: Long,
        startPlaying: Boolean,
    ) {
        try {
            val targetYtId = tracks[firstPlayable].youtubeId
            urlCache[targetYtId] = firstUrlString
            val realTarget = buildMediaItem(tracks[firstPlayable], firstUrlString)

            withContext(Dispatchers.Main) {
                if (generation == playbackGeneration && mediaController != null) {
                    runCatching {
                        // HOT REPLACE robusto (reanudación tras el placeholder):
                        // se sustituye el WAV silencioso por el MediaItem real y
                        // se fuerza una transición COMPLETA (setMediaItem ->
                        // prepare) para que ExoPlayer NO quede anclado en
                        // STATE_ENDED del silent_track (si el WAV ya terminó o
                        // la pantalla se apagó durante la resolución). Después
                        // se preserva playWhenReady: si había reproducción en
                        // curso (o arranque directo con audio) reanuda al
                        // instante; si la app está en primer plano con el WebView
                        // sonando, sigue pausado hasta el handoff/bloqueo.
                        val shouldResume = startPlaying || controller.playWhenReady
                        controller.setMediaItem(realTarget)
                        controller.prepare()
                        controller.playWhenReady = shouldResume
                        if (shouldResume) {
                            Log.d(
                                TAG,
                                "syncPlaylist: pista real reemplazada y reanudada " +
                                    "(playWhenReady=true)",
                            )
                        } else {
                            Log.d(
                                TAG,
                                "syncPlaylist: pista real reemplazada y preparada " +
                                    "(pausada hasta handoff/bloqueo)",
                            )
                        }
                    }.onFailure {
                        Log.w(TAG, "syncPlaylist: fallo al reemplazar el placeholder", it)
                    }
                }
                realTrackDef?.complete(true)
            }

            // FASE 2 — resolver el resto de la cola en paralelo y appendear en orden.
            val remainder = playlistOrderAfter(tracks.indices, firstPlayable)
            if (remainder.isEmpty()) return
            val missing = remainder.filter { vm ->
                urlCache[tracks[vm].youtubeId] == null
            }
            if (missing.isNotEmpty()) {
                val urls = withContext(Dispatchers.IO) {
                    YouTubeStreamResolver.resolveAudioUrls(missing.map { tracks[it].youtubeId })
                }
                missing.forEachIndexed { i, vm ->
                    urls[i]?.let { urlCache[tracks[vm].youtubeId] = it }
                }
            }

            if (generation != playbackGeneration || mediaController == null) return

            withContext(Dispatchers.Main) {
                if (generation != playbackGeneration || mediaController == null) return@withContext
                val newMap = HashMap(vmToServiceIndex)
                var appended = 0
                for (vm in remainder) {
                    val url = urlCache[tracks[vm].youtubeId] ?: continue
                    runCatching { controller.addMediaItem(buildMediaItem(tracks[vm], url)) }
                        .onSuccess {
                            newMap[vm] = newMap.size
                            appended++
                        }.onFailure {
                            Log.w(TAG, "syncPlaylist: fallo al añadir pista $vm", it)
                        }
                }
                if (appended > 0) {
                    vmToServiceIndex = newMap
                }
                Log.d(
                    TAG,
                    "syncPlaylist: backfill completado, $appended pistas añadidas " +
                        "(cola total de ${newMap.size})",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "syncPlaylist: backfill falló", e)
        }
    }

    /** Orden de la cola tras la primera pista, en el orden activo del ViewModel. */
    private fun playlistOrderAfter(indices: IntRange, first: Int): List<Int> =
        ((first + 1)..indices.last).toList() + (indices.first until first).toList()

    /**
     * Busca la primera pista reproducible empezando por [target] y siguiendo el
     * orden activo. Usa [urlCache] (pulsaciones repetidas = instantáneo) y
     * resuelve con NewPipe en `Dispatchers.IO`. Devuelve URL+índice.
     */
    private suspend fun resolveFirstPlayable(
        tracks: List<Song>,
        target: Int,
    ): Pair<String, Int>? {
        val order = listOf(target) + playlistOrderAfter(tracks.indices, target)
        for (vm in order) {
            val ytId = tracks[vm].youtubeId
            val fromCache = urlCache[ytId]
            if (fromCache != null) return fromCache to vm
            val url = YouTubeStreamResolver.resolveAudioUrls(listOf(ytId)).firstOrNull()
            if (url != null) {
                urlCache[ytId] = url
                return url to vm
            }
        }
        return null
    }

    /**
     * Item provisional con URI válida (WAV silencioso empaquetado) y la metadata
     * real en caché. Permite que la notificación aparezca al instante y evita el
     * NPE de ExoPlayer con ítems sin URI. Se reemplaza por la URL real en
     * cuanto NewPipe la resuelve.
     */
    private fun buildPlaceholderItem(song: Song): MediaItem {
        val placeholderUri = Uri.parse("$PLACEHOLDER_SCHEME:///${R.raw.silent_track}")
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(placeholderUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setArtworkUri(
                        Uri.parse(String.format(ARTWORK_URL_TEMPLATE, song.youtubeId)),
                    )
                    .build(),
            )
            .build()
    }

    /** Reproduce (o solo busca, si [play] es false) la pista en el índice del ViewModel. */
    suspend fun seekTo(vmIndex: Int, play: Boolean) {
        val controller = mediaController ?: return
        val serviceIndex = vmToServiceIndex[vmIndex] ?: run {
            Log.w(
                TAG,
                "seekTo: pista $vmIndex aún no cargada en el servicio (cola en inflado)",
            )
            return
        }
        awaitFirstTrackReady()
        withContext(Dispatchers.Main) {
            controller.seekTo(serviceIndex, 0L)
            if (play) controller.play()
        }
    }

    /**
     * Reanuda la reproducción del servicio (sin cambiar la pista actual).
     * Si el player quedó en STATE_ENDED (p.ej. porque el silent_track del
     * placeholder terminó antes de que el ítem real estuviera listo), hace un
     * seek al inicio del ítem actual para que ExoPlayer reanude de verdad en
     * lugar de quedarse parado en silencio.
     */
    suspend fun play() {
        val controller = mediaController ?: return
        awaitFirstTrackReady()
        withContext(Dispatchers.Main) {
            if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(controller.currentMediaItemIndex, 0L)
            }
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
        awaitFirstTrackReady()
        withContext(Dispatchers.Main) {
            // Si la posición del WebView es válida la usamos; si el player
            // quedó en ENDED (silent_track terminó) reiniciamos al inicio del
            // ítem real para reanudar sin depender del salto manual.
            if (positionMs != null && positionMs > 0) {
                controller.seekTo(controller.currentMediaItemIndex, positionMs)
            } else if (controller.playbackState == Player.STATE_ENDED) {
                controller.seekTo(controller.currentMediaItemIndex, 0L)
            }
            controller.play()
        }
    }

    /**
     * Espera (máx 15 s) a que el placeholder de la primera pista se reemplace
     * por su URL real, para que play() no reproduzca silencio al pasar de la
     * app a segundo plano. Si no hay syncPlaylist en curso, no espera nada.
     */
    private suspend fun awaitFirstTrackReady() {
        val deferred = realTrackDef ?: return
        withTimeoutOrNull(15_000L) {
            try {
                deferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "awaitFirstTrackReady: interrumpido", e)
            }
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
        playbackGeneration += 1
        realTrackDef?.complete(false)
        realTrackDef = null
        val controller = mediaController ?: return
        runCatching {
            controller.stop()
            controller.clearMediaItems()
        }
        _isActive.value = false
    }

    /** Libera la conexión con el servicio (al destruirse el ViewModel). */
    fun release() {
        playbackGeneration += 1
        realTrackDef?.complete(false)
        realTrackDef = null
        backgroundScope.cancel()
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
