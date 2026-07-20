package com.example.tamilsfmradio

import android.content.Context

/** Persists favorite station identifiers (the station's stream URL) across app restarts. */
object FavoritesStore {
    private const val PREFS_NAME = "mr_radio_prefs"
    private const val KEY_FAVORITES = "favorite_urls"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet()).orEmpty()

    fun isFavorite(context: Context, stationId: String): Boolean =
        getAll(context).contains(stationId)

    /** Toggles favorite state for the station and returns the new state. */
    fun toggle(context: Context, stationId: String): Boolean {
        val current = getAll(context).toMutableSet()
        val nowFavorite = if (current.contains(stationId)) {
            current.remove(stationId)
            false
        } else {
            current.add(stationId)
            true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFavorite
    }
}
