package com.sun.subtitleoverlay

import android.Manifest
import android.app.AlertDialog
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import com.sun.subtitleoverlay.subtitle.SubtitleCueHandoff
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private lateinit var authCard: LinearLayout
    private lateinit var customerCard: LinearLayout
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var googleSignInButton: LinearLayout
    private lateinit var userEmailView: TextView
    private lateinit var permissionSummaryView: TextView
    private lateinit var overlayPermissionButton: TextView
    private lateinit var playbackPermissionButton: TextView
    private lateinit var notificationPermissionButton: TextView
    private lateinit var permissionDetailsContainer: LinearLayout
    private lateinit var permissionToggleView: TextView
    private lateinit var searchInput: EditText
    private lateinit var libraryCountView: TextView
    private lateinit var tracksContainer: LinearLayout
    private lateinit var selectedTrackLabel: TextView
    private lateinit var startOverlayButton: TextView
    private lateinit var overlayLoadingRow: LinearLayout
    private lateinit var overlayLoadingLabel: TextView
    private lateinit var statusView: TextView

    private var selectedTrack: AuthorizedSubtitleTrack? = null
    private var multiSub1Track: AuthorizedSubtitleTrack? = null
    private var multiSub2Track: AuthorizedSubtitleTrack? = null
    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()
    private var overlayStartInProgress = false
    private val repository by lazy { CustomerSubtitleRepository(this) }
    private val executor = Executors.newSingleThreadExecutor()

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (::notificationPermissionButton.isInitialized) updatePermissionButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = COLOR_SURFACE
        window.navigationBarColor = COLOR_SURFACE
        setContentView(buildContent())
        requestNotificationPermissionIfNeeded()
        if (!handleGoogleOAuthCallback(intent)) {
            restoreCustomerSession()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGoogleOAuthCallback(intent)
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
            }, matchWrap(bottom = dp(8)))

            addView(actionButton("Multi Subtitle", secondary = true) {
                openMultiSubtitleSetup()
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

                addView(actionButton("Sign in") { signIn() }, matchWrap(bottom = dp(12)))

                addView(TextView(context).apply {
                    text = "OR"
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setTextColor(COLOR_MUTED)
                }, matchWrap(bottom = dp(10)))

                googleSignInButton = googleAuthButton {
                    startGoogleSignIn()
                }
                addView(googleSignInButton, matchWrap(bottom = dp(9)))
                addView(sectionDescription(
                    "Use Google to sign in or create a Subtitle Companion customer account."
                ), matchWrap())
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

                    val permissionHeader = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val permissionHeading = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(sectionTitle("Permissions"), matchWrap())
                    }
                    permissionHeader.addView(
                        permissionHeading,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    permissionToggleView = compactButton("Hide") {
                        setPermissionDetailsExpanded(permissionDetailsContainer.visibility != View.VISIBLE)
                    }
                    permissionHeader.addView(permissionToggleView)
                    addView(permissionHeader, matchWrap(bottom = dp(9)))

                    permissionSummaryView = TextView(context).apply {
                        textSize = 12f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(12), dp(11), dp(12), dp(11))
                        background = roundedBackground(COLOR_WARNING_FILL, dp(10), COLOR_PRIMARY_DARK)
                        setTextColor(COLOR_TEXT)
                    }
                    addView(permissionSummaryView, matchWrap(bottom = dp(10)))

                    permissionDetailsContainer = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(sectionDescription(
                            "Complete these once so subtitles can appear over Netflix and playback can stay in sync."
                        ), matchWrap(bottom = dp(12)))

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
                    addView(permissionDetailsContainer, matchWrap())
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

                    startOverlayButton = actionButton("Start subtitle overlay") { startSelectedOverlay() }
                    addView(startOverlayButton, matchWrap(bottom = dp(7)))

                    overlayLoadingRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        setPadding(dp(8), dp(4), dp(8), dp(10))

                        addView(ProgressBar(context).apply {
                            isIndeterminate = true
                            indeterminateTintList = android.content.res.ColorStateList.valueOf(COLOR_PRIMARY)
                        }, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                            marginEnd = dp(9)
                        })

                        overlayLoadingLabel = TextView(context).apply {
                            text = "Loading subtitle…"
                            textSize = 12f
                            typeface = Typeface.DEFAULT_BOLD
                            setTextColor(COLOR_MUTED)
                        }
                        addView(overlayLoadingLabel)
                    }
                    addView(overlayLoadingRow, matchWrap())

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

    private fun startGoogleSignIn() {
        if (::googleSignInButton.isInitialized && !googleSignInButton.isEnabled) return
        setGoogleSignInBusy(true)
        setStatus("Checking Google sign-in…")

        executor.execute {
            val result = runCatching { repository.createGoogleSignInUrl() }
            runOnUiThread {
                result.onSuccess { authorizationUrl ->
                    setStatus("Opening Google sign-in…")
                    val opened = runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, authorizationUrl.toUri()))
                    }
                    setGoogleSignInBusy(false)
                    if (opened.isFailure) {
                        repository.clearPendingGoogleSignIn()
                        setStatus(opened.exceptionOrNull()?.message ?: "Unable to open Google sign-in.")
                    }
                }.onFailure { error ->
                    setGoogleSignInBusy(false)
                    setStatus(error.message ?: "Unable to start Google sign-in.")
                }
            }
        }
    }

    private fun handleGoogleOAuthCallback(sourceIntent: Intent?): Boolean {
        val data = sourceIntent?.data ?: return false
        if (data.scheme != "subtitlecompanion" || data.host != "auth-callback") return false

        val oauthError = data.getQueryParameter("error_description")
            ?: data.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            repository.clearPendingGoogleSignIn()
            setStatus("Google sign-in failed: $oauthError")
            return true
        }

        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            repository.clearPendingGoogleSignIn()
            setStatus("Google sign-in did not return an authorization code.")
            return true
        }

        setStatus("Finishing Google sign-in…")
        executor.execute {
            val result = runCatching { repository.completeGoogleSignIn(code) }
            runOnUiThread {
                result.onSuccess { session ->
                    passwordInput.text.clear()
                    setSignedIn(session)
                    Toast.makeText(this, "Signed in with Google.", Toast.LENGTH_SHORT).show()
                    loadTracks()
                }.onFailure { error ->
                    setStatus(error.message ?: "Unable to complete Google sign-in.")
                }
            }
        }
        return true
    }

    private fun signOut() {
        setStatus("Signing out…")
        executor.execute {
            repository.signOut()
            SubtitleCueHandoff.clear()
            runOnUiThread {
                stopService(Intent(this, OverlayService::class.java))
                selectedTrack = null
                multiSub1Track = null
                multiSub2Track = null
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
                    multiSub1Track = multiSub1Track?.let { selected -> tracks.firstOrNull { it.id == selected.id } }
                    multiSub2Track = multiSub2Track?.let { selected -> tracks.firstOrNull { it.id == selected.id } }
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
                    track.label,
                    track.provider,
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
                    if (track.label.isNotBlank() && !track.label.equals("Default", ignoreCase = true)) append(" · ${track.label}")
                    if (track.provider.isNotBlank()) append(" · ${track.provider}")
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
        if (overlayStartInProgress) return

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

        setOverlayLoading(true, "Securely loading subtitle from private storage…")
        setStatus("Loading the private subtitle…")
        executor.execute {
            val result = runCatching {
                val loaded = repository.loadAuthorizedSubtitle(track.id)
                val token = SubtitleCueHandoff.prepare(loaded.cues)
                PreparedSubtitle(token, track.displayLabel, loaded.cues.size)
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    setOverlayLoading(true, "Starting subtitle overlay…")
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START
                        putExtra(OverlayService.EXTRA_CUE_HANDOFF_TOKEN, prepared.token)
                    }
                    runCatching { ContextCompat.startForegroundService(this, intent) }
                        .onSuccess {
                            setOverlayLoading(false)
                            setStatus("")
                            Toast.makeText(
                                this,
                                "${prepared.cueCount} cues ready · ${prepared.label}",
                                Toast.LENGTH_SHORT,
                            ).show()
                            moveTaskToBack(true)
                        }
                        .onFailure { error ->
                            SubtitleCueHandoff.clear(prepared.token)
                            setOverlayLoading(false)
                            setStatus(error.message ?: "Unable to start subtitle overlay.")
                        }
                }.onFailure { error ->
                    setOverlayLoading(false)
                    setStatus(error.message ?: "Unable to load this subtitle.")
                }
            }
        }
    }

    private fun openMultiSubtitleSetup() {
        if (!repository.hasStoredSession()) {
            Toast.makeText(this, "Sign in before setting up Multi Subtitle.", Toast.LENGTH_LONG).show()
            return
        }
        if (allTracks.size < 2) {
            Toast.makeText(this, "At least two authorized subtitles are required.", Toast.LENGTH_LONG).show()
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val sub1 = actionButton(multiTrackButtonLabel("Sub 1", multiSub1Track), secondary = true) {}
        val sub2 = actionButton(multiTrackButtonLabel("Sub 2", multiSub2Track), secondary = true) {}
        sub1.setOnClickListener {
            showMultiTrackPicker("Choose Sub 1", multiSub1Track) { track ->
                multiSub1Track = track
                sub1.text = multiTrackButtonLabel("Sub 1", track)
            }
        }
        sub2.setOnClickListener {
            showMultiTrackPicker("Choose Sub 2", multiSub2Track) { track ->
                multiSub2Track = track
                sub2.text = multiTrackButtonLabel("Sub 2", track)
            }
        }
        content.addView(sub1, matchWrap(bottom = dp(10)))
        content.addView(sub2, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Multi Subtitle")
            .setMessage("Choose two authorized subtitles. Sub 1 and Sub 2 can be moved and resized independently on the video.")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val first = multiSub1Track
                val second = multiSub2Track
                when {
                    first == null || second == null -> Toast.makeText(this, "Choose both Sub 1 and Sub 2.", Toast.LENGTH_SHORT).show()
                    first.id == second.id -> Toast.makeText(this, "Choose two different subtitles.", Toast.LENGTH_SHORT).show()
                    else -> {
                        dialog.dismiss()
                        startMultiSubtitleOverlay(first, second)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showMultiTrackPicker(
        title: String,
        current: AuthorizedSubtitleTrack?,
        onSelected: (AuthorizedSubtitleTrack) -> Unit,
    ) {
        val labels = allTracks.map { it.displayLabel }.toTypedArray()
        val checked = allTracks.indexOfFirst { it.id == current?.id }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                onSelected(allTracks[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun multiTrackButtonLabel(slot: String, track: AuthorizedSubtitleTrack?): String =
        if (track == null) "$slot · Choose subtitle" else "$slot · ${track.displayLabel}"

    private fun startMultiSubtitleOverlay(
        first: AuthorizedSubtitleTrack,
        second: AuthorizedSubtitleTrack,
    ) {
        if (overlayStartInProgress) return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Display over other apps is required before starting subtitles.", Toast.LENGTH_LONG).show()
            openOverlaySettings()
            return
        }
        setOverlayLoading(true, "Loading Sub 1 and Sub 2 from private storage…")
        setStatus("Loading both private subtitles…")
        executor.execute {
            val result = runCatching {
                val loaded1 = repository.loadAuthorizedSubtitle(first.id)
                val loaded2 = repository.loadAuthorizedSubtitle(second.id)
                val token = SubtitleCueHandoff.prepare(loaded1.cues, loaded2.cues)
                PreparedSubtitle(
                    token = token,
                    label = "${first.languageName} + ${second.languageName}",
                    cueCount = loaded1.cues.size + loaded2.cues.size,
                )
            }
            runOnUiThread {
                result.onSuccess { prepared ->
                    setOverlayLoading(true, "Starting Multi Subtitle overlay…")
                    val intent = Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START
                        putExtra(OverlayService.EXTRA_CUE_HANDOFF_TOKEN, prepared.token)
                    }
                    runCatching { ContextCompat.startForegroundService(this, intent) }
                        .onSuccess {
                            setOverlayLoading(false)
                            setStatus("")
                            Toast.makeText(this, "Multi Subtitle ready · ${prepared.label}", Toast.LENGTH_SHORT).show()
                            moveTaskToBack(true)
                        }
                        .onFailure { error ->
                            SubtitleCueHandoff.clear(prepared.token)
                            setOverlayLoading(false)
                            setStatus(error.message ?: "Unable to start Multi Subtitle overlay.")
                        }
                }.onFailure { error ->
                    setOverlayLoading(false)
                    setStatus(error.message ?: "Unable to load both subtitles.")
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
        val allPermissionsGranted = overlayGranted && playbackGranted && notificationsGranted

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
            else -> "✓ Ready · All recommended permissions are enabled."
        }
        permissionSummaryView.text = summary
        permissionSummaryView.setTextColor(if (allPermissionsGranted) COLOR_SUCCESS else COLOR_TEXT)
        permissionSummaryView.background = roundedBackground(
            if (allPermissionsGranted) COLOR_READY_FILL else COLOR_WARNING_FILL,
            dp(10),
            if (allPermissionsGranted) COLOR_READY_BORDER else COLOR_PRIMARY_DARK,
        )

        if (allPermissionsGranted) {
            setPermissionDetailsExpanded(false)
        } else {
            setPermissionDetailsExpanded(true)
        }
    }

    private fun setPermissionDetailsExpanded(expanded: Boolean) {
        if (!::permissionDetailsContainer.isInitialized || !::permissionToggleView.isInitialized) return
        permissionDetailsContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        permissionToggleView.text = if (expanded) "Hide" else "Show"
    }

    private fun setOverlayLoading(loading: Boolean, message: String = "Loading subtitle…") {
        overlayStartInProgress = loading
        if (!::startOverlayButton.isInitialized) return

        startOverlayButton.text = if (loading) "Loading subtitle…" else "Start subtitle overlay"
        startOverlayButton.isClickable = !loading
        startOverlayButton.isFocusable = !loading
        startOverlayButton.alpha = if (loading) 0.65f else 1f

        if (::overlayLoadingRow.isInitialized) {
            overlayLoadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        }
        if (::overlayLoadingLabel.isInitialized) {
            overlayLoadingLabel.text = message
        }
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

    private fun googleAuthButton(action: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = roundedBackground(Color.WHITE, dp(12), 0xFFDADCE0.toInt())
        isClickable = true
        isFocusable = true
        contentDescription = "Continue with Google"

        addView(ImageView(context).apply {
            setImageResource(R.drawable.google_g_logo)
            contentDescription = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginEnd = dp(10)
        })

        addView(TextView(context).apply {
            text = "Continue with Google"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF202124.toInt())
            gravity = Gravity.CENTER_VERTICAL
        })

        setOnClickListener { action() }
    }

    private fun setGoogleSignInBusy(busy: Boolean) {
        if (!::googleSignInButton.isInitialized) return
        googleSignInButton.isEnabled = !busy
        googleSignInButton.isClickable = !busy
        googleSignInButton.alpha = if (busy) 0.65f else 1f
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
        val token: String,
        val label: String,
        val cueCount: Int,
    )

    companion object {
        const val PREFS_NAME = "subtitle_overlay"
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
