package com.minimal.carlauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.update.ApkInstaller
import com.minimal.carlauncher.update.ReleaseInfo
import com.minimal.carlauncher.update.UpdateRepository
import com.minimal.carlauncher.update.UpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Changelog, download progress and install.
 *
 * The download itself lives in [UpdateRepository] at application scope, so closing and
 * reopening this dialog reattaches to the same transfer instead of restarting it.
 */
object UpdateDialog {

    fun show(
        activity: AppCompatActivity,
        repository: UpdateRepository,
        release: ReleaseInfo,
        onNeedsUnknownSources: () -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null, false)
        val headline = view.findViewById<TextView>(R.id.updateHeadline)
        val changelog = view.findViewById<TextView>(R.id.updateChangelog)
        val progress = view.findViewById<ProgressBar>(R.id.updateProgress)
        val progressText = view.findViewById<TextView>(R.id.updateProgressText)

        headline.text = activity.getString(R.string.update_available, release.tag)
        changelog.text = release.body.ifBlank { release.name }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setView(view)
            .setPositiveButton(R.string.update_download, null)
            .setNegativeButton(R.string.action_close, null)
            .create()

        // Held so the collector is torn down with the dialog rather than leaking for the
        // lifetime of the activity.
        var collector: Job? = null

        dialog.setOnShowListener {
            val action = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            collector = activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    repository.state.collect { state ->
                        when (state) {
                            is UpdateState.Downloading -> {
                                progress.visibility = View.VISIBLE
                                progressText.visibility = View.VISIBLE
                                if (state.total > 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = Format.percent(state.bytes, state.total)
                                } else {
                                    progress.isIndeterminate = true
                                }
                                progressText.text =
                                    Format.byteProgressText(state.bytes, state.total)
                                action.isEnabled = false
                                action.setText(R.string.update_downloading)
                            }

                            is UpdateState.ReadyToInstall -> {
                                progress.visibility = View.GONE
                                progressText.visibility = View.GONE
                                action.isEnabled = true
                                action.setText(R.string.update_install)
                                action.setOnClickListener {
                                    if (!ApkInstaller.canInstall(activity)) {
                                        onNeedsUnknownSources()
                                        return@setOnClickListener
                                    }
                                    val error = ApkInstaller.install(activity, state.file)
                                    if (error != null) {
                                        Toast.makeText(activity, error, Toast.LENGTH_LONG).show()
                                    } else {
                                        dialog.dismiss()
                                    }
                                }
                            }

                            is UpdateState.Error -> {
                                progress.visibility = View.GONE
                                progressText.visibility = View.GONE
                                changelog.text = state.message
                                action.isEnabled = true
                                action.setText(R.string.update_download)
                            }

                            else -> {
                                progress.visibility = View.GONE
                                progressText.visibility = View.GONE
                                action.isEnabled = true
                                action.setText(R.string.update_download)
                                action.setOnClickListener { repository.download(release) }
                            }
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
}
