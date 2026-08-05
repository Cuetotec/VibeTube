package com.cuetotech.vibetube.player

/**
 * Control de bajo nivel sobre el reproductor WebView usado por
 * [PlaybackHandoff]. Lo implementa internamente [YouTubePlayerView] y se
 * expone a través de [WebPlayerControlHandle].
 *
 * Todos los métodos deben ser seguros de llamar desde cualquier hilo: la
 * implementación interna encola las operaciones en el hilo principal del
 * WebView ([android.webkit.WebView.post]) y ejecuta el JavaScript del player
 * de YouTube con la comprobación defensiva de que `window.player` exista.
 */
interface WebPlayerControl {
    /** Reanuda la reproducción del vídeo en el WebView. */
    fun play()

    /** Pausa la reproducción del vídeo en el WebView. */
    fun pause()

    /** Silencia/activa el sonido del WebView. */
    fun setMuted(muted: Boolean)

    /** Busca a la posición indicada (ms) y reanuda la reproducción. */
    fun seekTo(positionMs: Long)

    /**
     * Consulta la posición de reproducción actual (ms). El callback SIEMPRE se
     * invoca (una sola vez); recibe [null] si no se pudo obtener (WebView no
     * listo, error de JS o posición no válida).
     */
    fun currentPosition(onResult: (Long?) -> Unit)
}

/**
 * Punto de enganche entre [YouTubePlayerView] y [PlaybackHandoff]. La vista
 * registra su implementación interna ([control]) mientras está compuesta y se
 * des-registra al destruirse. [onAttached]/[onDetached] permiten al handoff
 * reaccionar a la aparición/desaparición del reproductor WebView (p. ej. para
 * que el servicio reanude el audio cuando el usuario abandona la pantalla).
 *
 * Este handle se crea una sola vez en el [PlaybackHandoff] (que vive en el
 * ViewModel) y se comparte con la UI: así el handoff siempre consulta la
 * instancia de control que la vista tiene registrada en ese momento.
 */
class WebPlayerControlHandle(
    val onAttached: (() -> Unit)? = null,
    val onDetached: (() -> Unit)? = null,
) {
    internal var control: WebPlayerControl? = null

    /** true si la vista está compuesta y registró su control. */
    fun isAttached(): Boolean = control != null

    fun play() {
        control?.play()
    }

    fun pause() {
        control?.pause()
    }

    fun setMuted(muted: Boolean) {
        control?.setMuted(muted)
    }

    fun seekTo(positionMs: Long) {
        control?.seekTo(positionMs)
    }

    fun currentPosition(onResult: (Long?) -> Unit) {
        if (control == null) {
            onResult(null)
        } else {
            control?.currentPosition(onResult)
        }
    }
}
