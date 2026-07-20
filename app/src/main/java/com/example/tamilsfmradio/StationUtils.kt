package com.example.tamilsfmradio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StationUtils {

    private val reachabilityClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Some station names/brands we can't safely word-split (kept as a single token).
    private val KNOWN_WORDS = setOf(
        "tamil", "ragam", "raagam", "radio", "fm", "vaanam", "vanam", "thayagam", "chellam",
        "sivan", "swiss", "lanka", "sri", "muthamil", "rewind", "amudham", "yugam", "jei",
        "klang", "hd", "world", "live", "murasu", "voice", "global", "desi", "geetham",
        "paadal", "paadu", "kadhal", "sangeetham", "cool", "active", "plus", "gold", "prime",
        "star", "network", "station", "eagle", "athavan", "paramankurichi", "malaysia",
        "india", "suriyan", "sooriyan", "ilamai", "ini", "then", "sun", "big", "old", "new",
        "hits", "classic", "super", "beats", "evergreen", "friends", "british", "indian",
        "kannadasan", "panpalai", "melodies", "tube", "kalai", "isai", "aruvi", "imai",
        "sweety", "etr", "thuli", "vaanoli", "vasantham", "devaprasannam", "trt", "oli",
        "tamizen", "kollywood", "south", "ss", "thaalam", "rocky", "num", "magizhchi",
        "ibc", "chat", "american", "german", "eye", "kummalam", "vanavil", "canada",
        "france", "australia", "switzerland", "vaigai", "vsvp", "tech", "ullasam",
        "thavakalai", "yet", "punnagai", "anow", "siragugal", "shakthi", "pirai",
        "geethavani", "lite", "rahman", "beat", "beats", "maestro"
    )

    private val ACRONYMS = setOf("fm", "hd", "hd1", "hd2", "hd3", "trt", "air", "msv", "ar")

    private const val MIN_BITRATE = 128

    /** Server-side query already asks for bitrateMin, this is just a safety net. */
    fun filterQuality(stations: List<RadioStation>): List<RadioStation> =
        stations.filter { it.bitrate >= MIN_BITRATE }

    /**
     * Two API entries can point at the exact same stream (mirrors/relays) under different
     * display names, or share a display name while pointing at different mirror servers.
     * Dedupe on both signals, keeping the higher-bitrate entry.
     */
    fun dedupe(stations: List<RadioStation>): List<RadioStation> {
        val byUrl = LinkedHashMap<String, RadioStation>()
        for (station in stations) {
            val key = station.url.trimEnd(';').trim().lowercase()
            if (key.isEmpty()) continue
            val existing = byUrl[key]
            if (existing == null || station.bitrate > existing.bitrate) {
                byUrl[key] = station
            }
        }

        val byName = LinkedHashMap<String, RadioStation>()
        for (station in byUrl.values) {
            val key = normalizeNameKey(station.name)
            val existing = byName[key]
            if (existing == null || station.bitrate > existing.bitrate) {
                byName[key] = station
            }
        }
        return byName.values.toList()
    }

    private fun normalizeNameKey(name: String): String {
        var key = name.lowercase().filter { it.isLetterOrDigit() }
        for (suffix in listOf("hd3", "hd2", "hd1", "hd")) {
            if (key.length > suffix.length + 2 && key.endsWith(suffix)) {
                key = key.removeSuffix(suffix)
                break
            }
        }
        return key.trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    }

    /** Turns raw API slugs ("tamilragam", "muthamil-fm-88-9-hd3") into readable titles. */
    fun prettify(rawName: String): String {
        val rough = rawName.replace(Regex("[_.]+"), "-").trim()
        if (rough.isEmpty()) return rawName

        val rawTokens = rough.split(Regex("[\\s-]+")).filter { it.isNotBlank() }
        val tokens = mergeNumericTokens(rawTokens)

        return tokens.joinToString(" ") { token ->
            if (token.any { it.isDigit() }) {
                token
            } else {
                segmentWord(token.lowercase()).joinToString(" ") { formatWord(it) }
            }
        }
    }

    /** Joins adjacent pure-digit tokens ("88","9") into a decimal frequency ("88.9"). */
    private fun mergeNumericTokens(tokens: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val current = tokens[i]
            if (current.isNotEmpty() && current.all { it.isDigit() } &&
                i + 1 < tokens.size && tokens[i + 1].all { it.isDigit() }
            ) {
                result.add("${current}.${tokens[i + 1]}")
                i += 2
            } else {
                result.add(current)
                i += 1
            }
        }
        return result
    }

    private fun formatWord(word: String): String {
        val lower = word.lowercase()
        if (lower in ACRONYMS) return lower.uppercase()
        return lower.replaceFirstChar { it.uppercase() }
    }

    /** Greedy/DP dictionary word-break for concatenated slugs like "eaglefmhd". */
    private fun segmentWord(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        if (word.length <= 3) return listOf(word)

        val n = word.length
        val prev = IntArray(n + 1) { -1 }
        prev[0] = 0
        for (i in 1..n) {
            if (prev[i] != -1) continue
            for (j in 0 until i) {
                if (prev[j] != -1 && word.substring(j, i) in KNOWN_WORDS) {
                    prev[i] = j
                    break
                }
            }
        }
        if (prev[n] == -1) return listOf(word)

        val parts = ArrayDeque<String>()
        var idx = n
        while (idx > 0) {
            val start = prev[idx]
            parts.addFirst(word.substring(start, idx))
            idx = start
        }
        return parts.toList()
    }

    /**
     * Best-effort liveness check: drops stations that fail fast (dead host, 404, refused
     * connection). Anything that doesn't resolve within the overall budget is kept rather
     * than penalized, since a slow check isn't proof the stream is actually dead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun filterReachable(stations: List<RadioStation>): List<RadioStation> = coroutineScope {
        val semaphore = Semaphore(10)
        val pending = stations.map { station ->
            station to async(Dispatchers.IO) {
                semaphore.withPermit { isReachable(station.url) }
            }
        }

        withTimeoutOrNull(15_000) {
            pending.forEach { it.second.join() }
        }

        val result = pending.mapNotNull { (station, deferred) ->
            val reachable = if (deferred.isCompleted) deferred.getCompleted() else true
            if (reachable) station else null
        }
        pending.forEach { if (!it.second.isCompleted) it.second.cancel() }
        result
    }

    private fun isReachable(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-4096")
                .header("Icy-MetaData", "0")
                .build()
            reachabilityClient.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        } catch (e: Exception) {
            false
        }
    }
}
