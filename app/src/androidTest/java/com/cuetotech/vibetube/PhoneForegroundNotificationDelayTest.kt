package com.cuetotech.vibetube

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.player.PlaybackController
import com.cuetotech.vibetube.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reproduce el flujo del TELÉFONO (PlaybackController.syncPlaylist, igual que
 * al pulsar una canción en la app) y verifica el diseño asíncrono:
 *
 *  - La notificación con la metadata en caché aparece AL INSTANTE (placeholder
 *    silencioso con URI válida), SIN esperar a que NewPipe resuelva el stream.
 *  - La cola se infla en segundo plano (backfill a 2 items).
 *  - La URL real reemplaza al placeholder: la pista pasa de un WAV de 250ms a
 *    una canción real (posición del player avanza más allá de 250ms).
 *
 * Mide los tiempos clave y los vuelca a logcat con la etiqueta
 * `VibeTubePhoneTest`. Todas las operaciones sobre MediaController se ejecutan
 * en el main thread (requisito de Media3).
 */
@RunWith(AndroidJUnit4::class)
class PhoneForegroundNotificationDelayTest {

    private companion object {
        const val TAG = "VibeTubePhoneTest"
        const val WAIT_TOTAL_MS = 45_000L
        const val FIRST_TITLE = "Test Track Uno"
        const val SECOND_TITLE = "Test Track Dos"
        const val PLACEHOLDER_DURATION_MS = 250L
        const val REAL_PLAYBACK_THRESHOLD_MS = 1000L
        val YT_1 = "dQw4w9WgXcQ"
        val YT_2 = "9bZkp7q19f0"
    }

    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun immediateNotification_thenRealPlaybackAndQueueBackfill() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val playbackController = PlaybackController(targetContext)
        val tracks = listOf(
            Song("s1", YT_1, FIRST_TITLE, "Artist A", 213L),
            Song("s2", YT_2, SECOND_TITLE, "Artist B", 253L),
        )

        val mainExecutor = ContextCompat.getMainExecutor(targetContext)
        val mainHandler = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val startMs = SystemClock.elapsedRealtime()

        mainExecutor.execute {
            val token = SessionToken(
                targetContext,
                ComponentName(targetContext, PlaybackService::class.java),
            )
            val observerFuture = MediaController.Builder(targetContext, token).buildAsync()
            observerFuture.addListener({
                val observer = try {
                    observerFuture.get()
                } catch (e: Exception) {
                    Log.e(TAG, "No se pudo conectar el MediaController de observación", e)
                    null
                }
                if (observer == null) {
                    done.countDown()
                    return@addListener
                }

                testScope.launch {
                    val ok = playbackController.syncPlaylist(
                        tracks = tracks,
                        startIndex = 0,
                        repeatMode = Player.REPEAT_MODE_ALL,
                        startPlaying = true,
                    )
                    Log.i(TAG, "syncPlaylist devolvió $ok")
                }

                var placeholderAt = -1L
                var notifAt = -1L
                var playingRealAt = -1L
                var backfillAt = -1L
                var lastCount = 0
                var lastPosition = 0L

                observer.addListener(object : Player.Listener {
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        Log.i(
                            TAG,
                            "onTimelineChanged(reason=$reason count=${observer.mediaItemCount})",
                        )
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        Log.i(TAG, "onPlaybackStateChanged($state) pos=${observer.currentPosition}")
                    }
                })

                fun poll(@Suppress("UNUSED_PARAMETER") elapsed: Long) {
                    val now = SystemClock.elapsedRealtime() - startMs
                    val count = observer.mediaItemCount
                    val pos = observer.currentPosition

                    if (count != lastCount) {
                        lastCount = count
                        Log.i(TAG, "timeline items=$count pos=$pos a t=${now}ms")
                    }
                    if (pos > lastPosition + 50L) {
                        lastPosition = pos
                        Log.i(TAG, "pos=$pos a t=${now}ms")
                    }

                    if (placeholderAt < 0 && count > 0) {
                        placeholderAt = now
                        Log.i(TAG, "PLACEHOLDER en timeline (items=$count) a t=${now}ms")
                    }

                    if (playingRealAt < 0 && pos > REAL_PLAYBACK_THRESHOLD_MS) {
                        playingRealAt = now
                        Log.i(TAG, "REAL PLAYBACK pos=${pos}ms a t=${now}ms")
                    }

                    if (backfillAt < 0 && count >= 2) {
                        backfillAt = now
                        Log.i(TAG, "BACKFILL cola completa (items=2) a t=${now}ms")
                    }

                    if (notifAt < 0) {
                        val nm = targetContext.getSystemService(Context.NOTIFICATION_SERVICE)
                                as NotificationManager
                        nm.activeNotifications.forEach { status ->
                            val title = status.notification.extras
                                .getCharSequence(Notification.EXTRA_TITLE)?.toString()
                            if (title == FIRST_TITLE) {
                                notifAt = now
                                Log.i(
                                    TAG,
                                    "NOTIFICACION con metadata (title='$FIRST_TITLE') a t=${now}ms",
                                )
                            }
                        }
                    }

                    if (now < WAIT_TOTAL_MS &&
                        !(notifAt >= 0 && playingRealAt >= 0 && backfillAt >= 0)
                    ) {
                        mainHandler.postDelayed({ poll(elapsed + 100L) }, 100L)
                    } else {
                        Log.i(
                            TAG,
                            "=== RESUMEN phone: placeholder=${placeholderAt}ms | " +
                                "notificacion=${notifAt}ms | playingReal=${playingRealAt}ms | " +
                                "backfill=${backfillAt}ms ===",
                        )
                        assertTrue(
                            "El placeholder debió entrar al timeline",
                            placeholderAt >= 0,
                        )
                        assertTrue(
                            "La notificación con metadata debió aparecer SIN esperar a NewPipe",
                            notifAt >= 0,
                        )
                        assertTrue(
                            "La pista real debió empezar a reproducir (pos > ${REAL_PLAYBACK_THRESHOLD_MS}ms)",
                            playingRealAt >= 0,
                        )
                        assertTrue(
                            "La cola debió inflarse a 2 items en background",
                            backfillAt >= 0,
                        )
                        if (notifAt >= 0 && playingRealAt >= 0) {
                            Log.i(
                                TAG,
                                "notificacion ${notifAt}ms < playingReal ${playingRealAt}ms -> " +
                                    "metadata visible sin esperar a que suene la pista real",
                            )
                        }
                        assertTrue(
                            "La notificación debió aparecer ANTES de que la pista real empiece a sonar",
                            notifAt >= 0 && playingRealAt >= 0 && notifAt <= playingRealAt,
                        )
                        playbackController.stop()
                        playbackController.release()
                        observer.release()
                        done.countDown()
                    }
                }
                mainHandler.post { poll(0L) }
            }, mainExecutor)
        }

        assertTrue(
            "El test no terminó en ${WAIT_TOTAL_MS}ms",
            done.await(WAIT_TOTAL_MS + 10_000L, TimeUnit.MILLISECONDS),
        )
    }
}