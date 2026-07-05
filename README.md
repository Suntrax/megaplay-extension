# MegaPlay Extension

A scraper extension for the [AnimeClient](https://github.com/Suntrax/AnimeClient)
app that resolves anime episode stream URLs via **megaplay.buzz**, using
**api.allanime.day** (mkissa.to's data backend) for the episode list.

## Two-mode contract

The extension's `ContentProvider` supports two query modes, selected by the
presence of `episode` and `lang` parameters:

### Mode 1 — Episode list

```
content://com.blissless.megaplay.provider/scrape?anime=Blue Lock&anilistId=137822
```

Returns the available episodes with per-episode langs (no m3u8 URLs yet):

```json
{
  "episodes": [
    {"number": "1", "langs": ["sub", "dub"]},
    {"number": "2", "langs": ["sub", "dub"]},
    ...
    {"number": "24", "langs": ["sub", "dub"]}
  ]
}
```

**HTTP calls:** 2 (allanime search + allanime detail).

The allanime search matches the show by **AniList ID** (parsed from the
thumbnail URL `.../bx{anilistId}-{hash}.png`), so the correct season is always
picked — searching "Blue Lock" with `anilistId=137822` returns Season 1
(24 episodes), not Season 2 (14 episodes).

### Mode 2 — Single stream

```
content://com.blissless.megaplay.provider/scrape?anilistId=137822&episode=1&lang=sub
```

Returns the m3u8 URL **plus the required headers and soft-subtitle tracks**
for playback:

```json
{
  "url": "https://9hjkrt.nekostream.site/.../master.m3u8",
  "headers": {
    "Referer": "https://megaplay.buzz/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ..."
  },
  "url_with_headers": "https://...master.m3u8|Referer=https%3A%2F%2Fmegaplay.buzz%2F&User-Agent=Mozilla%2F5.0...",
  "subtitles": [
    {"label": "Arabic",                          "language": "ar", "url": "https://.../ara-6.vtt",  "default": false},
    {"label": "English",                         "language": "en", "url": "https://.../eng-2.vtt",  "default": true },
    {"label": "French",                          "language": "fr", "url": "https://.../fre-7.vtt",  "default": false},
    {"label": "German",                          "language": "de", "url": "https://.../ger-8.vtt",  "default": false},
    {"label": "Italian",                         "language": "it", "url": "https://.../ita-9.vtt",  "default": false},
    {"label": "Portuguese - Portuguese(Brazil)", "language": "pt", "url": "https://.../por-3.vtt",  "default": false},
    {"label": "Russian",                         "language": "ru", "url": "https://.../rus-10.vtt", "default": false},
    {"label": "Spanish",                         "language": "es", "url": "https://.../spa-5.vtt",  "default": false},
    {"label": "Spanish - Spanish(Latin_America)","language": "es", "url": "https://.../spa-4.vtt",  "default": false}
  ]
}
```

**HTTP calls:** 2 (megaplay embed page + megaplay getSourcesNew JSON).

### Subtitles

Subtitles are **soft** — separate WebVTT files, NOT burned into the video.
The player renders them as an overlay and the user can toggle them on/off,
switch languages, or disable entirely.

The extension parses megaplay's `tracks` array and returns a `subtitles` array
with one entry per language:

| Field      | Type    | Description                                                  |
| ---------- | ------- | ------------------------------------------------------------ |
| `label`    | String  | Human-readable name (e.g. `"English"`)                       |
| `language` | String  | ISO 639-1 code (e.g. `"en"`); `"und"` if unknown             |
| `url`      | String  | VTT file URL                                                 |
| `default`  | Boolean | `true` if the player should auto-enable this track on first play |

The subtitle files are hosted on a different CDN (`lostproject.club`) but
share the same hash path as the video, so they require the same
`Referer: https://megaplay.buzz/` header.

### Why headers are included

The stream CDN (`nekostream.site`) requires `Referer: https://megaplay.buzz/`
on **every** request — master m3u8, sub-playlist, AND segment files. Without
it, the CDN returns HTTP 403. (Note: `Referer: https://mkissa.to/` is rejected
by the CDN — only `megaplay.buzz` works.)

The extension returns three fields to support different player types:

| Field                | Use case                                                     |
| -------------------- | ------------------------------------------------------------ |
| `url`                | The plain m3u8 URL (for ExoPlayer — pair with `headers`)     |
| `headers`            | Map of required HTTP headers (for ExoPlayer's `setDefaultRequestProperties`) |
| `url_with_headers`   | Pipe-encoded URL `url\|Header=value&...` (for VLC, mpv, Kodi, ffplay) |
| `subtitles`          | Array of soft-subtitle VTT tracks (for ExoPlayer's `SubtitleConfiguration`) |

### Errors

```json
{"error": "Description of what went wrong."}
```

## ExoPlayer integration

Use the `url` + `headers` + `subtitles` fields with Media3. The subtitles need
their own `DataSource.Factory` (or you can reuse the same one — the headers
work for both video and subtitle CDNs since they share the same hash path):

```kotlin
// 1. Build a DataSource.Factory that injects the Referer on every request
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(headers["User-Agent"])
    .setDefaultRequestProperties(headers)

// 2. Build subtitle configurations from the `subtitles` array
val subtitleConfigs = subtitles.map { sub ->
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
        .setMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(sub.language)
        .setLabel(sub.label)
        .setSelectionFlags(
            if (sub.default) C.SELECTION_FLAG_DEFAULT else 0
        )
        .build()
}

// 3. Build the MediaItem with both the video URL and the subtitle tracks
val mediaItem = MediaItem.Builder()
    .setUri(url)
    .setSubtitleConfigurations(subtitleConfigs)
    .build()

// 4. Build the player with the DataSource attached
val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
    )
    .build()
player.setMediaItem(mediaItem)
player.prepare()
```

ExoPlayer will auto-enable the track marked `default: true` on first play, and
the user can switch languages via the player UI. The same `Referer` header is
applied to both video segment requests and subtitle file requests.

Or use OkHttp with an interceptor (Tensei main app uses this approach):

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("Referer", "https://megaplay.buzz/")
            .header("User-Agent", userAgent)
            .build())
    }
    .build()

val dataSourceFactory = OkHttpDataSource.Factory(client)
```

## Why split into two modes?

Fetching the m3u8 for every episode upfront would be 2×N HTTP calls (e.g. 48
for a 24-episode show with sub+dub). Instead, the extension returns the episode
list cheaply (2 calls), and the AnimeClient only requests the m3u8 for the
specific episode the user selects (2 more calls).

## Pipeline

### Episode list (Mode 1)

1. Search `api.allanime.day` for the show → allanime show id (matched by AniList ID).
2. Fetch `availableEpisodesDetail` → `{"sub": ["1","2",...], "dub": [...]}`.
3. Build a per-episode list: for each episode number, which langs have it.

### Stream URL (Mode 2)

1. `GET https://megaplay.buzz/stream/ani/<anilistId>/<ep>/<lang>` → HTML page
   with a `data-id="..."` attribute on `#megaplay-player`.
2. `GET https://megaplay.buzz/stream/getSourcesNew?id=<data_id>` → JSON with
   `sources.file` containing the m3u8 URL.
3. Attach the required `Referer` + `User-Agent` headers to the response.

mkissa.to embeds megaplay as one of its source providers, so the URL megaplay
returns IS one of mkissa's source URLs. We use megaplay directly because it
works via plain HTTP (no Cloudflare, no WebView).

## Spoofed referrer

Two different referrers are used, for two different purposes:

| Target                  | Referrer                     | Purpose                                    |
| ----------------------- | ---------------------------- | ------------------------------------------ |
| allanime API            | `https://mkissa.to/`         | allanime checks the referrer to identify the calling site |
| megaplay.buzz embed     | `https://mkissa.to/`         | megaplay checks the referrer to authorise the embed load |
| megaplay getSourcesNew  | (the megaplay embed URL)     | megaplay checks the referrer is its own page |
| Stream CDN (nekostream) | `https://megaplay.buzz/`     | CDN requires megaplay.buzz as referrer, returns 403 otherwise |

## Package info

| Field                     | Value                                              |
| ------------------------- | -------------------------------------------------- |
| Package                   | `com.blissless.megaplay`                           |
| Display name              | `Tensei: MegaPlay`                                 |
| ContentProvider authority | `com.blissless.megaplay.provider`                  |
| Beacon action             | `com.blissless.animeclient.EXTENSION_BEACON`       |

## Dependencies

None. Uses only Android built-in APIs (`HttpURLConnection`, `org.json`) per
the extension rules. APK size ~40-50KB.

## Files

```
app/src/main/
├── AndroidManifest.xml
├── java/com/blissless/megaplay/
│   ├── ExtensionBeaconReceiver.kt   # empty — exists only for discovery
│   ├── ScraperProvider.kt           # ContentProvider: routes between modes
│   └── MegaPlayScraper.kt           # listEpisodes() + retrieveStream()
└── keepRules/rules.keep             # R8 keep rules
```
