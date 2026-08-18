package com.sun.subtitleoverlay

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.sun.subtitleoverlay.customer.AuthorizedSubtitleTrack
import com.sun.subtitleoverlay.customer.CustomerBackendConfig
import com.sun.subtitleoverlay.customer.CustomerSession
import com.sun.subtitleoverlay.customer.CustomerSubtitleRepository
import com.sun.subtitleoverlay.overlay.OverlayService
import com.sun.subtitleoverlay.subtitle.SrtParser
import java.io.File
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var authCard: LinearLayout
    private lateinit var customerCard: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var userEmailView: TextView
    private lateinit var overlayPermissionButton: TextView
    private lateinit var playbackPermissionButton: TextView
    private lateinit var tracksContainer: LinearLayout
    private lateinit var selectedTrackLabel: TextView
    private lateinit var statusView: TextView

    private var selectedTrack: AuthorizedSubtitleTrack? = null
    private val repository by lazy { CustomerSubtitleRepository(this) }
    private val executor = Executors.newSingleThreadExecutor()

    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        File(cacheDir, AUTHORIZED_CACHE_FILENAME).delete()
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
        restoreCustomerSession()
    }

    override fun onResume() {
        super.onResume()
        if (::overlayPermissionButton.isInitialized) updatePermissionButtons()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(32))
            setBackgroundColor(COLOR_SURFACE)

            addView(TextView(context).apply {
                text = "Subtitle Companion"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
            }, matchWrap(bottom = dp(6)))

            addView(TextView(context).apply {
                text = "Private subtitles shared with your account"
                textSize = 15f
                setTextColor(COLOR_MUTED)
            }, matchWrap(bottom = dp(22)))

            addView(actionButton("Request a subtitle", secondary = true) {
                openRequestPortal()
            }, matchWrap(bottom = dp(18)))

            authCard = sectionCard().apply {
                addView(sectionTitle("Sign in"), matchWrap(bottom = dp(6)))
                addView(sectionDescription("Use the same account as the Subtitle Companion customer portal."), matchWrap(bottom = dp(14)))

                emailInput = inputField("Email").apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    setAutofillHints(View.AUTOFILL_HINT_EMAIL_ADDRESS)
                }
                addView(emailInput, matchWrap(bottom = dp(10)))

                passwordInput = inputField("Password").apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                }
                addView(passwordInput, matchWrap(bottom = dp(12)))

                addView(actionButton("Sign in") { signIn() }, matchWrap())
            }
            addView(authCard, matchWrap(bottom = dp(16)))

            customerCard = sectionCard().apply {
                visibility = View.GONE

                val accountRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                userEmailView = TextView(context).apply {
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(COLOR_TEXT)
                }
                accountRow.addView(userEmailView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                accountRow.addView(compactButton("Sign out") { signOut() })
                addView(accountRow, matchWrap(bottom = dp(18)))

                addView(sectionTitle("Device permissions"), matchWrap(bottom = dp(10)))
                overlayPermissionButton = actionButton("1  Allow display over other apps") {
                    openOverlaySettings()
                }
                addView(overlayPermissionButton, matchWrap(bottom = dp(10)))

                playbackPermissionButton = actionButton("2  Enable automatic playback sync", secondary = true) {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                addView(playbackPermissionButton, matchWrap(bottom = dp(8)))

                addView(sectionDescription(
                    "Automatic sync uses playback state published by the video app. If unavailable, manual controls remain active."
                ), matchWrap(bottom = dp(22)))

                val libraryHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                libraryHeader.addView(sectionTitle("My subtitles"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                libraryHeader.addView(compactButton("Refresh") { loadTracks() })
                addView(libraryHeader, matchWrap(bottom = dp(8)))

                addView(sectionDescription(
                    "Only subtitles explicitly shared with your account appear here. Files cannot be exported from the app."
                ), matchWrap(bottom = dp(12)))

                tracksContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(tracksContainer, matchWrap(bottom = dp(12)))

                selectedTrackLabel = TextView(context).apply {
                    text = "Choose a subtitle from your library"
                    textSize = 13f
                    setTextColor(COLOR_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = roundedBackground(Color.WHITE, dp(12), COLOR_BORDER)
                }
                addView(selectedTrackLabel, matchWrap(bottom = dp(14)))

                addView(actionButton("Start subtitle overlay") { startSelectedOverlay() }, matchWrap(bottom = dp(10)))
                addView(actionButton("Stop overlay", secondary = true) {
                    stopService(Intent(context, OverlayService::class.java))
                }, matchWrap())
            }
            addView(customerCard, matchWrap(bottom = dp(16)))

            statusView = TextView(context).apply {
                textSize = 13f
                setTextColor(COLOR_MUTED)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            addView(statusView, matchWrap())
        }

        return ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
    }

    private fun restoreCustomerSession() {
        setStatus("Checking your account…")
        executor.execute {
            val result = runCatching { repository.restoreSession() }
            runOnUiThread {
                val session = result.getOrNull()
                setSignedIn(session)
                if (session != null) {
                    loadTracks()
                } else {
                    setStatus("")
                }
            }
        }
    }

    private fun signIn() {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        setStatus("Signing in…")
        executor.execute {
            val result = runCatching { repository.signIn(email, password) }
            runOnUiThread {
                result.onSuccess { session ->
                    passwordInput.text.clear()
                    setSignedIn(session)
                    loadTracks()
                }.onFailure { error ->
                    setStatus(error.message ?: "Unable to sign in.")
                }
            }
        }
    }

    private fun signOut() {
        setStatus("Signing out…")
        executor.execute {
            repository.signOut()
            File(cacheDir, AUTHORIZED_CACHE_FILENAME).delete()
            runOnUiThread {
                stopService(Intent(this, OverlayService::class.java))
                selectedTrack = null
                tracksContainer.removeAllViews()
                selectedTrackLabel.text = "Choose a subtitle from your library"
                selectedTrackLabel.setTextColor(COLOR_MUTED)
                setSignedIn(null)
                setStatus("")
            }
        }
    }

    private fun setSignedIn(session: CustomerSession?) {
        val signedIn = session != null
        authCard.visibility = if (signedIn) View.GONE else View.VISIBLE
        customerCard.visibility = if (signedIn) View.VISIBLE else View.GONE
        if (session != null) {
            userEmailView.text = session.email
            updatePermissionButtons()
        }
    }

    private fun loadTracks() {
        if (!repository.hasStoredSession()) return
        setStatus("Loading your authorized subtitles…")
        executor.execute {
            val result = runCatching { repository.authorizedTracks() }
            runOnUiThread {
                result.onSuccess { tracks ->
                    renderTracks(tracks)
                    setStatus(
                        if (tracks.isEmpty()) "No subtitles have been shared with this account yet."
                        else ""
                    )
                }.onFailure { error ->
                    if (!repository.hasStoredSession()) setSignedIn(null)
                    setStatus(error.message ?: "Unable to load your subtitles.")
                }
            }
        }
    }

    private fun renderTracks(tracks: List<AuthorizedSubtitleTrack>) {
        tracksContainer.removeAllViews()
        if (tracks.isEmpty()) {
            tracksContainer.addView(sectionDescription("Your library is empty."), matchWrap())
            selectedTrack = null
            selectedTrackLabel.text = "Choose a subtitle from your library"
            selectedTrackLabel.setTextColor(COLOR_MUTED)
            return
        }

        for (track in tracks) {
            val item = TextView(this).apply {
                text = buildString {
                    append(track.displayTitle)
                    append('\n')
                    append(track.languageName)
                    if (track.cueCount > 0) append(" · ${track.cueCount} cues")
                }
                textSize = 14f
                setTextColor(COLOR_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(Color.WHITE, dp(12), COLOR_BORDER)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedTrack = track
                    selectedTrackLabel.text = "Selected: ${track.displayLabel}"
                    selectedTrackLabel.setTextColor(COLOR_TEXT)
                    for (index in 0 until tracksContainer.childCount) {
                        tracksContainer.getChildAt(index).alpha = 0.72f
                    }
                    alpha = 1f
                }
            }
            tracksContainer.addView(item, matchWrap(bottom = dp(8)))
        }
    }

    private fun startSelectedOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps first.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val track = selectedTrack
        if (track == null) {
            Toast.makeText(this, "Choose a subtitle from your library first.", Toast.LENGTH_LONG).show()
            return
        }

        setStatus("Loading the private subtitle…")
        executor.execute {
            val result = runCatching {
                val loaded = repository.loadAuthorizedSubtitle(track.id)
                val file = File(cacheDir, AUTHORIZED_CACHE_FILENAME)
                file.writeText(SrtParser.render(loaded.cues), Charsets.UTF_8)
                PreparedSubtitle(file, track.displayLabel, loaded.cues.size)
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START
                        putExtra(OverlayService.EXTRA_AUTHORIZED_SRT_PATH, prepared.file.absolutePath)
                        putExtra(OverlayService.EXTRA_AUTHORIZED_TRACK_LABEL, prepared.label)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    setStatus("")
                    Toast.makeText(
                        this,
                        "${prepared.cueCount} subtitle cues loaded.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    moveTaskToBack(true)
                }.onFailure { error ->
                    File(cacheDir, AUTHORIZED_CACHE_FILENAME).delete()
                    setStatus(error.message ?: "Unable to load this subtitle.")
                }
            }
        }
    }

    private fun openRequestPortal() {
        val url = "${CustomerBackendConfig.PORTAL_URL}?view=request"
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun sectionCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(Color.WHITE, dp(18), COLOR_BORDER)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT)
    }

    private fun sectionDescription(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(COLOR_MUTED)
    }

    private fun inputField(hintValue: String) = EditText(this).apply {
        hint = hintValue
        textSize = 16f
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedBackground(COLOR_INPUT, dp(12), COLOR_BORDER)
        setSingleLine(true)
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

    private fun compactButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(COLOR_PRIMARY)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = roundedBackground(COLOR_INPUT, dp(10), COLOR_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun setStatus(message: String) {
        if (!::statusView.isInitialized) return
        statusView.text = message
        statusView.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
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

    private data class PreparedSubtitle(
        val file: File,
        val label: String,
        val cueCount: Int,
    )

    companion object {
        const val PREFS_NAME = "subtitle_overlay"
        private const val AUTHORIZED_CACHE_FILENAME = "authorized-subtitle.srt"
        private const val COLOR_SURFACE = 0xFFF6F5FA.toInt()
        private const val COLOR_PRIMARY = 0xFF6750A4.toInt()
        private const val COLOR_TEXT = 0xFF242329.toInt()
        private const val COLOR_MUTED = 0xFF6D6A74.toInt()
        private const val COLOR_BORDER = 0xFFE2DFE8.toInt()
        private const val COLOR_INPUT = 0xFFF9F8FC.toInt()
    }
}
