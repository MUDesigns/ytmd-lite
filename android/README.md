# YTMD Lite for Android

Native Android port of YTMD Lite: **Kotlin + Jetpack Compose**, with the same dense
monospace IDE look as the desktop app (palette, accent picker, optional code theme,
terminal-style player).

## How it fits together

| Desktop (Tauri) | Android |
| --- | --- |
| Svelte UI (`src/`) | Compose UI (`app/src/main/java/.../ui`) recreating the same screens and styling |
| `python-backend/server.py` as a sidecar on `:9847` | **The same `server.py`**, run in-process by [Chaquopy](https://chaquo.com/chaquopy/) on a random loopback port (`app/src/main/python/ytmd_android.py`) |
| HTML5 audio + backend yt-dlp proxy | Media3 ExoPlayer in a `MediaSessionService` (background playback, notification + lock-screen controls); streams resolved on-device by [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), with the backend's yt-dlp proxy as fallback |
| Google login webview (`auth.rs`) | `LoginActivity` WebView → `/auth/cookie-login` |
| Last.fm (`lastfm.rs`) | `lastfm/LastFm.kt`, same browser-approval flow and credentials file |
| `ytmd-lite://` + share page links | Intent filters for `ytmd-lite://song|playlist/…` and `https://mudesigns.github.io/ytmd-lite/?song=|playlist=` |

The Python sources are not copied: Gradle reads them straight from `../python-backend`,
so backend fixes ship to both apps. The only Android-specific hook in `server.py` is the
`YTMD_DATA_DIR` environment variable, which points profiles and caches at app-private storage.

Because any app on a phone can connect to loopback ports, the embedded API only answers
requests carrying a per-launch random token (`X-YTMD-Token`), so other apps cannot use
your signed-in YouTube session.

The Android app does not include Discord Rich Presence, the mini player (the media
notification and lock screen replace it), audio-output selection, the auto-updater,
or saved listening sessions.

## Offline downloads

Long-press a song, album, playlist, or artist and choose **Download**, use the Download
button on a collection page, or **Download** on the Queue page. **Library → Downloads**
(also reachable from Settings and from load errors while offline) lists collections and
songs on the phone with progress, cancel, and retry.

Unlike the desktop app, Android does not use the backend for downloads: `playback/Downloads.kt`
runs a Media3 `DownloadManager` that saves tracks into a non-evicting `SimpleCache` under
`filesDir/downloads`, resolving `ytmd://<videoId>` through the same NewPipe path as playback
(`playback/YtmdDataSource.kt`). `YtmdDownloadService` is a `dataSync` foreground service with a
progress notification so downloads survive the app being backgrounded; they pause without a
network and continue when it returns. The player reads the download cache first (read-only),
then the 256 MB streaming cache, then the network. Which collections and pinned songs want each
track is kept in `filesDir/offline-library.json`; removing a playlist deletes only tracks nothing
else references. Thumbnails snap to two sizes (320 px and 1024 px) so views share cached files,
and downloads prefetch both into Coil's cache and `ArtworkProvider`, which now also backs the
media notification artwork. In Android Auto, **Library** starts with a **Downloads** folder and
still works without an account or a connection.

## Android Auto

`PlaybackService` is a Media3 `MediaLibraryService`, so Android Auto shows the app as a media source
(declared by `res/xml/automotive_app_desc.xml`). It uses the phone's sign-in, backend, and player.
The browse tree lives in `playback/LibraryTree.kt`:

- Four tabs: **Home**, **Library** (needs a Google account), **Explore** (the phone's Discover), and
  **Recent** (the last 50 tracks played on this device).
- Albums, playlists, artists, and moods open as folders. Picking a song queues the rest of its shelf or
  list after it; playlists and albums also have a **Shuffle** row and artists an **Artist radio** row.
- In-car search and voice ("play … on YTMD Lite"): the top search result decides what plays: a song
  plus its radio, an album/playlist, or an artist radio.
- Now Playing adds **Like** (signed-in accounts; synced with the phone UI through `data/Account.kt`) and
  an **Autoplay** toggle. These buttons also appear in the phone's media notification.
- Auto only loads browse artwork from `content://` URIs, so `playback/ArtworkProvider.kt` serves cached
  copies of YouTube thumbnails (Google image hosts only).

To try it without a car, install the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
and enable Android Auto's developer settings. Debug and sideloaded builds also need **Unknown sources**
turned on in those settings.

Android Automotive OS (cars with Google built in) is not supported.

## Build

Requirements:

- JDK 17
- Android SDK with platform 37 (`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`)
- Python **3.12** on the build machine (Chaquopy compiles the backend's bytecode with it)

```sh
cd android
# local.properties (not committed):
#   sdk.dir=/path/to/Android/Sdk
#   chaquopy.buildPython=/path/to/python3.12     # optional if `python3.12` / `py -3.12` is on PATH
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Optional Last.fm scrobbling uses the same gitignored `src-tauri/lastfm.secrets.json` as the
desktop build (see the main README). Without it, the Last.fm panel shows "not available".

Builds include `arm64-v8a` (phones) and `x86_64` (emulators).

## CI

[`.github/workflows/android.yml`](../.github/workflows/android.yml) builds the APK on pull requests
and branch pushes that touch `android/` or `python-backend/` (download it from the run's
**ytmd-lite-android** artifact). On `main`, `release.yml` first detects what changed: desktop
paths (`src/`, `src-tauri/`, …) build the Windows app, `android/` builds the APK, and
`python-backend/` builds both, since the APK bundles it. Both apps share one release version.
An Android-only change gets its own release containing just the APK, which is never marked
Latest (the desktop auto-updater reads the Latest release). `vX.Y.Z` tags, changes to
`release.yml`, and manual runs (with a target choice) build both.

Release APKs are signed when these repository secrets exist; otherwise CI builds a
debug-signed APK (named `…_android-debug.apk`), which cannot update a release install:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password (defaults to the keystore password) |

Create a keystore once with
`keytool -genkeypair -keystore release.jks -alias ytmd -keyalg RSA -keysize 2048 -validity 10000`
and keep it safe: Android only installs updates signed with the same key. `LASTFM_SECRETS_JSON`
is shared with the desktop build. Locally, the same `YTMD_KEYSTORE`, `YTMD_KEYSTORE_PASSWORD`,
`YTMD_KEY_ALIAS`, and `YTMD_KEY_PASSWORD` environment variables sign `./gradlew assembleRelease`.

## Notes

- First launch takes a few seconds while Python starts; the status line shows `API …` until ready.
- Google blocks sign-in from embedded WebViews ("This browser or app may not be secure"). `auth/BrowserIdentity.kt` makes the sign-in WebView present itself consistently as mobile Chrome (no `X-Requested-With` header, no WebView markers in the user agent or client hints, a `window.chrome` object). If Google changes its checks, start there.
- Stream resolution runs without a PO token. If YouTube starts requiring one for the clients
  NewPipeExtractor uses, playback falls back to the backend's yt-dlp path, which is slower.
