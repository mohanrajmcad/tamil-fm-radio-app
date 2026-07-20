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
    val codec: String = ""
)
