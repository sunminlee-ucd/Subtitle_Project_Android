from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/MainActivity.kt"
OVERLAY = ROOT / "SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/overlay/OverlayService.kt"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Missing patch anchor: {label}")
    return text.replace(old, new, 1)


main = MAIN.read_text(encoding="utf-8")
main = replace_once(
    main,
    '''            addView(actionButton("Request a subtitle", secondary = true) {\n                openRequestPortal()\n            }, matchWrap(bottom = dp(18)))\n''',
    '''            addView(actionButton("Request a subtitle", secondary = true) {\n                openRequestPortal()\n            }, matchWrap(bottom = dp(8)))\n\n            addView(actionButton("Multi Subtitle", secondary = true) {\n                openMultiSubtitleSetup()\n            }, matchWrap(bottom = dp(18)))\n''',
    "multi subtitle button",
)
main = replace_once(
    main,
    '''    private fun openRequestPortal() {\n''',
    '''    private fun openMultiSubtitleSetup() {\n        Toast.makeText(\n            this,\n            "Multi Subtitle setup will be available here. Next step: choose two authorized subtitle languages.",\n            Toast.LENGTH_LONG,\n        ).show()\n    }\n\n    private fun openRequestPortal() {\n''',
    "multi subtitle action",
)
MAIN.write_text(main, encoding="utf-8")

overlay = OVERLAY.read_text(encoding="utf-8")
overlay = replace_once(
    overlay,
    '''import android.view.MotionEvent\nimport android.view.View\n''',
    '''import android.view.MotionEvent\nimport android.view.ScaleGestureDetector\nimport android.view.View\n''',
    "scale gesture import",
)
overlay = replace_once(
    overlay,
    '''    private var subtitleTextSizeSp = DEFAULT_SUBTITLE_TEXT_SIZE_SP\n    private var subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP\n''',
    '''    private var subtitleTextSizeSp = DEFAULT_SUBTITLE_TEXT_SIZE_SP\n    private var subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP\n    private var subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP\n''',
    "horizontal offset field",
)
overlay = replace_once(
    overlay,
    '''        subtitleTextSizeSp = preferences.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE_SP)\n        subtitleBottomMarginDp = preferences.getInt(KEY_SUBTITLE_BOTTOM_MARGIN, DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP)\n''',
    '''        subtitleTextSizeSp = preferences.getFloat(KEY_SUBTITLE_TEXT_SIZE, DEFAULT_SUBTITLE_TEXT_SIZE_SP)\n        subtitleBottomMarginDp = preferences.getInt(KEY_SUBTITLE_BOTTOM_MARGIN, DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP)\n        subtitleHorizontalOffsetDp = preferences.getInt(\n            KEY_SUBTITLE_HORIZONTAL_OFFSET,\n            DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP,\n        )\n''',
    "load horizontal offset",
)
overlay = replace_once(
    overlay,
    '''        background = normalSubtitleBackground()\n        visibility = View.INVISIBLE\n        setOnClickListener { toggleRenderedStudyCues() }\n    }\n''',
    '''        background = normalSubtitleBackground()\n        visibility = View.INVISIBLE\n        setOnClickListener { toggleRenderedStudyCues() }\n        installSubtitleGestures(this)\n    }\n''',
    "install subtitle gestures",
)
overlay = replace_once(
    overlay,
    '''    private fun changeSubtitleTextSize(deltaSp: Float) {\n        subtitleTextSizeSp = (subtitleTextSizeSp + deltaSp)\n            .coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)\n        subtitleView?.textSize = subtitleTextSizeSp\n        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {\n            putFloat(KEY_SUBTITLE_TEXT_SIZE, subtitleTextSizeSp)\n        }\n        updateOverlay()\n    }\n''',
    '''    private fun changeSubtitleTextSize(deltaSp: Float) {\n        setSubtitleTextSize(subtitleTextSizeSp + deltaSp, persist = true)\n    }\n\n    private fun setSubtitleTextSize(sizeSp: Float, persist: Boolean) {\n        subtitleTextSizeSp = sizeSp.coerceIn(MIN_SUBTITLE_TEXT_SIZE_SP, MAX_SUBTITLE_TEXT_SIZE_SP)\n        subtitleView?.textSize = subtitleTextSizeSp\n        if (persist) saveSubtitleTextSize()\n        updateOverlay()\n    }\n\n    private fun saveSubtitleTextSize() {\n        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {\n            putFloat(KEY_SUBTITLE_TEXT_SIZE, subtitleTextSizeSp)\n        }\n    }\n''',
    "text size helpers",
)
overlay = replace_once(
    overlay,
    '''    private fun resetSubtitlePosition() {\n        subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP\n        applySubtitlePosition()\n        saveSubtitlePosition()\n    }\n\n    private fun applySubtitlePosition() {\n        val view = subtitleView ?: return\n        val params = view.layoutParams as? WindowManager.LayoutParams ?: return\n        params.y = dp(subtitleBottomMarginDp)\n        windowManager.updateViewLayout(view, params)\n        updateOverlay()\n    }\n\n    private fun saveSubtitlePosition() {\n        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {\n            putInt(KEY_SUBTITLE_BOTTOM_MARGIN, subtitleBottomMarginDp)\n        }\n    }\n''',
    '''    private fun resetSubtitlePosition() {\n        subtitleBottomMarginDp = DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP\n        subtitleHorizontalOffsetDp = DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP\n        applySubtitlePosition()\n        saveSubtitlePosition()\n    }\n\n    private fun applySubtitlePosition() {\n        val view = subtitleView ?: return\n        val params = view.layoutParams as? WindowManager.LayoutParams ?: return\n        params.x = dp(subtitleHorizontalOffsetDp)\n        params.y = dp(subtitleBottomMarginDp)\n        windowManager.updateViewLayout(view, params)\n        updateOverlay()\n    }\n\n    private fun saveSubtitlePosition() {\n        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit {\n            putInt(KEY_SUBTITLE_BOTTOM_MARGIN, subtitleBottomMarginDp)\n            putInt(KEY_SUBTITLE_HORIZONTAL_OFFSET, subtitleHorizontalOffsetDp)\n        }\n    }\n\n    private fun installSubtitleGestures(view: TextView) {\n        var initialX = 0\n        var initialY = 0\n        var touchX = 0f\n        var touchY = 0f\n        var dragging = false\n        var scaled = false\n\n        val scaleDetector = ScaleGestureDetector(\n            this,\n            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {\n                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {\n                    scaled = true\n                    return true\n                }\n\n                override fun onScale(detector: ScaleGestureDetector): Boolean {\n                    setSubtitleTextSize(subtitleTextSizeSp * detector.scaleFactor, persist = false)\n                    return true\n                }\n\n                override fun onScaleEnd(detector: ScaleGestureDetector) {\n                    saveSubtitleTextSize()\n                }\n            },\n        )\n\n        view.setOnTouchListener { _, event ->\n            scaleDetector.onTouchEvent(event)\n            val params = view.layoutParams as? WindowManager.LayoutParams\n                ?: return@setOnTouchListener false\n\n            when (event.actionMasked) {\n                MotionEvent.ACTION_DOWN -> {\n                    initialX = params.x\n                    initialY = params.y\n                    touchX = event.rawX\n                    touchY = event.rawY\n                    dragging = false\n                    scaled = false\n                    true\n                }\n\n                MotionEvent.ACTION_POINTER_DOWN -> {\n                    scaled = true\n                    true\n                }\n\n                MotionEvent.ACTION_MOVE -> {\n                    if (scaleDetector.isInProgress || event.pointerCount > 1) {\n                        true\n                    } else {\n                        val dx = (event.rawX - touchX).toInt()\n                        val dy = (event.rawY - touchY).toInt()\n                        if (dragging || kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) {\n                            dragging = true\n                            val maxX = (resources.displayMetrics.widthPixels / 2 - dp(24)).coerceAtLeast(0)\n                            val maxY = (resources.displayMetrics.heightPixels - dp(48)).coerceAtLeast(0)\n                            params.x = (initialX + dx).coerceIn(-maxX, maxX)\n                            params.y = (initialY - dy).coerceIn(0, maxY)\n                            windowManager.updateViewLayout(view, params)\n                            true\n                        } else {\n                            true\n                        }\n                    }\n                }\n\n                MotionEvent.ACTION_UP -> {\n                    if (dragging) {\n                        subtitleHorizontalOffsetDp = pxToDp(params.x)\n                        subtitleBottomMarginDp = pxToDp(params.y)\n                        saveSubtitlePosition()\n                    } else if (!scaled && studyModeEnabled) {\n                        view.performClick()\n                    }\n                    true\n                }\n\n                MotionEvent.ACTION_CANCEL -> true\n                else -> true\n            }\n        }\n    }\n''',
    "subtitle position and gestures",
)
overlay = replace_once(
    overlay,
    '''        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or\n            (if (studyModeEnabled) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or\n            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,\n''',
    '''        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or\n            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,\n''',
    "subtitle always touchable",
)
overlay = replace_once(
    overlay,
    '''    ).apply {\n        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL\n        y = dp(subtitleBottomMarginDp)\n    }\n\n    private fun controllerLayoutParams()''',
    '''    ).apply {\n        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL\n        x = dp(subtitleHorizontalOffsetDp)\n        y = dp(subtitleBottomMarginDp)\n    }\n\n    private fun controllerLayoutParams()''',
    "subtitle x layout param",
)
overlay = replace_once(
    overlay,
    '''    private fun updateSubtitleTouchability() {\n        val view = subtitleView ?: return\n        val params = view.layoutParams as? WindowManager.LayoutParams ?: return\n        params.flags = if (studyModeEnabled) {\n            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()\n        } else {\n            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE\n        }\n        view.isClickable = studyModeEnabled\n        view.contentDescription = if (studyModeEnabled) {\n            "Tap subtitle to save or remove it from the study list"\n        } else {\n            null\n        }\n        windowManager.updateViewLayout(view, params)\n    }\n''',
    '''    private fun updateSubtitleTouchability() {\n        val view = subtitleView ?: return\n        val params = view.layoutParams as? WindowManager.LayoutParams ?: return\n        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()\n        view.isClickable = true\n        view.contentDescription = if (studyModeEnabled) {\n            "Drag subtitle to move it. Pinch to resize it. Tap to save or remove it from the study list."\n        } else {\n            "Drag subtitle to move it. Pinch with two fingers to resize it."\n        }\n        windowManager.updateViewLayout(view, params)\n    }\n''',
    "touchability",
)
overlay = replace_once(
    overlay,
    '''    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()\n\n    companion object {\n''',
    '''    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()\n\n    private fun pxToDp(value: Int): Int =\n        (value / resources.displayMetrics.density).toInt()\n\n    companion object {\n''',
    "px to dp helper",
)
overlay = replace_once(
    overlay,
    '''        private const val KEY_SUBTITLE_BOTTOM_MARGIN = "subtitle_bottom_margin_dp"\n        private const val KEY_PLAYBACK_SPEED = "playback_speed"\n''',
    '''        private const val KEY_SUBTITLE_BOTTOM_MARGIN = "subtitle_bottom_margin_dp"\n        private const val KEY_SUBTITLE_HORIZONTAL_OFFSET = "subtitle_horizontal_offset_dp"\n        private const val KEY_PLAYBACK_SPEED = "playback_speed"\n''',
    "horizontal offset key",
)
overlay = replace_once(
    overlay,
    '''        private const val DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP = 24\n        private const val MIN_SUBTITLE_BOTTOM_MARGIN_DP = 0\n''',
    '''        private const val DEFAULT_SUBTITLE_BOTTOM_MARGIN_DP = 24\n        private const val DEFAULT_SUBTITLE_HORIZONTAL_OFFSET_DP = 0\n        private const val MIN_SUBTITLE_BOTTOM_MARGIN_DP = 0\n''',
    "horizontal offset default",
)
OVERLAY.write_text(overlay, encoding="utf-8")

print("Applied Multi Subtitle button and subtitle drag/pinch gestures.")
