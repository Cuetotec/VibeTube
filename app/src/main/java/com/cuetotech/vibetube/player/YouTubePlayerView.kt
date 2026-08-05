package com.cuetotech.vibetube.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cuetotech.vibetube.R
import com.cuetotech.vibetube.data.Song

private const val TAG = "VibeTubePlayer"
private const val JS_BRIDGE_NAME = "VibeTubeBridge"

// Estado YT.PlayerState.ENDED de la API de YouTube (0): el vídeo terminó.
const val YT_PLAYER_STATE_ENDED = 0

// Origen/base del documento: dominio oficial de incrustación de YouTube sin
// cookies (youtube-nocookie.com). El iframe del embed se comunica con la página
// padre vía postMessage usando 'origin' como origen objetivo; al coincidir la
// Base URL y 'origin', onStateChange/onVideoEnded llegan al puente sin ser
// bloqueados. Usar "https://www.youtube.com" como base dispara el error 152-4
// (suplantación), y un dominio propio inválido hacía que el embed cayera al
// fallback de youtube.com, bloqueando los mensajes iframe->padre.
private val PLAYER_ORIGIN: String = "https://www.youtube-nocookie.com"
private const val GENERIC_ERROR = -1

private class PlayerRef {
    var container: ViewGroup? = null
    var view: WebView? = null
    var loadedVideoId: String? = null

    // Bandera de control en Kotlin: evita procesar onVideoEnded más de una vez
    // por canción aunque el puente JS repita el evento (defensa extra junto a
    // la deduplicación hasEnded del JavaScript).
    var endedHandled: Boolean = false
}

private class JsBridge(
    private val errorHandler: (Int) -> Unit,
    private val stateChangeHandler: (Int) -> Unit,
    private val endedHandler: () -> Unit,
    private val readyHandler: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    // Deduplicación en Kotlin (defensa ante el bucle del widgetapi): aunque el
    // JS llame al bridge en ráfagas, solo se REENVÍA al ViewModel el primer
    // evento y los cambios de estado reales. Evita saturar el main thread.
    private var lastReadyForward = 0L
    private var lastStateForward = Int.MIN_VALUE
    private var lastStateForwardTime = 0L
    private var lastEndedForward = 0L
    private val minForwardIntervalMs = 500L
    private val minStateIntervalMs = 300L

    @JavascriptInterface
    fun onPlayerError(errorCode: Int) {
        mainHandler.post {
            Log.d(TAG, "onPlayerError: code=$errorCode")
            errorHandler(errorCode)
        }
    }

    @JavascriptInterface
    fun onPlayerStateChange(stateCode: Int) {
        val now = SystemClock.elapsedRealtime()
        if (stateCode != lastStateForward && now - lastStateForwardTime > minStateIntervalMs) {
            lastStateForward = stateCode
            lastStateForwardTime = now
            mainHandler.post {
                Log.d(TAG, "onPlayerStateChange: state=$stateCode")
                stateChangeHandler(stateCode)
            }
        }
    }

    @JavascriptInterface
    fun onVideoEnded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEndedForward > minForwardIntervalMs) {
            lastEndedForward = now
            mainHandler.post {
                Log.d(TAG, "onVideoEnded: el vídeo terminó, avanzando a la siguiente canción")
                endedHandler()
            }
        }
    }

    @JavascriptInterface
    fun onPlayerReady() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadyForward > minForwardIntervalMs) {
            lastReadyForward = now
            mainHandler.post {
                Log.d(TAG, "onPlayerReady: reproductor JS listo")
                readyHandler()
            }
        }
    }
}

private fun buildPlayerHtml(videoId: String, muted: Boolean): String {
    val safeId = videoId.replace("'", "\\'")
    val mutedJs = if (muted) "true" else "false"
    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <meta name="referrer" content="strict-origin-when-cross-origin">
          <style>
            html, body { margin: 0; padding: 0; background: #000; height: 100%; overflow: hidden; }
            #player { position: absolute; top: 0; left: 0; width: 100%; height: 100%; }
          </style>
        </head>
        <body>
          <div id="player"></div>
          <script>
            function extractErrorCode(data) {
              if (typeof data === 'number') return data;
              if (typeof data === 'string') {
                var match = data.match(/(\d+)/);
                return match ? parseInt(match[0], 10) : -1;
              }
              return -1;
            }

            var bridge = function() { return window.$JS_BRIDGE_NAME; };
            var player = null;
            var lastState = null;
            var readySent = false;
            var hasEnded = false;
            var pendingJsVideoId = null;
            // muted = true cuando la reproducción en segundo plano (servicio
            // Media3/ExoPlayer) está activa: el WebView solo muestra el vídeo y
            // el audio real lo reproduce el servicio.
            var muted = $mutedJs;

            function applyMute() {
              if (muted && player) {
                try { player.mute(); } catch (e) {}
              }
            }

            // Notifica el final de vídeo (ENDED, state=0) una sola vez por
            // reproducción: la bandera hasEnded evita que el evento llegue en
            // ráfagas al puente Android/JavaScript y sature la cola de llamadas.
            // Además solo se reenvía al puente un CAMBIO de estado (el widgetapi
            // puede repetir el mismo estado en bucle cuando el embed comparte
            // origen con la página padre).
            function handleState(code) {
              if (code !== lastState) {
                lastState = code;
                if (bridge()) {
                  bridge().onPlayerStateChange(code);
                }
              }
              if (code === 0) {
                if (!hasEnded) {
                  hasEnded = true;
                  if (bridge()) {
                    bridge().onVideoEnded();
                  }
                }
              } else if (code === 1 || code === 3) {
                // PLAYING (1) / BUFFERING (3): el vídeo sigue en curso, se
                // vuelve a permitir reportar el final de la reproducción.
                hasEnded = false;
              }
            }

            // Cambia de pista SIN recrear el WebView ni recargar el HTML: se usa
            // cuando el ViewModel avanza a la siguiente canción (autoplay). Si el
            // reproductor aún no se ha creado, el vídeo queda pendiente y se
            // carga en cuanto onYouTubeIframeAPIReady cree el player.
            function loadVideo(videoId) {
              // Cambio de vídeo: se reinicia la bandera para que el final de la
              // nueva reproducción se pueda reportar de nuevo, y el último
              // estado para que los nuevos estados se reenvíen al puente.
              hasEnded = false;
              lastState = null;
              if (player) {
                try {
                  player.loadVideoById(videoId);
                  // Fuerza la reproducción aunque el WebView bloquee el autoplay
                  // por gestos de usuario.
                  player.playVideo();
                  applyMute();
                } catch (e) {}
              } else {
                pendingJsVideoId = videoId;
              }
            }

            var tag = document.createElement('script');
            tag.src = 'https://www.youtube.com/iframe_api';
            var first = document.getElementsByTagName('script')[0];
            first.parentNode.insertBefore(tag, first);

            function onYouTubeIframeAPIReady() {
              player = new YT.Player('player', {
                videoId: '$safeId',
                // host OMITIDO: el embed se sirve desde www.youtube.com (dominio
                // por defecto), cross-origin respecto a la Base URL
                // youtube-nocookie.com. Esto evita el bucle infinito de onReady
                // que provocaba host=same-origin, y los eventos postMessage
                // (onReady/onStateChange/onVideoEnded) llegan igualmente al
                // puente. El iframe del embed es cross-origin y no puede
                // invocar directamente window.VibeTubeBridge.
                playerVars: {
                  autoplay: 1,
                  rel: 0,
                  playsinline: 1,
                  enablejsapi: 1,
                  origin: '$PLAYER_ORIGIN',
                  widget_referrer: '$PLAYER_ORIGIN'
                },
                events: {
                  onReady: function() {
                    // El widgetapi puede re-despachar onReady en bucle: solo se
                    // notifica al puente la primera vez (readySent).
                    if (!readySent) {
                      readySent = true;
                      if (bridge()) {
                        bridge().onPlayerReady();
                      }
                    }
                    // Fuerza el inicio de la reproducción: el WebView puede
                    // bloquear el autoplay por gestos aunque playerVars tenga
                    // autoplay: 1, así que se llama playVideo() explícitamente.
                    try {
                      player.playVideo();
                    } catch (e) {}
                    applyMute();
                  },
                  // Cualquier error (100/101/150/152/152-4/153...) activa el
                  // overlay de error directamente, sin reintentar en bucle.
                  onError: function(event) {
                    if (bridge()) {
                      bridge().onPlayerError(extractErrorCode(event.data));
                    }
                  },
                  // Cambio de estado del reproductor: 0=ended, 1=playing,
                  // 2=paused, 3=buffering, 5=cued. El estado 0 permite pasar
                  // automáticamente a la siguiente canción (autoplay).
                  onStateChange: function(event) {
                    handleState(event.data);
                  }
                }
              });

              // Si se pidió cambiar de vídeo antes de que el player existiera
              // (autoplay muy temprano), se aplica ahora sobre el reproductor
              // ya creado.
              if (pendingJsVideoId) {
                var pendingId = pendingJsVideoId;
                pendingJsVideoId = null;
                try {
                  player.loadVideoById(pendingId);
                  player.playVideo();
                  applyMute();
                } catch (e) {}
              }
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun YouTubePlayerView(
    currentSong: Song?,
    onAddSong: () -> Unit,
    showAddSong: Boolean = true,
    onEnded: () -> Unit = {},
    onStateChange: ((Int) -> Unit)? = null,
    playbackTick: Int = 0,
    muted: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerRef = remember { PlayerRef() }
    var playerReady by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<Int?>(null) }
    val videoId = currentSong?.youtubeId.orEmpty()

    // Crea el WebView con el reproductor embebido (el vídeo inicial se carga
    // con autoplay). Se invoca UNA sola vez; nunca se recrea al cambiar de
    // canción para no reiniciar la reproducción.
    val setupPlayer: (String) -> Unit = { newVideoId ->
        val container = playerRef.container
        if (container != null) {
            runCatching {
                // Permite inspeccionar el WebView desde Chrome DevTools
                // (chrome://inspect) en builds de depuración.
                WebView.setWebContentsDebuggingEnabled(true)
                val webView = WebView(container.context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.userAgentString = buildPlayerUserAgent(container.context)
                    addJavascriptInterface(
                        JsBridge(
                            errorHandler = { code -> playerError = code },
                            stateChangeHandler = { state ->
                                Log.d(TAG, "Estado del reproductor: $state (videoId=$newVideoId)")
                                // PLAYING (1) / BUFFERING (3): la reproducción
                                // sigue en curso, se vuelve a permitir avanzar
                                // al terminar esta canción.
                                if (state == 1 || state == 3) {
                                    playerRef.endedHandled = false
                                }
                                onStateChange?.invoke(state)
                            },
                            endedHandler = {
                                // Un solo avance por canción: la primera vez que
                                // llega ENDED se avanza y se marca como manejado;
                                // cualquier repetición del evento se ignora.
                                if (!playerRef.endedHandled) {
                                    playerRef.endedHandled = true
                                    onEnded()
                                }
                            },
                            readyHandler = {
                                playerReady = true
                                Log.d(TAG, "onPlayerReady (videoId=$newVideoId)")
                            },
                        ),
                        JS_BRIDGE_NAME,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            super.onReceivedError(view, request, error)
                            if (request.isForMainFrame && playerError == null) {
                                view.post { playerError = GENERIC_ERROR }
                            }
                        }
                    }
                    webChromeClient = WebChromeClient()
                    setBackgroundColor(android.graphics.Color.BLACK)
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                playerRef.view = webView
                playerRef.loadedVideoId = newVideoId
                Log.d(TAG, "Cargando reproductor con ID: $newVideoId")
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadDataWithBaseURL(
                    PLAYER_ORIGIN,
                    buildPlayerHtml(newVideoId, muted),
                    "text/html",
                    "utf-8",
                    null,
                )
            }.onFailure { exception ->
                Log.e(TAG, "No se pudo inicializar el WebView", exception)
            }
        } else {
            Log.w(TAG, "setupPlayer: contenedor no disponible")
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    playerRef.container = this
                }
            },
            update = {},
        )

        // Ante un error de reproducción se muestra un aviso + botón "Abrir en
        // YouTube", evitando que el reproductor quede colgado en pantalla negra.
        if (playerError != null) {
            PlayerErrorOverlay(
                videoId = videoId,
                message = context.getString(R.string.player_error_message),
                onOpenInYoutube = { openInYoutube(context, videoId) },
            )
        }

        if (showAddSong) {
            IconButton(
                onClick = onAddSong,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.player_add_to_playlist),
                    tint = Color.White,
                )
            }
        }
    }

    LaunchedEffect(playerError) {
        if (playerError != null) {
            Toast.makeText(context, R.string.player_error_message, Toast.LENGTH_SHORT).show()
        }
    }

    // El WebView se crea UNA sola vez y se reutiliza: al cambiar de canción se
    // carga el nuevo vídeo con player.loadVideoById(videoId) (autoplay), en vez
    // de recrear el WebView entero (más frágil y más lento).
    DisposableEffect(Unit) {
        if (videoId.isNotBlank() && playerRef.container != null) {
            setupPlayer(videoId)
        }
        onDispose {
            Log.d(TAG, "Liberando reproductor con ID: $videoId")
            disposePlayer(playerRef)
            playerReady = false
        }
    }

    // Observa directamente el ID de la canción actual (currentSong). Cuando el
    // ViewModel avanza de pista (playNextTrack/playNextSavedTrack), el ID cambia
    // y este efecto carga el nuevo vídeo vía player.loadVideoById(videoId), SIN
    // recrear el WebView ni recargar el HTML. Si el reproductor JS aún no está
    // listo, loadVideo() lo deja pendiente (pendingJsVideoId) y se aplica en
    // cuanto el player se cree.
    LaunchedEffect(videoId) {
        if (videoId.isNotBlank() && playerRef.loadedVideoId != videoId) {
            playerRef.loadedVideoId = videoId
            playerRef.endedHandled = false
            playerError = null
            Log.d(TAG, "Video actual cambiado a: $videoId")
            playerRef.view?.let { webView ->
                if (playerReady) {
                    loadVideoInPlayer(webView, videoId, muted)
                } else {
                    queueVideoInPlayer(webView, videoId)
                }
            }
        }
    }

    // Repetición de la canción actual (RepeatMode.ONE): cuando el ViewModel
    // incrementa el token SIN cambiar de pista, se vuelve a ejecutar
    // loadVideoById sobre el mismo vídeo (reinicio desde el principio). El
    // token no se reinicia con avances normales, porque ahí el cambio de
    // videoId ya dispara la carga en el LaunchedEffect(videoId).
    LaunchedEffect(playbackTick) {
        if (playbackTick > 0 && videoId.isNotBlank() &&
            playerRef.loadedVideoId == videoId && playerReady
        ) {
            Log.d(TAG, "Reiniciando canción actual (RepeatMode.ONE): $videoId")
            playerRef.endedHandled = false
            playerRef.view?.let { webView -> loadVideoInPlayer(webView, videoId, muted) }
        }
    }

    // Aplica/retira el silenciado en el reproductor ya creado cuando cambia el
    // estado de la reproducción en segundo plano: si el servicio Media3 está
    // reproduciendo el audio real, el WebView solo muestra el vídeo mudo; si no
    // (p. ej. la extracción del stream falló), el WebView vuelve a dar sonido.
    LaunchedEffect(muted) {
        if (videoId.isNotBlank() && playerRef.view != null && playerReady) {
            val muteCall = if (muted) "mute()" else "unMute()"
            playerRef.view?.post {
                runCatching {
                    Log.d(TAG, "Aplicando ${if (muted) "mute" else "unMute"} en el WebView")
                    playerRef.view?.evaluateJavascript(
                        "if (window.player && typeof player.$muteCall === 'function') { player.$muteCall; }",
                        null,
                    )
                }
            }
        }
    }
}

private fun disposePlayer(playerRef: PlayerRef) {
    runCatching {
        playerRef.view?.let { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            if (webView.parent != null) {
                (webView.parent as? ViewGroup)?.removeView(webView)
            }
            webView.destroy()
        }
    }
    playerRef.view = null
    playerRef.loadedVideoId = null
}

// Carga el vídeo directamente sobre el reproductor YA existente, sin recrear el
// WebView: ejecuta SOLO JavaScript (player.loadVideoById) sobre el player listo,
// con una comprobación defensiva por si el objeto player aún no está expuesto.
private fun loadVideoInPlayer(webView: WebView, videoId: String, muted: Boolean) {
    val safeId = videoId.replace("'", "\\'")
    val muteJs = if (muted) "player.mute();" else ""
    webView.post {
        runCatching {
            Log.d(TAG, "loadVideoInPlayer (player.loadVideoById + playVideo): $videoId")
            webView.evaluateJavascript(
                "if (window.player && typeof player.loadVideoById === 'function') { player.loadVideoById('$safeId'); player.playVideo(); $muteJs }",
                null,
            )
        }.onFailure { exception ->
            Log.e(TAG, "No se pudo cargar el vídeo $videoId", exception)
        }
    }
}

// Encola el vídeo cuando el reproductor JS aún no está listo: loadVideo()
// guarda el id en pendingJsVideoId y se aplica en cuanto onYouTubeIframeAPIReady
// cree el player (sin recrear el WebView).
private fun queueVideoInPlayer(webView: WebView, videoId: String) {
    val safeId = videoId.replace("'", "\\'")
    webView.post {
        runCatching {
            Log.d(TAG, "queueVideoInPlayer (loadVideo pendiente): $videoId")
            webView.evaluateJavascript("loadVideo('$safeId');", null)
        }.onFailure { exception ->
            Log.e(TAG, "No se pudo encolar el vídeo $videoId", exception)
        }
    }
}

private fun buildPlayerUserAgent(context: Context): String {
    val defaultUa = WebSettings.getDefaultUserAgent(context)
    return defaultUa
        .replace("; wv", "")
        .replace(Regex("Version/\\d+\\.\\d+"), "Version/1.0")
        .replace(Regex("Chrome/\\d+\\.\\d+\\.\\d+\\.\\d+"), "Chrome/120.0.0.0")
}

@Composable
private fun PlayerErrorOverlay(
    videoId: String,
    message: String,
    onOpenInYoutube: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenInYoutube) {
            Text(stringResource(R.string.player_error_open_in_youtube))
        }
    }
}

private fun openInYoutube(context: Context, videoId: String) {
    runCatching {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.youtube.com/watch?v=$videoId"),
        )
        context.startActivity(intent)
    }
}
