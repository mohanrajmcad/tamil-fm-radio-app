package com.example.tamilsfmradio

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface RadioBrowserApi {
    // The plain bylanguage endpoint has no quality knob and returns a lot of stations with
    // bitrate=0 (unset) or very low bitrate. The advanced search endpoint supports
    // bitrateMin, which cuts that out server-side while still ranking by popularity.
    @GET("json/stations/search")
    suspend fun searchStations(
        @Query("language") language: String? = null,
        @Query("name") name: String? = null,
        @Query("tag") tag: String? = null,
        @Query("bitrateMin") bitrateMin: Int = 128,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true,
        @Query("hidebroken") hidebroken: Boolean = true,
        @Query("limit") limit: Int = 320
    ): List<RadioStation>
}

object RadioBrowserClient {
    private const val BASE_URL = "https://de1.api.radio-browser.info/"

    val api: RadioBrowserApi by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        retrofit.create(RadioBrowserApi::class.java)
    }

    /**
     * Some legitimate Tamil stations (e.g. France's vanavilFM, Suryan FM Chennai, confirmed
     * via manual research) have an empty `language` field and no "tamil" in their name, so
     * neither a language-only nor a name-only query catches them all. Merge language + name
     * + tag searches, plus a small hand-curated list for stations whose registered stream
     * URL in the database is dead but whose site serves a working stream directly -
     * StationUtils.dedupe() cleans up the overlap between all of these.
     */
    suspend fun getTamilStations(): List<RadioStation> {
        val byLanguage = api.searchStations(language = "tamil", limit = 320)
        val byName = api.searchStations(name = "tamil", limit = 150)
        val byTag = api.searchStations(tag = "tamil", limit = 100)
        return byLanguage + byName + byTag + CustomStations.ALL
    }
}
