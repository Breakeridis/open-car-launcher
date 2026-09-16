package com.minimal.carlauncher.ui

import android.app.Activity
import android.content.ComponentName
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Constants
import com.minimal.carlauncher.core.DockCodec
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.data.AppEntry
import com.minimal.carlauncher.data.AppRepository
import com.minimal.carlauncher.databinding.ViewDockBinding
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user-assignable part of the dock. Slots are a fixed-length list so positions stay put:
 * removing an app blanks its slot rather than shifting the rest left.
 */
class DockController(
    private val activity: Activity,
    private val binding: ViewDockBinding,
    private val repository: AppRepository,
    private val scope: CoroutineScope
) {

    fun bind() {
        val slots = Prefs.dockSlots
        val container = binding.dockSlots
        container.removeAllViews()

        val inflater = LayoutInflater.from(activity)
        slots.forEachIndexed { index, stored ->
            val view = inflater.inflate(R.layout.item_dock, container, false)
            val icon = view.findViewById<ImageView>(R.id.dockSlotIcon)

            if (stored.isBlank()) {
                bindEmptySlot(view, icon, index)
            } else {
                bindFilledSlot(view, icon, index, stored)
            }
            container.addView(view)
        }
    }

    private fun bindEmptySlot(view: View, icon: ImageView, index: Int) {
        icon.setImageResource(R.drawable.ic_add)
        icon.setBackgroundResource(R.drawable.bg_empty_slot)
        view.contentDescription = activity.getString(R.string.dock_empty_slot)
        view.setOnClickListener { pickForSlot(index) }
        view.setOnLongClickListener {
            pickForSlot(index)
            true
        }
    }

    private fun bindFilledSlot(view: View, icon: ImageView, index: Int, stored: String) {
        icon.background = null
        val component = ComponentName.unflattenFromString(stored)
        val entry = component?.let { repository.find(it) }
            ?: repository.findByPackage(IntentUtil.packageOf(stored))

        view.contentDescription = entry?.label ?: stored

        if (entry != null) {
            val cached = repository.iconCache.peek(entry.key)
            if (cached != null) {
                icon.setImageBitmap(cached)
            } else {
                icon.setImageDrawable(null)
                scope.launch {
                    val bitmap = withContext(Dispatchers.Default) {
                        repository.iconCache.load(entry.component)
                    }
                    if (bitmap != null) icon.setImageBitmap(bitmap)
                }
            }
        } else {
            icon.setImageResource(R.drawable.ic_add)
        }

        view.setOnClickListener { source ->
            if (!IntentUtil.launchStored(activity, stored, source)) {
                // The app went away between binds - clear the slot instead of leaving a dead icon.
                setSlot(index, "")
                toast(R.string.app_not_installed)
            }
        }
        view.setOnLongClickListener {
            showSlotActions(index, entry?.label ?: stored)
            true
        }
    }

    private fun showSlotActions(index: Int, label: String) {
        val options = arrayOf(
            activity.getString(R.string.action_replace),
            activity.getString(R.string.action_remove)
        )
        AlertDialog.Builder(activity)
            .setTitle(label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickForSlot(index)
                    1 -> setSlot(index, "")
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickForSlot(index: Int) {
        AppPicker.show(activity, repository, scope, R.string.pick_dock_app) { entry ->
            setSlot(index, entry.component.flattenToShortString())
        }
    }

    /** Called from the drawer's long-press menu. */
    fun pin(entry: AppEntry): Boolean {
        val current = Prefs.dockSlots
        val free = current.indexOfFirst { it.isBlank() }
        if (free < 0) {
            toast(R.string.dock_full)
            return false
        }
        setSlot(free, entry.component.flattenToShortString())
        toast(R.string.pinned_to_dock)
        return true
    }

    fun reset() {
        Prefs.dockSlots = DockCodec.empty(Constants.DOCK_SLOT_COUNT)
        bind()
    }

    /** Self-heals the dock when a docked app is uninstalled. */
    fun onPackageRemoved(packageName: String) {
        val updated = DockCodec.removePackage(Prefs.dockSlots, packageName) ?: return
        Prefs.dockSlots = updated
        bind()
    }

    private fun setSlot(index: Int, value: String) {
        val updated = Prefs.dockSlots.toMutableList()
        if (index !in updated.indices) return
        updated[index] = value
        Prefs.dockSlots = updated
        bind()
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
