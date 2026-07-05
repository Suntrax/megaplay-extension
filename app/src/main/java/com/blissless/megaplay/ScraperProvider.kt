package com.blissless.megaplay

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContentProvider that exposes the MegaPlay scraper to the AnimeClient app.
 *
 * Two query modes (selected by presence of `episode` + `lang` params):
 *
 * **Mode 1 — Episode list** (no `episode` / `lang` params):
 *   content://com.blissless.megaplay.provider/scrape?anime=<name>&anilistId=<id>
 *   → {"episodes": [{"number":"1","langs":["sub","dub"]}, ...]}
 *
 * **Mode 2 — Single stream** (with `episode` + `lang` params):
 *   content://com.blissless.megaplay.provider/scrape?anilistId=<id>&episode=1&lang=sub
 *   → {"url": "https://...master.m3u8"}
 *
 * Errors:
 *   {"error": "Description of what went wrong."}
 */
class ScraperProvider : ContentProvider() {

    companion object {
        private const val TAG = "MegaPlay/Provider"
        const val AUTHORITY = "com.blissless.megaplay.provider"
        const val PATH_SCRAPE = "scrape"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_SCRAPE")
        private const val CODE_SCRAPES = 1
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITY, PATH_SCRAPE, CODE_SCRAPES)
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        if (uriMatcher.match(uri) != CODE_SCRAPES) return null

        val animeName = uri.getQueryParameter("anime")
        val anilistId = uri.getQueryParameter("anilistId")
        val episode = uri.getQueryParameter("episode")
        val lang = uri.getQueryParameter("lang")

        val cursor = MatrixCursor(arrayOf("data"))

        try {
            // Route between the two modes
            val result: Map<String, Any> = if (!episode.isNullOrBlank() && !lang.isNullOrBlank()) {
                // Mode 2: fetch a single m3u8
                Log.d(TAG, "mode=stream anilistId=$anilistId ep=$episode lang=$lang")
                MegaPlayScraper.retrieveStream(context!!, anilistId, episode, lang)
            } else {
                // Mode 1: list episodes
                Log.d(TAG, "mode=list anime=$animeName anilistId=$anilistId")
                MegaPlayScraper.listEpisodes(animeName, anilistId)
            }

            val json = serialiseResult(result)
            cursor.addRow(arrayOf(json))
        } catch (e: Exception) {
            Log.e(TAG, "scrape failed", e)
            cursor.addRow(arrayOf("{\"error\":\"Scraping failed: ${e.message}\"}"))
        }
        return cursor
    }

    /**
     * Convert the result map to a JSON string.
     *
     * Handles four shapes:
     *   {"error": "..."}                       — error message
     *   {"url": "...", "headers": {...},
     *    "url_with_headers": "...",
     *    "subtitles": [...]}                   — single stream URL (Mode 2)
     *   {"episodes": [...]}                    — episode list (Mode 1)
     */
    @Suppress("UNCHECKED_CAST")
    private fun serialiseResult(result: Map<String, Any>): String {
        val obj = JSONObject()

        if (result.containsKey("error")) {
            obj.put("error", result["error"].toString())
            return obj.toString()
        }

        if (result.containsKey("url")) {
            obj.put("url", result["url"].toString())

            // Serialise the headers map (if present) as a JSON object
            val headers = result["headers"] as? Map<String, String>
            if (headers != null) {
                val headersObj = JSONObject()
                for ((k, v) in headers) {
                    headersObj.put(k, v)
                }
                obj.put("headers", headersObj)
            }

            // Include the pipe-encoded URL (if present) for players that
            // support the `url|Header=value&...` format.
            result["url_with_headers"]?.let { obj.put("url_with_headers", it.toString()) }

            // Serialise the subtitles array (if present).
            // Each entry: {"label": "...", "language": "...", "url": "...", "default": bool}
            val subtitles = result["subtitles"] as? List<Map<String, Any>>
            if (subtitles != null) {
                val subsArr = JSONArray()
                for (sub in subtitles) {
                    val subObj = JSONObject()
                    subObj.put("label", sub["label"].toString())
                    subObj.put("language", sub["language"].toString())
                    subObj.put("url", sub["url"].toString())
                    subObj.put("default", sub["default"] as Boolean)
                    subsArr.put(subObj)
                }
                obj.put("subtitles", subsArr)
            }

            return obj.toString()
        }

        if (result.containsKey("episodes")) {
            val arr = JSONArray()
            val episodes = result["episodes"] as List<*>
            for (ep in episodes) {
                val epMap = ep as Map<*, *>
                val epObj = JSONObject()
                epObj.put("number", epMap["number"].toString())
                val langsArr = JSONArray()
                for (lang in epMap["langs"] as List<*>) {
                    langsArr.put(lang.toString())
                }
                epObj.put("langs", langsArr)
                arr.put(epObj)
            }
            obj.put("episodes", arr)
            return obj.toString()
        }

        // Fallback: shouldn't happen, but return something useful
        obj.put("error", "Unexpected result shape: ${result.keys}")
        return obj.toString()
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
