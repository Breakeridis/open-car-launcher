package com.minimal.carlauncher.ui

import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.minimal.carlauncher.BuildConfig
import com.minimal.carlauncher.CarLauncherApp
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.update.UpdateRepository
import com.minimal.carlauncher.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

object AboutDialog {

    fun show(
        activity: AppCompatActivity,
        repository: UpdateRepository,
        onUpdateAvailable: (UpdateState) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null, false)
        val version = view.findViewById<TextView>(R.id.aboutVersion)
        val repo = view.findViewById<TextView>(R.id.aboutRepo)
        val status = view.findViewById<TextView>(R.id.aboutStatus)
        val checkButton = view.findViewById<Button>(R.id.btnCheckUpdates)
        val crashButton = view.findViewById<Button>(R.id.btnCrashLog)

        version.text = activity.getString(R.string.about_version, BuildConfig.VERSION_NAME)
        repo.text = if (Constants.isUpdaterConfigured) {
            activity.getString(R.string.about_repo, Constants.GITHUB_OWNER, Constants.GITHUB_REPO)
        } else {
            activity.getString(R.string.about_repo_unset)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setView(view)
            .setPositiveButton(R.string.action_close, null)
            .create()

        var collector: Job? = null

        checkButton.isEnabled = Constants.isUpdaterConfigured
        checkButton.setOnClickListener { repository.check(force = true) }

        crashButton.setOnClickListener { showCrashLog(activity) }

        dialog.setOnShowListener {
            collector = activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    repository.state.collect { state ->
                        status.text = when (state) {
                            is UpdateState.NotConfigured ->
                                activity.getString(R.string.update_not_configured)
                            is UpdateState.Checking ->
                                activity.getString(R.string.update_checking)
                            is UpdateState.UpToDate ->
                                activity.getString(R.string.update_up_to_date)
                            is UpdateState.Available ->
                                activity.getString(R.string.update_available, state.release.tag)
                            is UpdateState.ReadyToInstall ->
                                activity.getString(R.string.update_available, state.release.tag)
                            is UpdateState.Downloading ->
                                activity.getString(R.string.update_downloading)
                            is UpdateState.Error -> state.message
                            is UpdateState.Idle -> ""
                        }

                        if (state is UpdateState.Available || state is UpdateState.ReadyToInstall) {
                            dialog.dismiss()
                            onUpdateAvailable(state)
                        }
                    }
                }
            }
        }

        dialog.setOnDismissListener {
            collector?.cancel()
            repository.markSeen()
        }
        dialog.show()
    }

    /**
     * The crash log is the only realistic way to debug a launcher that is sitting in a car,
     * so it is readable without adb.
     */
    private fun showCrashLog(activity: AppCompatActivity) {
        val file = File(activity.cacheDir, CarLauncherApp.CRASH_LOG)
        val text = if (file.exists()) {
            // Only the tail matters, and a huge string would jam the dialog.
            file.readText().takeLast(8_000)
        } else {
            activity.getString(R.string.about_no_crash_log)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.crash_log_title)
            .setMessage(text.ifBlank { activity.getString(R.string.about_no_crash_log) })
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.action_clear) { _, _ -> file.delete() }
            .show()
    }
}
