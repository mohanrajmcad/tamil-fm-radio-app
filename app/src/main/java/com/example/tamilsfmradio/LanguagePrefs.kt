package com.example.tamilsfmradio

import android.content.Context

enum class AppLanguage(val apiValue: String, val displayName: String) {
    TAMIL("tamil", "Tamil"),
    ENGLISH("english", "English")
}

/** Persists which station language the app is browsing - set once via the first-launch
 *  picker, changeable anytime after. Both MainActivity and RadioPlaybackService read this
 *  independently (they each run their own station fetch - see CLAUDE.md), so it lives in
 *  SharedPreferences rather than in-memory state owned by either one. */
object LanguagePrefs {
    private const val PREFS_NAME = "mr_radio_prefs"
    private const val KEY_LANGUAGE = "app_language"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSet(context: Context): Boolean =
        prefs(context).contains(KEY_LANGUAGE)

    fun get(context: Context): AppLanguage {
        val value = prefs(context).getString(KEY_LANGUAGE, null)
        return AppLanguage.entries.find { it.apiValue == value } ?: AppLanguage.TAMIL
    }

    fun set(context: Context, language: AppLanguage) {
        prefs(context).edit().putString(KEY_LANGUAGE, language.apiValue).apply()
    }
}
