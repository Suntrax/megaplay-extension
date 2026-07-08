package com.blissless.megaplay

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * MegaPlay.buzz + allanime scraper for the AnimeClient / Tensei extension system.
 *
 * Two public entry points (called by ScraperProvider depending on query params):
 *
 *   1. listEpisodes(animeName, anilistId)
 *      Returns the episode list with per-episode available langs.
 *      Uses allanime (mkissa.to's data backend) — 2 HTTP calls total.
 *
 *   2. retrieveStream(anilistId, episode, lang)
 *      Returns the m3u8 URL for the requested episode × lang, PLUS the
 *      other lang's stream (sub + dub both fetched, requested one first).
 *      Uses megaplay.buzz directly — 4 HTTP calls total (2 per lang).
 */
object MegaPlayScraper {

    private const val TAG = "MegaPlay/Scraper"

    private const val ALLANIME_API = "https://api.allanime.day/api"
    private const val MEGAPLAY_BASE = "https://megaplay.buzz"

    /**
     * Spoofed referrer. megaplay.buzz refuses direct embed loads (returns
     * "Error - MegaPlay") unless the Referer header is set to one of the
     * host sites that pay for the embed. mkissa.to is one such host.
     */
    private const val SPOOFED_REFERER = "https://mkissa.to/"

    /**
     * Persisted-query hashes that allanime's frontend ships with.
     * Public identifiers, not authentication tokens.
     */
    private const val HASH_SEARCH =
        "a24c500a1b765c68ae1d8dd85174931f661c71369c89b92b88b75a725afc471c"
    private const val HASH_DETAIL =
        "5c28dfdef2a7db7acfdb55d215802d4c51e32dc0d91b10e627a2c22df9e41c2b"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** The two langs we fetch streams for. */
    private val STREAM_LANGS = listOf("sub", "dub")

    // =========================================================================
    // Public function 1 — listEpisodes
    // =========================================================================

    /**
     * Return the available episodes for the show, with per-episode langs.
     *
     * Uses allanime's `availableEpisodesDetail` field — the same data
     * mkissa.to uses to render its episode picker.
     *
     * @return A map that ScraperProvider serialises to:
     *         {"episodes": [{"number": "1", "langs": ["sub","dub"]}, ...]}
     *         or {"error": "..."} on failure.
     */
    fun listEpisodes(animeName: String?, anilistId: String?): Map<String, Any> {
        Log.d(TAG, "listEpisodes: animeName=$animeName anilistId=$anilistId")

        if (animeName.isNullOrBlank() && anilistId.isNullOrBlank()) {
            return mapOf("error" to "No anime name or AniList ID provided.")
        }

        val query = animeName?.takeIf { it.isNotBlank() } ?: run {
            return mapOf("error" to "Anime name is required for episode search.")
        }

        val allanimeShowId: String = try {
            searchAllAnime(query, anilistId)
        } catch (e: Exception) {
            return mapOf("error" to "allanime search failed: ${e.message}")
        } ?: return mapOf("error" to "No allanime match for '$query'.")

        Log.d(TAG, "allanime show id = $allanimeShowId")

        val episodesByLang: Map<String, List<String>> = try {
            fetchAvailableEpisodes(allanimeShowId)
        } catch (e: Exception) {
            return mapOf("error" to "allanime episode-list fetch failed: ${e.message}")
        }

        if (episodesByLang.isEmpty()) {
            return mapOf("error" to "No episodes found on allanime for '$query'.")
        }

        Log.d(TAG, "episodes by lang: ${episodesByLang.mapValues { it.value.size }}")

        val allEpisodeNumbers = mutableSetOf<String>()
        for (eps in episodesByLang.values) {
            allEpisodeNumbers.addAll(eps)
        }

        val sortedEpisodes = allEpisodeNumbers.sortedWith(compareBy(
            { it.toIntOrNull() != null },
            { it.toIntOrNull() ?: 0 }
        ))

        val episodesList = sortedEpisodes.map { epNum ->
            val langs = episodesByLang.filter { (_, eps) -> eps.contains(epNum) }
                .keys
                .filter { it == "sub" || it == "dub" }
                .sorted()
            mapOf("number" to epNum, "langs" to langs)
        }

        return mapOf("episodes" to episodesList)
    }

    // =========================================================================
    // Public function 2 — retrieveStream
    // =========================================================================

    /**
     * Return the m3u8 stream for a single episode, fetching BOTH sub and dub.
     *
     * The user-requested lang is returned first (and also duplicated into the
     * top-level `url`/`headers`/etc. fields for backwards compatibility).
     * The other lang is returned second in the `streams` array. If the other
     * lang fails to fetch (e.g. dub not available for this episode), it is
     * simply omitted — the requested lang's stream is always returned.
     *
     * Why fetch both? So the player can offer a "switch audio" toggle without
     * having to make a second round-trip to the extension. Costs 2 extra HTTP
     * calls per stream request (4 total instead of 2), but saves a full
     * ContentProvider query + UI round-trip when the user switches langs.
     *
     * @return A map with:
     *           {"url": "...",            // requested lang (backwards compat)
     *            "headers": {...},
     *            "url_with_headers": "...",
     *            "subtitles": [...],
     *            "streams": [             // both langs, requested first
     *              {"lang": "sub", "default": true,  "url": "...", "headers": {...},
     *               "url_with_headers": "...", "subtitles": [...]},
     *              {"lang": "dub", "default": false, "url": "...", "headers": {...},
     *               "url_with_headers": "...", "subtitles": [...]}
     *            ]}
     *         or {"error": "..."} on failure (only if the REQUESTED lang fails).
     */
    fun retrieveStream(
        @Suppress("UNUSED_PARAMETER") context: Context,
        anilistId: String?,
        episode: String,
        lang: String,
    ): Map<String, Any> {
        Log.d(TAG, "retrieveStream: anilistId=$anilistId episode=$episode lang=$lang")

        if (anilistId.isNullOrBlank()) {
            return mapOf("error" to "AniList ID is required for stream retrieval.")
        }
        val aniId = anilistId.toIntOrNull()
            ?: return mapOf("error" to "Invalid AniList ID: '$anilistId'.")

        val requestedLang = if (lang in STREAM_LANGS) lang else "sub"
        val otherLang = STREAM_LANGS.first { it != requestedLang }

        // Step 1: fetch the requested lang (must succeed — otherwise error)
        val requestedStream = fetchSingleStream(aniId, episode, requestedLang)
            ?: return mapOf(
                "error" to "Episode $episode ($requestedLang) not found on megaplay."
            )

        Log.d(TAG, "requested ($requestedLang): ok")

        // Step 2: fetch the other lang (best-effort — failure is OK)
        val otherStream = try {
            fetchSingleStream(aniId, episode, otherLang)?.also {
                Log.d(TAG, "other ($otherLang): ok")
            }
        } catch (e: Exception) {
            Log.w(TAG, "other ($otherLang) failed (non-fatal): ${e.message}")
            null
        }

        // Step 3: build the streams array — requested lang first
        val streams = mutableListOf<Map<String, Any>>()
        streams.add(buildStreamEntry(requestedLang, requestedStream, isDefault = true))
        if (otherStream != null) {
            streams.add(buildStreamEntry(otherLang, otherStream, isDefault = false))
        }

        // Step 4: top-level fields mirror the requested lang (backwards compat)
        return mapOf(
            "url" to requestedStream.getValue("url"),
            "headers" to requestedStream.getValue("headers"),
            "url_with_headers" to requestedStream.getValue("url_with_headers"),
            "subtitles" to requestedStream.getValue("subtitles"),
            "streams" to streams,
        )
    }

    /**
     * Fetch a single lang's stream from megaplay. Returns null if the episode
     * doesn't exist for this lang (megaplay returns an "Error - MegaPlay" page).
     *
     * @return A map with keys: url, headers, url_with_headers, subtitles
     *         (all four always present on success).
     */
    private fun fetchSingleStream(
        anilistId: Int,
        episode: String,
        lang: String,
    ): Map<String, Any>? {
        // Step 1: GET the megaplay embed page → extract data-id
        val embedUrl = "$MEGAPLAY_BASE/stream/ani/$anilistId/$episode/$lang"
        val html = httpGet(embedUrl, SPOOFED_REFERER)

        val dataIdMatch = Regex("""data-id="(\d+)"""").find(html) ?: run {
            Log.d(TAG, "no data-id for $lang (episode not available in this lang)")
            return null
        }
        val dataId = dataIdMatch.groupValues[1]

        // Step 2: GET getSourcesNew → JSON with m3u8 URL + tracks array
        val srcUrl = "$MEGAPLAY_BASE/stream/getSourcesNew?id=$dataId&id=$dataId"
        val srcJson = JSONObject(httpGet(srcUrl, embedUrl, "XMLHttpRequest"))

        val fileUrl = srcJson.optJSONObject("sources")?.optString("file", "")
        if (fileUrl.isNullOrEmpty() ||
            (".m3u8" !in fileUrl && ".mp4" !in fileUrl)
        ) {
            Log.w(TAG, "no file URL in megaplay response for $lang")
            return null
        }

        Log.d(TAG, "$lang m3u8 = $fileUrl")

        val headers = mapOf(
            "Referer" to "https://megaplay.buzz/",
            "User-Agent" to UA,
        )
        val urlWithHeaders = buildPipeUrl(fileUrl, headers)
        val subtitles = parseSubtitleTracks(srcJson.optJSONArray("tracks"))

        return mapOf(
            "url" to fileUrl,
            "headers" to headers,
            "url_with_headers" to urlWithHeaders,
            "subtitles" to subtitles,
        )
    }

    /**
     * Wrap a single-lang stream result into the `streams` array entry shape,
     * adding `lang` and `default` fields.
     */
    private fun buildStreamEntry(
        lang: String,
        stream: Map<String, Any>,
        isDefault: Boolean,
    ): Map<String, Any> {
        return mapOf(
            "lang" to lang,
            "default" to isDefault,
            "url" to stream.getValue("url"),
            "headers" to stream.getValue("headers"),
            "url_with_headers" to stream.getValue("url_with_headers"),
            "subtitles" to stream.getValue("subtitles"),
        )
    }

    // =========================================================================
    // Subtitle parsing
    // =========================================================================

    /**
     * Parse megaplay's `tracks` JSON array into a list of subtitle maps.
     *
     * Each output entry has:
     *   - label:    Human-readable name (e.g. "English")
     *   - language: ISO 639-1 code (e.g. "en") — parsed from the label
     *   - url:      VTT file URL
     *   - default:  true if the player should auto-enable this track
     *
     * Skips tracks that aren't `kind=captions` (e.g. thumbnails).
     */
    private fun parseSubtitleTracks(tracks: org.json.JSONArray?): List<Map<String, Any>> {
        if (tracks == null) return emptyList()
        val result = mutableListOf<Map<String, Any>>()
        for (i in 0 until tracks.length()) {
            val track = tracks.optJSONObject(i) ?: continue
            val kind = track.optString("kind", "")
            if (kind != "captions") continue

            val label = track.optString("label", "Unknown")
            val file = track.optString("file", "")
            if (file.isEmpty()) continue

            val isDefault = track.optBoolean("default", false)
            val language = labelToLanguageCode(label)

            result.add(mapOf(
                "label" to label,
                "language" to language,
                "url" to file,
                "default" to isDefault,
            ))
        }
        return result
    }

    /**
     * Convert a megaplay subtitle label like "English" or "Spanish - Spanish(Latin_America)"
     * into a 2-letter ISO 639-1 code. Falls back to "und" (undefined).
     */
    private fun labelToLanguageCode(label: String): String {
        val firstWord = label.substringBefore(" ").lowercase().trim()
        return when (firstWord) {
            "arabic" -> "ar"
            "english" -> "en"
            "french" -> "fr"
            "german" -> "de"
            "italian" -> "it"
            "portuguese" -> "pt"
            "russian" -> "ru"
            "spanish" -> "es"
            "chinese" -> "zh"
            "japanese" -> "ja"
            "korean" -> "ko"
            "dutch" -> "nl"
            "polish" -> "pl"
            "turkish" -> "tr"
            "ukrainian" -> "uk"
            "hindi" -> "hi"
            "indonesian" -> "id"
            "thai" -> "th"
            "vietnamese" -> "vi"
            else -> "und"
        }
    }

    /**
     * Build a pipe-encoded URL with headers, e.g.:
     *   https://example.com/stream.m3u8|Referer=https%3A%2F%2Fmegaplay.buzz%2F&User-Agent=Mozilla%2F5.0...
     */
    private fun buildPipeUrl(url: String, headers: Map<String, String>): String {
        if (headers.isEmpty()) return url
        val encoded = headers.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        return "$url|$encoded"
    }

    // =========================================================================
    // allanime helpers
    // =========================================================================

    /**
     * Search allanime for shows matching `query`. Returns the show id.
     *
     * If `anilistId` is provided, scans all search results and picks the
     * one whose AniList ID matches (parsed from the thumbnail URL
     * `.../bx{anilistId}-{hash}.png`). This prevents picking the wrong
     * season — e.g. searching "Blue Lock" returns Season 2 first, but
     * if the user asked for AniList ID 137822 (Season 1), we match by ID.
     */
    private fun searchAllAnime(query: String, anilistId: String? = null): String? {
        val variables = JSONObject().apply {
            put("search", JSONObject().apply { put("query", query) })
            put("limit", 26)
            put("page", 1)
            put("translationType", "sub")
        }
        val extensions = JSONObject().apply {
            put("persistedQuery", JSONObject().apply {
                put("version", 1)
                put("sha256Hash", HASH_SEARCH)
            })
        }
        val url = "$ALLANIME_API?variables=" +
                URLEncoder.encode(variables.toString(), "UTF-8") +
                "&extensions=" + URLEncoder.encode(extensions.toString(), "UTF-8")
        val resp = JSONObject(httpGet(url, SPOOFED_REFERER))
        val edges = resp.getJSONObject("data").getJSONObject("shows").getJSONArray("edges")
        if (edges.length() == 0) return null

        if (!anilistId.isNullOrBlank()) {
            val targetId = anilistId.trim()
            val anilistIdPattern = Regex("""/bx(\d+)-""")
            for (i in 0 until edges.length()) {
                val edge = edges.getJSONObject(i)
                val thumbnail = edge.optString("thumbnail", "")
                val match = anilistIdPattern.find(thumbnail)
                if (match != null && match.groupValues[1] == targetId) {
                    val showId = edge.getString("_id")
                    val showName = edge.optString("name", "?")
                    Log.d(TAG, "matched anilistId=$targetId -> allanime _id=$showId ($showName)")
                    return showId
                }
            }
            for (i in 0 until edges.length()) {
                val edge = edges.getJSONObject(i)
                val englishName = edge.optString("englishName", "").lowercase()
                val nativeName = edge.optString("nativeName", "").lowercase()
                if (englishName == query.lowercase() || nativeName == query.lowercase()) {
                    val showId = edge.getString("_id")
                    val showName = edge.optString("name", "?")
                    Log.d(TAG, "fallback name match -> allanime _id=$showId ($showName)")
                    return showId
                }
            }
            Log.w(TAG, "no anilistId match for $targetId in ${edges.length()} results; using first")
        }

        return edges.getJSONObject(0).getString("_id")
    }

    /** Fetch availableEpisodesDetail. Returns {"sub": [...], "dub": [...], "raw": [...]}. */
    private fun fetchAvailableEpisodes(showId: String): Map<String, List<String>> {
        val variables = JSONObject().apply { put("_id", showId) }
        val extensions = JSONObject().apply {
            put("persistedQuery", JSONObject().apply {
                put("version", 1)
                put("sha256Hash", HASH_DETAIL)
            })
        }
        val url = "$ALLANIME_API?variables=" +
                URLEncoder.encode(variables.toString(), "UTF-8") +
                "&extensions=" + URLEncoder.encode(extensions.toString(), "UTF-8")
        val resp = JSONObject(httpGet(url, SPOOFED_REFERER))
        val show = resp.getJSONObject("data").getJSONObject("show")
        val ed = show.optJSONObject("availableEpisodesDetail") ?: return emptyMap()

        val result = mutableMapOf<String, List<String>>()
        for (lang in arrayOf("sub", "dub", "raw")) {
            val arr = ed.optJSONArray(lang) ?: continue
            val eps = (0 until arr.length()).map { arr.getString(it) }
            if (eps.isNotEmpty()) result[lang] = eps
        }
        return result
    }

    // =========================================================================
    // HTTP helper (HttpURLConnection only — no OkHttp, per Tensei rules)
    // =========================================================================

    private fun httpGet(
        urlStr: String,
        referer: String? = null,
        xRequestedWith: String? = null,
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*;q=0.8")
            referer?.let { setRequestProperty("Referer", it) }
            xRequestedWith?.let { setRequestProperty("X-Requested-With", it) }
        }
        try {
            val code = conn.responseCode
            if (code in 200..299) {
                return conn.inputStream.bufferedReader().use { it.readText() }
            }
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw IOException("HTTP $code for $urlStr${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
        } finally {
            conn.disconnect()
        }
    }
}
