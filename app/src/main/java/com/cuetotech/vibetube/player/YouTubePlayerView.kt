package com.cuetotech.vibetube.player

import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cuetotech.vibetube.R

private const val TAG = "VibeTube"
private const val EMBED_BASE_URL = "https://www.youtube.com/embed/"

private class PlayerRef {
    var container: ViewGroup? = null
    var view: WebView? = null
}

@Composable
fun YouTubePlayerView(
    videoId: String,
    onAddSong: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // El estado del WebView vive en remember: nunca se re-crea en cada ciclo de
    // recomposición. El contenedor tiene tamaño fijo (fillMaxWidth + height 220.dp)
    // impuesto por el llamador.
    val playerRef = remember { PlayerRef() }

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

    // Solo se (re)crea el WebView cuando videoId cambia realmente. Al salir de la
    // pantalla (o al cambiar de vídeo) onDispose libera el WebView con destroy().
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
                    webViewClient = WebViewClient()
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
                webView.loadUrl(EMBED_BASE_URL + videoId + "?autoplay=1")
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
