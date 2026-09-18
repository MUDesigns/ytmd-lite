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

- Node 20+
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
```

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

Every push to `main` builds signed Windows installers with the bundled Python backend and publishes a new [GitHub prerelease](https://github.com/MUDesigns/ytmd-lite/releases), tagged `main-<run number>`. Download the `.exe` or `.msi` installer from that build's assets.

The [release workflow](https://github.com/MUDesigns/ytmd-lite/actions/workflows/release.yml) can also be started manually. Pushing a `v*` tag publishes a stable release used by the auto-updater; main prereleases do not replace the stable update channel. Main builds retain the app version in the source configuration.

## Auto-update

Release builds query:

`https://github.com/MUDesigns/ytmd-lite/releases/latest/download/latest.json`

On launch, a newer version is downloaded, verified with the embedded public key, installed, and the app relaunches.

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
