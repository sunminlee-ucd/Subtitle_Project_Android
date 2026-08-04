package com.sun.subtitleoverlay.playback

import android.service.notification.NotificationListenerService

/**
 * Grants MediaSessionManager access to media sessions published by video apps.
 * Notification contents are not read or stored by this application.
 */
class PlaybackNotificationListener : NotificationListenerService()
