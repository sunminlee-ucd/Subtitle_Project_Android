package com.sun.subtitleoverlay.subtitle

object SrtParser {
    private val timingLine = Regex(
        """^\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})(?:\s+.*)?$"""
    )

    fun parse(input: String): List<SubtitleCue> {
        val normalized = input
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return normalized
            .split(Regex("\n[\\t ]*\n+"))
            .mapNotNull(::parseBlock)
            .sortedBy(SubtitleCue::startMs)
    }

    private fun parseBlock(block: String): SubtitleCue? {
        val lines = block.lines().map(String::trimEnd).filter(String::isNotBlank)
        val timingIndex = lines.indexOfFirst { timingLine.matches(it) }
        if (timingIndex < 0 || timingIndex == lines.lastIndex) return null

        val match = timingLine.matchEntire(lines[timingIndex]) ?: return null
        val start = timestampToMs(match.groupValues, 1)
        val end = timestampToMs(match.groupValues, 5)
        if (end <= start) return null

        val text = lines.drop(timingIndex + 1).joinToString("\n").trim()
        if (text.isEmpty()) return null
        return SubtitleCue(start, end, text)
    }

    private fun timestampToMs(groups: List<String>, offset: Int): Long {
        val hours = groups[offset].toLong()
        val minutes = groups[offset + 1].toLong()
        val seconds = groups[offset + 2].toLong()
        val millis = groups[offset + 3].toLong()
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }
}

