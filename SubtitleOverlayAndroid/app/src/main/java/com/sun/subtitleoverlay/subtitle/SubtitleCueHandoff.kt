package com.sun.subtitleoverlay.subtitle

import java.security.MessageDigest
import java.util.UUID

data class SubtitleCueSnapshot(
    val token: String,
    val listId: String,
    val cues: List<SubtitleCue>,
    val secondaryListId: String? = null,
    val secondaryCues: List<SubtitleCue> = emptyList(),
)

object SubtitleCueHandoff {
    private var active: SubtitleCueSnapshot? = null

    @Synchronized
    fun prepare(cues: List<SubtitleCue>): String = prepare(cues, emptyList())

    @Synchronized
    fun prepare(primaryCues: List<SubtitleCue>, secondaryCues: List<SubtitleCue>): String {
        require(primaryCues.isNotEmpty()) { "No primary subtitle cues are available." }
        val copiedPrimary = primaryCues.toList()
        val copiedSecondary = secondaryCues.toList()
        val token = UUID.randomUUID().toString()
        active = SubtitleCueSnapshot(
            token = token,
            listId = fingerprint(copiedPrimary),
            cues = copiedPrimary,
            secondaryListId = copiedSecondary.takeIf { it.isNotEmpty() }?.let(::fingerprint),
            secondaryCues = copiedSecondary,
        )
        return token
    }

    @Synchronized
    fun get(token: String): SubtitleCueSnapshot? =
        active?.takeIf { token.isNotBlank() && it.token == token }

    @Synchronized
    fun current(): SubtitleCueSnapshot? = active

    @Synchronized
    fun clear(token: String? = null) {
        if (token == null || active?.token == token) active = null
    }

    private fun fingerprint(cues: List<SubtitleCue>): String {
        val canonical = buildString {
            for (cue in cues) {
                append(cue.startMs)
                append('\u0001')
                append(cue.endMs)
                append('\u0001')
                append(cue.text)
                append('\u0002')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
