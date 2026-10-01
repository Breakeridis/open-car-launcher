package com.minimal.carlauncher.radio

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.RadioFrequency
import com.minimal.carlauncher.core.RadioMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RadioState(
    /** Notification access granted - without it no media session is readable. */
    val sessionAccess: Boolean = false,
    /** A tuner media session is live right now. */
    val connected: Boolean = false,
    val sourcePackage: String? = null,
    val frequency: RadioFrequency? = null,
    val stationName: String? = null,
    val radioText: String? = null,
    val playing: Boolean = false
)

enum class TuneResult {
    /** Handed to the live tuner session; confirmation follows via the callback. */
    SENT,
    /** No session to talk to, so the radio app was opened with a play-from-search intent. */
    LAUNCHED,
    FAILED
}

/**
 * Bridges the launcher's radio widget to the head unit's native tuner app.
 *
 * Read path: the tuner's MediaSession metadata (needs notification access).
 * Control path, in order of preference:
 *  1. MediaController transport controls - skipToNext/Previous for seek, playFromSearch
 *     with the radio media focus for direct tuning;
 *  2. media key events through AudioManager, which reach whichever app owns audio focus -
 *     on these units that is the tuner while it is playing, exactly like the steering-wheel
 *     seek buttons;
 *  3. a MEDIA_PLAY_FROM_SEARCH intent at the tuner package (opens the radio app).
 *
 * Whether (1) honours a direct frequency is up to the tuner app, so [tune] verifies the result
 * against the frequency the tuner reports back instead of assuming success.
 */
class RadioRepository(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaSessionListener::class.java)

    private val _state = MutableStateFlow(rememberedState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            attach(null)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        attach(chooseRadioSession(list.orEmpty()))
    }

    val hasSessionAccess: Boolean
        get() = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    /** Called from the activity's onResume - access may have been granted while we were away. */
    fun start() {
        if (!hasSessionAccess || sessionManager == null) {
            _state.value = _state.value.copy(sessionAccess = false, connected = false)
            return
        }
        if (!listening) {
            try {
                sessionManager.addOnActiveSessionsChangedListener(
                    sessionsListener, listenerComponent, main
                )
                listening = true
            } catch (e: SecurityException) {
                _state.value = _state.value.copy(sessionAccess = false)
                return
            }
        }
        val sessions = try {
            sessionManager.getActiveSessions(listenerComponent)
        } catch (e: SecurityException) {
            emptyList()
        }
        attach(chooseRadioSession(sessions))
    }

    fun stop() {
        if (listening) {
            runCatching { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
            listening = false
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    /** Re-run session selection, e.g. after the user picked a different radio app. */
    fun reselect() {
        stop()
        start()
    }

    // ---------------------------------------------------------------- controls

    /** Seek to the next / previous receivable station. */
    fun seek(up: Boolean): Boolean {
        controller?.let { c ->
            if (up) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
            return true
        }
        return dispatchMediaKey(if (up) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    /** One manual frequency step from the current station. */
    fun step(up: Boolean, onConfirmed: (Boolean) -> Unit): TuneResult {
        val current = _state.value.frequency ?: return TuneResult.FAILED
        return tune(current.step(up), onConfirmed)
    }

    fun tune(target: RadioFrequency, onConfirmed: (Boolean) -> Unit): TuneResult {
        val extras = Bundle().apply {
            putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Radio.ENTRY_CONTENT_TYPE)
            putString(SearchManager.QUERY, target.searchQuery)
        }

        val c = controller
        if (c != null) {
            c.transportControls.playFromSearch(target.searchQuery, extras)
            main.postDelayed(
                { onConfirmed(_state.value.frequency == target) },
                Constants.RADIO_TUNE_CONFIRM_MS
            )
            return TuneResult.SENT
        }

        val pkg = Prefs.radioPackage ?: _state.value.sourcePackage ?: return TuneResult.FAILED
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(pkg)
            .putExtras(extras)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            TuneResult.LAUNCHED
        } catch (e: Exception) {
            TuneResult.FAILED
        }
    }

    private fun dispatchMediaKey(keyCode: Int): Boolean {
        val am = audioManager ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------- sessions

    /**
     * Picks the tuner among the active sessions: the user's chosen radio app first, then a
     * package that looks like a tuner, then any session whose metadata reads as a frequency
     * (vendor tuners sometimes hide behind opaque package names).
     */
    private fun chooseRadioSession(sessions: List<MediaController>): MediaController? {
        Prefs.radioPackage?.let { chosen ->
            sessions.firstOrNull { it.packageName == chosen }?.let { return it }
        }
        sessions.firstOrNull { RadioFrequency.looksLikeRadioPackage(it.packageName) }
            ?.let { return it }
        return sessions.firstOrNull { readout(it.metadata).frequency != null }
    }

    private fun attach(next: MediaController?) {
        if (next?.sessionToken == controller?.sessionToken && next != null) {
            publish()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, main)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = _state.value.copy(
                sessionAccess = hasSessionAccess,
                connected = false,
                playing = false
            )
            return
        }

        val readout = readout(c.metadata)
        val previous = _state.value
        // Keep the last frequency while a seek is in progress (many tuners blank it).
        val frequency = readout.frequency ?: previous.frequency
        val next = RadioState(
            sessionAccess = true,
            connected = true,
            sourcePackage = c.packageName,
            frequency = frequency,
            stationName = readout.stationName,
            radioText = readout.radioText,
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        )
        _state.value = next

        if (readout.frequency != null && readout.frequency.encode() != Prefs.radioLastFrequency) {
            Prefs.radioLastFrequency = readout.frequency.encode()
        }
        val name = readout.stationName.orEmpty()
        if (name != Prefs.radioLastStation) Prefs.radioLastStation = name
    }

    private fun readout(metadata: MediaMetadata?) = RadioMetadata.extract(
        if (metadata == null) emptyList() else listOf(
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
        )
    )

    /** Shows the last station instantly at ignition, before the tuner republishes anything. */
    private fun rememberedState() = RadioState(
        frequency = RadioFrequency.decode(Prefs.radioLastFrequency),
        stationName = Prefs.radioLastStation.ifBlank { null }
    )
}
