package com.minimal.carlauncher.ui

import android.app.Activity
import android.content.ComponentName
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.data.AppRepository
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.CoroutineScope

/**
 * The three big quick-launch cards. Tap launches, long-press re-assigns.
 *
 * Every launch path ends in a picker rather than a dead end: on a head unit the "right"
 * package name is firmware dependent, so the user must always be able to point at whatever
 * their unit actually ships.
 */
class QuickCardsController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val repository: AppRepository,
    private val scope: CoroutineScope
) {

    fun bind() {
        binding.cardProjection.setOnClickListener { launchProjection() }
        binding.cardProjection.setOnLongClickListener {
            pickProjection()
            true
        }

        binding.cardNav.setOnClickListener { launchStoredOrPick(it, Slot.NAV) }
        binding.cardNav.setOnLongClickListener {
            pick(Slot.NAV)
            true
        }

        binding.cardMusic.setOnClickListener { launchStoredOrPick(it, Slot.MUSIC) }
        binding.cardMusic.setOnLongClickListener {
            pick(Slot.MUSIC)
            true
        }

        refreshLabels()
    }

    fun refreshLabels() {
        bindSubtitle(binding.subtitleNav, Prefs.navPackage, R.string.card_not_set)
        bindSubtitle(binding.subtitleMusic, Prefs.musicPackage, R.string.card_not_set)

        val projection = Prefs.projectionPackage ?: firstInstalledProjection()
        if (projection == null) {
            binding.subtitleProjection.setText(R.string.projection_hint)
        } else {
            bindSubtitle(binding.subtitleProjection, projection, R.string.projection_hint)
        }
    }

    fun onPackageRemoved(packageName: String) {
        if (Prefs.navPackage?.let { IntentUtil.packageOf(it) } == packageName) Prefs.navPackage = null
        if (Prefs.musicPackage?.let { IntentUtil.packageOf(it) } == packageName) Prefs.musicPackage = null
        if (Prefs.projectionPackage == packageName) Prefs.projectionPackage = null
        refreshLabels()
    }

    // ------------------------------------------------------------------ projection

    private fun launchProjection() {
        Prefs.projectionPackage?.let { stored ->
            if (IntentUtil.launchPackage(activity, stored)) return
            Prefs.projectionPackage = null   // it was uninstalled
        }

        val found = firstInstalledProjection()
        if (found != null) {
            Prefs.projectionPackage = found  // cache the winner for a one-call launch next time
            if (IntentUtil.launchPackage(activity, found)) {
                refreshLabels()
                return
            }
        }

        toast(R.string.no_projection_app)
        pickProjection()
    }

    /** Walks the candidate table in order; these apps are rebranded per dongle vendor. */
    private fun firstInstalledProjection(): String? {
        for (target in Constants.PROJECTION_TARGETS) {
            for (candidate in target.candidates) {
                if (repository.isInstalled(candidate)) return candidate
            }
        }
        return null
    }

    private fun pickProjection() {
        AppPicker.show(activity, repository, scope, R.string.pick_projection_app) { entry ->
            Prefs.projectionPackage = entry.packageName
            refreshLabels()
        }
    }

    // --------------------------------------------------------------- nav and music

    private enum class Slot { NAV, MUSIC }

    private fun launchStoredOrPick(source: View, slot: Slot) {
        val stored = when (slot) {
            Slot.NAV -> Prefs.navPackage
            Slot.MUSIC -> Prefs.musicPackage
        }
        if (stored.isNullOrBlank()) {
            pick(slot)
            return
        }
        if (!IntentUtil.launchStored(activity, stored, source)) {
            clear(slot)
            toast(R.string.app_not_installed)
            pick(slot)
        }
    }

    private fun pick(slot: Slot) {
        val titleRes = when (slot) {
            Slot.NAV -> R.string.pick_nav_app
            Slot.MUSIC -> R.string.pick_music_app
        }
        AppPicker.show(activity, repository, scope, titleRes) { entry ->
            val flattened = entry.component.flattenToShortString()
            when (slot) {
                Slot.NAV -> Prefs.navPackage = flattened
                Slot.MUSIC -> Prefs.musicPackage = flattened
            }
            refreshLabels()
        }
    }

    private fun clear(slot: Slot) {
        when (slot) {
            Slot.NAV -> Prefs.navPackage = null
            Slot.MUSIC -> Prefs.musicPackage = null
        }
        refreshLabels()
    }

    // ---------------------------------------------------------------------- shared

    private fun bindSubtitle(view: TextView, stored: String?, emptyRes: Int) {
        if (stored.isNullOrBlank()) {
            view.setText(emptyRes)
            return
        }
        val packageName = IntentUtil.packageOf(stored)
        val entry = ComponentName.unflattenFromString(stored)?.let { repository.find(it) }
            ?: repository.findByPackage(packageName)

        view.text = when {
            entry != null -> entry.label
            repository.isInstalled(packageName) -> packageName
            else -> activity.getString(R.string.card_not_installed)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
