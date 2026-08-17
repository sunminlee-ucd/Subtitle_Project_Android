package com.sun.subtitleoverlay.study

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.core.widget.doAfterTextChanged
import com.sun.subtitleoverlay.MainActivity
import com.sun.subtitleoverlay.subtitle.SrtParser
import com.sun.subtitleoverlay.subtitle.SubtitleCue
import java.security.MessageDigest
import java.util.Locale

class StudySavedCuesActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var cueList: LinearLayout
    private var cues: List<SubtitleCue> = emptyList()
    private var selectedCueIndices: List<Int> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        loadSavedCues()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(COLOR_SURFACE)
        }

        root.addView(TextView(this).apply {
            text = "Saved Study subtitles"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        }, matchWrap(bottom = dp(5)))

        root.addView(TextView(this).apply {
            text = "Search the subtitles you saved by tapping the overlay in Study mode."
            textSize = 13f
            setTextColor(COLOR_MUTED)
        }, matchWrap(bottom = dp(14)))

        val search = EditText(this).apply {
            hint = "Search saved subtitles"
            textSize = 15f
            setSingleLine(true)
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = roundedBackground(Color.WHITE, dp(10), COLOR_BORDER)
            doAfterTextChanged { renderCueList(it?.toString().orEmpty()) }
        }
        root.addView(search, matchWrap(bottom = dp(10)))

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(dp(2), 0, dp(2), dp(8))
        }
        root.addView(statusView, matchWrap())

        cueList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                cueList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        return root
    }

    private fun loadSavedCues() {
        runCatching {
            val preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            val uri = preferences.getString(MainActivity.KEY_LAST_SRT_URI, null)?.toUri()
                ?: error("No SRT file is currently loaded.")
            val filename = queryDisplayName(uri)
            val content = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: error("The selected SRT file cannot be opened.")

            cues = SrtParser.parse(content)
            val subtitleListId = subtitleFingerprint(filename, content)
            val stored = preferences.getString("$SELECTION_KEY_PREFIX$subtitleListId", "").orEmpty()
            selectedCueIndices = stored
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .filter { it in cues.indices }
                .distinct()
                .sortedBy { cues[it].startMs }

            renderCueList("")
        }.onFailure { error ->
            cues = emptyList()
            selectedCueIndices = emptyList()
            renderCueList("")
            Toast.makeText(
                this,
                error.message ?: "Unable to load the saved Study subtitles.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun renderCueList(rawQuery: String) {
        if (!::cueList.isInitialized || !::statusView.isInitialized) return
        val query = rawQuery.trim().lowercase(Locale.getDefault())
        val matching = selectedCueIndices.filter { index ->
            query.isEmpty() || cues[index].text.lowercase(Locale.getDefault()).contains(query)
        }
        val visible = matching.take(MAX_VISIBLE_CUES)

        cueList.removeAllViews()
        statusView.text = when {
            selectedCueIndices.isEmpty() -> "0 subtitles saved"
            query.isEmpty() -> "${selectedCueIndices.size} subtitles saved"
            else -> "${matching.size} of ${selectedCueIndices.size} saved subtitles match"
        }

        if (visible.isEmpty()) {
            cueList.addView(TextView(this).apply {
                text = when {
                    selectedCueIndices.isEmpty() -> "Tap an overlaid subtitle in Study mode to save it."
                    else -> "No saved subtitles match this search."
                }
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(30), dp(16), dp(30))
            }, matchWrap())
            return
        }

        for (index in visible) {
            val cue = cues[index]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = roundedBackground(Color.WHITE, dp(10), COLOR_BORDER)
            }
            card.addView(TextView(this).apply {
                text = "#${index + 1}  ${formatTime(cue.startMs)} → ${formatTime(cue.endMs)}"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(COLOR_MUTED)
            }, matchWrap(bottom = dp(4)))
            card.addView(TextView(this).apply {
                text = cue.text.replace(Regex("\\s+"), " ")
                textSize = 15f
                setTextColor(COLOR_TEXT)
            }, matchWrap())
            cueList.addView(card, matchWrap(bottom = dp(8)))
        }

        if (matching.size > visible.size) {
            cueList.addView(TextView(this).apply {
                text = "Showing ${visible.size} of ${matching.size}. Search to narrow the list."
                textSize = 12f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(14))
            }, matchWrap())
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index) ?: "subtitles.srt"
            }
        }
        return uri.lastPathSegment ?: "subtitles.srt"
    }

    private fun subtitleFingerprint(filename: String, content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$filename\u0000$content".toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val hours = safe / 3_600_000L
        val minutes = (safe % 3_600_000L) / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val millis = safe % 1_000L
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap(bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { bottomMargin = bottom }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SELECTION_KEY_PREFIX = "study_selection_indices_"
        private const val MAX_VISIBLE_CUES = 200
        private const val COLOR_SURFACE = 0xFFF6F5FA.toInt()
        private const val COLOR_TEXT = 0xFF242329.toInt()
        private const val COLOR_MUTED = 0xFF6D6A74.toInt()
        private const val COLOR_BORDER = 0xFFE2DFE8.toInt()
    }
}
