package com.sun.subtitleoverlay.subtitle

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

