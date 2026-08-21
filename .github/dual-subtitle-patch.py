from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/MainActivity.kt"
OVERLAY = ROOT / "SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/overlay/OverlayService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing patch anchor: {label}")
    return text.replace(old, new, 1)

main = MAIN.read_text(encoding="utf-8")
main = replace_once(main, "import android.Manifest\n", "import android.Manifest\nimport android.app.AlertDialog\n", "AlertDialog import")
main = replace_once(
    main,
    "    private var selectedTrack: AuthorizedSubtitleTrack? = null\n    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()\n",
    "    private var selectedTrack: AuthorizedSubtitleTrack? = null\n    private var multiSub1Track: AuthorizedSubtitleTrack? = null\n    private var multiSub2Track: AuthorizedSubtitleTrack? = null\n    private var allTracks: List<AuthorizedSubtitleTrack> = emptyList()\n",
    "multi track fields",
)
main = replace_once(
    main,
    "                selectedTrack = null\n                allTracks = emptyList()\n",
    "                selectedTrack = null\n                multiSub1Track = null\n                multiSub2Track = null\n                allTracks = emptyList()\n",
    "signout multi reset",
)
main = replace_once(
    main,
    "                    selectedTrack = selectedTrack?.let { selected ->\n                        tracks.firstOrNull { it.id == selected.id }\n                    }\n",
    "                    selectedTrack = selectedTrack?.let { selected ->\n                        tracks.firstOrNull { it.id == selected.id }\n                    }\n                    multiSub1Track = multiSub1Track?.let { selected -> tracks.firstOrNull { it.id == selected.id } }\n                    multiSub2Track = multiSub2Track?.let { selected -> tracks.firstOrNull { it.id == selected.id } }\n",
    "refresh multi selections",
)
old_multi = '''    private fun openMultiSubtitleSetup() {\n        Toast.makeText(\n            this,\n            "Multi Subtitle setup will be available here. Next step: choose two authorized subtitle languages.",\n            Toast.LENGTH_LONG,\n        ).show()\n    }\n'''
new_multi = '''    private fun openMultiSubtitleSetup() {\n        if (!repository.hasStoredSession()) {\n            Toast.makeText(this, "Sign in before setting up Multi Subtitle.", Toast.LENGTH_LONG).show()\n            return\n        }\n        if (allTracks.size < 2) {\n            Toast.makeText(this, "At least two authorized subtitles are required.", Toast.LENGTH_LONG).show()\n            return\n        }\n\n        val content = LinearLayout(this).apply {\n            orientation = LinearLayout.VERTICAL\n            setPadding(dp(20), dp(8), dp(20), 0)\n        }\n        val sub1 = actionButton(multiTrackButtonLabel("Sub 1", multiSub1Track), secondary = true) {}\n        val sub2 = actionButton(multiTrackButtonLabel("Sub 2", multiSub2Track), secondary = true) {}\n        sub1.setOnClickListener {\n            showMultiTrackPicker("Choose Sub 1", multiSub1Track) { track ->\n                multiSub1Track = track\n                sub1.text = multiTrackButtonLabel("Sub 1", track)\n            }\n        }\n        sub2.setOnClickListener {\n            showMultiTrackPicker("Choose Sub 2", multiSub2Track) { track ->\n                multiSub2Track = track\n                sub2.text = multiTrackButtonLabel("Sub 2", track)\n            }\n        }\n        content.addView(sub1, matchWrap(bottom = dp(10)))\n        content.addView(sub2, matchWrap())\n\n        val dialog = AlertDialog.Builder(this)\n            .setTitle("Multi Subtitle")\n            .setMessage("Choose two authorized subtitles. Sub 1 and Sub 2 can be moved and resized independently on the video.")\n            .setView(content)\n            .setNegativeButton("Cancel", null)\n            .setPositiveButton("Start", null)\n            .create()\n        dialog.setOnShowListener {\n            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {\n                val first = multiSub1Track\n                val second = multiSub2Track\n                when {\n                    first == null || second == null -> Toast.makeText(this, "Choose both Sub 1 and Sub 2.", Toast.LENGTH_SHORT).show()\n                    first.id == second.id -> Toast.makeText(this, "Choose two different subtitles.", Toast.LENGTH_SHORT).show()\n                    else -> {\n                        dialog.dismiss()\n                        startMultiSubtitleOverlay(first, second)\n                    }\n                }\n            }\n        }\n        dialog.show()\n    }\n\n    private fun showMultiTrackPicker(\n        title: String,\n        current: AuthorizedSubtitleTrack?,\n        onSelected: (AuthorizedSubtitleTrack) -> Unit,\n    ) {\n        val labels = allTracks.map { it.displayLabel }.toTypedArray()\n        val checked = allTracks.indexOfFirst { it.id == current?.id }\n        AlertDialog.Builder(this)\n            .setTitle(title)\n            .setSingleChoiceItems(labels, checked) { dialog, which ->\n                onSelected(allTracks[which])\n                dialog.dismiss()\n            }\n            .setNegativeButton("Cancel", null)\n            .show()\n    }\n\n    private fun multiTrackButtonLabel(slot: String, track: AuthorizedSubtitleTrack?): String =\n        if (track == null) "$slot · Choose subtitle" else "$slot · ${track.displayLabel}"\n\n    private fun startMultiSubtitleOverlay(\n        first: AuthorizedSubtitleTrack,\n        second: AuthorizedSubtitleTrack,\n    ) {\n        if (overlayStartInProgress) return\n        if (!Settings.canDrawOverlays(this)) {\n            Toast.makeText(this, "Display over other apps is required before starting subtitles.", Toast.LENGTH_LONG).show()\n            openOverlaySettings()\n            return\n        }\n        setOverlayLoading(true, "Loading Sub 1 and Sub 2 from private storage…")\n        setStatus("Loading both private subtitles…")\n        executor.execute {\n            val result = runCatching {\n                val loaded1 = repository.loadAuthorizedSubtitle(first.id)\n                val loaded2 = repository.loadAuthorizedSubtitle(second.id)\n                val token = SubtitleCueHandoff.prepare(loaded1.cues, loaded2.cues)\n                PreparedSubtitle(\n                    token = token,\n                    label = "${first.languageName} + ${second.languageName}",\n                    cueCount = loaded1.cues.size + loaded2.cues.size,\n                )\n            }\n            runOnUiThread {\n                result.onSuccess { prepared ->\n                    setOverlayLoading(true, "Starting Multi Subtitle overlay…")\n                    val intent = Intent(this, OverlayService::class.java).apply {\n                        action = OverlayService.ACTION_START\n                        putExtra(OverlayService.EXTRA_CUE_HANDOFF_TOKEN, prepared.token)\n                    }\n                    runCatching { ContextCompat.startForegroundService(this, intent) }\n                        .onSuccess {\n                            setOverlayLoading(false)\n                            setStatus("")\n                            Toast.makeText(this, "Multi Subtitle ready · ${prepared.label}", Toast.LENGTH_SHORT).show()\n                            moveTaskToBack(true)\n                        }\n                        .onFailure { error ->\n                            SubtitleCueHandoff.clear(prepared.token)\n                            setOverlayLoading(false)\n                            setStatus(error.message ?: "Unable to start Multi Subtitle overlay.")\n                        }\n                }.onFailure { error ->\n                    setOverlayLoading(false)\n                    setStatus(error.message ?: "Unable to load both subtitles.")\n                }\n            }\n        }\n    }\n'''
main = replace_once(main, old_multi, new_multi, "multi subtitle setup")
MAIN.write_text(main, encoding="utf-8")

overlay = OVERLAY.read_text(encoding="utf-8")
overlay = replace_once(
    overlay,
    "    private var subtitleView: TextView? = null\n    private var controllerView: View? = null\n",
    "    private var subtitleView: TextView? = null\n    private var secondarySubtitleView: TextView? = null\n    private var controllerView: View? = null\n",
    "secondary view field",
)
overlay = replace_once(
    overlay,
    "    private var cues: List<SubtitleCue> = emptyList()\n    private var activeCueToken = \"\"\n    private var subtitleListId = \"\"\n    private var renderedCueIndices: List<Int> = emptyList()\n",
    "    private var cues: List<SubtitleCue> = emptyList()\n    private var secondaryCues: List<SubtitleCue> = emptyList()\n    private var activeCueToken = \"\"\n    private var subtitleListId = \"\"\n    private var renderedCueIndices: List<Int> = emptyList()\n    private var renderedSecondaryCueIndices: List<Int> = emptyList()\n",
    "secondary cue fields",
)
overlay = replace_once(
    overlay,
    "    private var subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP\n    private var lastSessionRefreshMs = 0L\n",
    "    private var subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP\n    private var secondarySubtitleTextSizeSp = DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP\n    private var secondarySubtitleBottomMarginDp = DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP\n    private var secondarySubtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP\n    private var lastSessionRefreshMs = 0L\n",
    "secondary position fields",
)
overlay = replace_once(
    overlay,
    "        subtitleHorizontalOffsetDp = preferences.getInt(\n            KEY_SUBTITLE_HORIZONTAL_OFFSET,\n            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,\n        )\n",
    "        subtitleHorizontalOffsetDp = preferences.getInt(\n            KEY_SUBTITLE_HORIZONTAL_OFFSET,\n            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,\n        )\n        secondarySubtitleTextSizeSp = preferences.getFloat(\n            KEY_SECONDARY_SUBTITLE_TEXT_SIZE,\n            DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP,\n        )\n        secondarySubtitleBottomMarginDp = preferences.getInt(\n            KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN,\n            DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP,\n        )\n        secondarySubtitleHorizontalOffsetDp = preferences.getInt(\n            KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET,\n            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,\n        )\n",
    "load secondary prefs",
)
overlay = replace_once(
    overlay,
    "            cues = snapshot.cues\n            activeCueToken = token\n",
    "            cues = snapshot.cues\n            secondaryCues = snapshot.secondaryCues\n            activeCueToken = token\n",
    "load secondary snapshot",
)
overlay = replace_once(
    overlay,
    "        subtitleView?.let(::removeWindowSafely)\n        controllerView?.let(::removeWindowSafely)\n        subtitleView = null\n        controllerView = null\n",
    "        subtitleView?.let(::removeWindowSafely)\n        secondarySubtitleView?.let(::removeWindowSafely)\n        controllerView?.let(::removeWindowSafely)\n        subtitleView = null\n        secondarySubtitleView = null\n        controllerView = null\n",
    "destroy secondary",
)
overlay = replace_once(
    overlay,
    "        subtitleView?.let(::removeWindowSafely)\n        controllerView?.let(::removeWindowSafely)\n\n        subtitleView = createSubtitleView().also {\n            windowManager.addView(it, subtitleLayoutParams())\n        }\n",
    "        subtitleView?.let(::removeWindowSafely)\n        secondarySubtitleView?.let(::removeWindowSafely)\n        controllerView?.let(::removeWindowSafely)\n\n        subtitleView = createSubtitleView(slot = 1).also {\n            windowManager.addView(it, subtitleLayoutParams(slot = 1))\n        }\n        secondarySubtitleView = secondaryCues.takeIf { it.isNotEmpty() }?.let {\n            createSubtitleView(slot = 2).also { view ->\n                windowManager.addView(view, subtitleLayoutParams(slot = 2))\n            }\n        }\n",
    "show secondary window",
)
overlay = replace_once(
    overlay,
    "    private fun createSubtitleView(): TextView = TextView(this).apply {\n        textSize = subtitleTextSizeSp\n",
    "    private fun createSubtitleView(slot: Int): TextView = TextView(this).apply {\n        textSize = if (slot == 1) subtitleTextSizeSp else secondarySubtitleTextSizeSp\n",
    "slot create view",
)
overlay = replace_once(
    overlay,
    "        setOnClickListener { toggleRenderedStudyCues() }\n        installSubtitleGestures(this)\n",
    "        if (slot == 1) setOnClickListener { toggleRenderedStudyCues() }\n        installSubtitleGestures(this, slot)\n",
    "slot gestures",
)
overlay = replace_once(
    overlay,
    "    private fun installSubtitleGestures(view: TextView) {\n",
    "    private fun installSubtitleGestures(view: TextView, slot: Int) {\n",
    "gesture signature",
)
overlay = replace_once(
    overlay,
    "                    setSubtitleTextSize(subtitleTextSizeSp * detector.scaleFactor, persist = false)\n",
    "                    if (slot == 1) {\n                        setSubtitleTextSize(subtitleTextSizeSp * detector.scaleFactor, persist = false)\n                    } else {\n                        secondarySubtitleTextSizeSp = (secondarySubtitleTextSizeSp * detector.scaleFactor)\n                            .coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)\n                        secondarySubtitleView?.textSize = secondarySubtitleTextSizeSp\n                    }\n",
    "slot pinch",
)
overlay = replace_once(
    overlay,
    "                override fun onScaleEnd(detector: ScaleGestureDetector) {\n                    saveSubtitleTextSize()\n                }\n",
    "                override fun onScaleEnd(detector: ScaleGestureDetector) {\n                    if (slot == 1) saveSubtitleTextSize() else saveSecondarySubtitleState()\n                }\n",
    "slot pinch save",
)
overlay = replace_once(
    overlay,
    "                            val maxX = (resources.displayMetrics.widthPixels / 2 - dp(24)).coerceAtLeast(0)\n                            val maxY = (resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(0)\n                            params.x = (initialX + dx).coerceIn(-maxX, maxX)\n                            params.y = (initialY - dy).coerceIn(0, maxY)\n",
    "                            params.x = initialX + dx\n                            params.y = initialY - dy\n",
    "remove position clamp",
)
overlay = replace_once(
    overlay,
    "                    if (dragging) {\n                        subtitleHorizontalOffsetDp = pxToDp(params.x)\n                        subtitleBottomMarginDp = pxToDp(params.y)\n                        saveSubtitlePosition()\n                    } else if (!scaled && studyModeEnabled) {\n                        view.performClick()\n                    }\n",
    "                    if (dragging) {\n                        if (slot == 1) {\n                            subtitleHorizontalOffsetDp = pxToDp(params.x)\n                            subtitleBottomMarginDp = pxToDp(params.y)\n                            saveSubtitlePosition()\n                        } else {\n                            secondarySubtitleHorizontalOffsetDp = pxToDp(params.x)\n                            secondarySubtitleBottomMarginDp = pxToDp(params.y)\n                            saveSecondarySubtitleState()\n                        }\n                    } else if (!scaled && studyModeEnabled && slot == 1) {\n                        view.performClick()\n                    }\n",
    "slot drag save",
)
overlay = replace_once(
    overlay,
    "    private fun makeDraggable(view: View) {\n",
    "    private fun saveSecondarySubtitleState() {\n        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {\n            putFloat(KEY_SECONDARY_SUBTITLE_TEXT_SIZE, secondarySubtitleTextSizeSp)\n            putInt(KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN, secondarySubtitleBottomMarginDp)\n            putInt(KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET, secondarySubtitleHorizontalOffsetDp)\n        }\n    }\n\n    private fun makeDraggable(view: View) {\n",
    "secondary state save",
)
overlay = replace_once(
    overlay,
    "    private fun subtitleLayoutParams() = WindowManager.LayoutParams(\n",
    "    private fun subtitleLayoutParams(slot: Int) = WindowManager.LayoutParams(\n",
    "slot layout signature",
)
overlay = replace_once(
    overlay,
    "    ).apply {\n        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL\n        x = dp(subtitleHorizontalOffsetDp)\n        y = dp(subtitleBottomMarginDp)\n    }\n\n    private fun controllerLayoutParams()",
    "    ).apply {\n        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL\n        x = dp(if (slot == 1) subtitleHorizontalOffsetDp else secondarySubtitleHorizontalOffsetDp)\n        y = dp(if (slot == 1) subtitleBottomMarginDp else secondarySubtitleBottomMarginDp)\n    }\n\n    private fun controllerLayoutParams()",
    "slot layout position",
)
overlay = replace_once(
    overlay,
    "        renderedCueIndices = emptyList()\n        studyPlaybackEngine?.stop()\n",
    "        renderedCueIndices = emptyList()\n        renderedSecondaryCueIndices = emptyList()\n        studyPlaybackEngine?.stop()\n",
    "reset secondary rendering",
)
overlay = replace_once(
    overlay,
    "        positionView?.text = \"${formatTime(rawPosition)}  Δ${formatOffset(offsetMs)}\"\n",
    "        if (secondaryCues.isNotEmpty()) {\n            val nextSecondary = activeCueIndices(secondaryCues, subtitlePosition)\n            if (nextSecondary != renderedSecondaryCueIndices) {\n                renderedSecondaryCueIndices = nextSecondary\n                secondarySubtitleView?.apply {\n                    if (renderedSecondaryCueIndices.isNotEmpty()) {\n                        text = renderedSecondaryCueIndices.joinToString(\"\\n\") { index -> secondaryCues[index].text }\n                        visibility = View.VISIBLE\n                    } else {\n                        text = \"\"\n                        visibility = View.INVISIBLE\n                    }\n                }\n            }\n        }\n\n        positionView?.text = \"${formatTime(rawPosition)}  Δ${formatOffset(offsetMs)}\"\n",
    "render secondary",
)
overlay = replace_once(
    overlay,
    "    private fun activeCueIndices(positionMs: Long): List<Int> = cues.indices.filter { index ->\n        val cue = cues[index]\n        cue.startMs <= positionMs && positionMs < cue.endMs\n    }\n",
    "    private fun activeCueIndices(positionMs: Long): List<Int> = activeCueIndices(cues, positionMs)\n\n    private fun activeCueIndices(source: List<SubtitleCue>, positionMs: Long): List<Int> = source.indices.filter { index ->\n        val cue = source[index]\n        cue.startMs <= positionMs && positionMs < cue.endMs\n    }\n",
    "generic active cues",
)
overlay = replace_once(
    overlay,
    "        private const val KEY_SUBTITLE_HORIZONTAL_OFFSET = \"subtitle_horizontal_offset_dp\"\n        private const val KEY_PLAYBACK_SPEED = \"playback_speed\"\n",
    "        private const val KEY_SUBTITLE_HORIZONTAL_OFFSET = \"subtitle_horizontal_offset_dp\"\n        private const val KEY_SECONDARY_SUBTITLE_TEXT_SIZE = \"secondary_subtitle_text_size_sp\"\n        private const val KEY_SECONDARY_SUBTITLE_BOTTOM_MARGIN = \"secondary_subtitle_bottom_margin_dp\"\n        private const val KEY_SECONDARY_SUBTITLE_HORIZONTAL_OFFSET = \"secondary_subtitle_horizontal_offset_dp\"\n        private const val KEY_PLAYBACK_SPEED = \"playback_speed\"\n",
    "secondary pref keys",
)
overlay = replace_once(
    overlay,
    "        private const val DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP = 0\n        private const val MIN_SUBTITLE_BOTTOM_MARGIN_DP = 0\n",
    "        private const val DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP = 0\n        private const val DEFAULT_SECONDARY_SUBTITLE_TEXT_SIZE_SP = 20f\n        private const val DEFAULT_SECONDARY_SUBTITLE_BOTTOM_MARGIN_DP = 86\n        private const val MIN_SUBTITLE_BOTTOM_MARGIN_DP = 0\n",
    "secondary defaults",
)
OVERLAY.write_text(overlay, encoding="utf-8")
print("Applied complete dual subtitle setup and independent gestures.")
