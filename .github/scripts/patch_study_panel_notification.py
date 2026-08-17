from pathlib import Path

path = Path("SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/overlay/OverlayService.kt")
text = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    "import com.sun.subtitleoverlay.RestoreControlsActivity\n",
    "",
    "remove RestoreControlsActivity import",
)

replace_once(
    "    private var watchModeView: TextView? = null\n    private var studyModeView: TextView? = null\n",
    "",
    "remove Watch/Study view fields",
)

old_mode_row = '''        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        watchModeView = chip("Watch", widthDp = 58, textSizeSp = 10f, description = "Switch to Watch mode") {
            setStudyMode(false)
        }
        studyModeView = chip("Study", widthDp = 58, textSizeSp = 10f, description = "Switch to Study mode") {
            setStudyMode(true)
        }
        modeRow.addView(watchModeView)
        modeRow.addView(studyModeView)
        modeRow.addView(chip("−", widthDp = 30, textSizeSp = 16f, description = "Decrease study repeat count") {
            changeStudyRepeatCount(-1)
        })
'''
new_mode_row = '''        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeRow.addView(chip("−", widthDp = 30, textSizeSp = 16f, description = "Decrease study repeat count") {
            changeStudyRepeatCount(-1)
        })
'''
replace_once(old_mode_row, new_mode_row, "remove Watch/Study buttons")

replace_once(
    '        panel.addView(collapsibleSection("Study mode", studyControls))\n',
    '''        panel.addView(
            collapsibleSection(
                title = "Study mode",
                content = studyControls,
                initiallyExpanded = studyModeEnabled,
                onExpandedChanged = ::setStudyMode,
            )
        )
''',
    "wire Study section to Study mode",
)

replace_once(
    '''    private fun collapsibleSection(
        title: String,
        content: View,
        initiallyExpanded: Boolean = false,
    ): LinearLayout {
''',
    '''    private fun collapsibleSection(
        title: String,
        content: View,
        initiallyExpanded: Boolean = false,
        onExpandedChanged: ((Boolean) -> Unit)? = null,
    ): LinearLayout {
''',
    "add expansion callback",
)

replace_once(
    '''        sectionHeader.setOnClickListener {
            expanded = !expanded
            renderState()
            section.requestLayout()
            controllerView?.requestLayout()
        }
''',
    '''        sectionHeader.setOnClickListener {
            expanded = !expanded
            renderState()
            onExpandedChanged?.invoke(expanded)
            section.requestLayout()
            controllerView?.requestLayout()
        }
''',
    "invoke expansion callback",
)

replace_once(
    '''    private fun updateStudyModeUi() {
        watchModeView?.apply {
            background = if (!studyModeEnabled) activeModeBackground() else chipBackground()
            setTextColor(if (!studyModeEnabled) COLOR_ACTIVE_MODE_TEXT else Color.WHITE)
        }
        studyModeView?.apply {
            background = if (studyModeEnabled) activeModeBackground() else chipBackground()
            setTextColor(if (studyModeEnabled) COLOR_ACTIVE_MODE_TEXT else Color.WHITE)
        }
        studyRepeatView?.text = "${studyRepeatCount}×"
        updateStudyStatus()
    }
''',
    '''    private fun updateStudyModeUi() {
        studyRepeatView?.text = "${studyRepeatCount}×"
        updateStudyStatus()
    }
''',
    "simplify Study mode UI update",
)

replace_once(
    '                "${selectedStudyCueIndices.size} saved · switch to Study to select clips"\n',
    '                "${selectedStudyCueIndices.size} saved · expand Study mode to select clips"\n',
    "update Study status copy",
)

replace_once(
    "    private fun activeModeBackground() = roundedBackground(COLOR_STUDY_ACCENT, dp(9), COLOR_STUDY_ACCENT)\n\n",
    "",
    "remove active mode background",
)

replace_once(
    '''        .setContentText("Tap Controls to restore the hidden interface.")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
''',
    '''        .setContentText("Tap to restore the hidden control panel.")
        .setOngoing(true)
        .setContentIntent(servicePendingIntent(ACTION_SHOW_CONTROLS, 0))
''',
    "make notification body restore controls",
)

replace_once(
    '''            "Controls",
            restoreControlsPendingIntent(),
''',
    '''            "Controls",
            servicePendingIntent(ACTION_SHOW_CONTROLS, 1),
''',
    "make Controls action restore controls directly",
)

replace_once(
    '''    private fun restoreControlsPendingIntent() = PendingIntent.getActivity(
        this,
        1,
        Intent(this, RestoreControlsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

''',
    "",
    "remove restore Activity pending intent",
)

replace_once(
    "        private const val COLOR_ACTIVE_MODE_TEXT = 0xFF10150D.toInt()\n",
    "",
    "remove unused active mode color",
)

path.write_text(text)
