package com.musico.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class LrcParserTest {
    @Test
    fun parsesTimestampFormats() {
        val lyrics = LrcParser.parse("[00:12.50]Hello\n[01:02]World\n[01:02.345]Milli")
        assertTrue(lyrics.isTimed)
        assertEquals(listOf("Hello", "World", "Milli"), lyrics.lines.map { it.text })
        assertEquals(12.5, lyrics.lines[0].timeSec!!, 0.001)
        assertEquals(62.0, lyrics.lines[1].timeSec!!, 0.001)
        assertEquals(62.345, lyrics.lines[2].timeSec!!, 0.001)
    }

    @Test
    fun multipleTimestampsOnOneLineAndSorting() {
        val lyrics = LrcParser.parse("[00:30.00]Verse\n[00:10.00][00:40.00]Chorus")
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lyrics.lines.map { it.text })
        assertEquals(listOf(10.0, 30.0, 40.0), lyrics.lines.map { it.timeSec!! })
        assertEquals(listOf(0, 1, 2), lyrics.lines.map { it.id })
    }

    @Test
    fun metadataTagsIgnored() {
        val lyrics = LrcParser.parse("[ar:Someone]\n[ti:Song]\n[offset:500]\n[00:01.00]Line")
        assertEquals(listOf("Line"), lyrics.lines.map { it.text })
    }

    @Test
    fun crlfLineEndings() {
        val lyrics = LrcParser.parse("[00:01.00]One\r\n[00:02.00]Two\r\n")
        assertEquals(listOf("One", "Two"), lyrics.lines.map { it.text })
    }

    @Test
    fun timedLineWithEmptyTextIsKeptAsGap() {
        val lyrics = LrcParser.parse("[00:01.00]One\n[00:05.00]\n[00:09.00]Two")
        assertEquals(listOf("One", "", "Two"), lyrics.lines.map { it.text })
    }

    @Test
    fun plainTextIsUntimed() {
        val lyrics = LrcParser.parse("First line\n\nSecond line\n")
        assertFalse(lyrics.isTimed)
        assertEquals(listOf("First line", "Second line"), lyrics.lines.map { it.text })
        assertNull(lyrics.lines[0].timeSec)
        assertNull(lyrics.lineIndexAt(10.0))
    }

    @Test
    fun lineIndexAtTime() {
        val lyrics = LrcParser.parse("[00:10.00]A\n[00:20.00]B")
        assertNull(lyrics.lineIndexAt(5.0))
        assertEquals(0, lyrics.lineIndexAt(10.0))
        assertEquals(0, lyrics.lineIndexAt(19.9))
        assertEquals(1, lyrics.lineIndexAt(500.0))
    }

    @Test
    fun decodesUtf8Gb18030AndUtf16() {
        val text = "[00:01.00]你好，世界"
        assertEquals(text, LrcParser.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, LrcParser.decode(text.toByteArray(Charset.forName("GB18030"))))
        assertEquals(text, LrcParser.decode(text.toByteArray(Charsets.UTF_16)))
    }

    @Test
    fun decodeStripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val data = bom + "[00:01.00]Hi".toByteArray(Charsets.UTF_8)
        val decoded = LrcParser.decode(data)
        assertNotNull(decoded)
        assertEquals(listOf("Hi"), LrcParser.parse(decoded!!).lines.map { it.text })
    }
}
