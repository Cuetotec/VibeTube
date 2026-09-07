@file:OptIn(UnstableApi::class)
package com.cuetotech.vibetube.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import java.util.concurrent.ConcurrentHashMap

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

    private val serviceJob = SupervisorJob()
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG_MEDIA, "serviceScope: excepción no capturada", exception)
    }
    private val serviceScope =
        CoroutineScope(Dispatchers.Main + serviceJob + serviceExceptionHandler)

    private val urlCache = ConcurrentHashMap<String, String>()
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

            // Provider de notificaciones: DEBE registrarse ANTES de la sesión
            // para que MediaLibraryService lo encuentre al crear la notificación.
            setMediaNotificationProvider(CustomNotificationProvider())

            // Sesión con layout personalizado (shuffle) para Android Auto.
            mediaLibrarySession =
                MediaLibrarySession.Builder(this, exoPlayer, LibraryCallback())
                    .setCustomLayout(ImmutableList.of(buildShuffleButton()))
                    .build()

            // Actualiza el layout dinámico cuando cambia el modo shuffle.
            exoPlayer.addListener(object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    updateCustomLayout()
                }
            })

            Log.d(TAG_MEDIA, "PlaybackService: onCreate OK")
        } catch (e: Exception) {
            Log.e(TAG_MEDIA, "PlaybackService: error en onCreate", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Shuffle button helpers
    // ──────────────────────────────────────────────────────────────

    private fun buildShuffleButton(): CommandButton = CommandButton.Builder(
        if (::exoPlayer.isInitialized && exoPlayer.shuffleModeEnabled)
            CommandButton.ICON_SHUFFLE_ON
        else
            CommandButton.ICON_SHUFFLE_OFF,
    )
        .setDisplayName("Aleatorio")
        .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
        .build()

    private fun updateCustomLayout() {
        mediaLibrarySession?.setCustomLayout(ImmutableList.of(buildShuffleButton()))
    }

    // ──────────────────────────────────────────────────────────────
    //  CustomNotificationProvider
    //
    //  Implementa MediaNotification.Provider y delega en
    //  DefaultMediaNotificationProvider. Inyecta el botón de shuffle
    //  en la lista de mediaButtonPreferences para que la notificación
    //  del sistema (y Android Auto) lo muestre.
    // ──────────────────────────────────────────────────────────────

    private inner class CustomNotificationProvider : MediaNotification.Provider {
        private val defaultProvider = DefaultMediaNotificationProvider(this@PlaybackService)

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = try {
            val shuffleButton = CommandButton.Builder(
                if (exoPlayer.shuffleModeEnabled)
                    CommandButton.ICON_SHUFFLE_ON
                else
                    CommandButton.ICON_SHUFFLE_OFF,
            )
                .setDisplayName("Aleatorio")
                .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE)
                .build()

            val customButtons = ImmutableList.builder<CommandButton>()
                .add(shuffleButton)
                .addAll(mediaButtonPreferences)
                .build()

            defaultProvider.createNotification(
                mediaSession,
                customButtons,
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
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().build()
            val playerCommands =
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .add(Player.COMMAND_SET_SHUFFLE_MODE)
                    .add(Player.COMMAND_GET_TIMELINE)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            return MediaSession.ConnectionResult.accept(sessionCommands, playerCommands)
        }

        /**
         * Resuelve la URL de audio de la canción seleccionada en `Dispatchers.IO`,
         * retorna a ExoPlayer con esa URI (latencia mínima), y resuelve el resto
         * de la cola en background.
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
                    val youtubeIds = mediaItems.map { splitMediaId(it.mediaId).second }
                    val targetIndex = startIndex.coerceIn(mediaItems.indices)
                    val targetYtId = youtubeIds[targetIndex]

                    // 1. Resolver la canción seleccionada en IO
                    if (!urlCache.containsKey(targetYtId)) {
                        val url = withContext(Dispatchers.IO) {
                            YouTubeStreamResolver.resolveAudioUrls(listOf(targetYtId))
                                .firstOrNull()
                        }
                        if (url != null) urlCache[targetYtId] = url
                    }

                    // 2. Preparar items con URIs disponibles
                    val prepared = mediaItems.mapIndexed { index, item ->
                        val cached = urlCache[youtubeIds[index]]
                        if (cached != null) item.buildUpon().setUri(Uri.parse(cached)).build()
                        else item
                    }

                    // 3. Responder a ExoPlayer/Auto inmediatamente
                    settableFuture.set(
                        MediaItemsWithStartPosition(prepared, targetIndex, startPositionMs),
                    )

                    // 4. Resolver el resto en background (IO)
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
                    }
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
                            val playlist = withContext(Dispatchers.IO) {
                                playlistRepository.getPlaylist(parentId)
                            }
                            val songs = playlist?.tracks.orEmpty()
                                .map { it.asMediaItem(parentId) }
                            Log.d(TAG_MEDIA, "onGetChildren($parentId): ${songs.size} canciones")
                            ImmutableList.copyOf(songs)
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
