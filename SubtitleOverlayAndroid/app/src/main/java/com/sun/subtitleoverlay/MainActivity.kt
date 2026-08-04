package com.sun.subtitleoverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.sun.subtitleoverlay.overlay.OverlayService
import com.sun.subtitleoverlay.subtitle.SrtParser

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var selectedFileLabel: TextView
    private lateinit var overlayPermissionButton: TextView
    private lateinit var playbackPermissionButton: TextView
    private var selectedSrt: Uri? = null

    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val openSrt = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectSrt(uri, persist = true)
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        restoreLastSrt()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionButtons()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
            setBackgroundColor(COLOR_SURFACE)

            addView(TextView(context).apply {
                text = "Subtitle Overlay"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
            }, matchWrap(bottom = dp(6)))

            addView(TextView(context).apply {
                text = "Local SRT subtitles over Netflix and YouTube"
                textSize = 15f
                setTextColor(COLOR_MUTED)
            }, matchWrap(bottom = dp(26)))

            overlayPermissionButton = actionButton("1  Allow display over other apps") {
                openOverlaySettings()
            }
            addView(overlayPermissionButton, matchWrap(bottom = dp(10)))

            playbackPermissionButton = actionButton("2  Enable automatic playback sync") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            addView(playbackPermissionButton, matchWrap(bottom = dp(8)))

            addView(TextView(context).apply {
                text = "Automatic sync uses playback state published by the video app. If unavailable, manual controls remain active."
                textSize = 12f
                setTextColor(COLOR_MUTED)
                setPadding(dp(4), 0, dp(4), 0)
            }, matchWrap(bottom = dp(22)))

            addView(actionButton("3  Choose an SRT file", secondary = true) {
                openSrt.launch(arrayOf("application/x-subrip", "text/plain", "application/octet-stream"))
            }, matchWrap(bottom = dp(10)))

            selectedFileLabel = TextView(context).apply {
                text = "No SRT selected"
                textSize = 14f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundedBackground(Color.WHITE, dp(12), COLOR_BORDER)
            }
            addView(selectedFileLabel, matchWrap(bottom = dp(22)))

            addView(actionButton("Start subtitle overlay") { startOverlay() }, matchWrap(bottom = dp(10)))
            addView(actionButton("Stop overlay", secondary = true) {
                stopService(Intent(context, OverlayService::class.java))
            }, matchWrap())
        }

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun actionButton(
        label: String,
        secondary: Boolean = false,
        action: () -> Unit,
    ) = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (secondary) COLOR_PRIMARY else Color.WHITE)
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedBackground(
            if (secondary) Color.WHITE else COLOR_PRIMARY,
            dp(14),
            if (secondary) COLOR_BORDER else COLOR_PRIMARY,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun selectSrt(uri: Uri, persist: Boolean) {
        runCatching {
            val displayName = queryDisplayName(uri)
            require(displayName.endsWith(".srt", ignoreCase = true)) { "Please select an .srt file." }
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("The selected file cannot be opened.")
            val cueCount = SrtParser.parse(text).size
            require(cueCount > 0) { "No valid subtitle cues were found." }

            if (persist) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                preferences.edit {
                    putString(KEY_LAST_SRT_URI, uri.toString())
                    putString(KEY_LAST_SRT_NAME, displayName)
                }
            }
            selectedSrt = uri
            selectedFileLabel.text = "$displayName  ·  $cueCount cues"
            selectedFileLabel.setTextColor(COLOR_TEXT)
        }.onFailure { error ->
            if (!persist) preferences.edit {
                remove(KEY_LAST_SRT_URI)
                remove(KEY_LAST_SRT_NAME)
            }
            Toast.makeText(this, error.message ?: "Unable to read SRT file.", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreLastSrt() {
        val savedUri = preferences.getString(KEY_LAST_SRT_URI, null) ?: return
        selectSrt(savedUri.toUri(), persist = false)
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: "subtitles.srt"
        }
        return uri.lastPathSegment ?: "subtitles.srt"
    }

    private fun updatePermissionButtons() {
        overlayPermissionButton.text = if (Settings.canDrawOverlays(this)) {
            "✓  Display over other apps enabled"
        } else {
            "1  Allow display over other apps"
        }
        playbackPermissionButton.text = if (hasNotificationAccess()) {
            "✓  Automatic playback sync enabled"
        } else {
            "2  Enable automatic playback sync"
        }
    }

    private fun hasNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps first.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val uri = selectedSrt
        if (uri == null) {
            Toast.makeText(this, "Choose an SRT file first.", Toast.LENGTH_LONG).show()
            return
        }

        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Subtitle overlay started.", Toast.LENGTH_SHORT).show()
        moveTaskToBack(true)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        const val PREFS_NAME = "subtitle_overlay"
        const val KEY_LAST_SRT_URI = "last_srt_uri"
        private const val KEY_LAST_SRT_NAME = "last_srt_name"
        private const val COLOR_SURFACE = 0xFFF6F5FA.toInt()
        private const val COLOR_PRIMARY = 0xFF6750A4.toInt()
        private const val COLOR_TEXT = 0xFF242329.toInt()
        private const val COLOR_MUTED = 0xFF6D6A74.toInt()
        private const val COLOR_BORDER = 0xFFE2DFE8.toInt()
    }
}
