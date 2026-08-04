package com.cuetotech.vibetube

import com.cuetotech.vibetube.data.Song
import com.cuetotech.vibetube.ui.playlists.RepeatMode
import com.cuetotech.vibetube.ui.playlists.nextTrack
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
    fun `sequential OFF advances and stops at the end`() {
        assertEquals("b", nextTrack("a", tracks, RepeatMode.OFF, false, emptyList()))
        assertEquals("c", nextTrack("b", tracks, RepeatMode.OFF, false, emptyList()))
        assertNull(nextTrack("c", tracks, RepeatMode.OFF, false, emptyList()))
    }

    @Test
    fun `sequential ALL wraps to the first track on the last track`() {
        assertEquals("a", nextTrack("c", tracks, RepeatMode.ALL, false, emptyList()))
        assertEquals("b", nextTrack("a", tracks, RepeatMode.ALL, false, emptyList()))
    }

    @Test
    fun `ONE keeps the current track regardless of shuffle`() {
        assertEquals("b", nextTrack("b", tracks, RepeatMode.ONE, false, emptyList()))
        assertEquals("a", nextTrack("a", tracks, RepeatMode.ONE, true, listOf("a", "b", "c")))
    }

    @Test
    fun `returns null with empty list or null current`() {
        assertNull(nextTrack("a", emptyList(), RepeatMode.OFF, false, emptyList()))
        assertNull(nextTrack(null, tracks, RepeatMode.OFF, false, emptyList()))
        assertNull(nextTrack(null, tracks, RepeatMode.ONE, false, emptyList()))
    }

    @Test
    fun `returns null when current track is unknown`() {
        assertNull(nextTrack("zzz", tracks, RepeatMode.OFF, false, emptyList()))
    }

    @Test
    fun `single track OFF stops, ALL and ONE wrap on itself`() {
        val single = listOf(song("a"))
        assertNull(nextTrack("a", single, RepeatMode.OFF, false, emptyList()))
        assertEquals("a", nextTrack("a", single, RepeatMode.ALL, false, emptyList()))
        assertEquals("a", nextTrack("a", single, RepeatMode.ONE, false, emptyList()))
    }

    @Test
    fun `shuffle OFF follows the order and stops at the end`() {
        val order = listOf("b", "a", "c")
        assertEquals("a", nextTrack("b", tracks, RepeatMode.OFF, true, order))
        assertEquals("c", nextTrack("a", tracks, RepeatMode.OFF, true, order))
        assertNull(nextTrack("c", tracks, RepeatMode.OFF, true, order))
    }

    @Test
    fun `shuffle ALL wraps to the start of the order`() {
        val order = listOf("b", "a", "c")
        assertEquals("b", nextTrack("c", tracks, RepeatMode.ALL, true, order))
    }

    @Test
    fun `shuffle falls back to sequential when the order is empty`() {
        assertEquals("b", nextTrack("a", tracks, RepeatMode.OFF, true, emptyList()))
    }

    @Test
    fun `shuffle falls back to sequential when current is not in the order`() {
        val order = listOf("b", "c")
        assertEquals("b", nextTrack("a", tracks, RepeatMode.OFF, true, order))
    }
}
