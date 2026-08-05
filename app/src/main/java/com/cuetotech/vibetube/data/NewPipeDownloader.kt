package com.cuetotech.vibetube.data

import android.util.Log
import okhttp3.ConnectionSpec
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val TAG = "VibeTubeStream"

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

// Cookie de consentimiento de Google (SOCS). Se envía por defecto para que
// YouTube no redirija a consent.youtube.com (banner de la UE, ucbcb=1), lo que
// provocaba un bucle de 21 redirecciones y un ProtocolException en OkHttp.
private const val CONSENT_COOKIE_NAME = "SOCS"
private const val CONSENT_COOKIE_VALUE = "CAESEwgDEgk2ODE3ODk1OTAaAmVuIAEaBgiA_L2yBg"
private const val CONSENT_COOKIE_DOMAIN = "youtube.com"

/**
 * [CookieJar] de OkHttp en memoria: guarda las cookies que devuelve YouTube
 * (YSC, VISITOR_INFO1_LIVE, GPS, etc.) y las reenvía en cada request. Sin este
 * jar, el consentimiento de YouTube no se recuerda y el servidor vuelve a
 * redirigir a consent.youtube.com en cada llamada, entrando en un bucle.
 */
private class InMemoryCookieJar : CookieJar {

    private val cookieStore = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        cookieStore.removeAll { it.expiresAt < now }
        cookies.forEach { newCookie ->
            cookieStore.removeAll {
                it.name == newCookie.name &&
                    it.domain == newCookie.domain &&
                    it.path == newCookie.path
            }
            cookieStore += newCookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookieStore.removeAll { it.expiresAt < now }
        return cookieStore.filter { it.matches(url) }
    }

    /** Siembra el jar con una cookie inicial (p. ej. la de consentimiento). */
    @Synchronized
    fun seed(name: String, value: String, domain: String) {
        cookieStore += Cookie.Builder()
            .name(name)
            .value(value)
            .domain(domain)
            .path("/")
            .secure()
            .expiresAt(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(365))
            .build()
    }
}

/**
 * Implementación del [Downloader] de NewPipeExtractor usando OkHttp. NewPipe
 * exige que la app proporcione el cliente HTTP que usará para llamar a los
 * endpoints de YouTube (innerTube, etc.) durante la extracción del stream.
 *
 * El cliente lleva un [InMemoryCookieJar] sembrado con la cookie SOCS de
 * consentimiento para evitar el bucle de redirecciones de consent.youtube.com.
 */
internal class NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS))
        .cookieJar(
            InMemoryCookieJar().also {
                it.seed(CONSENT_COOKIE_NAME, CONSENT_COOKIE_VALUE, CONSENT_COOKIE_DOMAIN)
            },
        )
        .build()

    override fun execute(request: Request): Response {
        Log.d(TAG, "NewPipe ${request.httpMethod()} ${request.url()}")
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .header("User-Agent", USER_AGENT)

        // Se añaden las cabeceras que pide NewPipe (p. ej. Content-Type de los
        // POST JSON de innerTube), sin duplicar las existentes.
        request.headers().forEach { (name, values) ->
            requestBuilder.removeHeader(name)
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                Log.d(
                    TAG,
                    "NewPipe response: code=${response.code} for ${request.url()} " +
                        "(len=${response.body?.contentLength() ?: -1})",
                )
                if (response.code == 429) {
                    Log.w(TAG, "HTTP 429 → ReCaptchaException (YouTube pide captcha)")
                    throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                }
                return Response(
                    response.code,
                    response.message,
                    response.headers.toMultimap(),
                    response.body?.string(),
                    response.request.url.toString(),
                )
            }
        } catch (exception: IOException) {
            Log.e(TAG, "Error de red en NewPipe para ${request.url()}", exception)
            throw exception
        }
    }
}
