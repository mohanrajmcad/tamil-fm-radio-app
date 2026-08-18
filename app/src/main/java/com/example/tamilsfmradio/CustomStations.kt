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
            codec = "MP3",
            isVerifiedLive = true
        ),

        // The following were sourced from a Tamil internet radio directory and individually
        // curl-verified (HTTP 200, audio content-type, icy-br header matching the bitrate
        // below) before being added - none of these came through the radio-browser.info API.

        RadioStation(
            name = "Shahimsha Online Radio",
            url = "https://radio.shahimsha.com/listen/shahimsha/radio.mp3",
            bitrate = 192,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Tamil Murasam FM",
            url = "https://tamilmurasam.radioca.st/live",
            bitrate = 160,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Tamil Panpalai Universe",
            url = "http://167.114.174.204:8028/anz",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        // icy-genre confirmed "Tamil Web Radio" at verification time.
        RadioStation(
            name = "Vijay FM",
            url = "http://136.243.44.41:8513/stream",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        // icy-genre confirmed "Tamil" at verification time (there are unrelated non-Tamil
        // "Star FM" stations elsewhere - this specific stream was checked, not just the name).
        RadioStation(
            name = "Star FM",
            url = "http://ec1.everestcast.host:1640/stream",
            bitrate = 320,
            country = "Sri Lanka",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "American Tamil Radio",
            url = "https://hello.citrus3.com:8188/stream",
            bitrate = 128,
            country = "United States",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),

        // Second verification pass, sourced from a wider search (a GitHub gist of Chennai/
        // diaspora FM stream mirrors, plus the rest of the same directory) - same curl
        // verification standard as above. Most of the big-name Chennai FM mirrors found this
        // way (Hello FM, Radio Mirchi, Big FM, Radio City) turned out to be dead IPs, so they
        // were left out rather than added unverified.

        RadioStation(
            name = "Golden Vanavil",
            url = "https://s7.yesstreaming.net:8060/stream",
            bitrate = 128,
            country = "France",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "90s Hits Radio VanavilFM",
            url = "https://s7.yesstreaming.net:8062/stream",
            bitrate = 128,
            country = "France",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Vanavilfm Online",
            url = "http://s7.yesstreaming.net:9000/stream",
            bitrate = 128,
            country = "France",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Pollachi FM",
            url = "http://104.200.16.182:8000/stream",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        // icy-genre confirmed "Tamil" at verification time.
        RadioStation(
            name = "Thaalam FM",
            url = "http://ec4.yesstreaming.net:1990/stream",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Tamil Katerumbu FM",
            url = "https://tamilkaterumbufm-prabak78.radioca.st/stream",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        // icy-genre confirmed "Tamil Gospel" at verification time - Christian content, not
        // general-format radio, but genuinely Tamil-language.
        RadioStation(
            name = "Theophony Tamil Christian Radio",
            url = "http://mediatechnica.com:8004/stream.mp3",
            bitrate = 128,
            country = "India",
            tags = "tamil,christian",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Akaram Radio",
            url = "https://globalwebmusic.com/akaram.mp3",
            bitrate = 128,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        // Named with "(UK)" to avoid colliding with the API-imported "Lanka Sri FM" (Sri
        // Lanka feed) under dedupe's normalized name key - same brand, distinct regional feed.
        RadioStation(
            name = "Lankasri FM (UK)",
            url = "http://media2.lankasri.fm/;stream.mp3",
            bitrate = 128,
            country = "United Kingdom",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),

        // Third pass: official All India Radio (Akashvani) Tamil Nadu stations, in response
        // to a request for known/official names like Kodaikanal FM. AIR's HLS streams don't
        // send an icy-br header, so bitrate here is measured directly (segment byte size /
        // segment duration from the .ts playlist) rather than read off a header - still a
        // real verification, just a different method than the icecast stations above.
        // AIR Kodaikanal itself is currently dead on AIR's own CDN (404) - not included.
        // AIR Chennai and AIR Akashvani were reachable but only serve 32-79kbps variants,
        // under this app's 128kbps quality floor - left out rather than lowering the bar.

        RadioStation(
            name = "AIR Tiruchirappalli PC",
            url = "https://radio.wavespb.com/live/340834aaac8e1081/340834aaac8e1081.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Tiruchirappalli FM",
            url = "https://radio.wavespb.com/live/bd8f073da8cde368/bd8f073da8cde368.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Madurai PC",
            url = "https://radio.wavespb.com/live/f910c4cf0415b953/f910c4cf0415b953.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Madurai FM",
            url = "https://radio.wavespb.com/live/5c28fdbb318c1f13/5c28fdbb318c1f13.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Coimbatore",
            url = "https://radio.wavespb.com/live/a6eec8ec7d6ee49d/a6eec8ec7d6ee49d.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Dharmapuri",
            url = "https://radio.wavespb.com/live/36378083c1be092b/36378083c1be092b.m3u8",
            bitrate = 192,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Nagercoil Kumari FM",
            url = "https://radio.wavespb.com/live/10c64309264eb009/10c64309264eb009.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Ooty",
            url = "https://radio.wavespb.com/live/e2ac3971fdada7d1/e2ac3971fdada7d1.m3u8",
            bitrate = 192,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "AIR Tirunelveli",
            url = "https://radio.wavespb.com/live/ee7f2a29ab3b6bf9/ee7f2a29ab3b6bf9.m3u8",
            bitrate = 256,
            country = "India",
            tags = "tamil",
            codec = "AAC",
            isVerifiedLive = true
        ),

        // Fourth pass: major Sri Lankan Tamil broadcasters, found via a Sri Lanka radio
        // directory rather than the Tamil-specific ones used above (those turned up mostly
        // dead links this round - Chennai FM mirrors, several rcast.net entries, and an AIR
        // Puducherry stream all confirmed dead; Singapore's Oli 96.8FM is geo-blocked outside
        // Singapore so left out despite resolving).

        // Sri Lanka Broadcasting Corporation's own national Tamil service - icy-name
        // confirmed "Tamil National" at verification time.
        RadioStation(
            name = "SLBC Tamil National Service",
            url = "http://220.247.227.6:8000/Tnsstream",
            bitrate = 128,
            country = "Sri Lanka",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Thendral FM",
            url = "http://220.247.227.20:8000/Threndralstream",
            bitrate = 128,
            country = "Sri Lanka",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        // Sri Lanka Rupavahini's Tamil-language channel.
        RadioStation(
            name = "Vasantham FM",
            url = "https://cp12.serverse.com/proxy/vasanthamfm?mp=/stream",
            bitrate = 128,
            country = "Sri Lanka",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),

        // User-supplied - radiobeat.in, DJ Muruga's Tamil radio network (Chennai). Both
        // curl-verified (audio/mpeg, icy-br 192) before adding.
        RadioStation(
            name = "Radio Beat",
            url = "https://live.djmuruga.com/listen/radio_beat/radio.mp3",
            bitrate = 192,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        ),
        RadioStation(
            name = "Radio Beat Love",
            url = "https://live.djmuruga.com/listen/radio_beat_love/radio.mp3",
            bitrate = 192,
            country = "India",
            tags = "tamil",
            codec = "MP3",
            isVerifiedLive = true
        )
    )
}
