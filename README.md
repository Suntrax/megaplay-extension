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

### Mode 2 — Stream

```
content://com.blissless.megaplay.provider/scrape?anilistId=137822&episode=1&lang=sub
```

Returns the m3u8 URL for the requested lang, **plus the other lang's stream
too** (so the player can offer a sub↔dub toggle without a second round-trip).
The requested lang is always first in the `streams` array and also duplicated
into the top-level fields for backwards compatibility.

```json
{
  "url": "https://9hjkrt.nekostream.site/.../master.m3u8",
  "headers": {
    "Referer": "https://megaplay.buzz/",
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ..."
  },
  "url_with_headers": "https://...master.m3u8|Referer=https%3A%2F%2Fmegaplay.buzz%2F&User-Agent=Mozilla%2F5.0...",
  "subtitles": [
    {"label": "English", "language": "en", "url": "https://.../eng-2.vtt", "default": true},
    {"label": "Spanish", "language": "es", "url": "https://.../spa-5.vtt", "default": false}
  ],
  "streams": [
    {
      "lang": "sub",
      "default": true,
      "url": "https://9hjkrt.nekostream.site/.../master.m3u8",
      "headers": {"Referer": "https://megaplay.buzz/", "User-Agent": "..."},
      "url_with_headers": "https://...master.m3u8|Referer=...",
      "subtitles": [
        {"label": "English", "language": "en", "url": "https://.../eng-2.vtt", "default": true},
        ...
      ]
    },
    {
      "lang": "dub",
      "default": false,
      "url": "https://9hjkrt.nekostream.site/.../master.m3u8",
      "headers": {"Referer": "https://megaplay.buzz/", "User-Agent": "..."},
      "url_with_headers": "https://...master.m3u8|Referer=...",
      "subtitles": [
        {"label": "English", "language": "en", "url": "https://.../eng-2.vtt", "default": true},
        ...
      ]
    }
  ]
}
```

**HTTP calls:** 4 (2 for the requested lang + 2 for the other lang).

### Why fetch both sub and dub?

So the player can offer a "switch audio" toggle without making a second
ContentProvider query. Costs 2 extra HTTP calls per stream request (4 total
instead of 2), but saves a full extension round-trip + UI refresh when the
user switches langs.

If the other lang isn't available for this episode (e.g. dub not yet
released), it's simply omitted from the `streams` array — the requested lang
is always returned. The requested lang only failing returns an error.

### Subtitles

Subtitles are **soft** — separate WebVTT files, NOT burned into the video.
The player renders them as an overlay and the user can toggle them on/off,
switch languages, or disable entirely.

The extension parses megaplay's `tracks` array and returns a `subtitles` array
with one entry per language. Each subtitle array is per-stream (sub and dub
can have different subtitle tracks):

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

The extension returns these fields to support different player types:

| Field                | Use case                                                     |
| -------------------- | ------------------------------------------------------------ |
| `url`                | The plain m3u8 URL of the requested lang (for ExoPlayer — pair with `headers`) |
| `headers`            | Map of required HTTP headers (for ExoPlayer's `setDefaultRequestProperties`) |
| `url_with_headers`   | Pipe-encoded URL `url\|Header=value&...` (for VLC, mpv, Kodi, ffplay) |
| `subtitles`          | Array of soft-subtitle VTT tracks for the requested lang      |
| `streams`            | Array of both sub + dub streams (requested lang first, `default: true`) |

### Errors

```json
{"error": "Description of what went wrong."}
```

## ExoPlayer integration

### Simple: play the requested lang only

Use the top-level `url` + `headers` + `subtitles` fields (these always point
to the user-requested lang):

```kotlin
val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(headers["User-Agent"])
    .setDefaultRequestProperties(headers)

val subtitleConfigs = subtitles.map { sub ->
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
        .setMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(sub.language)
        .setLabel(sub.label)
        .setSelectionFlags(if (sub.default) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

val mediaItem = MediaItem.Builder()
    .setUri(url)
    .setSubtitleConfigurations(subtitleConfigs)
    .build()

val player = ExoPlayer.Builder(context)
    .setMediaSourceFactory(
        DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
    )
    .build()
player.setMediaItem(mediaItem)
player.prepare()
```

### Advanced: sub↔dub switching via the `streams` array

To let the user switch between sub and dub without re-querying the extension,
iterate the `streams` array and build a `MediaItem` for the selected one. The
stream with `"default": true` is the one the user originally requested:

```kotlin
// Pick the stream to play (default = user's requested lang)
val stream = streams.first { it.default }
// ...or let the user pick: streams.first { it.lang == "dub" }

val dataSourceFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(stream.headers["User-Agent"])
    .setDefaultRequestProperties(stream.headers)

val subtitleConfigs = stream.subtitles.map { sub ->
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
        .setMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(sub.language)
        .setLabel(sub.label)
        .setSelectionFlags(if (sub.default) C.SELECTION_FLAG_DEFAULT else 0)
        .build()
}

val mediaItem = MediaItem.Builder()
    .setUri(stream.url)
    .setSubtitleConfigurations(subtitleConfigs)
    .build()

// When the user switches lang, just call player.setMediaItem(newMediaItem)
// — no need to re-query the extension.
```

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
specific episode the user selects (4 more calls — 2 for the requested lang +
2 for the other lang so the player can offer a sub↔dub toggle).

## Pipeline

### Episode list (Mode 1)

1. Search `api.allanime.day` for the show → allanime show id (matched by AniList ID).
2. Fetch `availableEpisodesDetail` → `{"sub": ["1","2",...], "dub": [...]}`.
3. Build a per-episode list: for each episode number, which langs have it.

### Stream URL (Mode 2)

1. Fetch the **requested lang**:
   - `GET https://megaplay.buzz/stream/ani/<anilistId>/<ep>/<lang>` → HTML with `data-id`
   - `GET https://megaplay.buzz/stream/getSourcesNew?id=<data_id>` → JSON with `sources.file` + `tracks`
2. Fetch the **other lang** (best-effort — failure is OK):
   - Same two requests with the other lang's URL
3. Build the `streams` array (requested lang first, `default: true`).
4. Attach the required `Referer` + `User-Agent` headers to every stream entry.

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
| Subtitle CDN (lostproject) | `https://megaplay.buzz/`  | Same hash path as video, requires same referrer |

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
