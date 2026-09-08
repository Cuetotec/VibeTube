@file:OptIn(UnstableApi::class)
package com.cuetotech.vibetube.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val ROOT_ID = "root_vibetube"
private const val MEDIA_ID_SEPARATOR = ":"
private const val TAG_MEDIA = "VibeTubeMedia"

/**
 * Servicio de reproducción en segundo plano para Android Auto y notificaciones.
 *
 * Diseño asíncrono garantizado:
 * - `onGetLibraryRoot` y `onConnect` retornan inmediatamente (Futures.immediateFuture).
 * - `onGetChildren` ejecuta Firestore en `Dispatchers.IO`.
 * - `onSetMediaItems` resuelve la URI de la canción seleccionada en `Dispatchers.IO`,
 *   responde a ExoPlayer/Auto inmediatamente, y resuelve el resto en background.
 * - `CustomNotificationProvider` implementa `MediaNotification.Provider` y delega
 *   en `DefaultMediaNotificationProvider` inyectando el botón de shuffle.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var exoPlayer: ExoPlayer

    /**
     * Player "envuelto" que se pasa a la MediaSession: mantiene SIEMPRE activos
     * COMMAND_SEEK_TO_NEXT / COMMAND_SEEK_TO_PREVIOUS (y shuffle/repeat) en
     * [Player.getAvailableCommands], aunque el timeline cambie dinámicamente.
     * Sin esto, con un solo item en la cola ExoPlayer reporta `availableCommands`
     * sin seek-next/prev y Android Auto deshabilita el mandodel volante.
     */
    private lateinit var sessionPlayer: ForwardingPlayer

    private val serviceJob = SupervisorJob()
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG_MEDIA, "serviceScope: excepción no capturada", exception)
    }
    private val serviceScope =
        CoroutineScope(Dispatchers.Main + serviceJob + serviceExceptionHandler)

    private val urlCache = ConcurrentHashMap<String, String>()
    private val artworkCache = ConcurrentHashMap<String, ByteArray>()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val authRepository = AuthRepository()
    private val playlistRepository = PlaylistRepository()

    // ──────────────────────────────────────────────────────────────
    //  onCreate — todo síncrono, todo en memoria, < 100 ms
    // ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(STREAM_USER_AGENT)
                .setAllowCrossProtocolRedirects(true)

            exoPlayer = ExoPlayer.Builder(this)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory),
                )
                .build()

            exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK)
            exoPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            exoPlayer.setHandleAudioBecomingNoisy(true)
            exoPlayer.playWhenReady = true

            // La sesión usa el player envuelto para que availableCommands incluya
            // siempre los comandos de transporte (mandos del volante / botones AA).
            sessionPlayer = TransportCommandsPlayer(exoPlayer)

            // Provider de notificaciones: DEBE registrarse ANTES de la sesión
            // para que MediaLibraryService lo encuentre al crear la notificación.
            setMediaNotificationProvider(CustomNotificationProvider())

            // Sesión con layout personalizado (prev/next/shuffle) para Android Auto
            // y preferencias explícitas de botones para la notificación del teléfono.
            mediaLibrarySession =
                MediaLibrarySession.Builder(this, sessionPlayer, LibraryCallback())
                    .setCustomLayout(buildAndroidAutoLayout())
                    .setMediaButtonPreferences(buildMediaButtonPreferences())
                    .build()

            // Actualiza el layout dinámico cuando cambia el modo shuffle.
            exoPlayer.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateCustomLayout()
                    mediaLibrarySession?.setMediaButtonPreferences(
                        buildMediaButtonPreferences(),
                    )
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    Log.d(
                        TAG_MEDIA,
                        "onMediaItemTransition: idx=${exoPlayer.currentMediaItemIndex}/" +
                            "${exoPlayer.mediaItemCount} uri=${mediaItem?.localConfiguration?.uri}",
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    Log.d(TAG_MEDIA, "onPlaybackStateChanged: state=$playbackState")
                }
            })

            Log.d(TAG_MEDIA, "PlaybackService: onCreate OK")
        } catch (e: Exception) {
            Log.e(TAG_MEDIA, "PlaybackService: error en onCreate", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Button helpers (Android Auto custom layout + notificación)
    // ──────────────────────────────────────────────────────────────

    private fun buildPrevButton(): CommandButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
        .setDisplayName("Anterior")
        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        .build()

    private fun buildNextButton(): CommandButton = CommandButton.Builder(CommandButton.ICON_NEXT)
        .setDisplayName("Siguiente")
        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        .build()

    private fun buildShuffleButton(): CommandButton = CommandButton.Builder(
        if (::exoPlayer.isInitialized && exoPlayer.shuffleModeEnabled)
            CommandButton.ICON_SHUFFLE_ON
        else
            CommandButton.ICON_SHUFFLE_OFF,
    )
        .setDisplayName("Aleatorio")
        .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
        .build()

    /**
     * Layout explícito para Android Auto en sus ranuras de control:
     * anterior, siguiente y aleatorio.
     */
    private fun buildAndroidAutoLayout(): ImmutableList<CommandButton> =
        ImmutableList.of(
            buildPrevButton(),
            buildNextButton(),
            buildShuffleButton(),
        )

    /**
     * Preferencias de botones de la notificación del teléfono.
     *
     * `DefaultMediaNotificationProvider.getMediaButtons` SOLO muestra botones de
     * `mediaButtonPreferences` que tengan un slot (`SLOT_BACK`, `SLOT_FORWARD` o
     * `SLOT_OVERFLOW`); un botón sin slot se descarta por completo (por eso antes
     * no aparecía el shuffle). Con estos slots:
     *   - shuffle → SLOT_BACK (1ª ranura, visible en compact view)
     *   - next    → SLOT_FORWARD (3ª ranura, compact)
     *   - prev    → SLOT_OVERFLOW (bajo la barra, al expandir)
     * play/pause lo añade automáticamente el provider en la ranura central.
     */
    private fun buildMediaButtonPreferences(): ImmutableList<CommandButton> =
        ImmutableList.of(
            CommandButton.Builder(
                if (::exoPlayer.isInitialized && exoPlayer.shuffleModeEnabled)
                    CommandButton.ICON_SHUFFLE_ON
                else
                    CommandButton.ICON_SHUFFLE_OFF,
            )
                .setDisplayName("Aleatorio")
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Siguiente")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("Anterior")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )

    private fun updateCustomLayout() {
        mediaLibrarySession?.setCustomLayout(buildAndroidAutoLayout())
    }

    // ──────────────────────────────────────────────────────────────
    //  TransportCommandsPlayer
    //
    //  Mantiene siempre activos los comandos de transporte en
    //  Player.getAvailableCommands() para que Android Auto y el volante
    //  tengan next/prev/shuffle/repeat aunque la cola sea de un solo item
    //  o cambie dinámicamente. Solo AÑADE comandos (no elimina ninguno),
    //  así que no hace falta ocultar onAvailableCommandsChanged.
    // ──────────────────────────────────────────────────────────────

    private inner class TransportCommandsPlayer(player: Player) : ForwardingPlayer(player) {

        private val alwaysAvailableCommands = intArrayOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
        )

        override fun isCommandAvailable(command: Int): Boolean =
            alwaysAvailableCommands.contains(command) || super.isCommandAvailable(command)

        override fun getAvailableCommands(): Player.Commands {
            val builder = super.getAvailableCommands().buildUpon()
            alwaysAvailableCommands.forEach(builder::add)
            return builder.build()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  CustomNotificationProvider
    //
    //  Implementa MediaNotification.Provider y delega en
    //  DefaultMediaNotificationProvider. Los botones (incluido el shuffle)
    //  se controlan vía setCustomLayout + setMediaButtonPreferences de la
    //  sesión, por lo que aquí solo se delega.
    // ──────────────────────────────────────────────────────────────

    private inner class CustomNotificationProvider : MediaNotification.Provider {
        private val defaultProvider = DefaultMediaNotificationProvider(this@PlaybackService)

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = try {
            defaultProvider.createNotification(
                mediaSession,
                mediaButtonPreferences,
                actionFactory,
                onNotificationChangedCallback,
            )
        } catch (e: Exception) {
            Log.e(TAG_MEDIA, "CustomNotificationProvider: error, usando default", e)
            defaultProvider.createNotification(
                mediaSession,
                mediaButtonPreferences,
                actionFactory,
                onNotificationChangedCallback,
            )
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle,
        ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo() = defaultProvider.notificationChannelInfo
    }

    // ──────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) =
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

    // ──────────────────────────────────────────────────────────────
    //  Helpers de mediaId
    // ──────────────────────────────────────────────────────────────

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

    private fun splitMediaId(mediaId: String): Pair<String?, String> {
        val index = mediaId.indexOf(MEDIA_ID_SEPARATOR)
        return if (index > 0) mediaId.substring(0, index) to mediaId.substring(index + 1)
        else null to mediaId
    }

    /**
     * Descarga los bytes de la portada desde una URL remota (en `Dispatchers.IO`)
     * y los cachea por youtubeId. Devuelve null si falla la descarga.
     */
    private suspend fun fetchArtworkData(youtubeId: String, imageUrl: String): ByteArray? {
        if (imageUrl.isBlank()) return null
        artworkCache[youtubeId]?.let { return it }
        val bytes = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(imageUrl).build()
            try {
                httpClient.newCall(request).execute().use { response: Response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()
                    } else {
                        Log.w(TAG_MEDIA, "fetchArtworkData: HTTP ${response.code} para $imageUrl")
                        null
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG_MEDIA, "fetchArtworkData: error descargando $imageUrl", e)
                null
            }
        }
        if (bytes != null) artworkCache[youtubeId] = bytes
        return bytes
    }

    /** Devuelve el [MediaItem] con el artwork embebido (artworkData) si está disponible. */
    private fun withArtwork(item: MediaItem, youtubeId: String): MediaItem {
        val artwork = artworkCache[youtubeId] ?: return item
        val metadata = item.mediaMetadata
            .buildUpon()
            .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        return item.buildUpon().setMediaMetadata(metadata).build()
    }

    /**
     * Carga la playlist completa desde Firestore (en `Dispatchers.IO`) y devuelve
     * sus canciones como [MediaItem] con artwork embebido. Usado tanto por
     * [LibraryCallback.onGetChildren] (browse de Android Auto) como por
     * [LibraryCallback.onSetMediaItems] para expandir un item seleccionado a la
     * cola completa de su playlist/folder.
     */
    private suspend fun loadPlaylistItems(playlistId: String): ImmutableList<MediaItem> {
        val playlist = withContext(Dispatchers.IO) {
            playlistRepository.getPlaylist(playlistId)
        }
        val rawSongs = playlist?.tracks.orEmpty()
        if (rawSongs.isEmpty()) {
            Log.w(TAG_MEDIA, "loadPlaylistItems($playlistId): playlist sin canciones")
            return ImmutableList.of()
        }
        // Descarga y cachea las portadas antes de servir los items.
        val artworkByYt = rawSongs.associateWith { song ->
            fetchArtworkData(song.youtubeId, song.imageUrl)
        }
        val items = rawSongs.map { song ->
            song.asMediaItem(playlistId).let { item ->
                val artwork = artworkByYt[song]
                if (artwork != null) {
                    val metadata = item.mediaMetadata
                        .buildUpon()
                        .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build()
                    item.buildUpon().setMediaMetadata(metadata).build()
                } else {
                    item
                }
            }
        }
        Log.d(TAG_MEDIA, "loadPlaylistItems($playlistId): ${items.size} canciones")
        return ImmutableList.copyOf(items)
    }

    /**
     * Prepara y entrega una cola de [MediaItem] al reproductor:
     * 1. Resuelve la URL de la canción seleccionada (latencia mínima, arranque
     *    rápido) y la portada.
     * 2. Responde a ExoPlayer/Auto INMEDIATAMENTE con la lista completa en
     *    [MediaItemsWithStartPosition] (indice inicial = canción elegida).
     * 3. Resuelve el resto de URLs en background y rellena las URIs en el
     *    timeline ya entregado (auto-advance sin cortes).
     */
    private suspend fun resolveQueueFuture(
        settableFuture: SettableFuture<MediaItemsWithStartPosition>,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        val youtubeIds = mediaItems.map { splitMediaId(it.mediaId).second }
        val targetIndex = if (mediaItems.isEmpty()) 0 else startIndex.coerceIn(mediaItems.indices)
        val targetYtId = youtubeIds.getOrNull(targetIndex)

        // 1. Resolver la canción seleccionada en IO (arranque rápido)
        if (targetYtId != null && !urlCache.containsKey(targetYtId)) {
            val url = withContext(Dispatchers.IO) {
                YouTubeStreamResolver.resolveAudioUrls(listOf(targetYtId))
                    .firstOrNull()
            }
            if (url != null) urlCache[targetYtId] = url
        }

        // 2. Portada de la canción seleccionada
        val targetItem = mediaItems.getOrNull(targetIndex)
        if (targetItem != null && targetYtId != null) {
            val targetImageUrl = targetItem.mediaMetadata.artworkUri?.toString().orEmpty()
            if (artworkCache[targetYtId] == null && targetImageUrl.isNotBlank()) {
                fetchArtworkData(targetYtId, targetImageUrl)
            }
        }

        // 3. Preparar items con URIs y artwork disponibles
        val prepared = mediaItems.mapIndexed { index, item ->
            val cached = urlCache[youtubeIds[index]]
            val withUrl = if (cached != null) {
                item.buildUpon().setUri(Uri.parse(cached)).build()
            } else {
                item
            }
            withArtwork(withUrl, youtubeIds[index])
        }

        // 4. Responder a ExoPlayer/Auto inmediatamente con la cola completa
        settableFuture.set(
            MediaItemsWithStartPosition(prepared, targetIndex, startPositionMs),
        )

        // 5. Resolver el resto en background (IO) y, al terminar, rellenar las
        //    URIs en el timeline que ya se entregó para que el auto-advance a la
        //    siguiente canción tenga URL.
        val remaining = youtubeIds.filterIndexed { i, id ->
            i != targetIndex && !urlCache.containsKey(id)
        }
        if (remaining.isNotEmpty()) {
            val urls = withContext(Dispatchers.IO) {
                YouTubeStreamResolver.resolveAudioUrls(remaining)
            }
            urls.forEachIndexed { i, url ->
                url?.let { urlCache[remaining[i]] = it }
            }
            backfillTimelineUris()
        }
    }

    /**
     * Copia en el timeline ACTUAL de ExoPlayer las URIs que ya estén resueltas en
     * [urlCache] para los MediaItems que llegaron sin [MediaItem.LocalConfiguration].
     * Sin este paso, al entregar la cola completa sin URIs para las canciones
     * siguientes, ExoPlayer no podría reproducirlas al avanzar de canción.
     */
    private fun backfillTimelineUris() {
        try {
            if (!::exoPlayer.isInitialized) return
            val count = exoPlayer.mediaItemCount
            if (count == 0) return
            var changed = false
            val rebuilt = (0 until count).map { index ->
                val item = exoPlayer.getMediaItemAt(index)
                val ytId = splitMediaId(item.mediaId).second
                urlCache[ytId]?.let { url ->
                    if (item.localConfiguration?.uri != null) {
                        item
                    } else {
                        changed = true
                        item.buildUpon().setUri(Uri.parse(url)).build()
                    }
                } ?: item
            }
            if (changed) {
                Log.d(TAG_MEDIA, "backfillTimelineUris: rellenando URIs de $count items")
                exoPlayer.replaceMediaItems(0, count, rebuilt)
            }
        } catch (e: Exception) {
            Log.w(TAG_MEDIA, "backfillTimelineUris: no se pudo rellenar URIs", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  LibraryCallback — TODOS los callbacks son async o instantáneos
    // ──────────────────────────────────────────────────────────────

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /** Retorna inmediatamente. Sin I/O, sin corutinas. */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d(
                TAG_MEDIA,
                "onGetLibraryRoot: client=${browser.packageName} uid=${browser.uid}",
            )
            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .setTitle("Listas de VibeTube")
                        .build(),
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        /** Retorna inmediatamente. Expone shuffle + timeline + controles. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            Log.d(
                TAG_MEDIA,
                "onConnect: client=${controller.packageName} uid=${controller.uid}",
            )
            // IMPORTANTE: usar DEFAULT_SESSION_AND_LIBRARY_COMMANDS. Incluye las
            // library commands (COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT, etc.) que
            // SystemUI/MediaBrowserCompat necesitan para resolver el root vía la
            // API legacy. Si solo se exponen las session commands, SystemUI recibe
            // "No root for client com.android.systemui".
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            // Comandos de transporte explícitos: control de pista siguiente/anterior,
            // modo de reproducción y aleatorio, tanto en la notificación como en
            // Android Auto.
            val playerCommands =
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_SET_REPEAT_MODE)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        /**
         * Intercepta/permite los botones físicos del volante y auriculares.
         *
         * Media3 ya mapea KEYCODE_MEDIA_NEXT/PREVIOUS a seekToNext/Precious en
         * [androidx.media3.session.MediaSessionImpl], pero lo hacemos explícito
         * para garantizar que el volante de Android Auto cambie de canción. La
         * llamada ocurre en el hilo de aplicación (Media3 lo verifica antes de
         * invocar el callback), por lo que no hay race con el player.
         */
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            Log.d(
                TAG_MEDIA,
                "onMediaButtonEvent: action=${intent.action} " +
                    "keyCode=${keyEvent?.keyCode} client=${controllerInfo.packageName}",
            )
            if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        exoPlayer.seekToNextMediaItem()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                        exoPlayer.seekToPreviousMediaItem()
                        return true
                    }
                }
            }
            // Otros eventos: los maneja Media3 internamente (play/pause, stop, ...).
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        /**
         * Sincroniza la COLA COMPLETA en ExoPlayer, no solo el item pulsado.
         *
         * Cuando Android Auto (o el control remoto legacy `playFromMediaId`)
         * selecciona una canción, suele llegar UN SOLO [MediaItem]. Si se le
         * entregara tal cual, ExoPlayer tendría un timeline de 1 item: los botones
         * next/prev no hacen nada y al terminar la canción se detiene.
         *
         * Aquí se expande ese item a la playlist/folder completa a la que pertenece
         * (vía el prefijo `playlistId` del mediaId) y se devuelve la lista completa
         * en [MediaItemsWithStartPosition], marcando como índice inicial la canción
         * elegida. Si el cliente ya envió la lista completa, se usa tal cual.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> {
            val settableFuture = SettableFuture.create<MediaItemsWithStartPosition>()

            serviceScope.launch {
                try {
                    // El cliente ya envió la cola completa: resolver tal cual.
                    if (mediaItems.size > 1) {
                        resolveQueueFuture(settableFuture, mediaItems, startIndex, startPositionMs)
                        return@launch
                    }

                    // Item único: intentar expandir a la playlist completa.
                    val mediaId = mediaItems.firstOrNull()?.mediaId
                    val (playlistId, youtubeId) = splitMediaId(mediaId.orEmpty())
                    if (playlistId != null && playlistId.isNotBlank()) {
                        val playlistItems = withContext(Dispatchers.IO) {
                            loadPlaylistItems(playlistId)
                        }
                        if (playlistItems.isNotEmpty()) {
                            val targetIndex = playlistItems.indexOfFirst {
                                splitMediaId(it.mediaId).second == youtubeId
                            }.let { if (it >= 0) it else 0 }
                            Log.d(
                                TAG_MEDIA,
                                "onSetMediaItems: expandiendo item único a ${playlistItems.size} " +
                                    "canciones (target=$targetIndex) de $playlistId",
                            )
                            resolveQueueFuture(
                                settableFuture,
                                playlistItems,
                                targetIndex,
                                startPositionMs,
                            )
                            return@launch
                        }
                    }

                    // Sin contexto de playlist: resolver el item único tal cual.
                    resolveQueueFuture(settableFuture, mediaItems, startIndex, startPositionMs)
                } catch (e: Exception) {
                    Log.e(TAG_MEDIA, "onSetMediaItems: error", e)
                    settableFuture.set(
                        MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs),
                    )
                }
            }
            return settableFuture
        }

        /**
         * Resuelve las URIs de items añadidos con `MediaController.addQueueItem`
         * (o legacy `addQueueItem`), que llegan sin [MediaItem.LocalConfiguration].
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val settableFuture = SettableFuture.create<List<MediaItem>>()

            serviceScope.launch {
                try {
                    val youtubeIds = mediaItems.map { splitMediaId(it.mediaId).second }
                    val missing = youtubeIds.filter { id ->
                        id.isNotBlank() && urlCache[id] == null
                    }
                    if (missing.isNotEmpty()) {
                        val urls = withContext(Dispatchers.IO) {
                            YouTubeStreamResolver.resolveAudioUrls(missing)
                        }
                        urls.forEachIndexed { i, url ->
                            url?.let { urlCache[missing[i]] = it }
                        }
                    }
                    val resolved = mediaItems.mapIndexed { i, item ->
                        urlCache[youtubeIds[i]]?.let {
                            item.buildUpon().setUri(Uri.parse(it)).build()
                        } ?: item
                    }
                    settableFuture.set(resolved)
                } catch (e: Exception) {
                    Log.e(TAG_MEDIA, "onAddMediaItems: error", e)
                    settableFuture.set(mediaItems)
                }
            }
            return settableFuture
        }

        /**
         * Carga playlists/canciones desde Firestore en `Dispatchers.IO`.
         */
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val settableFuture =
                SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()

            serviceScope.launch {
                try {
                    val userId = authRepository.currentUser()?.uid
                    if (userId == null) {
                        settableFuture.set(
                            LibraryResult.ofItemList(ImmutableList.of(), params),
                        )
                        return@launch
                    }

                    val items: ImmutableList<MediaItem> = when (parentId) {
                        ROOT_ID -> {
                            val playlists = withContext(Dispatchers.IO) {
                                playlistRepository.getUserPlaylists(userId)
                            }
                            Log.d(TAG_MEDIA, "onGetChildren(root): ${playlists.size} listas")
                            ImmutableList.copyOf(
                                playlists.map { p ->
                                    MediaItem.Builder()
                                        .setMediaId(p.id)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(p.title)
                                                .setIsBrowsable(true)
                                                .setIsPlayable(false)
                                                .setFolderType(MediaMetadata.FOLDER_TYPE_PLAYLISTS)
                                                .build(),
                                        )
                                        .build()
                                },
                            )
                        }

                        else -> {
                            // Reutiliza loadPlaylistItems: carga canciones + artwork.
                            val songs = loadPlaylistItems(parentId)
                            Log.d(TAG_MEDIA, "onGetChildren($parentId): ${songs.size} canciones")
                            songs
                        }
                    }

                    settableFuture.set(LibraryResult.ofItemList(items, params))
                } catch (e: Exception) {
                    Log.e(TAG_MEDIA, "onGetChildren: error para parentId=$parentId", e)
                    settableFuture.set(
                        LibraryResult.ofItemList(ImmutableList.of(), params),
                    )
                }
            }
            return settableFuture
        }
    }
}
