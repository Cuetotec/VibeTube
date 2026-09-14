package com.cuetotech.vibetube

import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuetotech.vibetube.player.PlaybackService
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduce el flujo de "Android Auto selecciona una canción":
 * un [MediaController] (lo mismo que usa Android Auto) envía UN SOLO
 * [MediaItem] con mediaId `playlistId:youtubeId` vía [Player.setMediaItems],
 * lo que dispara `MediaLibrarySession.Callback.onSetMediaItems`.
 *
 * Mide los tiempos clave y los vuelca a logcat con la etiqueta
 * `VibeTubeAATest` para diagnosticar el error "No se ha podido cargar tu
 * selección" (timeout de Android Auto):
 *  - Tiempo hasta que el timeline recibe el item (`onSetMediaItems` resuelve).
 *  - Tiempo hasta que el item tiene URI de audio resuelta
 *    ([MediaItem.LocalConfiguration] `!= null`).
 *  - Historial de estados de reproducción (IDLE → BUFFERING/READY).
 *
 * Todas las operaciones sobre el [MediaController] se ejecutan en el main
 * thread (requisito de Media3). El test NO lanza asserts de timeout duro:
 * el objetivo es observar y medir el comportamiento real en el emulador.
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoSelectionTimeoutTest {

    private companion object {
        const val TAG = "VibeTubeAATest"
        const val SESSION_TIMEOUT_S = 15L
        const val WAIT_TOTAL_MS = 25_000L
        // Playlist inexistente -> ejerce el camino de item único + resolución
        // NewPipe del target, sin depender de datos de Firestore reales.
        const val TEST_MEDIA_ID = "NON_EXISTENT_PLAYLIST:dQw4w9WgXcQ"
    }

    @Test
    fun selectSingleItem_measuresOnSetMediaItemsLatency() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val mainHandler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)

        mainExecutor.execute {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val buildFuture = MediaController.Builder(context, token).buildAsync()
            buildFuture.addListener({
                val controller = try {
                    buildFuture.get()
                } catch (e: Exception) {
                    Log.e(TAG, "Error al conectar MediaController", e)
                    null
                }
                if (controller == null) {
                    done.countDown()
                    return@addListener
                }
                runMeasureLoop(controller, mainHandler, done)
            }, mainExecutor)
        }

        val finished = done.await(WAIT_TOTAL_MS + SESSION_TIMEOUT_S * 1000, TimeUnit.MILLISECONDS)
        if (!finished) {
            Log.e(TAG, "El test no terminó dentro del margen esperado")
        }
    }

    private fun runMeasureLoop(
        controller: MediaController,
        mainHandler: Handler,
        done: CountDownLatch,
    ) {
        Log.i(TAG, "MediaController conectado al PlaybackService")

        val timelineEvents = mutableListOf<String>()
        controller.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                timelineEvents += "onTimelineChanged(reason=$reason,count=${timeline.windowCount})"
            }

            override fun onPlaybackStateChanged(state: Int) {
                timelineEvents += "state=${stateName(state)}"
            }
        })

        val item = MediaItem.Builder()
            .setMediaId(TEST_MEDIA_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Test selection")
                    .setArtist("VibeTube")
                    .build(),
            )
            .build()

        val startMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "=== setMediaItems(single item id=$TEST_MEDIA_ID) ===")
        controller.setMediaItems(listOf(item), 0, 0)
        controller.prepare()

        var timelineReadyAt = -1L
        var uriResolvedAt = -1L
        var readyAt = -1L
        var lastItemWithUri = false
        var lastPosMs = -1L
        var playbackProgressed = false

        fun poll(now: Long) {
            val elapsed = SystemClock.elapsedRealtime() - startMs
            val state = controller.playbackState

            if (timelineReadyAt < 0 && controller.mediaItemCount > 0) {
                timelineReadyAt = elapsed
                Log.i(TAG, "TIMELINE recibido a t=${elapsed}ms (items=${controller.mediaItemCount})")
            }

            val itemAtZero = if (controller.mediaItemCount > 0) controller.getMediaItemAt(0) else null
            val hasUri = itemAtZero?.localConfiguration?.uri != null
            if (uriResolvedAt < 0 && hasUri) {
                uriResolvedAt = elapsed
                Log.i(
                    TAG,
                    "URI resuelta a t=${elapsed}ms uri=${itemAtZero.localConfiguration?.uri}",
                )
            }
            if (hasUri != lastItemWithUri) {
                lastItemWithUri = hasUri
                Log.i(
                    TAG,
                    "t=${elapsed}ms item con URI=${hasUri} state=${stateName(state)} " +
                        "uri=${itemAtZero?.localConfiguration?.uri ?: "NULL"}",
                )
            }
            // READY real (no BUFFERING optimista): audio decodificándose.
            if (readyAt < 0 && state == Player.STATE_READY) {
                readyAt = elapsed
                val pos = controller.currentPosition
                Log.i(
                    TAG,
                    "Playback READY a t=${elapsed}ms (pos=${pos}ms) " +
                        "dur=${if (itemAtZero?.mediaMetadata?.durationMs != null) itemAtZero.mediaMetadata.durationMs else "?"}",
                )
            }
            // El avance de posición prueba que el audio fluye de verdad.
            // El MediaController solo sincroniza posición periódicamente, así que
            // usamos un umbral absoluto (cualquier muestra > 1000ms) en vez de deltas.
            if (state == Player.STATE_READY) {
                val pos = controller.currentPosition
                if (!playbackProgressed && pos > 1000L) {
                    playbackProgressed = true
                    Log.i(TAG, "PLAYBACK AVANZA a t=${elapsed}ms (pos=${pos}ms)")
                }
                if (pos != lastPosMs) {
                    lastPosMs = pos
                    Log.d(TAG, "pos=${pos}ms a t=${elapsed}ms")
                }
            }

            val doneCondition = uriResolvedAt >= 0 && readyAt >= 0 && playbackProgressed
            if (elapsed < WAIT_TOTAL_MS && !doneCondition) {
                mainHandler.postDelayed({ poll(elapsed + 100L) }, 100L)
            } else {
                Log.i(
                    TAG,
                    "=== RESUMEN: total=${elapsed}ms | timeline=${timelineReadyAt}ms | " +
                        "uri=${uriResolvedAt}ms | ready=${readyAt}ms | " +
                        "avanza=${playbackProgressed} | pos=${controller.currentPosition}ms | " +
                        "items=${controller.mediaItemCount} ===",
                )
                timelineEvents.forEach { Log.d(TAG, it) }
                controller.release()
                done.countDown()
            }
        }
        mainHandler.post { poll(0L) }
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN_$state"
    }
}