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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.sun.subtitleoverlay.MainActivity
import com.sun.subtitleoverlay.RestoreControlsActivity
import com.sun.subtitleoverlay.playback.PlaybackNotificationListener
import com.sun.subtitleoverlay.subtitle.SrtParser
import com.sun.subtitleoverlay.subtitle.SubtitleCue

@SuppressLint("SetTextI18n")
class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var mediaSessionManager: MediaSessionManager
    private var subtitleView: TextView? = null
    private var controllerView: View? = null
    private var positionView: TextView? = null
    private var statusView: TextView? = null
    private var playPauseView: TextView? = null
    private var speedView: TextView? = null
    private var studyModeView: TextView? = null
    private var cues: List<SubtitleCue> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private var isPlaying = false
    private var basePositionMs = 0L
    private var startedAtElapsedMs = 0L
    private var offsetMs = 0L
    private var manualPlaybackSpeed = DEFAULT_PLAYBACK_SPEED
    private var studyModeEnabled = false
    private var studyAutoPausedCueIndex = -1
    private var subtitleTextSizeSp = DEFAULT_SUBTITLE_TEXT_SIZE_SP
    private var subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP
    private var lastCueIndex = -1
    private var lastSessionRefreshMs = 0L

    private var activeController: MediaController? = null
    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateControllerState()
        }

        override fun onSessionDestroyed() {
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
        manualPlaybackSpeed = preferences.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED)
            .coerceIn(PLAYBACK_SPEEDS.first(), PLAYBACK_SPEEDS.last())
        studyModeEnabled = preferences.getBoolean(KEY_STUDY_MODE, false)
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

        val uri = intent?.data ?: getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getString(MainActivity.KEY_LAST_SRT_URI, null)
            ?.toUri()
        if (uri == null) {
            Toast.makeText(this, "Choose an SRT file first.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        return runCatching {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("The SRT file cannot be opened.")
            cues = SrtParser.parse(text)
            require(cues.isNotEmpty()) { "No valid subtitle cues were found." }
            showWindows()
            resetPlayback()
            stopSessionMonitoring()
            startSessionMonitoring()
            START_STICKY
        }.getOrElse { error ->
            Toast.makeText(this, "Unable to open SRT: ${error.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopSessionMonitoring()
        subtitleView?.let(::removeWindowSafely)
        controllerView?.let(::removeWindowSafely)
        subtitleView = null
        controllerView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showWindows() {
        subtitleView?.let(::removeWindowSafely)
        controllerView?.let(::removeWindowSafely)

        subtitleView = createSubtitleView().also {
            windowManager.addView(it, subtitleLayoutParams())
        }
        controllerView = createController().also {
            windowManager.addView(it, controllerLayoutParams())
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun createSubtitleView(): TextView = TextView(this).apply {
        textSize = subtitleTextSizeSp
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
        gravity = Gravity.CENTER
        maxWidth = resources.displayMetrics.widthPixels - dp(32)
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = roundedBackground(Color.argb(175, 8, 8, 10), dp(8))
        visibility = View.INVISIBLE
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
        panel.addView(collapsibleSection("Playback", playbackControls))

        val subtitleControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        subtitleControls.addView(chip("−.5", description = "Show subtitles 0.5 seconds earlier") { offsetMs -= 500L })
        subtitleControls.addView(chip("+.5", description = "Show subtitles 0.5 seconds later") { offsetMs += 500L })
        subtitleControls.addView(chip("a", textSizeSp = 12f, description = "Smaller subtitles") {
            changeSubtitleTextSize(-TEXT_SIZE_STEP_SP)
        })
        subtitleControls.addView(chip("A", textSizeSp = 18f, description = "Larger subtitles") {
            changeSubtitleTextSize(TEXT_SIZE_STEP_SP)
        })
        panel.addView(collapsibleSection("Subtitle timing & size", subtitleControls))

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
        panel.addView(collapsibleSection("Subtitle position", positionControls))

        val studyControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        }
        studyModeView = chip(
            if (studyModeEnabled) "Study ON" else "Study OFF",
            widthDp = 72,
            textSizeSp = 10f,
            description = "Toggle study mode",
        ) { toggleStudyMode() }
        studyControls.addView(studyModeView)
        studyControls.addView(chip("◀", textSizeSp = 16f, description = "Previous subtitle") { previousStudyCue() })
        studyControls.addView(chip("↺", textSizeSp = 17f, description = "Replay current subtitle") { replayStudyCue() })
        studyControls.addView(chip("▶", textSizeSp = 16f, description = "Next subtitle") { nextStudyCue() })
        panel.addView(collapsibleSection("Study mode", studyControls))

        makeDraggable(panel)
        return panel
    }

    private fun collapsibleSection(
        title: String,
        content: View,
        initiallyExpanded: Boolean = false,
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
            section.requestLayout()
            controllerView?.requestLayout()
        }

        renderState()
        section.addView(sectionHeader)
        section.addView(content)
        return section
    }

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
        subtitleTextSizeSp = (subtitleTextSizeSp + deltaSp)
            .coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)
        subtitleView?.textSize = subtitleTextSizeSp
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putFloat(KEY_SUBTITLE_TEXT_SIZE, subtitleTextSizeSp)
        }
        updateOverlay()
    }

    private fun changeSubtitlePosition(deltaDp: Int) {
        subtitleBottomMarginDp = (subtitleBottomMarginDp + deltaDp)
            .coerceIn(MIN_SUBTITLE_BOTTOM_MARGIN_DP, MAX_SUBTITLE_BOTTOM_MARGIN_DP)
        applySubtitlePosition()
        saveSubtitlePosition()
    }

    private fun resetSubtitlePosition() {
        subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP
        applySubtitlePosition()
        saveSubtitlePosition()
    }

    private fun applySubtitlePosition() {
        val view = subtitleView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.y = dp(subtitleBottomMarginDp)
        windowManager.updateViewLayout(view, params)
        updateOverlay()
    }

    private fun saveSubtitlePosition() {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putInt(KEY_SUBTITLE_BOTTOM_MARGIN, subtitleBottomMarginDp)
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

    private fun subtitleLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(subtitleBottomMarginDp)
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
        lastCueIndex = -1
        studyAutoPausedCueIndex = -1
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
            studyAutoPausedCueIndex = -1
            activeController?.transportControls?.seekTo((mediaPosition + deltaMs).coerceAtLeast(0L))
            return
        }

        basePositionMs = (manualPositionMs() + deltaMs).coerceAtLeast(0L)
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        lastCueIndex = -1
        studyAutoPausedCueIndex = -1
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

    private fun toggleStudyMode() {
        studyModeEnabled = !studyModeEnabled
        studyAutoPausedCueIndex = -1
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {
            putBoolean(KEY_STUDY_MODE, studyModeEnabled)
        }
        studyModeView?.text = if (studyModeEnabled) "Study ON" else "Study OFF"
        if (studyModeEnabled && usablePlaybackState() == null) {
            Toast.makeText(
                this,
                "Study mode is on. Automatic video pause requires a controllable MediaSession; subtitle navigation still works.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun previousStudyCue() {
        if (cues.isEmpty()) return
        val subtitlePosition = currentPositionMs() - offsetMs
        val active = findCueIndex(subtitlePosition)
        val target = if (active >= 0) {
            (active - 1).coerceAtLeast(0)
        } else {
            cueIndexAtOrBefore(subtitlePosition).coerceAtLeast(0)
        }
        seekToStudyCue(target, playAfterSeek = false)
    }

    private fun replayStudyCue() {
        if (cues.isEmpty()) return
        val subtitlePosition = currentPositionMs() - offsetMs
        val active = findCueIndex(subtitlePosition)
        val target = if (active >= 0) active else cueIndexAtOrBefore(subtitlePosition).coerceAtLeast(0)
        seekToStudyCue(target, playAfterSeek = true)
    }

    private fun nextStudyCue() {
        if (cues.isEmpty()) return
        val subtitlePosition = currentPositionMs() - offsetMs
        val active = findCueIndex(subtitlePosition)
        val target = if (active >= 0) {
            (active + 1).coerceAtMost(cues.lastIndex)
        } else {
            cueIndexAtOrAfter(subtitlePosition).coerceIn(0, cues.lastIndex)
        }
        seekToStudyCue(target, playAfterSeek = false)
    }

    private fun seekToStudyCue(index: Int, playAfterSeek: Boolean) {
        if (index !in cues.indices) return
        val targetPosition = (cues[index].startMs + offsetMs).coerceAtLeast(0L)
        studyAutoPausedCueIndex = -1
        lastCueIndex = -1

        val state = usablePlaybackState()
        if (state != null && state.actions and PlaybackState.ACTION_SEEK_TO != 0L) {
            runCatching {
                activeController?.transportControls?.seekTo(targetPosition)
                if (playAfterSeek) activeController?.transportControls?.play()
                else activeController?.transportControls?.pause()
            }
            return
        }

        basePositionMs = targetPosition
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        isPlaying = playAfterSeek
        updateOverlay()
    }

    private fun cueIndexAtOrBefore(positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var answer = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs <= positionMs) {
                answer = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return answer
    }

    private fun cueIndexAtOrAfter(positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        var answer = cues.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (cues[mid].startMs >= positionMs) {
                answer = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return answer
    }

    private fun maybeAutoPauseForStudy(previousCueIndex: Int, subtitlePosition: Long) {
        if (!studyModeEnabled || previousCueIndex !in cues.indices) return
        if (studyAutoPausedCueIndex == previousCueIndex) return
        if (subtitlePosition < cues[previousCueIndex].endMs) return

        val state = usablePlaybackState()
        if (state != null && isPlaybackMoving(state.state)) {
            studyAutoPausedCueIndex = previousCueIndex
            runCatching { activeController?.transportControls?.pause() }
        } else if (state == null && isPlaying) {
            studyAutoPausedCueIndex = previousCueIndex
            basePositionMs = manualPositionMs()
            isPlaying = false
            startedAtElapsedMs = SystemClock.elapsedRealtime()
        }
    }

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
        val subtitlePosition = rawPosition - offsetMs
        val index = findCueIndex(subtitlePosition)
        val previousCueIndex = lastCueIndex
        if (index != previousCueIndex) {
            maybeAutoPauseForStudy(previousCueIndex, subtitlePosition)
            lastCueIndex = index
            subtitleView?.apply {
                if (index >= 0) {
                    text = cues[index].text
                    visibility = View.VISIBLE
                } else {
                    text = ""
                    visibility = View.INVISIBLE
                }
            }
        }
        positionView?.text = "${formatTime(rawPosition)}  Δ${formatOffset(offsetMs)}"
        updateControllerState()
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
        studyModeView?.text = if (studyModeEnabled) "Study ON" else "Study OFF"
    }

    private fun findCueIndex(positionMs: Long): Int {
        var low = 0
        var high = cues.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs >= cue.endMs -> low = mid + 1
                else -> return mid
            }
        }
        return -1
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
            detachController(preservePosition = true)
            return
        }
        runCatching {
            attachBestController(mediaSessionManager.getActiveSessions(listenerComponent()))
        }.onFailure {
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
        .setContentText("Tap Controls to restore the hidden interface.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(
            android.R.drawable.ic_menu_view,
            "Controls",
            restoreControlsPendingIntent(),
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

    private fun restoreControlsPendingIntent() = PendingIntent.getActivity(
        this,
        1,
        Intent(this, RestoreControlsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
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

    companion object {
        const val ACTION_START = "com.sun.subtitleoverlay.action.START"
        const val ACTION_STOP = "com.sun.subtitleoverlay.action.STOP"
        const val ACTION_SHOW_CONTROLS = "com.sun.subtitleoverlay.action.SHOW_CONTROLS"
        const val ACTION_HIDE_CONTROLS = "com.sun.subtitleoverlay.action.HIDE_CONTROLS"

        private const val CHANNEL_ID = "subtitle_overlay"
        private const val NOTIFICATION_ID = 100
        private const val TICK_MS = 80L
        private const val SESSION_REFRESH_MS = 1_500L
        private const val KEY_SUBTITLE_TEXT_SIZE = "subtitle_text_size_sp"
        private const val KEY_SUBTITLE_BOTTOM_MARGIN = "subtitle_bottom_margin_dp"
        private const val KEY_PLAYBACK_SPEED = "playback_speed"
        private const val KEY_STUDY_MODE = "study_mode_enabled"
        private const val DEFAULT_SUBTITLE_TEXT_SIZE_SP = 22f
        private const val MIN_SUBTITLE_TEXT_SIZE_SP = 14f
        private const val MAX_SUBTITLE_TEXT_SIZE_SP = 36f
        private const val TEXT_SIZE_STEP_SP = 2f
        private const val DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP = 24
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
        private val PLAYBACK_SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        private val SUPPORTED_VIDEO_PACKAGES = setOf(
            NETFLIX_PACKAGE,
            YOUTUBE_PACKAGE,
            "com.google.android.apps.youtube.kids",
            "com.android.chrome",
        )
    }
}
