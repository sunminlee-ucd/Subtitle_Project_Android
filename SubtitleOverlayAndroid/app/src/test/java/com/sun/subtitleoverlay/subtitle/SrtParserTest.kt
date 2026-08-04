package com.sun.subtitleoverlay.subtitle

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtParserTest {
    @Test
    fun parsesWindowsLineEndingsAndMultilineText() {
        val input = """
            1\r
            00:00:01,250 --> 00:00:03,000\r
            첫 번째 줄\r
            두 번째 줄\r
            \r
            2\r
            00:00:04.000 --> 00:00:05.500\r
            Second cue\r
        """.trimIndent().replace("\\r", "\r")

        val cues = SrtParser.parse(input)

        assertEquals(2, cues.size)
        assertEquals(1_250L, cues[0].startMs)
        assertEquals("첫 번째 줄\n두 번째 줄", cues[0].text)
        assertEquals(5_500L, cues[1].endMs)
    }

    @Test
    fun ignoresInvalidBlocksAndSortsCues() {
        val input = """
            2
            00:00:05,000 --> 00:00:06,000
            later

            invalid block

            1
            00:00:01,000 --> 00:00:02,000
            earlier
        """.trimIndent()

        val cues = SrtParser.parse(input)

        assertEquals(listOf("earlier", "later"), cues.map { it.text })
    }
}

