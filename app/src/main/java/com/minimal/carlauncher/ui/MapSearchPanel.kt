package com.minimal.carlauncher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.minimal.carlauncher.R
import com.minimal.carlauncher.core.Format
import com.minimal.carlauncher.core.Prefs
import com.minimal.carlauncher.databinding.ActivityHomeBinding
import com.minimal.carlauncher.map.GeocodingClient
import com.minimal.carlauncher.map.Place
import com.minimal.carlauncher.nav.Geo
import com.minimal.carlauncher.nav.LatLon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Full-screen address search, styled like the app drawer: search field, then one card per
 * result with its name, the rest of the address and the distance from the car.
 */
class MapSearchPanel(
    private val activity: AppCompatActivity,
    private val binding: ActivityHomeBinding,
    private val onPicked: (Place) -> Unit
) {

    private val adapter = ResultsAdapter { place ->
        close()
        onPicked(place)
    }
    private var near: LatLon? = null
    private var searchJob: Job? = null

    val isOpen: Boolean get() = binding.searchPanel.visibility == View.VISIBLE

    fun bind() {
        binding.mapSearchResults.layoutManager = LinearLayoutManager(activity)
        binding.mapSearchResults.adapter = adapter
        binding.btnMapSearchClose.setOnClickListener { close() }
        binding.btnMapSearchGo.setOnClickListener { search() }
        binding.mapSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search()
                true
            } else {
                false
            }
        }
    }

    fun open(nearCar: LatLon?) {
        near = nearCar
        binding.searchPanel.visibility = View.VISIBLE
        binding.mapSearchInput.requestFocus()
        imm()?.showSoftInput(binding.mapSearchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    fun close() {
        searchJob?.cancel()
        hideKeyboard()
        binding.searchPanel.visibility = View.GONE
    }

    private fun search() {
        val query = binding.mapSearchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        hideKeyboard()
        showStatus(activity.getString(R.string.map_searching))
        searchJob?.cancel()
        searchJob = activity.lifecycleScope.launch {
            val results = try {
                GeocodingClient.search(query, near?.lat, near?.lon)
            } catch (e: Exception) {
                adapter.submit(emptyList(), near)
                showStatus(activity.getString(R.string.map_search_offline))
                return@launch
            }
            adapter.submit(results, near)
            showStatus(
                if (results.isEmpty()) activity.getString(R.string.map_search_none)
                else activity.getString(R.string.map_search_results, results.size)
            )
            binding.mapSearchResults.scrollToPosition(0)
        }
    }

    private fun showStatus(text: String) {
        binding.mapSearchStatus.text = text
        binding.mapSearchStatus.visibility = View.VISIBLE
    }

    private fun imm() =
        activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun hideKeyboard() {
        imm()?.hideSoftInputFromWindow(binding.mapSearchInput.windowToken, 0)
    }

    // ------------------------------------------------------------------ results

    private class ResultsAdapter(
        private val onClick: (Place) -> Unit
    ) : RecyclerView.Adapter<ResultsAdapter.Holder>() {

        private var items: List<Place> = emptyList()
        private var near: LatLon? = null

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.placeTitle)
            val subtitle: TextView = view.findViewById(R.id.placeSubtitle)
            val distance: TextView = view.findViewById(R.id.placeDistance)
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(places: List<Place>, nearCar: LatLon?) {
            items = places
            near = nearCar
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_place_result, parent, false)
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val place = items[position]
            // Nominatim's display_name is "Name, street, city, region, postcode, country":
            // the first part is the title, the rest is the subtitle.
            holder.title.text = place.title
            holder.subtitle.text = place.subtitle
            holder.subtitle.visibility = if (place.subtitle.isBlank()) View.GONE else View.VISIBLE
            val from = near
            holder.distance.text = if (from == null) "" else Format.distanceText(
                Prefs.speedUnit, Geo.distanceM(from, LatLon(place.latitude, place.longitude))
            )
            holder.itemView.setOnClickListener { onClick(place) }
        }
    }
}
