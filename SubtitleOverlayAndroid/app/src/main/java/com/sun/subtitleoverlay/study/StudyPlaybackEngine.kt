package com.sun.subtitleoverlay.study

import com.sun.subtitleoverlay.subtitle.SubtitleCue
import kotlin.math.abs

sealed interface StudyPlaybackCommand {
    data class SeekAndPlay(val positionMs: Long) : StudyPlaybackCommand
    data class PauseAndSeek(val positionMs: Long) : StudyPlaybackCommand
    data object ContinuePlaying : StudyPlaybackCommand
}

data class StudyPlaybackStatus(
    val active: Boolean,
    val cueIndex: Int,
    val completed: Int,
    val total: Int,
    val playlistPosition: Int,
    val playlistTotal: Int,
    val playlistComplete: Boolean,
)

class StudyPlaybackEngine(
    private val cues: List<SubtitleCue>,
) {
    private var activeCueIndex = -1
    private var activeStartMs = 0L
    private var activeEndMs = 0L
    private var completed = 0
    private var total = 0
    private var playlistCueIndices: List<Int> = emptyList()
    private var playlistPosition = 0
    private var playlistMode = false
    private var awaitingSeekConfirmation = false
    private var playlistComplete = false

    fun startCue(cueIndex: Int, repeatCount: Int, offsetMs: Long): StudyPlaybackCommand {
        require(cueIndex in cues.indices) { "The selected subtitle cue is no longer available." }
        playlistCueIndices = emptyList()
        playlistPosition = 0
        playlistMode = false
        playlistComplete = false
        return beginCue(cueIndex, repeatCount, offsetMs)
    }

    fun startPlaylist(
        rawCueIndices: Collection<Int>,
        repeatCount: Int,
        offsetMs: Long,
    ): StudyPlaybackCommand {
        val cueIndices = rawCueIndices
            .distinct()
            .filter { it in cues.indices }
            .sortedBy { cues[it].startMs }
        require(cueIndices.isNotEmpty()) { "Select at least one subtitle for the study playlist." }

        playlistCueIndices = cueIndices
        playlistPosition = 0
        playlistMode = true
        playlistComplete = false
        return beginCue(cueIndices.first(), repeatCount, offsetMs)
    }

    fun onPosition(positionMs: Long, offsetMs: Long): StudyPlaybackCommand? {
        if (activeCueIndex !in cues.indices) return null

        if (awaitingSeekConfirmation) {
            if (abs(positionMs - activeStartMs) <= SEEK_CONFIRM_TOLERANCE_MS) {
                awaitingSeekConfirmation = false
            }
            return null
        }

        if (positionMs < activeEndMs) return null

        completed += 1
        if (completed < total) {
            awaitingSeekConfirmation = true
            return StudyPlaybackCommand.SeekAndPlay(activeStartMs)
        }

        if (playlistMode && playlistPosition + 1 < playlistCueIndices.size) {
            playlistPosition += 1
            return beginCue(playlistCueIndices[playlistPosition], total, offsetMs)
        }

        val completedEndMs = activeEndMs
        val completedPlaylist = playlistMode
        clearActive()
        return if (completedPlaylist) {
            playlistComplete = true
            StudyPlaybackCommand.PauseAndSeek(completedEndMs)
        } else {
            StudyPlaybackCommand.ContinuePlaying
        }
    }

    fun stop() {
        clearActive()
        playlistComplete = false
    }

    fun status(): StudyPlaybackStatus = StudyPlaybackStatus(
        active = activeCueIndex in cues.indices,
        cueIndex = activeCueIndex,
        completed = completed,
        total = total,
        playlistPosition = playlistPosition,
        playlistTotal = if (playlistMode) playlistCueIndices.size else 0,
        playlistComplete = playlistComplete,
    )

    private fun beginCue(
        cueIndex: Int,
        repeatCount: Int,
        offsetMs: Long,
    ): StudyPlaybackCommand {
        val cue = cues[cueIndex]
        activeCueIndex = cueIndex
        completed = 0
        total = repeatCount.coerceIn(MIN_REPEAT_COUNT, MAX_REPEAT_COUNT)
        activeStartMs = (cue.startMs + offsetMs).coerceAtLeast(0L)
        activeEndMs = (cue.endMs + offsetMs).coerceAtLeast(activeStartMs + MIN_CUE_DURATION_MS)
        awaitingSeekConfirmation = true
        playlistComplete = false
        return StudyPlaybackCommand.SeekAndPlay(activeStartMs)
    }

    private fun clearActive() {
        activeCueIndex = -1
        activeStartMs = 0L
        activeEndMs = 0L
        completed = 0
        total = 0
        playlistCueIndices = emptyList()
        playlistPosition = 0
        playlistMode = false
        awaitingSeekConfirmation = false
    }

    companion object {
        const val MIN_REPEAT_COUNT = 1
        const val MAX_REPEAT_COUNT = 20
        const val DEFAULT_REPEAT_COUNT = 5
        private const val MIN_CUE_DURATION_MS = 50L
        private const val SEEK_CONFIRM_TOLERANCE_MS = 1_500L
    }
}
