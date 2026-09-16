package com.minimal.carlauncher.ui

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import com.minimal.carlauncher.R
import com.minimal.carlauncher.data.AppEntry
import com.minimal.carlauncher.data.AppRepository
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.util.IntentUtil
import kotlinx.coroutines.CoroutineScope

/**
 * The all-apps drawer, which is an overlay inside the home layout rather than its own screen.
 * The dock stays visible underneath it, so "pin to dock" has a visible target.
 */
class DrawerController(
    private val activity: Activity,
    private val binding: ActivityHomeBinding,
    private val repository: AppRepository,
    scope: CoroutineScope,
    private val onPinRequested: (AppEntry) -> Unit
) {

    private val adapter = AppGridAdapter(
        iconCache = repository.iconCache,
        scope = scope,
        onClick = { entry, view -> launch(entry, view) },
        onLongClick = { entry, _ -> showActions(entry) }
    )

    private var allApps: List<AppEntry> = emptyList()

    val isOpen: Boolean get() = binding.drawerContainer.visibility == View.VISIBLE

    fun bind() {
        val spanCount = activity.resources.getInteger(R.integer.drawer_span_count)
        val spacing = activity.resources.getDimensionPixelSize(R.dimen.app_grid_spacing)

        binding.appGrid.layoutManager = GridLayoutManager(activity, spanCount)
        binding.appGrid.setHasFixedSize(true)
        binding.appGrid.setItemViewCacheSize(24)
        binding.appGrid.addItemDecoration(GridSpacingDecoration(spacing))
        binding.appGrid.adapter = adapter

        binding.btnCloseDrawer.setOnClickListener { close() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
    }

    fun submitApps(apps: List<AppEntry>) {
        allApps = apps
        applyFilter()
    }

    fun open() {
        binding.drawerContainer.visibility = View.VISIBLE
        binding.appGrid.scrollToPosition(0)
    }

    fun close() {
        binding.drawerContainer.visibility = View.GONE
        clearSearch()
        hideKeyboard()
    }

    /** Called when HOME is pressed while the drawer is open, and on a long return from an app. */
    fun reset() {
        if (isOpen) close() else clearSearch()
    }

    private fun clearSearch() {
        if (binding.searchInput.text.isNotEmpty()) {
            binding.searchInput.setText("")
        }
        binding.searchInput.clearFocus()
    }

    private fun applyFilter() {
        val query = binding.searchInput.text?.toString().orEmpty()
        val filtered = AppPicker.filter(allApps, query)
        adapter.submitList(filtered)

        binding.drawerEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.drawerEmpty.setText(
            if (allApps.isEmpty()) R.string.loading_apps else R.string.no_apps_found
        )
    }

    private fun launch(entry: AppEntry, source: View) {
        if (IntentUtil.startApp(activity, entry.component, source)) {
            close()
        } else {
            android.widget.Toast
                .makeText(activity, R.string.could_not_launch, android.widget.Toast.LENGTH_SHORT)
                .show()
        }
    }

    private fun showActions(entry: AppEntry) {
        val options = arrayOf(
            activity.getString(R.string.action_open),
            activity.getString(R.string.action_pin_to_dock),
            activity.getString(R.string.action_app_info)
        )
        AlertDialog.Builder(activity)
            .setTitle(entry.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launch(entry, binding.appGrid)
                    1 -> onPinRequested(entry)
                    2 -> IntentUtil.openAppDetails(activity, entry.packageName)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }
}
