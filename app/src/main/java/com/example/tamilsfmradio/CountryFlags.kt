package com.example.tamilsfmradio

/** Cosmetic country -> flag emoji lookup for the station list. Unrecognized country strings
 *  just render without a flag rather than a broken/missing glyph. */
object CountryFlags {
    private val FLAGS = mapOf(
        "india" to "🇮🇳",
        "sri lanka" to "🇱🇰",
        "malaysia" to "🇲🇾",
        "singapore" to "🇸🇬",
        "united states" to "🇺🇸",
        "the united states" to "🇺🇸",
        "usa" to "🇺🇸",
        "canada" to "🇨🇦",
        "united kingdom" to "🇬🇧",
        "uk" to "🇬🇧",
        "france" to "🇫🇷",
        "australia" to "🇦🇺",
        "germany" to "🇩🇪",
        "united arab emirates" to "🇦🇪",
        "uae" to "🇦🇪",
        "qatar" to "🇶🇦",
        "saudi arabia" to "🇸🇦",
        "switzerland" to "🇨🇭",
        "netherlands" to "🇳🇱",
        "south africa" to "🇿🇦",
        "new zealand" to "🇳🇿",
        "reunion" to "🇷🇪",
        "kuwait" to "🇰🇼",
        "bahrain" to "🇧🇭",
        "oman" to "🇴🇲"
    )

    fun flagFor(country: String): String? = FLAGS[country.trim().lowercase()]
}
