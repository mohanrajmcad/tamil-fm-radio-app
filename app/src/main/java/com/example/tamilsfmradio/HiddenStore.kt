package com.example.tamilsfmradio

import android.content.Context

/**
 * Persists hidden station identifiers (the station's stream URL) across app restarts.
 * Stations end up here either because the user manually hid them, or because the
 * reachability check found them dead - tracked separately so a bad reachability run
 * (e.g. a transient network blip) can be safely undone without losing deliberate
 * user hides. The user can review and reactivate anything from the Hidden tab.
 */
object HiddenStore {
    private const val PREFS_NAME = "mr_radio_prefs"
    private const val KEY_MANUAL = "hidden_urls_manual"
    private const val KEY_AUTO = "hidden_urls_auto"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun manualSet(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MANUAL, emptySet()).orEmpty()

    private fun autoSet(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_AUTO, emptySet()).orEmpty()

    fun getAll(context: Context): Set<String> = manualSet(context) + autoSet(context)

    fun isHidden(context: Context, stationId: String): Boolean =
        getAll(context).contains(stationId)

    /** User explicitly hid this station from the app UI. */
    fun hide(context: Context, stationId: String) {
        val current = manualSet(context).toMutableSet()
        if (current.add(stationId)) {
            prefs(context).edit().putStringSet(KEY_MANUAL, current).apply()
        }
    }

    /** The reachability check found this station dead. */
    fun autoHide(context: Context, stationId: String) {
        val current = autoSet(context).toMutableSet()
        if (current.add(stationId)) {
            prefs(context).edit().putStringSet(KEY_AUTO, current).apply()
        }
    }

    /** Removes from both the manual and auto sets - a full un-hide regardless of source. */
    fun unhide(context: Context, stationId: String) {
        val manual = manualSet(context).toMutableSet()
        val auto = autoSet(context).toMutableSet()
        var changed = false
        if (manual.remove(stationId)) changed = true
        if (auto.remove(stationId)) changed = true
        if (changed) {
            prefs(context).edit()
                .putStringSet(KEY_MANUAL, manual)
                .putStringSet(KEY_AUTO, auto)
                .apply()
        }
    }

    /** Toggles hidden state (as a manual hide) for the station and returns the new state. */
    fun toggle(context: Context, stationId: String): Boolean {
        return if (isHidden(context, stationId)) {
            unhide(context, stationId)
            false
        } else {
            hide(context, stationId)
            true
        }
    }

    /** Wipes only the auto-hidden set, e.g. to recover from an over-aggressive dead-station run. */
    fun clearAutoHidden(context: Context) {
        prefs(context).edit().putStringSet(KEY_AUTO, emptySet()).apply()
    }
}
