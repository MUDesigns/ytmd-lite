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
**ytmd-lite-android** artifact). On `main` and `vX.Y.Z` tags, `release.yml` calls it with the
release version and attaches `YTMD-Lite_<version>_android.apk` to the GitHub release before
the release is published.

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
- Google may occasionally reject sign-in inside embedded browsers; retrying usually works.
- Stream resolution runs without a PO token. If YouTube starts requiring one for the clients
  NewPipeExtractor uses, playback falls back to the backend's yt-dlp path, which is slower.
