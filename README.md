# YTMD Lite

Lightweight YouTube Music desktop client built with **Tauri 2** + **Svelte 5**. Custom IDE/terminal-style UI, local `ytmusicapi` backend, HTML5 stream playback, Last.fm scrobbling, and Discord Rich Presence.

## Features

- Custom browse UI (Home, Explore, Library, Search, playlists, albums, artists, mixes)
- Google sign-in via a dedicated login webview (session cookies for the API)
- Local Flask API on `:9847` (adapted from Kodama’s `python-backend`)
- Player dock: queue, shuffle, repeat, like, audio output selection
- Last.fm now-playing / scrobble
- Discord Rich Presence (optional)
- Accent color picker
- Auto-update from [GitHub Releases](https://github.com/MUDesigns/ytmd-lite/releases)

## Requirements

### Develop

- Node 22+
- Rust (stable)
- Python 3.11+ (for the local API)
- Windows: WebView2 · macOS: WKWebView · Linux: WebKitGTK

### End users (installer)

The Windows installer bundles the UI. The music API runs from the bundled `python-backend` using a system Python install with dependencies from `python-backend/requirements.txt`, or a prebuilt `ytmd-backend` sidecar when present next to the app.

## Setup

```sh
git clone https://github.com/MUDesigns/ytmd-lite.git
cd ytmd-lite
npm install

python -m venv python-backend/.venv
# Windows:
python-backend\.venv\Scripts\activate
# macOS/Linux:
# source python-backend/.venv/bin/activate
pip install -r python-backend/requirements.txt
npm run playback:setup
```

Playback setup requires Node 22 or newer. It builds the pinned bgutil token
generator and stages it with Node under `src-tauri/resources`; restart the app's
backend after setup. Release builds run this step automatically. The generator
and Python provider versions must match (currently 1.3.1).

The player resolves the next queued song while the current song plays. Extraction
requests share a cache and in-progress work, with at most two extractions active.
Playback returns a resolution timeout after 25 seconds; an underlying extractor
may finish later, but retains its slot so repeated skips cannot create unlimited
background work. Each network operation has a shorter timeout, and new fallback
attempts stop after a 20-second budget. Browser-cookie scans are excluded from playback.

For latency diagnostics, frontend console entries tagged `[playback timing]`
separate URL resolution from audio startup. Backend `/debug/info` includes
`potAvailable`, `potServerRunning`, and `[stream timing]` logs for extraction and
first proxy bytes. Preparing a URL does not download the whole song.

### Last.fm (optional for scrobbling)

```sh
cp lastfm.secrets.json.example src-tauri/lastfm.secrets.json
# add api_key + secret from https://www.last.fm/api/account/create
```

## Develop

```sh
npm run tauri dev
```

On Windows, run development from a normal terminal; administrator access is not required.
For Discord Rich Presence, run Discord as the same Windows user without **Run as administrator**.

## Build

```sh
# optional: pack the Python API as a sidecar
python-backend\build_backend.bat

npm run tauri build
```

Signed updater artifacts require `TAURI_SIGNING_PRIVATE_KEY` (and optional password) in the environment. CI sets these from repository secrets.

### Automated builds

Every push to `main` builds signed Windows installers with the bundled Python backend and publishes an official [GitHub release](https://github.com/MUDesigns/ytmd-lite/releases). The app auto-updater discovers these releases through `latest.json`.

The [release workflow](https://github.com/MUDesigns/ytmd-lite/actions/workflows/release.yml) adds its run number to the patch component of the version in `src-tauri/tauri.conf.json` (for example, base `0.2.1` plus run `6` produces `0.2.7`). This gives each main build a newer embedded app version without committing version bumps. Keep the base version increasing when changing it. A `vX.Y.Z` tag builds that exact stable version; manual runs use the same automatic numbering as main pushes.

Releases remain drafts until installers, signatures, and `latest.json` have uploaded successfully. An older build finishing late does not replace a newer release as Latest.

## Auto-update

Release builds query:

`https://github.com/MUDesigns/ytmd-lite/releases/latest/download/latest.json`

On launch, a newer version is downloaded, verified with the embedded public key, installed, and the app relaunches.

Before installation, the app waits for its playback backend and token helper to
stop. Windows installers also stop helpers from the target installation directory,
so updates from older releases can replace files left locked by orphaned processes.
If backend shutdown fails, installation is deferred. Development builds skip updates.

## Layout

```
ytmd-lite/
  src/                 # Svelte UI
  src-tauri/           # Rust / Tauri shell
  python-backend/      # ytmusicapi Flask API (from Kodama)
  scripts/             # icon + tooling
  .github/workflows/   # release + updater
```

## Credits / references

YTMD Lite stands on the shoulders of these projects:

| Project | Role |
| --- | --- |
| [ytmdesktop/ytmdesktop](https://github.com/ytmdesktop/ytmdesktop) | Original YouTube Music Desktop App — Electron architecture, player UX, and the parent repo where this Lite experiment started |
| [KiyoshiTheDevil/Kodama](https://github.com/KiyoshiTheDevil/Kodama) | Tauri + custom UI + Python `ytmusicapi` backend pattern; `python-backend/server.py` is adapted from Kodama (AGPL-3.0) |
| [sigma67/ytmusicapi](https://github.com/sigma67/ytmusicapi) | Unofficial YouTube Music API used by the local backend |
| [tauri-apps/tauri](https://github.com/tauri-apps/tauri) | Desktop shell (webview + Rust) |
| [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) | Audio stream extraction for playback |

Brand assets and “Lite” chrome are original to this repository. YouTube and YouTube Music are trademarks of Google LLC; this project is not affiliated with Google.

## License

- Application shell (Svelte / Rust): **AGPL-3.0-only** (combined distribution with the network backend)
- `python-backend/` retains Kodama’s **AGPL-3.0** terms — see `python-backend/LICENSE.Kodama`
