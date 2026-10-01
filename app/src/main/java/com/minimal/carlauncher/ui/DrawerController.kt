package com.minimal.carlauncher.ui

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.MotionEvent
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
import kotlin.math.abs

/**
 * The full-screen all-apps drawer, an overlay inside the home layout rather than its own screen.
 * Dismissed by the close button, HOME, BACK, a tap on its margins or a downward fling.
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
        // The drawer covers the whole screen; a tap on its margins dismisses it.
        binding.drawerContainer.setOnClickListener { close() }
        bindSwipeDownToClose()

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

    /** A downward fling while the grid is already scrolled to the top closes the drawer. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun bindSwipeDownToClose() {
        val minVelocity = FLING_CLOSE_DP_PER_S * activity.resources.displayMetrics.density
        val detector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (velocityY > minVelocity && abs(velocityY) > abs(velocityX) * 1.5f &&
                    !binding.appGrid.canScrollVertically(-1)
                ) {
                    close()
                    return true
                }
                return false
            }
        })
        binding.appGrid.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false   // the grid still scrolls normally
        }
    }

    private fun hideKeyboard() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private companion object {
        const val FLING_CLOSE_DP_PER_S = 1_200f
    }
}
