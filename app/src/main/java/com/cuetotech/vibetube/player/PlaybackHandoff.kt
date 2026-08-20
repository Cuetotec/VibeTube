package com.cuetotech.vibetube.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "VibeTubeHandoff"

// Tiempo máximo de espera por la posición del WebView (getCurrentTime vía JS);
// si el reproductor no responde, se reanuda el servicio sin buscar.
private const val POSITION_TIMEOUT_MS = 1_000L

/**
 * Coordina la conmutación (handoff) entre el reproductor WebView (el vídeo
 * visible en primer plano) y el [PlaybackService] (el audio real en segundo
 * plano), basándose en el estado de la pantalla y de la app:
 *
 *  - **Primer plano** (pantalla encendida Y app visible): suena el WebView. El
 *    ExoPlayer del servicio queda en pausa (conservando la cola y la posición)
 *    para no producir "audio doble".
 *  - **Segundo plano** (pantalla apagada O app en segundo plano): el WebView se
 *    pausa y silencia, y el servicio reanuda la reproducción DESDE la posición
 *    exacta que tenía el WebView.
 *
 * El estado de primer plano se deriva de los broadcasts del sistema
 * [Intent.ACTION_SCREEN_ON]/[Intent.ACTION_SCREEN_OFF] y del ciclo de vida del
 * proceso ([ProcessLifecycleOwner]): primer plano = pantalla encendida && app
 * en primer plano.
 *
 * Todas las transiciones se ejecutan en el [scope] del ViewModel (seguro para
 * corrutinas); las lecturas de posición del WebView son asíncronas (JS) con
 * timeout y tolerantes a nulos, de modo que ningún fallo de lectura bloquea o
 * rompe la conmutación.
 */
class PlaybackHandoff(
    private val appContext: Context,
    private val playbackController: PlaybackController,
    private val scope: CoroutineScope,
) {

    private val _isForeground = MutableStateFlow(true)

    /** true cuando la pantalla está encendida y la app en primer plano. */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /**
     * Control del reproductor WebView que registra [YouTubePlayerView] al
     * componerse. Al registrarse/destruirse se reacciona para mantener el
     * servicio y el WebView en el estado correcto (ver [onWebPlayerAttached]).
     */
    val webPlayer = WebPlayerControlHandle(
        onAttached = { scope.launch { onWebPlayerAttached() } },
        onDetached = { scope.launch { onWebPlayerDetached() } },
    )

    private var screenOn = true
    private var processStarted = true
    private var receiverRegistered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> setScreenState(true)
                Intent.ACTION_SCREEN_OFF -> setScreenState(false)
            }
        }
    }

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            processStarted = true
            evaluateForeground()
        }

        override fun onStop(owner: LifecycleOwner) {
            processStarted = false
            evaluateForeground()
        }
    }

    /**
     * Registra el receptor de pantalla y el observador del ciclo de vida del
     * proceso, y arranca la vigilancia del estado del servicio. Idempotente;
     * se llama desde el init del ViewModel.
     */
    fun start() {
        if (receiverRegistered) return
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = powerManager?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            appContext,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        // Si el servicio se activa (o reconecta) estando la app en primer plano,
        // su audio se pausa de inmediato: el sonido lo aporta el WebView y así
        // se evita el "audio doble" entre el arranque del servicio y la primera
        // transición explícita. StateFlow ya no re-emite valores repetidos.
        scope.launch {
            playbackController.isActive.collect { active ->
                    if (active && _isForeground.value) {
                        Log.d(TAG, "Servicio activo en primer plano: pausando ExoPlayer")
                        playbackController.pause()
                    }
                }
        }
    }

    /** Des-registra los receptores al destruirse el ViewModel. */
    fun stop() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver) }
    }

    private fun setScreenState(on: Boolean) {
        screenOn = on
        evaluateForeground()
    }

    private fun evaluateForeground() {
        val foreground = screenOn && processStarted
        val previous = _isForeground.value
        if (foreground == previous) return
        _isForeground.value = foreground
        if (foreground) {
            scope.launch { handOffToWebView() }
        } else {
            scope.launch { handOffToService() }
        }
    }

    /**
     * Transición a segundo plano (pantalla apagada o app en segundo plano):
     * 1) captura la posición del WebView, 2) pausa y silencia el WebView,
     * 3) el servicio reanuda desde esa posición.
     */
    private suspend fun handOffToService() {
        if (!playbackController.isActive.value) {
            Log.d(TAG, "handOffToService: sin sesión en el servicio, se omite")
            return
        }
        Log.d(TAG, "handOffToService: segundo plano, delegando el audio al servicio")
        val position = readWebViewPosition()
        webPlayer.pause()
        webPlayer.setMuted(true)
        playbackController.playFromPosition(position)
    }

    /**
     * Transición a primer plano (pantalla encendida y app visible):
     * 1) captura la posición del ExoPlayer, 2) pausa el servicio, 3) el WebView
     * busca a esa posición, se des-silencia y reanuda.
     */
    private suspend fun handOffToWebView() {
        if (!playbackController.isActive.value) {
            Log.d(TAG, "handOffToWebView: sin sesión en el servicio, se omite")
            return
        }
        if (!webPlayer.isAttached()) {
            Log.d(
                TAG,
                "handOffToWebView: WebView no visible, el servicio sigue reproduciendo el audio",
            )
            return
        }
        Log.d(TAG, "handOffToWebView: primer plano, el WebView retoma el audio")
        val position = playbackController.currentPosition()
        playbackController.pause()
        webPlayer.setMuted(false)
        if (position != null && position > 0) {
            webPlayer.seekTo(position)
        }
        webPlayer.play()
    }

    // El WebView se acaba de componer con una sesión activa: sincroniza su
    // posición con la del servicio y lo deja como fuente de audio en primer
    // plano (p. ej. al volver a la pantalla de la lista con el servicio en
    // pausa, o al seleccionar una canción por primera vez).
    private suspend fun onWebPlayerAttached() {
        if (playbackController.isActive.value && _isForeground.value) {
            Log.d(TAG, "WebView registrado en primer plano: sincronizando con el servicio")
            handOffToWebView()
        }
    }

    // El WebView se destruyó (navegación a otra pantalla) con una sesión
    // activa: nadie puede sustituirlo, así que el servicio reanuda el audio
    // para que la música no se corte.
    private suspend fun onWebPlayerDetached() {
        if (playbackController.isActive.value && _isForeground.value) {
            Log.d(TAG, "WebView destruido en primer plano: el servicio reanuda el audio")
            playbackController.play()
        }
    }

    /**
     * Se invoca desde el ViewModel tras sincronizar o saltar de pista: aplica
     * la política de audio según el estado actual (pausar el servicio en
     * primer plano, reproducir en segundo plano).
     */
    suspend fun onPlaybackSynced() {
        if (_isForeground.value) {
            playbackController.pause()
        } else {
            playbackController.play()
        }
    }

    // Lee la posición del WebView (ms) con timeout y tolerante a nulos: si el
    // WebView no está registrado o no responde (player no listo), devuelve null
    // y la transición reanuda el servicio desde su posición actual.
    private suspend fun readWebViewPosition(): Long? {
        if (!webPlayer.isAttached()) return null
        return withTimeoutOrNull(POSITION_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                webPlayer.currentPosition { position ->
                    // Protección contra doble reanudación (timeout vs. callback):
                    // el timeout cancela la corrutina, y el callback tardío es no-op.
                    if (continuation.isActive) {
                        continuation.resume(position)
                    }
                }
            }
        }
    }
}
