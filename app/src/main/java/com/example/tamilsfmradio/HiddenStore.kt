package com.example.tamilsfmradio

import android.content.Context

/**
 * Persists hidden station identifiers (the station's stream URL) across app restarts.
 * Stations end up here either because the user manually hid them, or because the
 * reachability check found them dead - either way, the user can review and reactivate
 * them from the Hidden tab.
 */
object HiddenStore {
    private const val PREFS_NAME = "mr_radio_prefs"
    private const val KEY_HIDDEN = "hidden_urls"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()).orEmpty()

    fun isHidden(context: Context, stationId: String): Boolean =
        getAll(context).contains(stationId)

    fun hide(context: Context, stationId: String) {
        val current = getAll(context).toMutableSet()
        if (current.add(stationId)) {
            prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
        }
    }

    fun unhide(context: Context, stationId: String) {
        val current = getAll(context).toMutableSet()
        if (current.remove(stationId)) {
            prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
        }
    }

    /** Toggles hidden state for the station and returns the new state. */
    fun toggle(context: Context, stationId: String): Boolean {
        val current = getAll(context).toMutableSet()
        val nowHidden = if (current.contains(stationId)) {
            current.remove(stationId)
            false
        } else {
            current.add(stationId)
            true
        }
        prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
        return nowHidden
    }
}
