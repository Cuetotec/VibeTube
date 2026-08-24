@file:OptIn(UnstableApi::class)
package com.cuetotech.vibetube.player

import android.os.Bundle
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
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.cuetotech.vibetube.data.AuthRepository
import com.cuetotech.vibetube.data.PlaylistRepository
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.data.YouTubeStreamResolver
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import java.util.concurrent.ConcurrentHashMap


private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val ROOT_ID = "root_vibetube"

// Separador del mediaId de cada canción: "playlistId:youtubeId". Android Auto
// envía ese mediaId al pulsar la canción, y así sabemos a qué lista pertenece
// para cargarla completa en la cola (salto de pista en el volante).
private const val MEDIA_ID_SEPARATOR = ":"

// Carátula por defecto de una canción (miniatura hqdefault de YouTube).
private const val THUMBNAIL_BASE_URL = "https://i.ytimg.com/vi"

// Tag de depuración del media browser (Android Auto).
private const val TAG_MEDIA = "VibeTubeMedia"

// Espera máxima a que FirebaseAuth restaure la sesión al arrancar el proceso
// (el usuario puede estar registrado y currentUser aún devolver null unos
// instantes). Pasado este tiempo se devuelve la lista vacía.
private const val AUTH_TIMEOUT_MS = 500L
private const val AUTH_RETRY_DELAY_MS = 100L

class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // Caché de URLs de audio resueltas (youtubeId → audioUrl): evita re-extractar
    // la misma canción si el usuario pulsa dos veces sobre ella.
    private val urlCache = ConcurrentHashMap<String, String>()

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

        // Reproducción automática en cuanto la sesión reciba una cola: al
        // pulsar una canción en Android Auto, el reproductor arranca solo.
        player.playWhenReady = true

        // Listener de shuffle: actualiza dinámicamente el ícono de la
        // notificación y Android Auto entre ON/OFF según el estado.
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                val icon = if (shuffleModeEnabled) {
                    CommandButton.ICON_SHUFFLE_ON
                } else {
                    CommandButton.ICON_SHUFFLE_OFF
                }
                val shuffleButton = CommandButton.Builder(icon)
                    .setDisplayName("Aleatorio")
                    .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                    .build()
                mediaLibrarySession?.setCustomLayout(listOf(shuffleButton))
            }
        })

        // Botón de shuffle para la notificación y Android Auto.
        // setCustomLayout alimenta la MediaNotificationProvider (barra de
        // controles de la notificación) y Android Auto lo proyecta en su UI.
        val shuffleButton = CommandButton.Builder(CommandButton.ICON_SHUFFLE_OFF)
            .setDisplayName("Aleatorio")
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
            .build()

        mediaLibrarySession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setCustomLayout(listOf(shuffleButton))
            .build()

        // Proveedor de notificaciones personalizado: inyecta el botón de
        // shuffle en la notificación del sistema (lo que Android Auto proyecta).
        // DefaultMediaNotificationProvider NO incluye shuffle por defecto.
        setMediaNotificationProvider(CustomNotificationProvider())
    }

    /**
     * Proveedor de notificaciones que extiende [DefaultMediaNotificationProvider]
     * para añadir el botón de shuffle a la barra de controles de la notificación.
     * Android Auto proyecta lo que ve en la notificación del sistema, así que
     * al añadir shuffle aquí aparece automáticamente en el salpicadero.
     */
    private inner class CustomNotificationProvider : MediaNotification.Provider {
        private val defaultProvider = DefaultMediaNotificationProvider(this@PlaybackService)

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification {
            // Inyecta el botón de shuffle en mediaButtonPreferences para que
            // DefaultMediaNotificationProvider lo incluya en la notificación.
            val player = mediaSession.player
            val shuffleIcon = if (player?.shuffleModeEnabled == true) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            }
            val shuffleButton = CommandButton.Builder(shuffleIcon)
                .setDisplayName("Aleatorio")
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()

            val updatedPrefs = ImmutableList.builder<CommandButton>()
                .add(shuffleButton)
                .addAll(mediaButtonPreferences)
                .build()

            return defaultProvider.createNotification(
                mediaSession,
                updatedPrefs,
                actionFactory,
                onNotificationChangedCallback,
            )
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
            defaultProvider.notificationChannelInfo
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaLibrarySession

    override fun onDestroy() {
        urlCache.clear()
        serviceJob.cancel()
        mediaLibrarySession?.run {
            player.release()
            release()
        }
        mediaLibrarySession = null
        super.onDestroy()
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

    // Convierte una canción de la app en un elemento reproducible: mediaId =
    // "playlistId:youtubeId" (con esto onSetMediaItems sabe a qué lista
    // pertenece para montar la cola completa) e incluye la carátula del vídeo.
    private fun Song.asMediaItem(playlistId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId("$playlistId$MEDIA_ID_SEPARATOR$youtubeId")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setDurationMs(durationSeconds * C.MILLIS_PER_SECOND)
                    .setArtworkUri(Uri.parse(imageUrl))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    // Extrae (playlistId, youtubeId) de un mediaId de canción. Si el mediaId no
    // tiene el prefijo de lista (p. ej. contenido externo), playlistId es null.
    private fun splitMediaId(mediaId: String): Pair<String?, String> {
        val index = mediaId.indexOf(MEDIA_ID_SEPARATOR)
        return if (index > 0) {
            mediaId.substring(0, index) to mediaId.substring(index + 1)
        } else {
            null to mediaId
        }
    }

    private fun youtubeThumbnailUri(youtubeId: String): Uri =
        Uri.parse("$THUMBNAIL_BASE_URL/$youtubeId/hqdefault.jpg")

    // Añade la carátula (si el ítem recibido no la trae) a partir del
    // youtubeId, para que la notificación y Android Auto muestren portada.
    private fun MediaItem.ensureArtwork(): MediaItem {
        if (mediaMetadata.artworkUri != null) return this
        val (_, youtubeId) = splitMediaId(mediaId)
        return buildUpon()
            .setMediaMetadata(
                mediaMetadata.buildUpon()
                    .setArtworkUri(youtubeThumbnailUri(youtubeId))
                    .build(),
            )
            .build()
    }

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
                .map { it.asMediaItem(playlistId) }
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

        // Habilita el botón de Aleatorio (shuffle) en Android Auto: sin este
        // comando en availablePlayerCommands, el salpicadero no dibuja el
        // icono de shuffle y el usuario no puede activar la reproducción
        // aleatoria desde el coche.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
            val playerCommands =
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val alreadyResolved = mediaItems.all { it.localConfiguration?.uri != null }
            if (alreadyResolved) {
                return Futures.immediateFuture(mediaItems)
            }

            val settableFuture = SettableFuture.create<MutableList<MediaItem>>()
            serviceScope.launch {
                try {
                    val (_, selectedYoutubeId) = splitMediaId(
                        mediaItems.firstOrNull()?.mediaId.orEmpty(),
                    )
                    val youtubeIds = mediaItems.map { item ->
                        val (_, ytId) = splitMediaId(item.mediaId)
                        ytId
                    }

                    Log.d(TAG_MEDIA, "onAddMediaItems: resolviendo ${youtubeIds.size} URLs")
                    val urls = YouTubeStreamResolver.resolveAudioUrls(youtubeIds)

                    val resolved = mediaItems.mapIndexed { index, item ->
                        val url = urls[index]
                        if (url != null) {
                            val (_, ytId) = splitMediaId(item.mediaId)
                            urlCache[ytId] = url
                            item.ensureArtwork().buildUpon()
                                .setUri(Uri.parse(url))
                                .build()
                        } else {
                            Log.w(TAG_MEDIA, "onAddMediaItems: sin audio para ${youtubeIds[index]}")
                            item.ensureArtwork()
                        }
                    }

                    val selectedIndex = if (selectedYoutubeId.isNotEmpty()) {
                        mediaItems.indexOfFirst { it.mediaId.endsWith(selectedYoutubeId) }
                            .takeIf { it >= 0 } ?: 0
                    } else 0

                    Log.d(
                        TAG_MEDIA,
                        "onAddMediaItems: ${urls.count { it != null }}/${youtubeIds.size} pistas resueltas",
                    )
                    settableFuture.set(resolved.toMutableList())
                } catch (e: Exception) {
                    Log.e(TAG_MEDIA, "onAddMediaItems: error resolviendo URLs", e)
                    settableFuture.set(mediaItems)
                }
            }
            return settableFuture
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaItemsWithStartPosition> {

            // Si los ítems ya vienen con URIs resueltas (syncPlaylist del
            // teléfono), pasámoslos directamente sin re-extraer.
            val alreadyResolved = mediaItems.all { it.localConfiguration?.uri != null }
            if (alreadyResolved) {
                Log.d(TAG_MEDIA, "onSetMediaItems: ítems ya resueltos, pasando directamente")
                return Futures.immediateFuture(
                    MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
                )
            }

            // Sin URI → es una llamada de Android Auto con mediaId
            // "playlistId:youtubeId". Resolvemos las URLs de audio en paralelo
            // ANTES de devolver los ítems: ExoPlayer REQUIERE que cada ítem
            // tenga una URI para poder crear el MediaSource.
            val settableFuture = SettableFuture.create<MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val youtubeIds = mediaItems.map { item ->
                        val (_, ytId) = splitMediaId(item.mediaId)
                        ytId
                    }

                    Log.d(TAG_MEDIA, "onSetMediaItems: resolviendo ${youtubeIds.size} URLs")
                    val urls = YouTubeStreamResolver.resolveAudioUrls(youtubeIds)

                    val resolvedItems = mediaItems.mapIndexed { index, item ->
                        val url = urls[index]
                        if (url != null) {
                            val (_, ytId) = splitMediaId(item.mediaId)
                            urlCache[ytId] = url
                            item.ensureArtwork().buildUpon()
                                .setUri(Uri.parse(url))
                                .build()
                        } else {
                            Log.w(TAG_MEDIA, "onSetMediaItems: sin audio para ${youtubeIds[index]}")
                            item.ensureArtwork()
                        }
                    }

                    Log.d(
                        TAG_MEDIA,
                        "onSetMediaItems: ${urls.count { it != null }}/${youtubeIds.size} pistas resueltas",
                    )
                    settableFuture.set(
                        MediaItemsWithStartPosition(resolvedItems, startIndex, startPositionMs),
                    )
                } catch (e: Exception) {
                    Log.e(TAG_MEDIA, "onSetMediaItems: error resolviendo URLs", e)
                    settableFuture.set(
                        MediaItemsWithStartPosition(
                            mediaItems.map { it.ensureArtwork() },
                            startIndex,
                            startPositionMs,
                        ),
                    )
                }
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
