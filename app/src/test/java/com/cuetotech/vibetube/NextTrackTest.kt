package com.cuetotech.vibetube

import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.ui.playlists.nextTrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextTrackTest {

    private fun song(id: String) = Song(
        id = id,
        youtubeId = id,
        title = "Canción $id",
        artist = "Artista",
        durationSeconds = 120L,
    )

    private val tracks = listOf(song("a"), song("b"), song("c"))

    @Test
    fun `nextTrackId returns the following track`() {
        assertEquals("b", nextTrackId("a", tracks))
        assertEquals("c", nextTrackId("b", tracks))
    }

    @Test
    fun `nextTrackId wraps to the first track on the last track`() {
        assertEquals("a", nextTrackId("c", tracks))
    }

    @Test
    fun `nextTrackId returns null when current track is unknown`() {
        assertNull(nextTrackId("zzz", tracks))
    }

    @Test
    fun `nextTrackId returns null with empty list or null current`() {
        assertNull(nextTrackId("a", emptyList()))
        assertNull(nextTrackId(null, tracks))
    }

    @Test
    fun `nextTrackId wraps a single track on itself`() {
        assertEquals("a", nextTrackId("a", listOf(song("a"))))
    }
}
