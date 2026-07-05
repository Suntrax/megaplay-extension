# Tensei Extensions

Official template repository for creating background scraper extensions for the **Tensei Scraper** Android app.

## 📖 How it Works

Tensei extensions are headless Android apps (no UI) that run in the background. 
1. The Main App scans your phone for apps containing the `EXTENSION_BEACON` receiver.
2. When you search an anime, the Main App queries the extension's `ContentProvider`, passing both the `anime` (English name) and `anilistId`.
3. The extension scrapes its target website and returns a JSON string containing magnet links.

## 🛠️ Creating a New Extension

1. Click **"Use this template"** at the top of this repository to create a new repo for your extension.
2. Clone your new repo and open it in Android Studio.
3. In Android Studio, press `Ctrl+Shift+R` (or `Cmd+Shift+R` on Mac) to open **Replace in Path**.
   - Search for `com.blissless.tensei_extension_template` and replace it with your new package name (e.g., `com.blissless.seadex`).
   - Search for `TEMPLATE_NAME` and replace it with your extension's display name (e.g., `SeaDex`).
4. Move your Kotlin files into the new package folder structure (e.g., `com/blissless/seadex/`).
5. Place your release keystore at `app/release.jks` and update the passwords in `app/build.gradle.kts`.
6. Open `TemplateScraper.kt` (rename it if you like) and implement your scraping logic!

## 📦 Data Contract

The Main App sends two parameters to your `ContentProvider`:
- `anime`: The English and Romaji name of the anime (e.g., "Attack on Titan" and "Shingeki no Kyojin").
- `anilistId`: The Anilist ID (e.g., "137822").

Your scraper must return a JSON string in one of two formats:

**Format 1: Episode Map (e.g., [SubsPlease](https://github.com/Suntrax/subsplease-extension))**
```json
{
  "1": {
    "1080p": "magnet:?xt=urn:btih:...",
    "720p": "magnet:?xt=urn:btih:..."
  },
  "2": {
    "1080p": "magnet:?xt=urn:btih:..."
  }
}
```

**Format 2: Flat Magnet List (e.g., [SeaDex](https://github.com/Suntrax/seadex-extension))**
```json
[
  "magnet:?xt=urn:btih:...",
  "magnet:?xt=urn:btih:..."
]
```

If your scraper fails, return an error object:
```json
{
  "error": "Description of what went wrong."
}
```

## 🏗️ Building

Extensions are built to be as tiny as possible (~40KB). 
- Do not add any external dependencies (no OkHttp, no Jsoup, no Gson). Use Android's built-in `HttpURLConnection`, `WebView`, and `org.json`.
- R8 shrinking rules are stored in `app/src/main/keepRules/rules.keep`.
- Always build the **Release APK** (`./gradlew assembleRelease`) to ensure R8 shrinks the APK size.
