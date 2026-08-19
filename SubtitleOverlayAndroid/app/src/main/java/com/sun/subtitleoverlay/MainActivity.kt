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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import androidx.core.content.FileProvider
import androidx.core.content.edit
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
    private lateinit var permissionSummaryView: TextView
    private lateinit var overlayPermissionButton: TextView
    private lateinit var playbackPermissionButton: TextView
    private lateinit var notificationPermissionButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var libraryCountView: TextView
    private lateinit var tracksContainer: LinearLayout
    private lateinit var selectedTrackLabel: TextView
    private lateinit var statusView: TextView

    private var selectedTrack: AuthorizedSubtitleTrack? = null
    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()
    private val repository by lazy { CustomerSubtitleRepository(this) }
    private val executor = Executors.newSingleThreadExecutor()

    private val preferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (::notificationPermissionButton.isInitialized) updatePermissionButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_SURFACE
        window.navigationBarColor = COLOR_SURFACE
        File(cacheDir, AUTHORIZED_CACHE_FILENAME).delete()
        preferences.edit { remove(KEY_LAST_SRT_URI) }
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
            setPadding(dp(20), dp(22), dp(20), dp(32))
            setBackgroundColor(COLOR_SURFACE)

            addView(View(context).apply {
                setBackgroundColor(COLOR_PRIMARY)
            }, LinearLayout.LayoutParams(dp(44), dp(4)).apply {
                bottomMargin = dp(14)
            })

            addView(TextView(context).apply {
                text = "SUBTITLE COMPANION"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.12f
                setTextColor(COLOR_PRIMARY)
            }, matchWrap(bottom = dp(4)))

            addView(TextView(context).apply {
                text = "Your private subtitle library"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
            }, matchWrap(bottom = dp(7)))

            addView(TextView(context).apply {
                text = "Sign in, choose an authorized subtitle and watch it over your streaming app."
                textSize = 14f
                setTextColor(COLOR_MUTED)
            }, matchWrap(bottom = dp(20)))

            addView(actionButton("Request a subtitle", secondary = true) {
                openRequestPortal()
            }, matchWrap(bottom = dp(18)))

            authCard = sectionCard().apply {
                addView(sectionEyebrow("CUSTOMER ACCOUNT"), matchWrap(bottom = dp(5)))
                addView(sectionTitle("Sign in"), matchWrap(bottom = dp(6)))
                addView(sectionDescription("Use the same customer account as the Subtitle Companion website and Chrome extension."), matchWrap(bottom = dp(14)))

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

            customerCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE

                val accountCard = sectionCard().apply {
                    addView(sectionEyebrow("ACCOUNT"), matchWrap(bottom = dp(8)))
                    val accountRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    userEmailView = TextView(context).apply {
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLOR_TEXT)
                    }
                    accountRow.addView(
                        userEmailView,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    accountRow.addView(compactButton("Sign out") { signOut() })
                    addView(accountRow, matchWrap())
                }
                addView(accountCard, matchWrap(bottom = dp(12)))

                val permissionCard = sectionCard().apply {
                    addView(sectionEyebrow("DEVICE SETUP"), matchWrap(bottom = dp(5)))
                    addView(sectionTitle("Permissions"), matchWrap(bottom = dp(6)))
                    addView(sectionDescription(
                        "Complete these once so subtitles can appear over Netflix and playback can stay in sync."
                    ), matchWrap(bottom = dp(12)))

                    permissionSummaryView = TextView(context).apply {
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        background = roundedBackground(COLOR_WARNING_FILL, dp(10), COLOR_PRIMARY_DARK)
                        setTextColor(COLOR_TEXT)
                    }
                    addView(permissionSummaryView, matchWrap(bottom = dp(12)))

                    overlayPermissionButton = permissionActionButton("Grant") {
                        openOverlaySettings()
                    }
                    addView(permissionRow(
                        number = "1",
                        title = "Display over other apps",
                        description = "Required. Lets Subtitle Companion draw the subtitle layer on top of Netflix or another video app.",
                        action = overlayPermissionButton,
                    ), matchWrap(bottom = dp(9)))

                    playbackPermissionButton = permissionActionButton("Enable") {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                    addView(permissionRow(
                        number = "2",
                        title = "Playback sync access",
                        description = "Recommended. Reads playback state exposed by supported video apps for automatic timing and study controls.",
                        action = playbackPermissionButton,
                    ), matchWrap(bottom = dp(9)))

                    notificationPermissionButton = permissionActionButton("Allow") {
                        requestOrManageNotificationPermission()
                    }
                    addView(permissionRow(
                        number = "3",
                        title = "App notifications",
                        description = "Recommended. Keeps the overlay service visible and lets you restore a hidden control panel from the notification.",
                        action = notificationPermissionButton,
                    ), matchWrap())
                }
                addView(permissionCard, matchWrap(bottom = dp(12)))

                val libraryCard = sectionCard().apply {
                    val libraryHeader = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val heading = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(sectionEyebrow("AUTHORIZED LIBRARY"), matchWrap(bottom = dp(3)))
                        addView(sectionTitle("My subtitles"), matchWrap())
                    }
                    libraryHeader.addView(
                        heading,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    libraryHeader.addView(compactButton("Refresh") { loadTracks() })
                    addView(libraryHeader, matchWrap(bottom = dp(8)))

                    addView(sectionDescription(
                        "Only subtitles granted to this account appear here. Search by title, episode or language."
                    ), matchWrap(bottom = dp(12)))

                    searchInput = inputField("Search title, episode or language…").apply {
                        inputType = InputType.TYPE_CLASS_TEXT
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                renderFilteredTracks()
                            }
                            override fun afterTextChanged(s: Editable?) = Unit
                        })
                    }
                    addView(searchInput, matchWrap(bottom = dp(8)))

                    libraryCountView = TextView(context).apply {
                        text = "0 available"
                        textSize = 11f
                        setTextColor(COLOR_MUTED)
                        gravity = Gravity.END
                    }
                    addView(libraryCountView, matchWrap(bottom = dp(8)))

                    tracksContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(8), dp(8), dp(2))
                    }

                    val trackScroll = ScrollView(context).apply {
                        isFillViewport = false
                        isNestedScrollingEnabled = true
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        background = roundedBackground(COLOR_LIBRARY_SURFACE, dp(12), COLOR_BORDER)
                        addView(
                            tracksContainer,
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        )
                    }
                    addView(
                        trackScroll,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(LIBRARY_LIST_HEIGHT_DP),
                        ).apply { bottomMargin = dp(12) }
                    )

                    selectedTrackLabel = TextView(context).apply {
                        text = "No subtitle selected"
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(COLOR_MUTED)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        background = roundedBackground(COLOR_INPUT, dp(10), COLOR_BORDER)
                    }
                    addView(selectedTrackLabel, matchWrap(bottom = dp(14)))

                    addView(actionButton("Start subtitle overlay") { startSelectedOverlay() }, matchWrap(bottom = dp(9)))
                    addView(actionButton("Stop overlay", secondary = true) {
                        stopService(Intent(context, OverlayService::class.java))
                        Toast.makeText(context, "Subtitle overlay stopped.", Toast.LENGTH_SHORT).show()
                    }, matchWrap())
                }
                addView(libraryCard, matchWrap())
            }
            addView(customerCard, matchWrap(bottom = dp(16)))

            statusView = TextView(context).apply {
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
                gravity = Gravity.CENTER
                visibility = View.GONE
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = roundedBackground(COLOR_CARD, dp(12), COLOR_BORDER)
            }
            addView(statusView, matchWrap())
        }

        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_SURFACE)
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
                allTracks = emptyList()
                if (::searchInput.isInitialized) searchInput.text.clear()
                if (::tracksContainer.isInitialized) tracksContainer.removeAllViews()
                if (::selectedTrackLabel.isInitialized) updateSelectedTrackLabel()
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
                    allTracks = tracks
                    selectedTrack = selectedTrack?.let { selected ->
                        tracks.firstOrNull { it.id == selected.id }
                    }
                    updateSelectedTrackLabel()
                    renderFilteredTracks()
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

    private fun renderFilteredTracks() {
        if (!::tracksContainer.isInitialized || !::libraryCountView.isInitialized) return
        val query = if (::searchInput.isInitialized) {
            searchInput.text.toString().trim().lowercase()
        } else {
            ""
        }
        val filtered = if (query.isBlank()) {
            allTracks
        } else {
            allTracks.filter { track ->
                listOf(
                    track.title,
                    track.episodeLabel,
                    track.languageCode,
                    track.languageName,
                    track.displayTitle,
                    track.displayLabel,
                ).joinToString(" ").lowercase().contains(query)
            }
        }

        libraryCountView.text = when {
            allTracks.isEmpty() -> "0 available"
            query.isBlank() -> "${allTracks.size} available"
            else -> "${filtered.size} of ${allTracks.size} available"
        }

        tracksContainer.removeAllViews()
        if (filtered.isEmpty()) {
            tracksContainer.addView(
                emptyLibraryMessage(
                    if (allTracks.isEmpty()) {
                        "Your library is empty. Subtitles appear here after an administrator grants access."
                    } else {
                        "No subtitles match “${searchInput.text.toString().trim()}”."
                    }
                ),
                matchWrap(),
            )
            return
        }

        for (track in filtered) {
            val selected = selectedTrack?.id == track.id
            val item = TextView(this).apply {
                tag = track.id
                text = buildString {
                    append(track.displayTitle)
                    append('\n')
                    append(track.languageName)
                    if (track.languageCode.isNotBlank()) append(" · ${track.languageCode.uppercase()}")
                    if (track.cueCount > 0) append(" · ${track.cueCount} cues")
                }
                textSize = 14f
                setTextColor(COLOR_TEXT)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(0f, 1.08f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = if (selected) {
                    roundedBackground(COLOR_SELECTED_FILL, dp(11), COLOR_PRIMARY)
                } else {
                    roundedBackground(COLOR_TRACK_FILL, dp(11), COLOR_BORDER)
                }
                alpha = if (selected) 1f else 0.92f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedTrack = track
                    updateSelectedTrackLabel()
                    renderFilteredTracks()
                }
            }
            tracksContainer.addView(item, matchWrap(bottom = dp(7)))
        }
    }

    private fun updateSelectedTrackLabel() {
        if (!::selectedTrackLabel.isInitialized) return
        val track = selectedTrack
        if (track == null) {
            selectedTrackLabel.text = "No subtitle selected"
            selectedTrackLabel.setTextColor(COLOR_MUTED)
            selectedTrackLabel.background = roundedBackground(COLOR_INPUT, dp(10), COLOR_BORDER)
        } else {
            selectedTrackLabel.text = "Selected · ${track.displayLabel}"
            selectedTrackLabel.setTextColor(COLOR_TEXT)
            selectedTrackLabel.background = roundedBackground(COLOR_SELECTED_FILL, dp(10), COLOR_PRIMARY_DARK)
        }
    }

    private fun startSelectedOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Display over other apps is required before starting subtitles.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        val track = selectedTrack
        if (track == null) {
            Toast.makeText(this, "Choose a subtitle from My subtitles first.", Toast.LENGTH_LONG).show()
            return
        }

        if (!hasNotificationAccess()) {
            Toast.makeText(
                this,
                "Playback sync is not enabled. The overlay will still start, but automatic timing may be limited.",
                Toast.LENGTH_LONG,
            ).show()
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
                    val contentUri = FileProvider.getUriForFile(
                        this,
                        "$packageName.privatefiles",
                        prepared.file,
                    )
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START
                        data = contentUri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ContextCompat.startForegroundService(this, intent)
                    window.decorView.postDelayed({ prepared.file.delete() }, CACHE_DELETE_DELAY_MS)
                    setStatus("")
                    Toast.makeText(
                        this,
                        "${prepared.cueCount} cues ready · ${prepared.label}",
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
        if (!::overlayPermissionButton.isInitialized) return
        val overlayGranted = Settings.canDrawOverlays(this)
        val playbackGranted = hasNotificationAccess()
        val notificationsGranted = hasNotificationPermission()

        stylePermissionButton(
            overlayPermissionButton,
            granted = overlayGranted,
            grantedLabel = "✓ Granted",
            actionLabel = "Grant",
        )
        stylePermissionButton(
            playbackPermissionButton,
            granted = playbackGranted,
            grantedLabel = "✓ Enabled",
            actionLabel = "Enable",
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionButton.text = "✓ Available"
            notificationPermissionButton.setTextColor(COLOR_SUCCESS)
            notificationPermissionButton.background = roundedBackground(COLOR_INPUT, dp(9), COLOR_BORDER)
            notificationPermissionButton.alpha = 0.85f
            notificationPermissionButton.isClickable = false
        } else {
            notificationPermissionButton.isClickable = true
            notificationPermissionButton.alpha = 1f
            stylePermissionButton(
                notificationPermissionButton,
                granted = notificationsGranted,
                grantedLabel = "✓ Allowed",
                actionLabel = "Allow",
            )
        }

        val summary = when {
            !overlayGranted -> "Setup needed · Allow Display over other apps before starting subtitles."
            !playbackGranted -> "Overlay ready · Enable Playback sync access for automatic timing and study controls."
            !notificationsGranted -> "Almost ready · Allow app notifications so a hidden control panel can be restored easily."
            else -> "Ready to watch · All recommended permissions are enabled."
        }
        permissionSummaryView.text = summary
        permissionSummaryView.setTextColor(if (overlayGranted && playbackGranted && notificationsGranted) COLOR_SUCCESS else COLOR_TEXT)
        permissionSummaryView.background = roundedBackground(
            if (overlayGranted && playbackGranted && notificationsGranted) COLOR_READY_FILL else COLOR_WARNING_FILL,
            dp(10),
            if (overlayGranted && playbackGranted && notificationsGranted) COLOR_READY_BORDER else COLOR_PRIMARY_DARK,
        )
    }

    private fun stylePermissionButton(
        button: TextView,
        granted: Boolean,
        grantedLabel: String,
        actionLabel: String,
    ) {
        button.text = if (granted) grantedLabel else actionLabel
        button.setTextColor(if (granted) COLOR_SUCCESS else Color.WHITE)
        button.background = roundedBackground(
            if (granted) COLOR_INPUT else COLOR_PRIMARY,
            dp(9),
            if (granted) COLOR_BORDER else COLOR_PRIMARY,
        )
    }

    private fun hasNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
    }

    private fun requestOrManageNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun permissionRow(
        number: String,
        title: String,
        description: String,
        action: TextView,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(10), dp(12))
        background = roundedBackground(COLOR_INPUT, dp(12), COLOR_BORDER)

        val numberView = TextView(context).apply {
            text = number
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(COLOR_PRIMARY_DARK, dp(16), COLOR_PRIMARY_DARK)
        }
        addView(numberView, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            marginEnd = dp(10)
        })

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COLOR_TEXT)
            }, matchWrap(bottom = dp(3)))
            addView(TextView(context).apply {
                text = description
                textSize = 10.5f
                setTextColor(COLOR_MUTED)
            }, matchWrap())
        }
        addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
        })
        addView(action)
    }

    private fun sectionCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = roundedBackground(COLOR_CARD, dp(16), COLOR_BORDER)
    }

    private fun sectionEyebrow(value: String) = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(COLOR_PRIMARY)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(COLOR_TEXT)
    }

    private fun sectionDescription(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(COLOR_MUTED)
    }

    private fun emptyLibraryMessage(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(COLOR_MUTED)
        setPadding(dp(18), dp(28), dp(18), dp(28))
    }

    private fun inputField(hintValue: String) = EditText(this).apply {
        hint = hintValue
        textSize = 16f
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = roundedBackground(COLOR_INPUT, dp(11), COLOR_BORDER)
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
            if (secondary) COLOR_CARD_ALT else COLOR_PRIMARY,
            dp(12),
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
        background = roundedBackground(COLOR_INPUT, dp(9), COLOR_BORDER)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun permissionActionButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(9), dp(10), dp(9))
        minWidth = dp(72)
        background = roundedBackground(COLOR_PRIMARY, dp(9), COLOR_PRIMARY)
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
        const val KEY_LAST_SRT_URI = "last_srt_uri"
        private const val AUTHORIZED_CACHE_FILENAME = "authorized-subtitle.srt"
        private const val CACHE_DELETE_DELAY_MS = 5_000L
        private const val LIBRARY_LIST_HEIGHT_DP = 310

        private const val COLOR_SURFACE = 0xFF08090C.toInt()
        private const val COLOR_CARD = 0xFF14151A.toInt()
        private const val COLOR_CARD_ALT = 0xFF101116.toInt()
        private const val COLOR_LIBRARY_SURFACE = 0xFF0D0E12.toInt()
        private const val COLOR_TRACK_FILL = 0xFF18191E.toInt()
        private const val COLOR_PRIMARY = 0xFFE50914.toInt()
        private const val COLOR_PRIMARY_DARK = 0xFFB20710.toInt()
        private const val COLOR_TEXT = 0xFFF7F7F8.toInt()
        private const val COLOR_MUTED = 0xFFAAAAB2.toInt()
        private const val COLOR_BORDER = 0xFF303139.toInt()
        private const val COLOR_INPUT = 0xFF0F1014.toInt()
        private const val COLOR_SELECTED_FILL = 0xFF2B1014.toInt()
        private const val COLOR_WARNING_FILL = 0xFF231013.toInt()
        private const val COLOR_READY_FILL = 0xFF102018.toInt()
        private const val COLOR_READY_BORDER = 0xFF315B42.toInt()
        private const val COLOR_SUCCESS = 0xFF79D992.toInt()
    }
}
