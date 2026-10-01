package com.minimal.carlauncher.radio

import android.service.notification.NotificationListenerService

/**
 * Exists only so the user can grant "notification access", which is the one public way a
 * third-party app may read other apps' media sessions (MediaSessionManager.getActiveSessions).
 *
 * The framework broadcast-radio API (android.hardware.radio.RadioManager) is a system API
 * guarded by a privileged permission, so on the Allwinner T507 / A133 units - like every other
 * aftermarket head unit - the launcher cannot drive the tuner directly. It reads and controls
 * the native radio app through its media session instead. No notification is ever read.
 */
class MediaSessionListener : NotificationListenerService()
