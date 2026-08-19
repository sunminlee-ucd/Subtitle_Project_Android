package com.sun.subtitleoverlay.subtitle

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SubtitleCueHandoffTest {
    private val cues = listOf(
        SubtitleCue(1_000L, 2_000L, "Hello"),
        SubtitleCue(2_500L, 3_200L, "World"),
    )

    @Before
    fun setUp() = SubtitleCueHandoff.clear()

    @After
    fun tearDown() = SubtitleCueHandoff.clear()

    @Test
    fun preparedCuesAreAvailableOnlyForMatchingToken() {
        val token = SubtitleCueHandoff.prepare(cues)
        assertEquals(cues, SubtitleCueHandoff.get(token)?.cues)
        assertNull(SubtitleCueHandoff.get("wrong-token"))
    }

    @Test
    fun sameCueContentKeepsStableStudyListIdAcrossPreparations() {
        val first = SubtitleCueHandoff.prepare(cues)
        val firstSnapshot = assertNotNull(SubtitleCueHandoff.get(first)).let {
            SubtitleCueHandoff.get(first)!!
        }
        val second = SubtitleCueHandoff.prepare(cues)
        val secondSnapshot = SubtitleCueHandoff.get(second)!!
        assertNotEquals(firstSnapshot.token, secondSnapshot.token)
        assertEquals(firstSnapshot.listId, secondSnapshot.listId)
    }

    @Test
    fun clearingOldTokenDoesNotClearNewActiveSubtitle() {
        val oldToken = SubtitleCueHandoff.prepare(cues)
        val newToken = SubtitleCueHandoff.prepare(cues + SubtitleCue(4_000L, 5_000L, "Again"))
        SubtitleCueHandoff.clear(oldToken)
        assertNotNull(SubtitleCueHandoff.get(newToken))
    }
}
