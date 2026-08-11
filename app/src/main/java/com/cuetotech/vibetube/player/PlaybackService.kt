@file:OptIn(UnstableApi::class)
package com.cuetotech.vibetube.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.Song
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor


private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val ROOT_ID = "root_vibetube"

// Tag de depuración del media browser (Android Auto).
private const val TAG_MEDIA = "VibeTubeMedia"

// Espera máxima a que FirebaseAuth restaure la sesión al arrancar el proceso
// (el usuario puede estar registrado y currentUser aún devolver null unos
// instantes). Pasado este tiempo se devuelve la lista vacía.
private const val AUTH_TIMEOUT_MS = 2_000L
private const val AUTH_RETRY_DELAY_MS = 100L

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Repositorios reales de la app: las listas que ve Android Auto son las del
    // usuario autenticado en Firestore (no datos estáticos). Se construyen con
    // las instancias por defecto de Firebase, igual que los ViewModels.
    private val authRepository = AuthRepository()
    private val playlistRepository = PlaylistRepository()

    // Número de carpetas devueltas en la última consulta a la raíz. Sirve para
    // avisar a Android Auto (notifyChildrenChanged) cuando una consulta previa
    // devolvió la lista vacía (p. ej. sesión aún no restaurada) y después ya
    // hay contenido: así el cliente refresca la pantalla sin volver a entrar.
    private var lastRootChildrenCount = 0

    override fun onCreate() {
        super.onCreate()

        // Configuración de red del reproductor
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        // Construcción de Exoplayer
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory),
            )
            .build()

        // Configuración de comportamiento de audio y WakeLock
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        player.setHandleAudioBecomingNoisy(true)

        // Inicialización de la sesión multimedia para android auto
        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        serviceJob.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
    }

    private suspend fun getYoutubeAudioUrl(videoId: String): String? = withContext(Dispatchers.IO) {

        try {
            val url = "https://www.youtube.com/watch?v=$videoId"
            val extractor = ServiceList.YouTube.getStreamExtractor(url) as YoutubeStreamExtractor
            extractor.fetchPage()

            val audioStreams = extractor.audioStreams
            val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }

            bestAudio?.content
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Convierte una lista del usuario en un elemento navegable (carpeta) que
    // Android Auto muestra en la raíz; al pulsarlo se consultan sus canciones.
    private fun playlistFolderAsMediaItem(playlistId: String, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(playlistId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                    .build(),
            )
            .build()

    // Convierte una canción de la app en un elemento reproducible: mediaId = ID
    // del vídeo de YouTube, que onAddMediaItems usa para extraer la URL del audio.
    private fun Song.asMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(youtubeId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setDurationMs(durationSeconds * C.MILLIS_PER_SECOND)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    // Espera (con timeout) a que FirebaseAuth restaure la sesión al arrancar el
    // proceso: si currentUser devuelve null porque el auth todavía no ha
    // terminado de restaurarse, se reintenta cada AUTH_RETRY_DELAY_MS hasta
    // AUTH_TIMEOUT_MS. Devuelve el uid o null si se agotó el tiempo.
    private suspend fun awaitCurrentUserId(): String? = withTimeoutOrNull(AUTH_TIMEOUT_MS) {
        while (authRepository.currentUser()?.uid == null) {
            delay(AUTH_RETRY_DELAY_MS)
        }
        authRepository.currentUser()?.uid
    }

    // Lee las listas del usuario (una sola consulta) y las expone como carpetas.
    // Si no hay sesión tras el timeout o la consulta falla, se devuelve una lista
    // vacía (nunca se bloquea ni se lanza: el future siempre se resuelve).
    private suspend fun loadPlaylistFolders(): ImmutableList<MediaItem> {
        val ownerId = awaitCurrentUserId()
        Log.d(TAG_MEDIA, "onGetChildren(root): userId=$ownerId")
        if (ownerId == null) {
            Log.d(TAG_MEDIA, "onGetChildren(root): sin sesión tras $AUTH_TIMEOUT_MS ms → lista vacía")
            return ImmutableList.of()
        }
        return try {
            val playlists = playlistRepository.getUserPlaylists(ownerId)
            Log.d(
                TAG_MEDIA,
                "onGetChildren(root): ${playlists.size} listas del usuario $ownerId",
            )
            playlists
                .map { playlistFolderAsMediaItem(it.id, it.title) }
                .let { ImmutableList.copyOf(it) }
        } catch (e: Exception) {
            Log.e(TAG_MEDIA, "onGetChildren(root): error consultando listas", e)
            ImmutableList.of()
        }
    }

    // Lee la lista indicada y expone sus canciones como elementos reproducibles.
    private suspend fun loadPlaylistSongs(playlistId: String): ImmutableList<MediaItem> {
        return try {
            val songs = playlistRepository.getPlaylist(playlistId)
                ?.tracks
                .orEmpty()
            Log.d(TAG_MEDIA, "onGetChildren($playlistId): ${songs.size} canciones")
            songs
                .map { it.asMediaItem() }
                .let { ImmutableList.copyOf(it) }
        } catch (e: Exception) {
            Log.e(TAG_MEDIA, "onGetChildren($playlistId): error consultando canciones", e)
            ImmutableList.of()
        }
    }

    // Si la última consulta a la raíz devolvió la lista vacía (p. ej. sesión aún
    // no restaurada) y ahora ya hay contenido, avisa a Android Auto para que
    // recargue sin necesidad de que el usuario vuelva a entrar en la carpeta.
    private fun notifyRootIfPreviouslyEmpty(children: List<MediaItem>) {
        if (lastRootChildrenCount == 0 && children.isNotEmpty()) {
            Log.d(
                TAG_MEDIA,
                "onGetChildren(root): la lista anterior estaba vacía → " +
                    "notifyChildrenChanged($ROOT_ID)",
            )
            mediaLibrarySession?.notifyChildrenChanged(ROOT_ID, 0, null)
        }
        lastRootChildrenCount = children.size
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Listas de VibeTube")
                        .build()
                )
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {

            val settableFuture = SettableFuture.create<MutableList<MediaItem>>()

            serviceScope.launch {
                val updatedItems = mutableListOf<MediaItem>()

                for (item in mediaItems) {
                    val audioUrl = getYoutubeAudioUrl(item.mediaId)

                    if (audioUrl != null) {
                        val updatedItem = item.buildUpon()
                            .setUri(Uri.parse(audioUrl))
                            .build()
                        updatedItems.add(updatedItem)
                    } else {
                        updatedItems.add(item)
                    }
                }
                settableFuture.set(updatedItems)
            }
            return settableFuture
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {

            // La consulta a Firestore se ejecuta dentro de serviceScope (fuera
            // del hilo de llamada) y el resultado se entrega al resolver el
            // future, sin bloquear nunca el hilo principal. El future SOLO se
            // resuelve al terminar la carga (y siempre, incluso con excepción),
            // para que Android Auto nunca se quede esperando ni reciba un
            // resultado prematuro.
            val settableFuture = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                try {
                    val children = when (parentId) {
                        ROOT_ID -> {
                            val items = loadPlaylistFolders()
                            notifyRootIfPreviouslyEmpty(items)
                            items
                        }
                        else -> loadPlaylistSongs(parentId)
                    }
                    settableFuture.set(LibraryResult.ofItemList(children, params))
                } catch (e: Exception) {
                    Log.e(
                        TAG_MEDIA,
                        "onGetChildren: excepción inesperada para parentId=$parentId",
                        e,
                    )
                    settableFuture.set(LibraryResult.ofItemList(ImmutableList.of(), params))
                }
            }

            return settableFuture
        }
    }
}
