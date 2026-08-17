package com.sun.subtitleoverlay.study

import com.sun.subtitleoverlay.subtitle.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlaybackEngineTest {
    private val cues = listOf(
        SubtitleCue(1_000L, 2_000L, "one"),
        SubtitleCue(3_000L, 4_000L, "two"),
        SubtitleCue(5_000L, 6_000L, "three"),
    )

    @Test
    fun playlistSortsDeduplicatesAndRepeatsEachCue() {
        val engine = StudyPlaybackEngine(cues)

        assertEquals(
            StudyPlaybackCommand.SeekAndPlay(3_500L),
            engine.startPlaylist(listOf(2, 1, 2), repeatCount = 2, offsetMs = 500L),
        )

        assertEquals(null, engine.onPosition(3_500L, 500L))
        assertEquals(StudyPlaybackCommand.SeekAndPlay(3_500L), engine.onPosition(4_500L, 500L))
        assertEquals(null, engine.onPosition(3_500L, 500L))
        assertEquals(StudyPlaybackCommand.SeekAndPlay(5_500L), engine.onPosition(4_500L, 500L))
        assertEquals(null, engine.onPosition(5_500L, 500L))
        assertEquals(StudyPlaybackCommand.SeekAndPlay(5_500L), engine.onPosition(6_500L, 500L))
        assertEquals(null, engine.onPosition(5_500L, 500L))
        assertEquals(StudyPlaybackCommand.PauseAndSeek(6_500L), engine.onPosition(6_500L, 500L))

        val status = engine.status()
        assertFalse(status.active)
        assertTrue(status.playlistComplete)
    }

    @Test
    fun singleCueContinuesPlaybackAfterConfiguredRepeats() {
        val engine = StudyPlaybackEngine(cues)

        assertEquals(
            StudyPlaybackCommand.SeekAndPlay(1_000L),
            engine.startCue(0, repeatCount = 2, offsetMs = 0L),
        )
        assertEquals(null, engine.onPosition(1_000L, 0L))
        assertEquals(StudyPlaybackCommand.SeekAndPlay(1_000L), engine.onPosition(2_000L, 0L))
        assertEquals(null, engine.onPosition(1_000L, 0L))
        assertEquals(StudyPlaybackCommand.ContinuePlaying, engine.onPosition(2_000L, 0L))
        assertFalse(engine.status().active)
    }

    @Test
    fun repeatCountIsClampedToWebStudyModeRange() {
        val engine = StudyPlaybackEngine(cues)

        engine.startCue(0, repeatCount = 99, offsetMs = 0L)

        assertEquals(20, engine.status().total)
    }

    @Test
    fun stalePositionDoesNotCountAsCompletedUntilSeekIsObserved() {
        val engine = StudyPlaybackEngine(cues)

        engine.startCue(0, repeatCount = 2, offsetMs = 0L)

        assertEquals(null, engine.onPosition(10_000L, 0L))
        assertEquals(0, engine.status().completed)
        assertEquals(null, engine.onPosition(1_000L, 0L))
        assertEquals(StudyPlaybackCommand.SeekAndPlay(1_000L), engine.onPosition(2_000L, 0L))
    }
}
