package com.minimal.carlauncher.radio

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.minimal.carlauncher.CarLauncherApp

/**
 * Granting this listener "notification access" is the one public way a third-party app may
 * read the native tuner: its media session (MediaSessionManager.getActiveSessions) and, for
 * tuner apps that publish no session, its ongoing notification.
 *
 * The framework broadcast-radio API (android.hardware.radio.RadioManager) is a system API
 * guarded by a privileged permission, so on the Allwinner T507 / A133 units - like every other
 * aftermarket head unit - the launcher cannot drive the tuner directly. Only notifications from
 * the tuner app are looked at; everything else is ignored.
 */
class MediaSessionListener : NotificationListenerService() {

    private val radio: RadioRepository?
        get() = (application as? CarLauncherApp)?.radioRepository

    override fun onListenerConnected() {
        instance = this
        radio?.onNotificationsSnapshot(snapshot())
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        radio?.onNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        radio?.onNotificationRemoved(sbn)
    }

    /** Current notifications; empty while the listener is not bound. */
    fun snapshot(): List<StatusBarNotification> =
        runCatching { activeNotifications?.toList() }.getOrNull().orEmpty()

    companion object {
        /** The bound listener, for the diagnostics screen and the start-up scan. */
        @Volatile
        var instance: MediaSessionListener? = null
            private set
    }
}
