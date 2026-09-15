package com.cuetotech.vibetube.data

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

private const val TAG = "VibeTubeStream"

// Versión del cliente WEB de YouTube que usa NewPipeExtractor como respaldo
// (getClientVersion()) cuando no puede extraerla del HTML/JS de youtube.com.
// Preseleccionarla evita que el extractor tenga que visitar www.youtube.com
// (sw.js_data y la página de búsqueda con ?ucbcb=1), que en redes bloqueadas
// (IPs de datacenter) devuelve una página robot sin ytInitialData y hace
// fallar TODA la extracción con "Could not get ytInitialData" (o un bucle de
// redirecciones de consentimiento en builds sin cookie SOCS). Mismo workaround
// que usa YouTubeStreamResolver: al ser NewPipe un singleton global, se
// centraliza aquí para que TANTO la búsqueda COMO la resolución de streams
// apliquen la preselección, sin importar cuál de los dos se inicialice antes.
private const val PRESET_WEB_CLIENT_VERSION = "2.20260120.01.00"

/**
 * Inicializa NewPipeExtractor una única vez para toda la app (singleton
 * global) aplicando la preselección de la versión del cliente WEB de YouTube.
 *
 * Sin esta versión preseleccionada, `getClientVersion()` intenta descubrirla
 * visitando `www.youtube.com` (sw.js_data + página de búsqueda), lo que falla
 * con "Could not get ytInitialData" en redes bloqueadas. Al ser `NewPipe` un
 * singleton, la inicialización debe ser única y compartida: la usan tanto
 * [YouTubeStreamResolver] (URLs de audio) como [YouTubeSearchRepository]
 * (resultados de búsqueda).
 */
internal object NewPipeInitializer {

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized() {
        if (initialized) return
        Log.d(TAG, "Inicializando NewPipe (locale=${Locale.getDefault()}, country=US)")
        NewPipe.init(
            NewPipeDownloader(),
            Localization.fromLocale(Locale.getDefault()),
            ContentCountry("US"),
        )
        presetWebClientVersion()
        initialized = true
    }

    // Preselecciona la versión del cliente WEB en YoutubeParsingHelper. Al estar
    // poblado el campo estático, getClientVersion() la devuelve directamente y
    // el extractor se salta las peticiones a www.youtube.com (sw.js_data y la
    // página de búsqueda) que son las que fallan en redes bloqueadas. Si la
    // preselección fallara (minificación, cambio de campo), se ignora: el
    // extractor seguiría con su flujo normal.
    private fun presetWebClientVersion() {
        try {
            val helper = Class.forName(
                "org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper",
            )
            val field = helper.getDeclaredField("clientVersion")
            @Suppress("DEPRECATION")
            field.isAccessible = true
            val current = field.get(null) as? String
            if (current.isNullOrEmpty()) {
                field.set(null, PRESET_WEB_CLIENT_VERSION)
                Log.d(
                    TAG,
                    "Versión de cliente WEB preseleccionada: $PRESET_WEB_CLIENT_VERSION",
                )
            } else {
                Log.d(TAG, "Versión de cliente WEB ya disponible: $current")
            }
        } catch (exception: Exception) {
            Log.w(TAG, "No se pudo preseleccionar la versión de cliente WEB", exception)
        }
    }
}
