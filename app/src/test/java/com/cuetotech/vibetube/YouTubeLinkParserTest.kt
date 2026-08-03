package com.cuetotech.vibetube

import com.cuetotech.vibetube.data.YouTubeLinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkParserTest {

    @Test
    fun `extractVideoId returns id for watch urls`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `extractVideoId returns id for short urls`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.extractVideoId("https://youtu.be/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `extractVideoId returns id for embed and shorts urls`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"),
        )
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `extractVideoId trims surrounding whitespace`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeLinkParser.extractVideoId("  https://www.youtube.com/watch?v=dQw4w9WgXcQ  "),
        )
    }

    @Test
    fun `extractVideoId returns null for non youtube urls`() {
        assertNull(YouTubeLinkParser.extractVideoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLinkParser.extractVideoId("https://www.youtube.com/"))
        assertNull(YouTubeLinkParser.extractVideoId("no es una url"))
    }
}
