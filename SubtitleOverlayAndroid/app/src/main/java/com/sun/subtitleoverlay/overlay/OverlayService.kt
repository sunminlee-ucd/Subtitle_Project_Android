package com.sun.subtitleoverlay.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.sun.subtitleoverlay.MainActivity
import com.sun.subtitleoverlay.playback.PlaybackNotificationListener
import com.sun.subtitleoverlay.study.StudyPlaybackCommand
import com.sun.subtitleoverlay.study.StudyPlaybackEngine
import com.sun.subtitleoverlay.study.StudySavedCuesActivity
import com.sun.subtitleoverlay.subtitle.SubtitleCue
import com.sun.subtitleoverlay.subtitle.SubtitleCueHandoff

@SuppressLint("SetTextI18n")
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var mediaSessionManager: MediaSessionManager

    private var subtitleView: TextView? = null
    private var secondarySubtitleView: TextView? = null
    private var controllerView: View? = null
    private var positionView: TextView? = null
    private var statusView: TextView? = null
    private var playPauseView: TextView? = null
    private var speedView: TextView? = null
    private var studyRepeatView: TextView? = null
    private var studyStatusView: TextView? = null

    private var cues: List<SubtitleCue> = emptyList()
    private var secondaryCues: List<SubtitleCue> = emptyList()
    private var activeCueToken = ""
    private var subtitleListId = ""
    private var renderedCueIndices: List<Int> = emptyList()
    private var renderedSecondaryCueIndices: List<Int> = emptyList()
    private val selectedStudyCueIndices = linkedSetOf<Int>()
    private var studyPlaybackEngine: StudyPlaybackEngine? = null

    private val handler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private var basePositionMs = 0L
    private var startedAtElapsedMs = 0L
    private var offsetMs = 0L
    private var manualPlaybackSpeed = DEFAULT_PLAYBACK_SPEED
    private var studyModeEnabled = false
    private var studyRepeatCount = StudyPlaybackEngine.DEFAULT_REPEAT_COUNT
    private var studyStoppedByUser = false
    private var subtitleTextSizeSp = DEFAULT_SUBTITLE_TEXT_SIZE_SP
    private var subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP
    private var subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP
    private var secondarySubtitleTextSizeSp = DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP
    private var secondarySubtitleBottomMarginDp = DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP
    private var secondarySubtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP
    private var lastSessionRefreshMs = 0L

    private var activeController: MediaController? = null
    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateControllerState()
        }

        override fun onSessionDestroyed() {
            stopStudyRepeat(pause = false, userInitiated = false)
            detachController(preservePosition = true)
            refreshActiveController()
        }
    }
    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        attachBestController(controllers.orEmpty())
    }

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSessionRefreshMs >= SESSION_REFRESH_MS) {
                lastSessionRefreshMs = now
                refreshActiveController()
            }
            updateOverlay()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

        val preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        subtitleTextSizeSp = preferences.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE_SP)
        subtitleBottomMarginDp = preferences.getInt(KEY_SUBTITLE_BOTTOM_MARGIN, DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP)
        subtitleHorizontalOffsetDp = preferences.getInt(
            KEY_SUBTITLE_HORIZONTAL_OFFSET,
            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,
        )
        secondarySubtitleTextSizeSp = preferences.getFloat(
            KEY_SECONDARY_SUBTITLE_TEXT_SIZE,
            DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP,
        )
        secondarySubtitleBottomMarginDp = preferences.getInt(
            KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN,
            DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP,
        )
        secondarySubtitleHorizontalOffsetDp = preferences.getInt(
            KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET,
            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,
        )
        manualPlaybackSpeed = preferences.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)
            .coerceIn(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last())
        studyModeEnabled = preferences.getBoolean(KEY_STUDY_MODE, false)
        studyRepeatCount = preferences.getInt(
            KEY_STUDY_REPEAT_COUNT,
            StudyPlaybackEngine.DEFAULT_REPEAT_COUNT,
        ).coerceIn(StudyPlaybackEngine.MIN_REPEAT_COUNT, StudyPlaybackEngine.MAX_REPEAT_COUNT)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_CONTROLS -> {
                controllerView?.visibility = View.VISIBLE
                return START_STICKY
            }
            ACTION_HIDE_CONTROLS -> {
                hideController()
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Display-over-apps permission is required.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        val token = intent?.getStringExtra(EXTRA_CUE_HANDOFF_TOKEN).orEmpty()
        val snapshot = SubtitleCueHandoff.get(token)
        if (snapshot == null) {
            Toast.makeText(
                this,
                "Subtitle data is no longer available in memory. Return to the app and start the overlay again.",
                Toast.LENGTH_LONG,
            ).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return runCatching {
            require(snapshot.cues.isNotEmpty()) { "No valid subtitle cues were found." }

            stopStudyRepeat(pause = false, userInitiated = false)
            cues = snapshot.cues
            secondaryCues = snapshot.secondaryCues
            activeCueToken = token
            subtitleListId = snapshot.listId
            studyPlaybackEngine = StudyPlaybackEngine(cues)
            restoreStudySelection()

            showWindows()
            resetPlayback()
            stopSessionMonitoring()
            startSessionMonitoring()
            START_STICKY
        }.getOrElse { error ->
            SubtitleCueHandoff.clear(token)
            Toast.makeText(this, "Unable to load subtitles: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopStudyRepeat(pause = false, userInitiated = false)
        stopSessionMonitoring()
        subtitleView?.let(::removeWindowSafely)
        secondarySubtitleView?.let(::removeWindowSafely)
        controllerView?.let(::removeWindowSafely)
        subtitleView = null
        secondarySubtitleView = null
        controllerView = null
        if (activeCueToken.isNotBlank()) SubtitleCueHandoff.clear(activeCueToken)
        activeCueToken = ""
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showWindows() {
        subtitleView?.let(::removeWindowSafely)
        secondarySubtitleView?.let(::removeWindowSafely)
        controllerView?.let(::removeWindowSafely)

        subtitleView = createSubtitleView(slot = 1).also {
            windowManager.addView(it, subtitleLayoutParams(slot = 1))
        }
        secondarySubtitleView = secondaryCues.takeIf { it.isNotEmpty() }?.let {
            createSubtitleView(slot = 2).also { view ->
                windowManager.addView(view, subtitleLayoutParams(slot = 2))
            }
        }
        controllerView = createController().also {
            windowManager.addView(it, controllerLayoutParams())
        }
        updateStudyModeUi()
        updateSubtitleTouchability()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun createSubtitleView(slot: Int): TextView = TextView(this).apply {
        textSize = if (slot == 1) subtitleTextSizeSp else secondarySubtitleTextSizeSp
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
        gravity = Gravity.CENTER
        maxWidth = resources.displayMetrics.widthPixels - dp(32)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = normalSubtitleBackground()
        visibility = View.INVISIBLE
        if (slot == 1) setOnClickListener { toggleRenderedStudyCues() }
        installSubtitleGestures(this, slot)
    }

    private fun createController(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(6), dp(7), dp(7))
            background = roundedBackground(Color.argb(232, 20, 20, 24), dp(15), COLOR_PANEL_BORDER)
            elevation = dp(8).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            text = "MANUAL"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MANUAL)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = roundedBackground(Color.argb(190, 45, 44, 51), dp(8))
        }
        positionView = TextView(this).apply {
            text = "00:00  Δ+0.0s"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(dp(7), 0, dp(7), 0)
        }
        header.addView(statusView)
        header.addView(positionView)
        header.addView(chip("—", widthDp = 30, textSizeSp = 16f, description = "Hide controls") { hideController() })
        header.addView(chip("×", widthDp = 30, textSizeSp = 17f, description = "Stop overlay") { stopSelf() })
        panel.addView(header)

        val playbackControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        playbackControls.addView(chip("−5", description = "Back 5 seconds") { seekBy(-5_000L) })
        playPauseView = chip("▶", widthDp = 42, textSizeSp = 17f, description = "Play or pause") { togglePlayback() }
        playbackControls.addView(playPauseView)
        playbackControls.addView(chip("+5", description = "Forward 5 seconds") { seekBy(5_000L) })
        speedView = chip(
            formatPlaybackSpeed(manualPlaybackSpeed),
            widthDp = 48,
            textSizeSp = 11f,
            description = "Change playback speed",
        ) { cyclePlaybackSpeed() }
        playbackControls.addView(speedView)

        val subtitleControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        subtitleControls.addView(chip("−.5", description = "Show subtitles 0.5 seconds earlier") {
            offsetMs -= 500L
            updateOverlay()
        })
        subtitleControls.addView(chip("+.5", description = "Show subtitles 0.5 seconds later") {
            offsetMs += 500L
            updateOverlay()
        })
        subtitleControls.addView(chip("a", textSizeSp = 12f, description = "Smaller subtitles") {
            changeSubtitleTextSize(-TEXT_SIZE_STEP_SP)
        })
        subtitleControls.addView(chip("A", textSizeSp = 18f, description = "Larger subtitles") {
            changeSubtitleTextSize(TEXT_SIZE_STEP_SP)
        })

        val positionControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        positionControls.addView(chip("↓", textSizeSp = 18f, description = "Move subtitles down") {
            changeSubtitlePosition(-POSITION_STEP_DP)
        })
        positionControls.addView(chip("↑", textSizeSp = 18f, description = "Move subtitles up") {
            changeSubtitlePosition(POSITION_STEP_DP)
        })
        positionControls.addView(chip("↺", textSizeSp = 17f, description = "Reset subtitle position") {
            resetSubtitlePosition()
        })

        val studyControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeRow.addView(chip("−", widthDp = 30, textSizeSp = 16f, description = "Decrease study repeat count") {
            changeStudyRepeatCount(-1)
        })
        studyRepeatView = infoChip("${studyRepeatCount}×", widthDp = 42)
        modeRow.addView(studyRepeatView)
        modeRow.addView(chip("+", widthDp = 30, textSizeSp = 16f, description = "Increase study repeat count") {
            changeStudyRepeatCount(1)
        })
        studyControls.addView(modeRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        actionRow.addView(chip("Repeat current", widthDp = 80, textSizeSp = 9f, description = "Repeat the current subtitle") {
            repeatCurrentStudyCue()
        })
        actionRow.addView(chip("Play saved", widthDp = 70, textSizeSp = 9f, description = "Play saved study subtitles") {
            playSelectedStudyCues()
        })
        actionRow.addView(chip("Saved list", widthDp = 60, textSizeSp = 9f, description = "Search saved study subtitles") {
            openStudySavedCues()
        })
        actionRow.addView(chip("Stop", widthDp = 44, textSizeSp = 9f, description = "Stop study repetition") {
            stopStudyRepeat(pause = true, userInitiated = true)
        })
        actionRow.addView(chip("Clear", widthDp = 44, textSizeSp = 9f, description = "Clear saved study subtitles") {
            clearStudySelection()
        })
        studyControls.addView(actionRow)

        studyStatusView = TextView(this).apply {
            textSize = 10f
            setTextColor(COLOR_MUTED_TEXT)
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(5), dp(5), 0)
        }
        studyControls.addView(studyStatusView)
        val subtitlePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(subtitleControls)
            addView(positionControls)
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        val tabContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        lateinit var playbackTab: TextView
        lateinit var subtitleTab: TextView
        lateinit var studyTab: TextView
        fun showTab(tab: String) {
            playbackControls.visibility = if (tab == "playback") View.VISIBLE else View.GONE
            subtitlePage.visibility = if (tab == "subtitle") View.VISIBLE else View.GONE
            studyControls.visibility = if (tab == "study") View.VISIBLE else View.GONE
            playbackTab.background = tabBackground(tab == "playback")
            subtitleTab.background = tabBackground(tab == "subtitle")
            studyTab.background = tabBackground(tab == "study")
            playbackTab.setTextColor(if (tab == "playback") Color.WHITE else COLOR_MUTED_TEXT)
            subtitleTab.setTextColor(if (tab == "subtitle") Color.WHITE else COLOR_MUTED_TEXT)
            studyTab.setTextColor(if (tab == "study") Color.WHITE else COLOR_MUTED_TEXT)
            setStudyMode(tab == "study")
            tabContent.requestLayout()
            controllerView?.requestLayout()
        }
        playbackTab = tabButton("Playback") { showTab("playback") }
        subtitleTab = tabButton("Subtitle") { showTab("subtitle") }
        studyTab = tabButton("Study") { showTab("study") }
        tabRow.addView(playbackTab)
        tabRow.addView(subtitleTab)
        tabRow.addView(studyTab)
        panel.addView(tabRow)
        tabContent.addView(playbackControls)
        tabContent.addView(subtitlePage)
        tabContent.addView(studyControls)
        panel.addView(tabContent)
        showTab(if (studyModeEnabled) "study" else "playback")

        makeDraggable(panel)
        return panel
    }

    private fun collapsibleSection(
        title: String,
        content: View,
        initiallyExpanded: Boolean = false,
        onExpandedChanged: ((Boolean) -> Unit)? = null,
    ): LinearLayout {
        var expanded = initiallyExpanded
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val sectionHeader = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(30)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            background = roundedBackground(Color.argb(210, 42, 41, 48), dp(8), COLOR_BUTTON_BORDER)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            }
        }

        fun renderState() {
            sectionHeader.text = if (expanded) "▾ $title" else "▸ $title"
            sectionHeader.contentDescription = if (expanded) "Collapse $title controls" else "Expand $title controls"
            content.visibility = if (expanded) View.VISIBLE else View.GONE
        }

        sectionHeader.setOnClickListener {
            expanded = !expanded
            renderState()
            onExpandedChanged?.invoke(expanded)
            section.requestLayout()
            controllerView?.requestLayout()
        }

        renderState()
        section.addView(sectionHeader)
        section.addView(content)
        return section
    }

    private fun tabButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(76)
        minHeight = dp(32)
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(COLOR_MUTED_TEXT)
        background = tabBackground(false)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        }
    }

    private fun tabBackground(selected: Boolean) = roundedBackground(
        if (selected) Color.argb(255, 105, 80, 164) else Color.argb(210, 42, 41, 48),
        dp(9),
        if (selected) 0xFF9B7FD1.toInt() else COLOR_BUTTON_BORDER,
    )

    private fun chip(
        label: String,
        widthDp: Int = 36,
        textSizeSp: Float = 12f,
        description: String,
        action: () -> Unit,
    ) = TextView(this).apply {
        text = label
        textSize = textSizeSp
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        contentDescription = description
        minWidth = dp(widthDp)
        minHeight = dp(32)
        setPadding(dp(6), 0, dp(6), 0)
        background = chipBackground()
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        val margin = dp(1)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = margin
            marginEnd = margin
        }
    }

    private fun infoChip(label: String, widthDp: Int) = TextView(this).apply {
        text = label
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minWidth = dp(widthDp)
        minHeight = dp(32)
        setPadding(dp(6), 0, dp(6), 0)
        background = roundedBackground(Color.argb(255, 52, 51, 59), dp(9), COLOR_BUTTON_BORDER)
        val margin = dp(1)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = margin
            marginEnd = margin
        }
    }

    private fun chipBackground() = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedBackground(Color.argb(255, 105, 80, 164), dp(9), COLOR_BUTTON_BORDER),
        )
        addState(
            intArrayOf(),
            roundedBackground(Color.argb(255, 52, 51, 59), dp(9), COLOR_BUTTON_BORDER),
        )
    }

    private fun hideController() {
        controllerView?.visibility = View.GONE
        Toast.makeText(this, "Open the notification and tap Controls to show the interface again.", Toast.LENGTH_LONG).show()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun changeSubtitleTextSize(deltaSp: Float) {
        setSubtitleTextSize(subtitleTextSizeSp + deltaSp, persist = true)
    }

    private fun setSubtitleTextSize(sizeSp: Float, persist: Boolean) {
        subtitleTextSizeSp = sizeSp.coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)
        subtitleView?.textSize = subtitleTextSizeSp
        if (persist) saveSubtitleTextSize()
        updateOverlay()
    }

    private fun saveSubtitleTextSize() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(KEY_SUBTITLE_TEXT_SIZE, subtitleTextSizeSp)
        }
    }

    private fun changeSubtitlePosition(deltaDp: Int) {
        subtitleBottomMarginDp = (subtitleBottomMarginDp + deltaDp)
            .coerceIn(MIN_SUBTITLE_BOTTOM_MARGIN_DP, MAX_SUBTITLE_BOTTOM_MARGIN_DP)
        applySubtitlePosition()
        saveSubtitlePosition()
    }

    private fun resetSubtitlePosition() {
        subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP
        subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP
        applySubtitlePosition()
        saveSubtitlePosition()
    }

    private fun applySubtitlePosition() {
        val view = subtitleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = dp(subtitleHorizontalOffsetDp)
        params.y = dp(subtitleBottomMarginDp)
        windowManager.updateViewLayout(view, params)
        updateOverlay()
    }

    private fun saveSubtitlePosition() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(KEY_SUBTITLE_BOTTOM_MARGIN, subtitleBottomMarginDp)
            putInt(KEY_SUBTITLE_HORIZONTAL_OFFSET, subtitleHorizontalOffsetDp)
        }
    }

    private fun installSubtitleGestures(view: TextView, slot: Int) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragLocked = false
        var scaleLocked = false
        var tapCandidate = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaleLocked = true
                    tapCandidate = false
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (slot == 1) {
                        setSubtitleTextSize(subtitleTextSizeSp * detector.scaleFactor, persist = false)
                    } else {
                        secondarySubtitleTextSizeSp = (secondarySubtitleTextSizeSp * detector.scaleFactor)
                            .coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)
                        secondarySubtitleView?.textSize = secondarySubtitleTextSizeSp
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (slot == 1) saveSubtitleTextSize() else saveSecondarySubtitleState()
                }
            },
        )

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            val params = view.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragLocked = false
                    scaleLocked = false
                    tapCandidate = studyModeEnabled && slot == 1
                    true
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    scaleLocked = true
                    tapCandidate = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (scaleLocked || scaleDetector.isInProgress || event.pointerCount > 1) {
                        scaleLocked = true
                        tapCandidate = false
                        true
                    } else {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (!dragLocked && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                            dragLocked = true
                            tapCandidate = false
                        }
                        if (dragLocked) {
                            params.x = initialX + dx
                            params.y = initialY - dy
                            windowManager.updateViewLayout(view, params)
                        }
                        true
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // Once a multi-touch gesture begins, this gesture can never become a Study-mode tap.
                    scaleLocked = true
                    tapCandidate = false
                    true
                }

                MotionEvent.ACTION_UP -> {
                    when {
                        scaleLocked -> Unit
                        dragLocked -> {
                            if (slot == 1) {
                                subtitleHorizontalOffsetDp = pxToDp(params.x)
                                subtitleBottomMarginDp = pxToDp(params.y)
                                saveSubtitlePosition()
                            } else {
                                secondarySubtitleHorizontalOffsetDp = pxToDp(params.x)
                                secondarySubtitleBottomMarginDp = pxToDp(params.y)
                                saveSecondarySubtitleState()
                            }
                        }
                        tapCandidate -> view.performClick()
                    }
                    tapCandidate = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    tapCandidate = false
                    true
                }

                else -> true
            }
        }
    }

    private fun saveSecondarySubtitleState() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(KEY_SECONDARY_SUBTITLE_TEXT_SIZE, secondarySubtitleTextSizeSp)
            putInt(KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN, secondarySubtitleBottomMarginDp)
            putInt(KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET, secondarySubtitleHorizontalOffsetDp)
        }
    }

    private fun makeDraggable(view: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false

        view.setOnTouchListener { _, event ->
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) {
                        dragging = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        windowManager.updateViewLayout(view, params)
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun subtitleLayoutParams(slot: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        x = dp(if (slot == 1) subtitleHorizontalOffsetDp else secondarySubtitleHorizontalOffsetDp)
        y = dp(if (slot == 1) subtitleBottomMarginDp else secondarySubtitleBottomMarginDp)
    }

    private fun controllerLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(36)
    }

    private fun resetPlayback() {
        isPlaying = false
        basePositionMs = 0L
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        offsetMs = 0L
        renderedCueIndices = emptyList()
        renderedSecondaryCueIndices = emptyList()
        studyPlaybackEngine?.stop()
        studyStoppedByUser = false
        updateOverlay()
    }

    private fun togglePlayback() {
        val state = usablePlaybackState()
        if (state != null) {
            runCatching {
                if (isPlaybackMoving(state.state)) {
                    activeController?.transportControls?.pause()
                } else {
                    activeController?.transportControls?.play()
                }
            }
            return
        }

        if (isPlaying) {
            basePositionMs = manualPositionMs()
            isPlaying = false
        } else {
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            isPlaying = true
        }
        updateControllerState()
    }

    private fun seekBy(deltaMs: Long) {
        val mediaPosition = mediaPositionMs()
        val state = usablePlaybackState()
        if (mediaPosition != null && state != null && state.actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            activeController?.transportControls?.seekTo((mediaPosition + deltaMs).coerceAtLeast(0L))
            return
        }

        basePositionMs = (manualPositionMs() + deltaMs).coerceAtLeast(0L)
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        renderedCueIndices = emptyList()
        updateOverlay()
    }

    private fun cyclePlaybackSpeed() {
        val state = usablePlaybackState()
        val currentSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: manualPlaybackSpeed
        val nextSpeed = nextPlaybackSpeed(currentSpeed)

        if (state != null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                state.actions and PlaybackState.ACTION_SET_PLAYBACK_SPEED == 0L
            ) {
                Toast.makeText(
                    this,
                    "This video app does not expose playback-speed control to Subtitle Overlay.",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            runCatching {
                activeController?.transportControls?.setPlaybackSpeed(nextSpeed)
            }.onSuccess {
                manualPlaybackSpeed = nextSpeed
                savePlaybackSpeed()
                speedView?.text = formatPlaybackSpeed(nextSpeed)
            }.onFailure {
                Toast.makeText(this, "Unable to change playback speed.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        basePositionMs = manualPositionMs()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        manualPlaybackSpeed = nextSpeed
        savePlaybackSpeed()
        speedView?.text = formatPlaybackSpeed(nextSpeed)
        updateOverlay()
    }

    private fun nextPlaybackSpeed(current: Float): Float {
        val currentIndex = PLAYBACK_SPEEDS.indices.minByOrNull { index ->
            kotlin.math.abs(PLAYBACK_SPEEDS[index] - current)
        } ?: 0
        return PLAYBACK_SPEEDS[(currentIndex + 1) % PLAYBACK_SPEEDS.size]
    }

    private fun savePlaybackSpeed() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(KEY_PLAYBACK_SPEED, manualPlaybackSpeed)
        }
    }

    private fun setStudyMode(enabled: Boolean) {
        if (studyModeEnabled == enabled) return
        if (!enabled) {
            stopStudyRepeat(pause = false, userInitiated = false)
        }
        studyModeEnabled = enabled
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_STUDY_MODE, studyModeEnabled)
        }
        updateStudyModeUi()
        updateSubtitleTouchability()
        updateSubtitleAppearance()
    }

    private fun changeStudyRepeatCount(delta: Int) {
        studyRepeatCount = (studyRepeatCount + delta).coerceIn(
            StudyPlaybackEngine.MIN_REPEAT_COUNT,
            StudyPlaybackEngine.MAX_REPEAT_COUNT,
        )
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(KEY_STUDY_REPEAT_COUNT, studyRepeatCount)
        }
        studyRepeatView?.text = "${studyRepeatCount}×"
        studyStoppedByUser = false
        updateStudyStatus()
    }

    private fun toggleRenderedStudyCues() {
        if (!studyModeEnabled || renderedCueIndices.isEmpty() || subtitleListId.isBlank()) return

        val shouldRemove = renderedCueIndices.all(selectedStudyCueIndices::contains)
        for (index in renderedCueIndices) {
            if (shouldRemove) selectedStudyCueIndices.remove(index)
            else selectedStudyCueIndices.add(index)
        }
        saveStudySelection()
        studyStoppedByUser = false
        updateSubtitleAppearance()
        updateStudyStatus()
    }

    private fun clearStudySelection() {
        if (selectedStudyCueIndices.isEmpty()) return
        selectedStudyCueIndices.clear()
        saveStudySelection()
        studyStoppedByUser = false
        updateSubtitleAppearance()
        updateStudyStatus()
    }

    private fun repeatCurrentStudyCue() {
        if (!studyModeEnabled) {
            Toast.makeText(this, "Switch to Study mode first.", Toast.LENGTH_SHORT).show()
            return
        }
        val cueIndex = renderedCueIndices.firstOrNull()
        if (cueIndex == null) {
            Toast.makeText(this, "Wait until a subtitle is visible, then try again.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasControllableStudyPlayback()) return

        val command = runCatching {
            studyPlaybackEngine?.startCue(cueIndex, studyRepeatCount, offsetMs)
                ?: error("Study playback is unavailable.")
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: "Unable to start repetition.", Toast.LENGTH_LONG).show()
            return
        }
        studyStoppedByUser = false
        executeStudyPlaybackCommand(command)
        updateStudyStatus()
    }

    private fun playSelectedStudyCues() {
        if (!studyModeEnabled) {
            Toast.makeText(this, "Switch to Study mode first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedStudyCueIndices.isEmpty()) {
            Toast.makeText(this, "Tap subtitles on the video to save them first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!hasControllableStudyPlayback()) return

        val command = runCatching {
            studyPlaybackEngine?.startPlaylist(selectedStudyCueIndices, studyRepeatCount, offsetMs)
                ?: error("Study playback is unavailable.")
        }.getOrElse { error ->
            Toast.makeText(this, error.message ?: "Unable to start the study playlist.", Toast.LENGTH_LONG).show()
            return
        }
        studyStoppedByUser = false
        executeStudyPlaybackCommand(command)
        updateStudyStatus()
    }

    private fun openStudySavedCues() {
        startActivity(
            Intent(this, StudySavedCuesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun hasControllableStudyPlayback(): Boolean {
        val state = usablePlaybackState()
        if (state == null || state.actions and PlaybackState.ACTION_SEEK_TO == 0L) {
            Toast.makeText(
                this,
                "This video app does not expose seek control to Android, so Subtitle Overlay cannot repeat video clips accurately. Saving study subtitles still works.",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        return true
    }

    private fun stopStudyRepeat(pause: Boolean, userInitiated: Boolean) {
        val engine = studyPlaybackEngine ?: return
        val wasActive = engine.status().active
        engine.stop()
        if (pause && wasActive) {
            runCatching { activeController?.transportControls?.pause() }
        }
        if (userInitiated && wasActive) {
            studyStoppedByUser = true
        }
        updateStudyStatus()
    }

    private fun updateStudyPlayback(positionMs: Long) {
        val command = studyPlaybackEngine?.onPosition(positionMs, offsetMs) ?: return
        executeStudyPlaybackCommand(command)
        updateStudyStatus()
    }

    private fun executeStudyPlaybackCommand(command: StudyPlaybackCommand) {
        when (command) {
            is StudyPlaybackCommand.SeekAndPlay -> runCatching {
                activeController?.transportControls?.seekTo(command.positionMs)
                activeController?.transportControls?.play()
            }.onFailure {
                stopStudyRepeat(pause = false, userInitiated = false)
            }
            is StudyPlaybackCommand.PauseAndSeek -> runCatching {
                activeController?.transportControls?.pause()
                activeController?.transportControls?.seekTo(command.positionMs)
            }.onFailure {
                stopStudyRepeat(pause = false, userInitiated = false)
            }
            StudyPlaybackCommand.ContinuePlaying -> runCatching {
                activeController?.transportControls?.play()
            }
        }
    }

    private fun updateStudyModeUi() {
        studyRepeatView?.text = "${studyRepeatCount}×"
        updateStudyStatus()
    }

    private fun updateStudyStatus() {
        val status = studyPlaybackEngine?.status()
        studyStatusView?.text = when {
            status?.active == true && status.playlistTotal > 0 -> {
                "Clip ${status.playlistPosition + 1}/${status.playlistTotal} · repeat ${status.completed + 1}/${status.total} · ${selectedStudyCueIndices.size} saved"
            }
            status?.active == true -> {
                "Repeating ${status.completed + 1}/${status.total} · ${selectedStudyCueIndices.size} saved"
            }
            status?.playlistComplete == true -> {
                "Selected clips completed · ${selectedStudyCueIndices.size} saved"
            }
            studyStoppedByUser -> {
                "Repetition stopped · ${selectedStudyCueIndices.size} saved"
            }
            studyModeEnabled -> {
                "${selectedStudyCueIndices.size} saved · tap a subtitle to save/remove"
            }
            else -> {
                "${selectedStudyCueIndices.size} saved · expand Study mode to select clips"
            }
        }
    }

    private fun updateSubtitleTouchability() {
        val view = subtitleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        view.isClickable = true
        view.contentDescription = if (studyModeEnabled) {
            "Drag subtitle to move it. Pinch to resize it. Tap to save or remove it from the study list."
        } else {
            "Drag subtitle to move it. Pinch with two fingers to resize it."
        }
        windowManager.updateViewLayout(view, params)
    }

    private fun updateSubtitleAppearance() {
        val view = subtitleView ?: return
        val studyVisible = studyModeEnabled && renderedCueIndices.isNotEmpty()
        val selected = studyVisible && renderedCueIndices.all(selectedStudyCueIndices::contains)
        view.background = when {
            selected -> roundedBackground(COLOR_STUDY_SELECTED_FILL, dp(8), COLOR_STUDY_ACCENT)
            studyVisible -> roundedBackground(Color.argb(175, 8, 8, 10), dp(8), COLOR_STUDY_BORDER)
            else -> normalSubtitleBackground()
        }
    }

    private fun normalSubtitleBackground() = roundedBackground(Color.argb(175, 8, 8, 10), dp(8))

    private fun restoreStudySelection() {
        selectedStudyCueIndices.clear()
        if (subtitleListId.isBlank()) return
        val stored = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getString(studySelectionKey(subtitleListId), "")
            .orEmpty()
        stored.split(',')
            .mapNotNull(String::toIntOrNull)
            .filterTo(selectedStudyCueIndices) { it in cues.indices }
    }

    private fun saveStudySelection() {
        if (subtitleListId.isBlank()) return
        val preferences = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        preferences.edit {
            putString(
                studySelectionKey(subtitleListId),
                selectedStudyCueIndices.sorted().joinToString(","),
            )
            putLong(studySelectionUpdatedKey(subtitleListId), System.currentTimeMillis())
        }
        pruneStudySelections(preferences)
    }

    private fun pruneStudySelections(preferences: android.content.SharedPreferences) {
        val ids = preferences.all.keys
            .filter { it.startsWith(KEY_STUDY_SELECTION_PREFIX) }
            .map { it.removePrefix(KEY_STUDY_SELECTION_PREFIX) }
        if (ids.size <= MAX_STORED_STUDY_SELECTIONS) return

        val removeIds = ids
            .sortedByDescending { id -> preferences.getLong(studySelectionUpdatedKey(id), 0L) }
            .drop(MAX_STORED_STUDY_SELECTIONS)
        preferences.edit {
            for (id in removeIds) {
                remove(studySelectionKey(id))
                remove(studySelectionUpdatedKey(id))
            }
        }
    }

    private fun studySelectionKey(id: String) = "$KEY_STUDY_SELECTION_PREFIX$id"

    private fun studySelectionUpdatedKey(id: String) = "$KEY_STUDY_SELECTION_UPDATED_PREFIX$id"

    private fun manualPositionMs(): Long {
        if (!isPlaying) return basePositionMs
        val elapsed = SystemClock.elapsedRealtime() - startedAtElapsedMs
        return basePositionMs + (elapsed * manualPlaybackSpeed).toLong()
    }

    private fun currentPositionMs(): Long = mediaPositionMs() ?: manualPositionMs()

    private fun mediaPositionMs(): Long? {
        val state = usablePlaybackState() ?: return null
        var position = state.position
        if (isPlaybackMoving(state.state) && state.lastPositionUpdateTime > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
            position += (elapsed * state.playbackSpeed).toLong()
        }
        return position.coerceAtLeast(0L)
    }

    private fun usablePlaybackState(): PlaybackState? {
        val state = activeController?.playbackState ?: return null
        return state.takeIf { it.position >= 0L }
    }

    private fun updateOverlay() {
        subtitleView?.maxWidth = resources.displayMetrics.widthPixels - dp(32)
        val rawPosition = currentPositionMs()
        if (usablePlaybackState() != null) {
            updateStudyPlayback(rawPosition)
        }

        val subtitlePosition = rawPosition - offsetMs
        val nextRenderedCueIndices = activeCueIndices(subtitlePosition)
        if (nextRenderedCueIndices != renderedCueIndices) {
            renderedCueIndices = nextRenderedCueIndices
            subtitleView?.apply {
                if (renderedCueIndices.isNotEmpty()) {
                    text = renderedCueIndices.joinToString("\n") { index -> cues[index].text }
                    visibility = View.VISIBLE
                } else {
                    text = ""
                    visibility = View.INVISIBLE
                }
            }
            updateSubtitleAppearance()
        }

        if (secondaryCues.isNotEmpty()) {
            val nextSecondary = activeCueIndices(secondaryCues, subtitlePosition)
            if (nextSecondary != renderedSecondaryCueIndices) {
                renderedSecondaryCueIndices = nextSecondary
                secondarySubtitleView?.apply {
                    if (renderedSecondaryCueIndices.isNotEmpty()) {
                        text = renderedSecondaryCueIndices.joinToString("\n") { index -> secondaryCues[index].text }
                        visibility = View.VISIBLE
                    } else {
                        text = ""
                        visibility = View.INVISIBLE
                    }
                }
            }
        }

        positionView?.text = "${formatTime(rawPosition)}  Δ${formatOffset(offsetMs)}"
        updateControllerState()
        updateStudyStatus()
    }

    private fun activeCueIndices(positionMs: Long): List<Int> = activeCueIndices(cues, positionMs)

    private fun activeCueIndices(source: List<SubtitleCue>, positionMs: Long): List<Int> = source.indices.filter { index ->
        val cue = source[index]
        cue.startMs <= positionMs && positionMs < cue.endMs
    }

    private fun updateControllerState() {
        val state = usablePlaybackState()
        val packageName = activeController?.packageName
        if (state != null && packageName != null) {
            statusView?.apply {
                text = when (packageName) {
                    NETFLIX_PACKAGE -> "AUTO · N"
                    YOUTUBE_PACKAGE -> "AUTO · Y"
                    else -> "AUTO"
                }
                setTextColor(COLOR_AUTO)
            }
            playPauseView?.text = if (isPlaybackMoving(state.state)) "Ⅱ" else "▶"
            val activeSpeed = state.playbackSpeed.takeIf { it > 0f } ?: manualPlaybackSpeed
            speedView?.text = formatPlaybackSpeed(activeSpeed)
        } else {
            statusView?.apply {
                text = if (hasNotificationAccess()) "WAITING" else "MANUAL"
                setTextColor(COLOR_MANUAL)
            }
            playPauseView?.text = if (isPlaying) "Ⅱ" else "▶"
            speedView?.text = formatPlaybackSpeed(manualPlaybackSpeed)
        }
    }

    private fun startSessionMonitoring() {
        if (!hasNotificationAccess()) {
            updateControllerState()
            return
        }
        runCatching {
            mediaSessionManager.addOnActiveSessionsChangedListener(
                activeSessionsListener,
                listenerComponent(),
                handler,
            )
            refreshActiveController()
        }.onFailure {
            detachController(preservePosition = false)
        }
    }

    private fun stopSessionMonitoring() {
        runCatching { mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener) }
        detachController(preservePosition = false)
    }

    private fun refreshActiveController() {
        if (!hasNotificationAccess()) {
            stopStudyRepeat(pause = false, userInitiated = false)
            detachController(preservePosition = true)
            return
        }
        runCatching {
            attachBestController(mediaSessionManager.getActiveSessions(listenerComponent()))
        }.onFailure {
            stopStudyRepeat(pause = false, userInitiated = false)
            detachController(preservePosition = true)
        }
    }

    private fun attachBestController(controllers: List<MediaController>) {
        val candidate = controllers
            .filter { it.packageName in SUPPORTED_VIDEO_PACKAGES }
            .minByOrNull { controller ->
                when {
                    controller.packageName == NETFLIX_PACKAGE -> 0
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING -> 1
                    else -> 2
                }
            }

        if (candidate?.sessionToken == activeController?.sessionToken) return
        if (studyPlaybackEngine?.status()?.active == true) {
            stopStudyRepeat(pause = false, userInitiated = false)
        }
        detachController(preservePosition = true)
        activeController = candidate
        candidate?.registerCallback(mediaControllerCallback, handler)
        updateControllerState()
    }

    private fun detachController(preservePosition: Boolean) {
        if (preservePosition) {
            val state = activeController?.playbackState
            mediaPositionMs()?.let { position ->
                basePositionMs = position
                startedAtElapsedMs = SystemClock.elapsedRealtime()
                isPlaying = state?.let { isPlaybackMoving(it.state) } == true
                state?.playbackSpeed?.takeIf { it > 0f }?.let { speed ->
                    manualPlaybackSpeed = speed.coerceIn(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last())
                }
            }
        }
        activeController?.unregisterCallback(mediaControllerCallback)
        activeController = null
        updateControllerState()
    }

    private fun hasNotificationAccess(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun listenerComponent() = ComponentName(this, PlaybackNotificationListener::class.java)

    private fun isPlaybackMoving(state: Int): Boolean = state == PlaybackState.STATE_PLAYING ||
        state == PlaybackState.STATE_FAST_FORWARDING ||
        state == PlaybackState.STATE_REWINDING

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun formatOffset(ms: Long): String = "%+.1fs".format(ms / 1_000.0)

    private fun formatPlaybackSpeed(speed: Float): String = when {
        kotlin.math.abs(speed - speed.toInt()) < 0.01f -> "${speed.toInt()}×"
        else -> "%.2g×".format(speed)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("Subtitle overlay is running")
        .setContentText("Tap to restore the hidden control panel.")
        .setOngoing(true)
        .setContentIntent(servicePendingIntent(ACTION_SHOW_CONTROLS, 0))
        .addAction(
            android.R.drawable.ic_menu_view,
            "Controls",
            servicePendingIntent(ACTION_SHOW_CONTROLS, 1),
        )
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            servicePendingIntent(ACTION_STOP, 2),
        )
        .build()

    private fun servicePendingIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, OverlayService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Subtitle overlay", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun roundedBackground(fill: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun removeWindowSafely(view: View) {
        runCatching { windowManager.removeView(view) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun pxToDp(value: Int): Int =
        (value / resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.sun.subtitleoverlay.action.START"
        const val ACTION_STOP = "com.sun.subtitleoverlay.action.STOP"
        const val ACTION_SHOW_CONTROLS = "com.sun.subtitleoverlay.action.SHOW_CONTROLS"
        const val ACTION_HIDE_CONTROLS = "com.sun.subtitleoverlay.action.HIDE_CONTROLS"
        const val EXTRA_CUE_HANDOFF_TOKEN = "cue_handoff_token"

        private const val CHANNEL_ID = "subtitle_overlay"
        private const val NOTIFICATION_ID = 100
        private const val TICK_MS = 80L
        private const val SESSION_REFRESH_MS = 1_500L
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size_sp"
        private const val KEY_SUBTITLE_BOTTOM_MARGIN = "subtitle_bottom_margin_dp"
        private const val KEY_SUBTITLE_HORIZONTAL_OFFSET = "subtitle_horizontal_offset_dp"
        private const val KEY_SECONDARY_SUBTITLE_TEXT_SIZE = "secondary_subtitle_text_size_sp"
        private const val KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN = "secondary_subtitle_bottom_margin_dp"
        private const val KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET = "secondary_subtitle_horizontal_offset_dp"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_STUDY_MODE = "study_mode_enabled"
        private const val KEY_STUDY_REPEAT_COUNT = "study_repeat_count"
        private const val KEY_STUDY_SELECTION_PREFIX = "study_selection_indices_"
        private const val KEY_STUDY_SELECTION_UPDATED_PREFIX = "study_selection_updated_"
        private const val MAX_STORED_STUDY_SELECTIONS = 20
        private const val DEFAULT_SUBTITLE_TEXT_SIZE_SP = 22f
        private const val MIN_SUBTITLE_TEXT_SIZE_SP = 14f
        private const val MAX_SUBTITLE_TEXT_SIZE_SP = 36f
        private const val TEXT_SIZE_STEP_SP = 2f
        private const val DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP = 24
        private const val DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP = 0
        private const val DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP = 20f
        private const val DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP = 86
        private const val MIN_SUBTITLE_BOTTOM_MARGIN_DP = 0
        private const val MAX_SUBTITLE_BOTTOM_MARGIN_DP = 240
        private const val POSITION_STEP_DP = 12
        private const val DEFAULT_PLAYBACK_SPEED = 1f
        private const val NETFLIX_PACKAGE = "com.netflix.mediaclient"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val COLOR_AUTO = 0xFF78E08F.toInt()
        private const val COLOR_MANUAL = 0xFFFFCC80.toInt()
        private const val COLOR_PANEL_BORDER = 0xFF44424D.toInt()
        private const val COLOR_BUTTON_BORDER = 0xFF686571.toInt()
        private const val COLOR_MUTED_TEXT = 0xFFB8C3CC.toInt()
        private const val COLOR_STUDY_ACCENT = 0xFFB9FF5A.toInt()
        private const val COLOR_STUDY_BORDER = 0xFF78A63A.toInt()
        private const val COLOR_STUDY_SELECTED_FILL = 0xD2142D0A.toInt()
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val SUPPORTED_VIDEO_PACKAGES = setOf(
            NETFLIX_PACKAGE,
            YOUTUBE_PACKAGE,
            "com.google.android.apps.youtube.kids",
            "com.android.chrome",
        )
    }
}
