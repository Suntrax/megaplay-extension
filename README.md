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

### Mode 2 — Single stream

```
content://com.blissless.megaplay.provider/scrape?anilistId=137822&episode=1&lang=sub
```

Returns the m3u8 URL for the requested episode × lang:

```json
{
  "url": "https://9hjkrt.nekostream.site/.../master.m3u8"
}
```

**HTTP calls:** 2 (megaplay embed page + megaplay getSourcesNew JSON).

### Errors

```json
{"error": "Description of what went wrong."}
```

## Why split into two modes?

Fetching the m3u8 for every episode upfront would be 2×N HTTP calls (e.g. 48
for a 24-episode show with sub+dub). Instead, the extension returns the episode
list cheaply (2 calls), and the AnimeClient only requests the m3u8 for the
specific episode the user selects (2 more calls).

## Pipeline

### Episode list (Mode 1)

1. Search `api.allanime.day` for the show → allanime show id.
2. Fetch `availableEpisodesDetail` → `{"sub": ["1","2",...], "dub": [...]}`.
3. Build a per-episode list: for each episode number, which langs have it.

### Stream URL (Mode 2)

1. `GET https://megaplay.buzz/stream/ani/<anilistId>/<ep>/<lang>` → HTML page
   with a `data-id="..."` attribute on `#megaplay-player`.
2. `GET https://megaplay.buzz/stream/getSourcesNew?id=<data_id>` → JSON with
   `sources.file` containing the m3u8 URL.

mkissa.to embeds megaplay as one of its source providers, so the URL megaplay
returns IS one of mkissa's source URLs. We use megaplay directly because it
works via plain HTTP (no Cloudflare, no WebView).

## Spoofed referrer

megaplay.buzz refuses direct embed loads (returns "Error - MegaPlay") unless
the `Referer` header is set to one of the host sites that pay for the embed.
We spoof `https://mkissa.to/`.

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
