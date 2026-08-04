package com.sun.subtitleoverlay

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.sun.subtitleoverlay.overlay.OverlayService

/**
 * Closes the notification shade by briefly becoming the foreground activity,
 * restores the overlay controls, and immediately reveals the previously used video app.
 */
class RestoreControlsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startService(
            Intent(this, OverlayService::class.java)
                .setAction(OverlayService.ACTION_SHOW_CONTROLS)
        )
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
