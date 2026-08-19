from pathlib import Path

path = Path("SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/MainActivity.kt")
text = path.read_text(encoding="utf-8")

if "private lateinit var permissionDetailsContainer" in text:
    print("Compact permissions/loading UI already applied.")
    raise SystemExit(0)


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match, found {count}: {old[:80]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.widget.LinearLayout\nimport android.widget.ScrollView",
    "import android.widget.LinearLayout\nimport android.widget.ProgressBar\nimport android.widget.ScrollView",
)

replace_once(
    "    private lateinit var notificationPermissionButton: TextView\n    private lateinit var searchInput: EditText",
    "    private lateinit var notificationPermissionButton: TextView\n"
    "    private lateinit var permissionDetailsContainer: LinearLayout\n"
    "    private lateinit var permissionToggleView: TextView\n"
    "    private lateinit var searchInput: EditText",
)

replace_once(
    "    private lateinit var selectedTrackLabel: TextView\n    private lateinit var statusView: TextView",
    "    private lateinit var selectedTrackLabel: TextView\n"
    "    private lateinit var startOverlayButton: TextView\n"
    "    private lateinit var overlayLoadingRow: LinearLayout\n"
    "    private lateinit var overlayLoadingLabel: TextView\n"
    "    private lateinit var statusView: TextView",
)

replace_once(
    "    private var selectedTrack: AuthorizedSubtitleTrack? = null\n    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()",
    "    private var selectedTrack: AuthorizedSubtitleTrack? = null\n"
    "    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()\n"
    "    private var overlayStartInProgress = false",
)

permission_start = text.index("                val permissionCard = sectionCard().apply {")
permission_end_marker = "                addView(permissionCard, matchWrap(bottom = dp(12)))"
permission_end = text.index(permission_end_marker, permission_start) + len(permission_end_marker)
permission_block = '''                val permissionCard = sectionCard().apply {
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
                addView(permissionCard, matchWrap(bottom = dp(12)))'''
text = text[:permission_start] + permission_block + text[permission_end:]

replace_once(
    '                    addView(actionButton("Start subtitle overlay") { startSelectedOverlay() }, matchWrap(bottom = dp(9)))\n'
    '                    addView(actionButton("Stop overlay", secondary = true) {',
    '''                    startOverlayButton = actionButton("Start subtitle overlay") { startSelectedOverlay() }
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

                    addView(actionButton("Stop overlay", secondary = true) {''',
)

start_fn = text.index("    private fun startSelectedOverlay() {")
next_fn = text.index("    private fun openRequestPortal() {", start_fn)
new_start_fn = '''    private fun startSelectedOverlay() {
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

'''
text = text[:start_fn] + new_start_fn + text[next_fn:]

update_fn = text.index("    private fun updatePermissionButtons() {")
style_fn = text.index("    private fun stylePermissionButton(", update_fn)
new_update_fn = '''    private fun updatePermissionButtons() {
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

'''
text = text[:update_fn] + new_update_fn + text[style_fn:]

path.write_text(text, encoding="utf-8")
print("Applied compact permission card and overlay loading UI.")
