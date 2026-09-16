package com.minimal.carlauncher.ui

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.carlauncher.R
import com.minimal.carlauncher.data.AppEntry
import com.minimal.carlauncher.data.AppRepository
import kotlinx.coroutines.CoroutineScope
import java.util.Locale

/**
 * One searchable app list, reused by the nav card, the music card, the projection fallback
 * and the dock slots. Kept as a plain AlertDialog rather than a DialogFragment: the launcher
 * is a single activity that never saves instance state, so there is nothing to restore.
 */
object AppPicker {

    fun show(
        activity: Activity,
        repository: AppRepository,
        scope: CoroutineScope,
        titleRes: Int,
        onPicked: (AppEntry) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null, false)
        val list = view.findViewById<RecyclerView>(R.id.pickerList)
        val search = view.findViewById<android.widget.EditText>(R.id.pickerSearch)

        val all = repository.apps.value

        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        val adapter = AppGridAdapter(
            iconCache = repository.iconCache,
            scope = scope,
            layoutRes = R.layout.item_picker_row,
            onClick = { entry, _ ->
                dialog.dismiss()
                onPicked(entry)
            },
            onLongClick = { _, _ -> }
        )

        list.layoutManager = LinearLayoutManager(activity)
        list.setHasFixedSize(true)
        list.adapter = adapter
        adapter.submitList(all)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                adapter.submitList(filter(all, s?.toString().orEmpty()))
            }
        })

        dialog.show()
    }

    /** Matches on the precomputed lowercase sort key, so no allocation per entry per keystroke. */
    fun filter(all: List<AppEntry>, query: String): List<AppEntry> {
        val trimmed = query.trim().lowercase(Locale.getDefault())
        if (trimmed.isEmpty()) return all
        return all.filter { it.sortKey.contains(trimmed) }
    }
}
