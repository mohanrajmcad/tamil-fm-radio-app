package com.example.tamilsfmradio

import com.google.gson.annotations.SerializedName

data class RadioStation(
    @SerializedName("name")
    val name: String,
    @SerializedName("url_resolved")
    val url: String,
    @SerializedName("bitrate")
    val bitrate: Int = 0,
    @SerializedName("favicon")
    val favicon: String = "",
    @SerializedName("country")
    val country: String = "",
    @SerializedName("tags")
    val tags: String = "",
    @SerializedName("codec")
    val codec: String = "",
    /** True only for hand-picked CustomStations entries whose stream was manually curl-verified
     *  (HTTP 200 + audio content-type) at add time - not set for the bulk radio-browser.info
     *  import, which has no per-station manual verification step. */
    val isVerifiedLive: Boolean = false
)
