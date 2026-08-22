from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OVERLAY = ROOT / "SubtitleOverlayAndroid/app/src/main/java/com/sun/subtitleoverlay/overlay/OverlayService.kt"

text = OVERLAY.read_text(encoding="utf-8")

if "import android.view.ViewConfiguration\n" not in text:
    text = text.replace(
        "import android.view.View\nimport android.view.WindowManager\n",
        "import android.view.View\nimport android.view.ViewConfiguration\nimport android.view.WindowManager\n",
        1,
    )

start = text.index("    private fun installSubtitleGestures(view: TextView, slot: Int) {")
end = text.index("\n    private fun saveSecondarySubtitleState()", start)

replacement = '''    private fun installSubtitleGestures(view: TextView, slot: Int) {
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
'''

text = text[:start] + replacement + text[end:]
OVERLAY.write_text(text, encoding="utf-8")
print("Applied strict Study-mode tap/drag/pinch gesture separation.")
