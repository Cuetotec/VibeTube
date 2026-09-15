package com.cuetotech.vibetube

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cuetotech.vibetube.data.YouTubeSearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifica que la búsqueda de YouTube usando NewPipeExtractor (sin API key)
 * parsea correctamente los resultados y devuelve Songs con videoId, título,
 * artista y duración.
 */
@RunWith(AndroidJUnit4::class)
class YouTubeSearchTest {

    @Test
    fun searchReturnsResultsWithoutApiKey() = runBlocking {
        InstrumentationRegistry.getInstrumentation().targetContext

        val results = YouTubeSearchRepository().search("imagine dragons")

        assertTrue(
            "La búsqueda debería devolver resultados, obtuvo ${results.size}",
            results.isNotEmpty(),
        )
        val first = results.first()
        assertTrue("videoId no debe estar vacío", first.youtubeId.isNotBlank())
        assertTrue("Título no debe estar vacío: ${first.title}", first.title.isNotBlank())
        assertTrue("Artista no debe estar vacío: ${first.artist}", first.artist.isNotBlank())
    }

    @Test
    fun searchFilterVideosAndExtractDurations() = runBlocking {
        val results = YouTubeSearchRepository().search("queen bohemian rhapsody", maxResults = 10)

        assertTrue(
            "Debería haber al menos 1 vídeo, obtuvo ${results.size}",
            results.isNotEmpty(),
        )
        assertTrue(
            "Todos deberían tener duración > 0",
            results.all { it.durationSeconds > 0L },
        )
    }
}