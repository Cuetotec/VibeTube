package com.cuetotech.vibetube.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.cuetotech.vibetube.BuildConfig
import com.cuetotech.vibetube.R

private const val TAG = "VibeTube"
private const val JS_BRIDGE_NAME = "VibeTubeBridge"

// El origen/base del documento debe ser un dominio PROPIO (distinto de youtube.com).
// Si la página del reproductor y el iframe del embed comparten el mismo origen,
// YouTube lo rechaza con error 153/152 por configuración inválida.
private val PLAYER_ORIGIN: String = "https://${BuildConfig.APPLICATION_ID}"
private const val GENERIC_ERROR = -1

private class PlayerRef {
    var container: ViewGroup? = null
    var view: WebView? = null
}

private class JsBridge(
    private val onPlayerError: (Int) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onPlayerError(errorCode: Int) {
        mainHandler.post { onPlayerError(errorCode) }
    }
}

private fun buildPlayerHtml(videoId: String): String {
    val safeId = videoId.replace("'", "\\'")
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

            var tag = document.createElement('script');
            tag.src = 'https://www.youtube.com/iframe_api';
            var first = document.getElementsByTagName('script')[0];
            first.parentNode.insertBefore(tag, first);

            function onYouTubeIframeAPIReady() {
              new YT.Player('player', {
                videoId: '$safeId',
                playerVars: {
                  autoplay: 1,
                  rel: 0,
                  playsinline: 1,
                  origin: '$PLAYER_ORIGIN'
                },
                events: {
                  // Cualquier error (100/101/150/152/152-4/153...) activa el
                  // overlay de error directamente, sin reintentar en bucle.
                  onError: function(event) {
                    if (window.$JS_BRIDGE_NAME) {
                      window.$JS_BRIDGE_NAME.onPlayerError(extractErrorCode(event.data));
                    }
                  }
                }
              });
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
fun YouTubePlayerView(
    videoId: String,
    onAddSong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerRef = remember { PlayerRef() }
    var playerError by remember(videoId) { mutableStateOf<Int?>(null) }

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

    LaunchedEffect(playerError) {
        if (playerError != null) {
            Toast.makeText(context, R.string.player_error_message, Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(videoId) {
        val container = playerRef.container
        if (videoId.isNotBlank() && container != null) {
            runCatching {
                val webView = WebView(container.context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    settings.userAgentString = buildPlayerUserAgent(container.context)
                    addJavascriptInterface(
                        JsBridge { code -> playerError = code },
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
                Log.d(TAG, "Cargando reproductor con ID: $videoId")
                webView.clearCache(true)
                webView.clearHistory()
                webView.loadDataWithBaseURL(
                    PLAYER_ORIGIN,
                    buildPlayerHtml(videoId),
                    "text/html",
                    "utf-8",
                    null,
                )
            }.onFailure { exception ->
                Log.e(TAG, "No se pudo inicializar el WebView", exception)
            }
        }
        onDispose {
            Log.d(TAG, "Liberando reproductor con ID: $videoId")
            runCatching {
                playerRef.view?.let { webView ->
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    if (webView.parent != null) {
                        (webView.parent as? ViewGroup)?.removeView(webView)
                    }
                    webView.destroy()
                }
                playerRef.view = null
            }
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
