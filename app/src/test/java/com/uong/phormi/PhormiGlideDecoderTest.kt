package com.uong.phormi

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PhormiGlideDecoderTest {
    private val keys = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, letters ->
        letters.mapIndexed { column, char -> char to PhormiGlideDecoder.Point(column + row * .4f, row.toFloat()) }
    }.toMap()
    private val words = listOf("world", "help", "held", "hello", "house", "word", "would", "there", "their", "the", "test")
    private fun trace(word: String) = PhormiGlideDecoder.sample(word.map { keys.getValue(it) }, 64)
    @Test fun recognizesWordFromContinuousPathRatherThanCrossedKeys() {
        assertEquals("hello", PhormiGlideDecoder.decode(trace("hello"), keys, words, Locale.ENGLISH).first().word)
        assertEquals("world", PhormiGlideDecoder.decode(trace("world"), keys, words, Locale.ENGLISH).first().word)
    }
    @Test fun toleratesSmallFingerOffset() {
        val path = trace("house").map { PhormiGlideDecoder.Point(it.x + .12f, it.y - .08f) }
        assertEquals("house", PhormiGlideDecoder.decode(path, keys, words, Locale.ENGLISH).first().word)
    }
    @Test fun visibleNativeLetterKeysAreNotReplacedByDistantAsciiKeys() {
        val spanish = keys + ('ñ' to PhormiGlideDecoder.Point(9.4f, 1f))
        val path = PhormiGlideDecoder.sample("niño".map { spanish.getValue(it) }, 64)
        assertEquals("niño", PhormiGlideDecoder.decode(path, spanish, listOf("nino", "niño"), Locale("es")).first().word)
    }
    @Test fun rejectsTapsAndOutOfKeyboardPaths() {
        assertTrue(PhormiGlideDecoder.decode(List(20) { keys.getValue('a') }, keys, words, Locale.ENGLISH).isEmpty())
        assertTrue(PhormiGlideDecoder.decode(trace("house").map { PhormiGlideDecoder.Point(it.x + 90, it.y) }, keys, words, Locale.ENGLISH).isEmpty())
    }
    @Test fun returnsRankedAlternativesWithBoundedResultCount() {
        val result = PhormiGlideDecoder.decode(trace("there"), keys, words, Locale.ENGLISH, 2)
        assertEquals("there", result.first().word)
        assertTrue(result.size <= 2)
        assertEquals(result.map { it.score }.sorted(), result.map { it.score })
    }
}
