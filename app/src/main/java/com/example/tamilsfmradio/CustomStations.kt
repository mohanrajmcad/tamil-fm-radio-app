package com.example.tamilsfmradio

/**
 * Stations that don't reliably surface via the radio-browser.info API - e.g. their
 * registered stream URL in that database is dead, but the station itself is alive at a
 * different/current URL found by checking their own site directly. Merged in alongside
 * the API results so they go through the same dedupe/prettify/reachability pipeline.
 */
object CustomStations {
    val ALL = listOf(
        // radiopetti.in's radio-browser entry points at a dead airtime.pro host; the
        // station's own site serves the live stream directly. Confirmed working
        // (206 Partial Content, icy-br: 192, "Radiopetti Tamil FM").
        RadioStation(
            name = "Radio Petti",
            url = "https://radiopetti.in/radio.mp3",
            bitrate = 192,
            favicon = "https://radiopetti.in/logo_1.png",
            country = "India",
            tags = "tamil",
            codec = "MP3"
        )
    )
}
