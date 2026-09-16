package com.minimal.carlauncher.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.minimal.carlauncher.R
import com.minimal.carlauncher.data.AppEntry
import com.minimal.carlauncher.data.IconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drawer grid. Icons come from [IconCache]: already-rasterised bitmaps bind synchronously,
 * anything else is decoded on a background dispatcher with a staleness guard so a recycled
 * row never shows the previous app's icon.
 */
class AppGridAdapter(
    private val iconCache: IconCache,
    private val scope: CoroutineScope,
    private val layoutRes: Int = R.layout.item_app_grid,
    private val onClick: (AppEntry, View) -> Unit,
    private val onLongClick: (AppEntry, View) -> Unit
) : ListAdapter<AppEntry, AppGridAdapter.AppViewHolder>(DIFF) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).key.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AppViewHolder) {
        super.onViewRecycled(holder)
        holder.cancel()
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val icon: ImageView = itemView.findViewById(R.id.appIcon)
        private val label: TextView = itemView.findViewById(R.id.appLabel)
        private var job: Job? = null

        fun bind(entry: AppEntry) {
            label.text = entry.label
            itemView.setOnClickListener { onClick(entry, itemView) }
            itemView.setOnLongClickListener {
                onLongClick(entry, itemView)
                true
            }

            cancel()
            icon.tag = entry.key

            val cached: Bitmap? = iconCache.peek(entry.key)
            if (cached != null) {
                // Fast path - after the first scroll essentially everything lands here.
                icon.setImageBitmap(cached)
                return
            }

            icon.setImageDrawable(null)
            job = scope.launch {
                val bitmap = withContext(Dispatchers.Default) { iconCache.load(entry.component) }
                if (bitmap != null && icon.tag == entry.key) {
                    icon.setImageBitmap(bitmap)
                }
            }
        }

        fun cancel() {
            job?.cancel()
            job = null
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
                oldItem.component == newItem.component

            override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean =
                oldItem == newItem
        }
    }
}
