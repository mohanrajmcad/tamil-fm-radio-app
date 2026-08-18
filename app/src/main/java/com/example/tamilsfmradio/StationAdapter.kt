package com.example.tamilsfmradio

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load

class RadioStationAdapter(
    private val context: Context,
    private var stations: List<RadioStation>,
    private val onClick: (RadioStation) -> Unit,
    private val onFavoriteToggle: (RadioStation) -> Unit,
    private val onHideToggle: (RadioStation) -> Unit
) : RecyclerView.Adapter<RadioStationAdapter.StationViewHolder>() {

    private var selectedUrl: String? = null

    // android:textColorPrimary resolves to a ColorStateList, not a flat color, so reading
    // TypedValue.data directly (as if it were an int) yields garbage/invisible text.
    private val defaultNameColor: Int = run {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        if (typedValue.resourceId != 0) {
            try {
                ContextCompat.getColorStateList(context, typedValue.resourceId)?.defaultColor
                    ?: typedValue.data
            } catch (e: Exception) {
                typedValue.data
            }
        } else {
            typedValue.data
        }
    }

    class StationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val indicator: View = view.findViewById(R.id.nowPlayingIndicator)
        val logo: ImageView = view.findViewById(R.id.stationLogo)
        val name: TextView = view.findViewById(R.id.stationName)
        val liveBadge: TextView = view.findViewById(R.id.liveBadge)
        val badge: TextView = view.findViewById(R.id.qualityBadge)
        val url: TextView = view.findViewById(R.id.stationUrl)
        val favorite: TextView = view.findViewById(R.id.favoriteButton)
        val hide: TextView = view.findViewById(R.id.hideButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_station, parent, false)
        return StationViewHolder(view)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        val station = stations[position]
        val isSelected = station.url == selectedUrl
        val isFavorite = FavoritesStore.isFavorite(context, station.url)
        val isHidden = HiddenStore.isHidden(context, station.url)

        holder.name.text = station.name
        holder.name.setTextColor(if (isSelected) ACCENT_COLOR else defaultNameColor)
        holder.indicator.setBackgroundColor(if (isSelected) ACCENT_COLOR else Color.TRANSPARENT)
        val flag = CountryFlags.flagFor(station.country)
        val countryLabel = if (flag != null) "$flag ${station.country}" else station.country
        holder.url.text = "${station.bitrate}kbps • $countryLabel".trim(' ', '•').trim()

        holder.logo.load(station.favicon.ifBlank { null }) {
            placeholder(R.drawable.ic_radio_placeholder)
            error(R.drawable.ic_radio_placeholder)
            crossfade(true)
        }

        holder.liveBadge.visibility = if (station.isVerifiedLive) View.VISIBLE else View.GONE
        if (station.bitrate >= HD_BITRATE_THRESHOLD) {
            holder.badge.text = "HD"
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.badge.visibility = View.GONE
        }
        holder.favorite.text = if (isFavorite) "★" else "☆"
        holder.favorite.setTextColor(if (isFavorite) ACCENT_COLOR else defaultNameColor)
        // Stations only ever appear hidden when browsing the Hidden tab itself, where the
        // action naturally reads as "bring this back" rather than "hide again".
        holder.hide.text = if (isHidden) "↺" else "🚫"

        holder.itemView.setOnClickListener { onClick(station) }
        holder.favorite.setOnClickListener {
            onFavoriteToggle(station)
        }
        holder.hide.setOnClickListener {
            onHideToggle(station)
        }
    }

    override fun getItemCount(): Int = stations.size

    fun updateStations(newStations: List<RadioStation>) {
        stations = newStations
        notifyDataSetChanged()
    }

    /** Highlights the row for [url] as currently playing/selected; returns its position, or -1. */
    fun setSelected(url: String?): Int {
        val previousUrl = selectedUrl
        selectedUrl = url
        stations.indexOfFirst { it.url == previousUrl }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        val newIndex = stations.indexOfFirst { it.url == url }
        if (newIndex >= 0) notifyItemChanged(newIndex)
        return newIndex
    }

    companion object {
        private val ACCENT_COLOR = Color.parseColor("#FF6F00")
        private const val HD_BITRATE_THRESHOLD = 320
    }
}
