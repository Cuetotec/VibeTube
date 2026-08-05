package com.cuetotech.vibetube.player

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

// User-Agent de navegador: los streams de YouTube pueden rechazar peticiones
// con User-Agent de Android/ExoPlayer, así que se envían con el de Chrome.
private const val STREAM_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/**
 * Servicio multimedia que aloja el [ExoPlayer] y su [MediaSession]. La UI/el
 * ViewModel se conectan a este servicio mediante un [androidx.media3.session.MediaController]
 * (a través de [PlaybackServiceToken]) y le envían las [androidx.media3.common.MediaItem]
 * con la URL de audio real extraída de YouTube. Al ser un servicio en primer
 * plano (`foregroundServiceType="mediaPlayback"`), la reproducción continúa con
 * la pantalla apagada y el centro de control/la pantalla de bloqueo muestran los
 * controles (play/pausa, siguiente, anterior) y la notificación con los
 * metadatos de la canción.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // DataSource con User-Agent de navegador y soporte de redirecciones
        // cross-protocol (los enlaces de stream de YouTube suelen redirigir).
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(dataSourceFactory),
            )
            .build()

        // Mantiene el streaming con la pantalla apagada: WAKE_MODE_NETWORK
        // adquiere un wakelock parcial + lock de red mientras reproduce, para
        // que la CPU y la red no se suspendan al bloquear el móvil y el audio
        // no se corte por quedarse el player sin datos.
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        // Foco de audio de una app de música (necesario para convivir con otras
        // apps y para que la notificación/centro de control gestionen el audio).
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        // Pausa al desconectar auriculares/altavoces externos (comportamiento
        // estándar de una app de música) en lugar de seguir sonando por el
        // altavoz del móvil.
        player.setHandleAudioBecomingNoisy(true)

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
