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

    private fun next(current: String?, run: RepeatMode, shuffle: Boolean, order: List<String>, pos: Int = -1) =
        nextTrack(current, tracks, run, shuffle, order, pos).trackId

    @Test
    fun `sequential OFF advances and stops at the end`() {
        assertEquals("b", next("a", RepeatMode.OFF, false, emptyList()))
        assertEquals("c", next("b", RepeatMode.OFF, false, emptyList()))
        assertNull(next("c", RepeatMode.OFF, false, emptyList()))
    }

    @Test
    fun `sequential ALL wraps to the first track on the last track`() {
        assertEquals("a", next("c", RepeatMode.ALL, false, emptyList()))
        assertEquals("b", next("a", RepeatMode.ALL, false, emptyList()))
    }

    @Test
    fun `ONE keeps the current track regardless of shuffle`() {
        assertEquals("b", next("b", RepeatMode.ONE, false, emptyList()))
        assertEquals("a", next("a", RepeatMode.ONE, true, listOf("a", "b", "c")))
    }

    @Test
    fun `returns null with empty list or null current`() {
        assertNull(nextTrack("a", emptyList(), RepeatMode.OFF, false, emptyList()).trackId)
        assertNull(nextTrack(null, tracks, RepeatMode.OFF, false, emptyList()).trackId)
        assertNull(nextTrack(null, tracks, RepeatMode.ONE, false, emptyList()).trackId)
    }

    @Test
    fun `returns null when current track is unknown`() {
        assertNull(next("zzz", RepeatMode.OFF, false, emptyList()))
    }

    @Test
    fun `single track OFF stops, ALL and ONE wrap on itself`() {
        val single = listOf(song("a"))
        assertNull(nextTrack("a", single, RepeatMode.OFF, false, emptyList()).trackId)
        assertEquals("a", nextTrack("a", single, RepeatMode.ALL, false, emptyList()).trackId)
        assertEquals("a", nextTrack("a", single, RepeatMode.ONE, false, emptyList()).trackId)
    }

    @Test
    fun `shuffle OFF follows the order and stops at the end`() {
        val order = listOf("b", "a", "c")
        assertEquals("a", next("b", RepeatMode.OFF, true, order))
        assertEquals("c", next("a", RepeatMode.OFF, true, order))
        assertNull(next("c", RepeatMode.OFF, true, order))
    }

    @Test
    fun `shuffle ALL wraps to the start of the order`() {
        val order = listOf("b", "a", "c")
        assertEquals("b", next("c", RepeatMode.ALL, true, order))
    }

    @Test
    fun `shuffle falls back to sequential when the order is empty`() {
        assertEquals("b", next("a", RepeatMode.OFF, true, emptyList()))
    }

    @Test
    fun `shuffle falls back to sequential when current is not in the order`() {
        val order = listOf("b", "c")
        assertEquals("b", next("a", RepeatMode.OFF, true, order))
    }

    @Test
    fun `shuffle advances only over the order size without crashing on shorter order`() {
        val tracks4 = listOf(song("a"), song("b"), song("c"), song("d"))
        val order = listOf("b", "c", "d")
        assertEquals(
            "c",
            nextTrack("b", tracks4, RepeatMode.OFF, true, order, 0).trackId,
        )
        assertEquals(
            "d",
            nextTrack("c", tracks4, RepeatMode.OFF, true, order, 1).trackId,
        )
        assertNull(nextTrack("d", tracks4, RepeatMode.OFF, true, order, 2).trackId)
    }

    @Test
    fun `shuffle walks duplicate ids without stopping early via position`() {
        val tracksDup = listOf(song("a"), song("b"), song("a"), song("c"))
        val order = listOf("b", "a", "a", "c")
        val r1 = nextTrack("b", tracksDup, RepeatMode.OFF, true, order, 0)
        assertEquals("a", r1.trackId)
        val r2 = nextTrack("a", tracksDup, RepeatMode.OFF, true, order, r1.shufflePosition)
        assertEquals("a", r2.trackId)
        val r3 = nextTrack("a", tracksDup, RepeatMode.OFF, true, order, r2.shufflePosition)
        assertEquals("c", r3.trackId)
        val r4 = nextTrack("c", tracksDup, RepeatMode.OFF, true, order, r3.shufflePosition)
        assertNull(r4.trackId)
    }

    @Test
    fun `shuffle returns the advanced position for the caller to persist`() {
        val order = listOf("b", "a", "c")
        val r1 = nextTrack("b", tracks, RepeatMode.OFF, true, order, 0)
        assertEquals(1, r1.shufflePosition)
        val r2 = nextTrack("a", tracks, RepeatMode.OFF, true, order, r1.shufflePosition)
        assertEquals(2, r2.shufflePosition)
        val r3 = nextTrack("c", tracks, RepeatMode.OFF, true, order, r2.shufflePosition)
        assertNull(r3.trackId)
        assertEquals(3, r3.shufflePosition)
    }

    @Test
    fun `shuffle ALL wraps at the order boundary resetting the position`() {
        val order = listOf("b", "c")
        assertEquals("b", next("c", RepeatMode.ALL, true, order))
        val wrapped = nextTrack("c", tracks, RepeatMode.ALL, true, order, 1)
        assertEquals("b", wrapped.trackId)
        assertEquals(0, wrapped.shufflePosition)
    }
}