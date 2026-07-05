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
 *      Returns the m3u8 URL for one specific episode × lang.
 *      Uses megaplay.buzz directly — 2 HTTP calls total.
 *
 * Why split?
 *   - Listing episodes is cheap (1 GraphQL search + 1 GraphQL detail).
 *   - Fetching an m3u8 is also cheap (2 HTTP calls), but doing it for every
 *     episode upfront would be 2×N calls. Instead, we only fetch the m3u8
 *     for the episode the user actually selects.
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

        // Search allanime for the show (needs a text query — anilistId alone
        // isn't enough for allanime's search API).
        val query = animeName?.takeIf { it.isNotBlank() } ?: run {
            return mapOf("error" to "Anime name is required for episode search.")
        }

        val allanimeShowId: String = try {
            searchAllAnime(query, anilistId)
        } catch (e: Exception) {
            return mapOf("error" to "allanime search failed: ${e.message}")
        } ?: return mapOf("error" to "No allanime match for '$query'.")

        Log.d(TAG, "allanime show id = $allanimeShowId")

        // Fetch availableEpisodesDetail
        val episodesByLang: Map<String, List<String>> = try {
            fetchAvailableEpisodes(allanimeShowId)
        } catch (e: Exception) {
            return mapOf("error" to "allanime episode-list fetch failed: ${e.message}")
        }

        if (episodesByLang.isEmpty()) {
            return mapOf("error" to "No episodes found on allanime for '$query'.")
        }

        Log.d(TAG, "episodes by lang: ${episodesByLang.mapValues { it.value.size }}")

        // Build a per-episode list with available langs.
        // Collect every unique episode number across all langs, then for each
        // episode, list which langs have it.
        val allEpisodeNumbers = mutableSetOf<String>()
        for (eps in episodesByLang.values) {
            allEpisodeNumbers.addAll(eps)
        }

        // Sort numerically where possible
        val sortedEpisodes = allEpisodeNumbers.sortedWith(compareBy(
            { it.toIntOrNull() != null },
            { it.toIntOrNull() ?: 0 }
        ))

        val episodesList = sortedEpisodes.map { epNum ->
            val langs = episodesByLang.filter { (_, eps) -> eps.contains(epNum) }
                .keys
                .filter { it == "sub" || it == "dub" }  // skip "raw"
                .sorted()
            mapOf("number" to epNum, "langs" to langs)
        }

        return mapOf("episodes" to episodesList)
    }

    // =========================================================================
    // Public function 2 — retrieveStream
    // =========================================================================

    /**
     * Return the m3u8 URL for a single episode × lang via megaplay.buzz.
     *
     * mkissa.to embeds megaplay as one of its source providers, so the URL
     * megaplay returns IS one of mkissa's source URLs. We use megaplay
     * directly because it works via plain HTTP (no Cloudflare, no WebView).
     *
     * The stream CDN (nekostream.site) requires a `Referer: https://megaplay.buzz/`
     * header on every request (master m3u8, sub-playlist, AND segment files).
     * Without it, the CDN returns HTTP 403. We return the required headers
     * alongside the URL so the player app can attach them.
     *
     * Megaplay also returns a `tracks` array of soft-subtitle VTT files
     * (one per language). These are NOT burned into the video — the player
     * renders them as a separate overlay. We pass them through as a
     * `subtitles` array so the player can offer language switching.
     *
     * The subtitle files are hosted on a different CDN (lostproject.club)
     * but share the same hash path as the video, so they require the same
     * `Referer: https://megaplay.buzz/` header.
     *
     * @return A map with:
     *           {"url": "https://...m3u8",
     *            "headers": {"Referer": "...", "User-Agent": "..."},
     *            "url_with_headers": "https://...m3u8|Referer=...&User-Agent=...",
     *            "subtitles": [{"label": "English", "language": "en",
     *                           "url": "https://...vtt", "default": true}, ...]}
     *         or {"error": "..."} on failure.
     *
     * The `url_with_headers` field uses the pipe-encoded format that VLC,
     * mpv, Kodi, and similar players accept on the command line / clipboard.
     * ExoPlayer doesn't parse this format — it uses the `headers` map instead.
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

        // Step 1: GET the megaplay embed page → extract data-id
        val embedUrl = "$MEGAPLAY_BASE/stream/ani/$aniId/$episode/$lang"
        val html = try {
            httpGet(embedUrl, SPOOFED_REFERER)
        } catch (e: Exception) {
            return mapOf("error" to "Megaplay embed fetch failed: ${e.message}")
        }

        val dataIdMatch = Regex("""data-id="(\d+)"""").find(html)
        if (dataIdMatch == null) {
            val titleMatch = Regex("""<title>([^<]+)</title>""").find(html)
            val title = titleMatch?.groupValues?.get(1) ?: "unknown"
            return mapOf("error" to "Episode $episode ($lang) not found on megaplay (page title: '$title').")
        }
        val dataId = dataIdMatch.groupValues[1]
        Log.d(TAG, "megaplay data-id = $dataId")

        // Step 2: GET getSourcesNew → JSON with m3u8 URL + tracks array
        val srcUrl = "$MEGAPLAY_BASE/stream/getSourcesNew?id=$dataId&id=$dataId"
        val srcJson = try {
            JSONObject(httpGet(srcUrl, embedUrl, "XMLHttpRequest"))
        } catch (e: Exception) {
            return mapOf("error" to "Megaplay getSourcesNew failed: ${e.message}")
        }

        val fileUrl = srcJson.optJSONObject("sources")?.optString("file", "")
        return if (!fileUrl.isNullOrEmpty() &&
            (".m3u8" in fileUrl || ".mp4" in fileUrl)) {
            Log.d(TAG, "m3u8 = $fileUrl")

            // The CDN requires Referer: https://megaplay.buzz/ on every request.
            // User-Agent isn't strictly required by the CDN, but we include a
            // browser UA anyway because some segment URLs redirect to ad
            // domains that may be pickier.
            val headers = mapOf(
                "Referer" to "https://megaplay.buzz/",
                "User-Agent" to UA,
            )

            // Pipe-encoded URL for players that support the format
            // (VLC, mpv, Kodi, ffplay, etc.). Header values are URL-encoded
            // so special characters don't break the format.
            val urlWithHeaders = buildPipeUrl(fileUrl, headers)

            // Parse the subtitle tracks array.
            // Megaplay returns tracks like:
            //   {"file": "https://.../subtitles/eng-2.vtt",
            //    "label": "English", "kind": "captions", "default": true}
            // We translate these into a simpler shape that the player app
            // can consume directly. The `language` field is a 2-letter ISO
            // 639-1 code parsed from the label (falls back to "und").
            val subtitles = parseSubtitleTracks(srcJson.optJSONArray("tracks"))

            Log.d(TAG, "subtitles: ${subtitles.size} track(s)")
            for (sub in subtitles) {
                Log.d(TAG, "  ${sub["label"]} (${sub["language"]}) default=${sub["default"]}")
            }

            mapOf(
                "url" to fileUrl,
                "headers" to headers,
                "url_with_headers" to urlWithHeaders,
                "subtitles" to subtitles,
            )
        } else {
            mapOf("error" to "No stream URL in megaplay response.")
        }
    }

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
            if (kind != "captions") continue  // skip non-subtitle tracks

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
     * into a 2-letter ISO 639-1 language code. Falls back to "und" (undefined).
     */
    private fun labelToLanguageCode(label: String): String {
        // Take the first word of the label, lowercase it, and match against
        // a small lookup table. Megaplay uses English-language labels.
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
     *
     * This format is understood by VLC, mpv, Kodi, and ffplay. ExoPlayer
     * doesn't parse it — use the `headers` map for ExoPlayer instead.
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
     * one whose AniList ID matches. allanime embeds the AniList ID in each
     * result's thumbnail URL as `bx{anilistId}-{hash}.png`, so we parse
     * that out. This prevents picking the wrong season — e.g. searching
     * "Blue Lock" on allanime returns Season 2 first, but if the user
     * asked for AniList ID 137822 (Season 1), we match by ID instead.
     *
     * If no AniList ID match is found, falls back to the first result.
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

        // If we have an anilistId, scan results for a match.
        // allanime thumbnail URLs look like:
        //   https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx137822-{hash}.png
        // The number after "bx" is the AniList ID.
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
            // No thumbnail-ID match — try matching by name as a secondary
            // signal. allanime's `englishName` field sometimes equals the
            // exact title we searched for (e.g. "Blue Lock" vs "Blue Lock
            // Season 2").
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

        // Fallback: first result
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
