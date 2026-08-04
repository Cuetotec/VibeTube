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

    @Test
    fun `extractVideoIds returns multiple ids from mixed text`() {
        val text = """
            https://www.youtube.com/watch?v=aaaa11aaaaa
            https://youtu.be/bbbb11bbbbb
            Mira esto: https://www.youtube.com/shorts/cccc11ccccc
            https://www.youtube.com/watch?v=dddd11ddddd, https://youtu.be/eeee11eeeee
        """.trimIndent()
        assertEquals(
            listOf("aaaa11aaaaa", "bbbb11bbbbb", "cccc11ccccc", "dddd11ddddd", "eeee11eeeee"),
            YouTubeLinkParser.extractVideoIds(text),
        )
    }

    @Test
    fun `extractVideoIds removes duplicates and blank lines`() {
        val text = """
            
            https://www.youtube.com/watch?v=aaaa11aaaaa
            https://www.youtube.com/watch?v=aaaa11aaaaa
            
            aaaa11aaaaa
        """.trimIndent()
        assertEquals(listOf("aaaa11aaaaa"), YouTubeLinkParser.extractVideoIds(text))
    }

    @Test
    fun `extractVideoIds supports clean 11 character ids`() {
        assertEquals(
            listOf("dQw4w9WgXcQ"),
            YouTubeLinkParser.extractVideoIds("dQw4w9WgXcQ"),
        )
    }

    @Test
    fun `extractVideoIds returns empty for text without ids`() {
        assertEquals(emptyList<String>(), YouTubeLinkParser.extractVideoIds("no hay enlaces aquí"))
        assertEquals(emptyList<String>(), YouTubeLinkParser.extractVideoIds(""))
    }
}
