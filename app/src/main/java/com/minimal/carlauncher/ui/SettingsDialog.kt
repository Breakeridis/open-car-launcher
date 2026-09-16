package com.minimal.carlauncher.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.util.IntentUtil

object SettingsDialog {

    fun show(activity: Activity, onDockReset: () -> Unit, onPrefsChanged: () -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, null, false)

        val themeGroup = view.findViewById<RadioGroup>(R.id.groupTheme)
        val unitGroup = view.findViewById<RadioGroup>(R.id.groupUnits)
        val compassGroup = view.findViewById<RadioGroup>(R.id.groupCompass)

        themeGroup.check(
            when (Prefs.themeMode) {
                AppCompatDelegate.MODE_NIGHT_NO -> R.id.themeLight
                AppCompatDelegate.MODE_NIGHT_YES -> R.id.themeDark
                else -> R.id.themeSystem
            }
        )
        unitGroup.check(
            if (Prefs.speedUnit == Format.UNIT_MPH) R.id.unitMph else R.id.unitKmh
        )
        compassGroup.check(if (Prefs.compass16Point) R.id.compass16 else R.id.compass8)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_title)
            .setView(view)
            .setPositiveButton(R.string.action_close, null)
            .create()

        unitGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.speedUnit =
                if (checkedId == R.id.unitMph) Format.UNIT_MPH else Format.UNIT_KMH
            onPrefsChanged()
        }

        compassGroup.setOnCheckedChangeListener { _, checkedId ->
            Prefs.compass16Point = checkedId == R.id.compass16
            onPrefsChanged()
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.themeLight -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.themeDark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (mode == Prefs.themeMode) return@setOnCheckedChangeListener
            Prefs.themeMode = mode
            dialog.dismiss()
            // AppCompat walks its live delegates and recreates started activities itself -
            // do NOT call recreate() here, and keep uiMode out of the activity's configChanges
            // or this path is bypassed and half the views keep the old colours.
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        view.findViewById<Button>(R.id.btnResetDock).setOnClickListener {
            onDockReset()
            Toast.makeText(activity, R.string.dock_reset, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<Button>(R.id.btnSetHomeApp).setOnClickListener {
            IntentUtil.openHomeSettings(activity)
        }

        dialog.show()
    }
}
