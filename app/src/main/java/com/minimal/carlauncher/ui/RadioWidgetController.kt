package com.minimal.carlauncher.ui

import android.content.Intent
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.core.RadioFrequency
import com.minimal.carlauncher.data.AppRepository
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.radio.MediaSessionListener
import com.minimal.carlauncher.radio.RadioRepository
import com.minimal.carlauncher.radio.RadioState
import com.minimal.carlauncher.radio.TuneResult
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.CoroutineScope

/**
 * The FM/AM tuner widget: live frequency + RDS, seek buttons, four presets.
 *
 * Tap [-]/[+] seeks, long-press steps one channel. Tap a preset to tune it, long-press to
 * store the current station there. Tap the frequency to open the native radio app,
 * long-press it to choose which app that is.
 */
class RadioWidgetController(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val radio: RadioRepository,
    private val apps: AppRepository,
    private val scope: CoroutineScope
) {

    private val presetViews: List<TextView> by lazy {
        listOf(binding.preset1, binding.preset2, binding.preset3, binding.preset4)
    }

    fun bind() {
        binding.radioDisplay.setOnClickListener { openRadioApp() }
        binding.radioDisplay.setOnLongClickListener {
            pickRadioApp()
            true
        }
        binding.textRadioStatus.setOnClickListener { requestSessionAccess() }

        bindSeek(binding.btnSeekDown, up = false)
        bindSeek(binding.btnSeekUp, up = true)

        presetViews.forEachIndexed { index, view ->
            view.setOnClickListener { recallPreset(index, it) }
            view.setOnLongClickListener {
                savePreset(index, it)
                true
            }
        }
        render(radio.state.value)
    }

    fun render(state: RadioState) {
        val f = state.frequency
        binding.textRadioFreq.text = f?.numberText ?: activity.getString(R.string.radio_no_freq)
        binding.textRadioBand.text = f?.let { "${it.bandText}  ${it.unitText}" }
            ?: activity.getString(R.string.radio_band_fm)
        // A remembered (not live) frequency is shown dimmed, so it is not mistaken for live.
        binding.textRadioFreq.alpha = if (state.connected) 1f else 0.5f

        binding.textRadioStation.text = state.stationName.orEmpty()
        binding.textRadioStation.visibility =
            if (state.stationName.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.textRadioText.text = state.radioText.orEmpty()
        binding.textRadioText.visibility =
            if (state.radioText.isNullOrBlank()) View.GONE else View.VISIBLE

        when {
            !state.sessionAccess -> {
                binding.textRadioStatus.visibility = View.VISIBLE
                binding.textRadioStatus.setText(R.string.radio_grant_access)
            }
            !state.connected -> {
                binding.textRadioStatus.visibility = View.VISIBLE
                binding.textRadioStatus.setText(R.string.radio_not_playing)
            }
            else -> binding.textRadioStatus.visibility = View.GONE
        }

        val presets = Prefs.radioPresets
        presetViews.forEachIndexed { i, view ->
            val preset = RadioFrequency.decode(presets.getOrNull(i))
            view.text = preset?.numberText ?: activity.getString(R.string.radio_preset_empty)
            view.isSelected = preset != null && preset == f && state.connected
            view.contentDescription = preset?.let {
                activity.getString(R.string.radio_preset_desc, i + 1, it.searchQuery)
            } ?: activity.getString(R.string.radio_preset_empty_desc, i + 1)
        }
    }

    // ------------------------------------------------------------------- controls

    private fun bindSeek(view: View, up: Boolean) {
        view.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (!radio.seek(up)) toast(R.string.radio_unreachable)
        }
        view.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            handleTune(radio.step(up, this::onTuneConfirmed))
            true
        }
    }

    private fun recallPreset(index: Int, view: View) {
        val preset = RadioFrequency.decode(Prefs.radioPresets.getOrNull(index))
        if (preset == null) {
            savePreset(index, view)
            return
        }
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        handleTune(radio.tune(preset, this::onTuneConfirmed))
    }

    private fun handleTune(result: TuneResult) {
        when (result) {
            TuneResult.SENT, TuneResult.LAUNCHED -> Unit
            TuneResult.FAILED -> {
                toast(R.string.radio_unreachable)
                openRadioApp()
            }
        }
    }

    private fun onTuneConfirmed(ok: Boolean) {
        // The tuner app decides whether it honours a direct frequency request; say so
        // honestly rather than leaving the driver wondering.
        if (!ok) toast(R.string.radio_tune_ignored)
    }

    private fun savePreset(index: Int, view: View) {
        val state = radio.state.value
        val current = state.frequency
        if (current != null && state.connected) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            storePreset(index, current)
            return
        }
        // Nothing live to save - let the user type it (only reachable when parked, as the
        // tuner is normally playing while driving).
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.radio_preset_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(current?.numberText.orEmpty())
        }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.radio_preset_title, index + 1))
            .setView(input)
            .setPositiveButton(R.string.radio_preset_save) { _, _ ->
                val parsed = RadioFrequency.parseUserInput(input.text.toString())
                if (parsed == null) toast(R.string.radio_preset_invalid) else storePreset(index, parsed)
            }
            .setNeutralButton(R.string.action_clear) { _, _ -> storePreset(index, null) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun storePreset(index: Int, frequency: RadioFrequency?) {
        val presets = Prefs.radioPresets.toMutableList()
        if (index !in presets.indices) return
        presets[index] = frequency?.encode().orEmpty()
        Prefs.radioPresets = presets
        if (frequency != null) {
            Toast.makeText(
                activity,
                activity.getString(R.string.radio_preset_saved, frequency.searchQuery, index + 1),
                Toast.LENGTH_SHORT
            ).show()
        }
        render(radio.state.value)
    }

    // ------------------------------------------------------------------ radio app

    private fun radioPackage(): String? =
        Prefs.radioPackage
            ?: radio.state.value.sourcePackage
            ?: apps.apps.value.firstOrNull {
                RadioFrequency.looksLikeRadioPackage(it.packageName) ||
                    it.sortKey == "radio" || it.sortKey.startsWith("fm ") || it.sortKey == "fm"
            }?.packageName

    private fun openRadioApp() {
        val pkg = radioPackage()
        if (pkg == null) {
            pickRadioApp()
            return
        }
        if (!IntentUtil.launchPackage(activity, pkg)) {
            if (Prefs.radioPackage == pkg) Prefs.radioPackage = null
            toast(R.string.app_not_installed)
            pickRadioApp()
        }
    }

    fun pickRadioApp() {
        AppPicker.show(activity, apps, scope, R.string.pick_radio_app) { entry ->
            Prefs.radioPackage = entry.packageName
            radio.reselect()
        }
    }

    /**
     * Notification access is how a third-party app may read the tuner's media session. Some
     * stripped head-unit ROMs hide that settings screen, so the adb fallback is shown too.
     */
    fun requestSessionAccess() {
        val opened = IntentUtil.startSafely(
            activity, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        )
        if (opened) {
            toast(R.string.radio_access_hint)
            return
        }
        val component = "${BuildConfig.APPLICATION_ID}/${MediaSessionListener::class.java.name}"
        AlertDialog.Builder(activity)
            .setTitle(R.string.radio_access_title)
            .setMessage(activity.getString(R.string.radio_access_adb, component))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    /** What the launcher can see of the tuner - screenshot this when the widget stays empty. */
    fun showDiagnostics() {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            setPadding(pad, pad, pad, pad)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            this.text = radio.diagnostics()
        }
        val scroll = android.widget.ScrollView(activity).apply { addView(text) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.radio_diagnostics)
            .setView(scroll)
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.radio_diagnostics_refresh) { _, _ ->
                radio.reselect()
                showDiagnostics()
            }
            .show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
