//! App configuration: Last.fm flags, window bounds, API credentials.

use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use tauri::Manager;

const CONFIG_FILE: &str = "config.json";
const SECRETS_FILE: &str = "lastfm.secrets.json";
const KEYRING_SERVICE: &str = "ytmd-lite";
const KEYRING_USER_SESSION: &str = "lastfm-session";
const KEYRING_USER_TOKEN: &str = "lastfm-token";

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WindowBounds {
    pub x: f64,
    pub y: f64,
    pub width: f64,
    pub height: f64,
}

impl Default for WindowBounds {
    fn default() -> Self {
        Self {
            x: 100.0,
            y: 100.0,
            width: 1280.0,
            height: 800.0,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    pub last_fm_enabled: bool,
    pub scrobble_percent: u32,
    #[serde(default)]
    pub last_fm_username: Option<String>,
    #[serde(default)]
    pub audio_output_id: Option<String>,
    #[serde(default)]
    pub audio_output_label: Option<String>,
    /// Discord Rich Presence — off by default.
    #[serde(default)]
    pub discord_rpc_enabled: bool,
    /// UI accent hex (`#rrggbb`). Default matches Material primary.
    #[serde(default = "default_accent_color")]
    pub accent_color: String,
    pub window: WindowBounds,
}

fn default_accent_color() -> String {
    "#a8c7fa".into()
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            last_fm_enabled: false,
            scrobble_percent: 50,
            last_fm_username: None,
            audio_output_id: None,
            audio_output_label: None,
            discord_rpc_enabled: false,
            accent_color: default_accent_color(),
            window: WindowBounds::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LastFmSecretsFile {
    pub api_key: String,
    pub secret: String,
}

#[derive(Debug, Clone)]
pub struct LastFmCredentials {
    pub api_key: String,
    pub secret: String,
}

fn config_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("ytmd-lite")
}

fn ensure_config_dir() -> PathBuf {
    let dir = config_dir();
    let _ = fs::create_dir_all(&dir);
    dir
}

pub fn config_path() -> PathBuf {
    ensure_config_dir().join(CONFIG_FILE)
}

pub fn secrets_path() -> PathBuf {
    ensure_config_dir().join(SECRETS_FILE)
}

pub fn load_config() -> AppConfig {
    let path = config_path();
    match fs::read_to_string(&path) {
        Ok(raw) => serde_json::from_str(&raw).unwrap_or_default(),
        Err(_) => AppConfig::default(),
    }
}

pub fn save_config(config: &AppConfig) -> Result<(), String> {
    let path = config_path();
    let raw = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    fs::write(path, raw).map_err(|e| e.to_string())
}

fn read_secrets_file(path: &Path) -> Option<LastFmCredentials> {
    let raw = fs::read_to_string(path).ok()?;
    let file = serde_json::from_str::<LastFmSecretsFile>(&raw).ok()?;
    if file.api_key.is_empty() || file.secret.is_empty() {
        return None;
    }
    Some(LastFmCredentials {
        api_key: file.api_key,
        secret: file.secret,
    })
}

fn bundled_secrets_candidates() -> Vec<PathBuf> {
    vec![
        PathBuf::from("lastfm.secrets.json"),
        PathBuf::from("resources").join("lastfm.secrets.json"),
    ]
}

/// Copy bundled app credentials into AppData if the user hasn't configured any yet.
pub fn ensure_lastfm_credentials(app: &tauri::AppHandle) -> Result<(), String> {
    if secrets_path().exists() {
        return Ok(());
    }

    if let Ok(resource_dir) = app.path().resource_dir() {
        for name in bundled_secrets_candidates() {
            let candidate = resource_dir.join(name);
            if let Some(creds) = read_secrets_file(&candidate) {
                let raw = serde_json::to_string_pretty(&LastFmSecretsFile {
                    api_key: creds.api_key.clone(),
                    secret: creds.secret.clone(),
                })
                .map_err(|e| e.to_string())?;
                fs::write(secrets_path(), raw).map_err(|e| e.to_string())?;
                log::info!("Installed bundled Last.fm credentials to {}", secrets_path().display());
                return Ok(());
            }
        }
    }

    // Dev fallback: secrets next to the crate (not shipped to end users in release).
    #[cfg(debug_assertions)]
    {
        let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("lastfm.secrets.json");
        if let Some(creds) = read_secrets_file(&dev) {
            let raw = serde_json::to_string_pretty(&LastFmSecretsFile {
                api_key: creds.api_key.clone(),
                secret: creds.secret.clone(),
            })
            .map_err(|e| e.to_string())?;
            fs::write(secrets_path(), raw).map_err(|e| e.to_string())?;
            return Ok(());
        }
    }

    Ok(())
}

/// Load Last.fm API key/secret bundled with the app (users never configure this themselves).
pub fn load_lastfm_credentials() -> Option<LastFmCredentials> {
    if let (Ok(api_key), Ok(secret)) = (
        std::env::var("LASTFM_API_KEY"),
        std::env::var("LASTFM_SECRET"),
    ) {
        if !api_key.is_empty() && !secret.is_empty() {
            return Some(LastFmCredentials { api_key, secret });
        }
    }

    if let Some(creds) = read_secrets_file(&secrets_path()) {
        return Some(creds);
    }

    for candidate in bundled_secrets_candidates() {
        if let Some(creds) = read_secrets_file(&candidate) {
            return Some(creds);
        }
    }

    #[cfg(debug_assertions)]
    {
        let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("lastfm.secrets.json");
        if let Some(creds) = read_secrets_file(&dev) {
            return Some(creds);
        }
    }

    None
}

pub fn set_lastfm_username(username: Option<&str>) -> Result<(), String> {
    let mut cfg = load_config();
    cfg.last_fm_username = username.map(str::to_string);
    save_config(&cfg)
}

pub fn get_lastfm_username() -> Option<String> {
    load_config().last_fm_username.filter(|s| !s.is_empty())
}

pub fn get_session_key() -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER_SESSION)
        .ok()
        .and_then(|e| e.get_password().ok())
        .filter(|s| !s.is_empty())
}

pub fn set_session_key(session: Option<&str>) -> Result<(), String> {
    let entry =
        keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER_SESSION).map_err(|e| e.to_string())?;
    match session {
        Some(s) if !s.is_empty() => entry.set_password(s).map_err(|e| e.to_string()),
        _ => {
            let _ = entry.delete_credential();
            Ok(())
        }
    }
}

pub fn get_token() -> Option<String> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER_TOKEN)
        .ok()
        .and_then(|e| e.get_password().ok())
        .filter(|s| !s.is_empty())
}

pub fn set_token(token: Option<&str>) -> Result<(), String> {
    let entry =
        keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER_TOKEN).map_err(|e| e.to_string())?;
    match token {
        Some(s) if !s.is_empty() => entry.set_password(s).map_err(|e| e.to_string()),
        _ => {
            let _ = entry.delete_credential();
            Ok(())
        }
    }
}
