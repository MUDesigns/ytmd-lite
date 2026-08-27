//! Main chrome window (custom UI). YTM webviews live in `auth.rs` for cookies only.

use crate::config;
use crate::discord::DiscordService;
use crate::lastfm::{self, LastFmService};
use crate::player_state::{PlayerState, SharedPlayerState};
use crate::rpc::RpcHub;
use cpal::traits::HostTrait;
use serde_json::Value;
use std::sync::atomic::Ordering;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, LogicalPosition, LogicalSize, Manager, WebviewUrl};
use url::Url;

pub const WINDOW_LABEL: &str = "main";
pub const CHROME_LABEL: &str = "chrome";
/// Legacy label — no longer created as a child engine.
pub const YTM_LABEL: &str = "ytm";

pub fn create_main_window(
    app: &AppHandle,
    _player: SharedPlayerState,
    _lastfm: Arc<LastFmService>,
    rpc: Arc<RpcHub>,
) -> Result<(), String> {
    if app.get_window(WINDOW_LABEL).is_some() {
        return Ok(());
    }

    let cfg = config::load_config();
    let width = cfg.window.width.max(900.0);
    let height = cfg.window.height.max(600.0);

    let mut window_builder = tauri::window::WindowBuilder::new(app, WINDOW_LABEL)
        .title("YTMD Lite")
        .inner_size(width, height)
        .min_inner_size(720.0, 480.0)
        .position(cfg.window.x, cfg.window.y)
        .decorations(false)
        .resizable(true);

    if let Some(icon) = app.default_window_icon() {
        window_builder = window_builder.icon(icon.clone()).map_err(|e| e.to_string())?;
    }

    let window = window_builder.build().map_err(|e| e.to_string())?;

    let chrome_url = if cfg!(debug_assertions) {
        WebviewUrl::External(
            "http://localhost:1420"
                .parse()
                .map_err(|e: url::ParseError| e.to_string())?,
        )
    } else {
        WebviewUrl::App("index.html".into())
    };

    let _chrome = window
        .add_child(
            tauri::webview::WebviewBuilder::new(CHROME_LABEL, chrome_url).auto_resize(),
            LogicalPosition::new(0.0, 0.0),
            LogicalSize::new(width, height),
        )
        .map_err(|e| e.to_string())?;

    rpc.auth_visible.store(false, Ordering::Relaxed);
    rpc.ready.store(true, Ordering::Relaxed);
    let _ = app.emit("engine-status", rpc.status_json());

    Ok(())
}

/// Fit the chrome child webview to the full main window.
pub fn resize_chrome(app: &AppHandle) -> Result<(), String> {
    let window = app
        .get_window(WINDOW_LABEL)
        .ok_or_else(|| "main window missing".to_string())?;
    let chrome = match app.get_webview(CHROME_LABEL) {
        Some(w) => w,
        None => return Ok(()),
    };

    let size = window.inner_size().map_err(|e| e.to_string())?;
    let scale = window.scale_factor().map_err(|e| e.to_string())?;
    let logical_w = (size.width as f64 / scale).max(100.0);
    let logical_h = (size.height as f64 / scale).max(100.0);

    chrome
        .set_position(LogicalPosition::new(0.0, 0.0))
        .map_err(|e| e.to_string())?;
    chrome
        .set_size(LogicalSize::new(logical_w, logical_h))
        .map_err(|e| e.to_string())?;
    Ok(())
}

/// Legacy command name — resizes chrome to fill the window.
pub fn resize_ytm(app: &AppHandle, _rpc: &Arc<RpcHub>) -> Result<(), String> {
    resize_chrome(app)
}

/// Legacy: open Kodama-style login window instead of in-window YTM.
pub fn set_auth_visible(app: &AppHandle, rpc: &Arc<RpcHub>, visible: bool) -> Result<(), String> {
    rpc.auth_visible.store(visible, Ordering::Relaxed);
    let _ = app.emit("engine-status", rpc.status_json());
    if visible {
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            if let Err(e) = crate::auth::open_login_window(handle.clone()).await {
                let _ = handle.emit("status-message", format!("Login failed: {e}"));
            }
        });
    }
    Ok(())
}

pub fn set_ytm_visible(app: &AppHandle, rpc: &Arc<RpcHub>, visible: bool) -> Result<(), String> {
    set_auth_visible(app, rpc, visible)
}

/// Tray / global shortcuts → chrome HTML5 player.
pub fn media_command(app: &AppHandle, command: &str) -> Result<(), String> {
    let _ = app.emit("media-command", command.to_string());
    Ok(())
}

pub fn ingest_player_payload(
    app: &AppHandle,
    player: &SharedPlayerState,
    lastfm: &Arc<LastFmService>,
    discord: &Arc<DiscordService>,
    payload: PlayerState,
) {
    let mut significant = true;
    {
        let mut guard = player.write();
        let same_track = guard.video_details.as_ref().map(|v| &v.id)
            == payload.video_details.as_ref().map(|v| &v.id)
            && guard.track_state == payload.track_state
            && guard.video_details.as_ref().map(|v| &v.title)
                == payload.video_details.as_ref().map(|v| &v.title);
        let duration_grew = match (&guard.video_details, &payload.video_details) {
            (Some(old), Some(new)) => new.duration_seconds > old.duration_seconds,
            _ => false,
        };
        *guard = payload.clone();
        if same_track && !duration_grew {
            significant = false;
        }
    }
    let _ = app.emit("player-state", &payload);
    // Always poke Discord (reconnect / fingerprint no-op). Last.fm only on significant changes.
    discord.handle_player_state(&payload);
    if !significant {
        return;
    }
    let service = Arc::clone(lastfm);
    let app2 = app.clone();
    tauri::async_runtime::spawn(async move {
        lastfm::handle_player_state(service, app2, payload).await;
    });
}

pub fn refresh_audio_outputs(app: &AppHandle) -> Result<(), String> {
    let mut devices = vec![serde_json::json!({ "id": "", "label": "System default" })];

    match cpal::default_host().output_devices() {
        Ok(list) => {
            for (idx, dev) in list.enumerate() {
                let label = {
                    let s = dev.to_string();
                    if s.is_empty() {
                        format!("Output {}", idx + 1)
                    } else {
                        s
                    }
                };
                devices.push(serde_json::json!({
                    "id": format!("host:{}", label),
                    "label": label,
                }));
            }
        }
        Err(e) => log::warn!("cpal output_devices failed: {e}"),
    }

    let payload = serde_json::json!({
        "devices": devices,
        "setSinkId": true,
        "error": if devices.len() <= 1 {
            Some("No output devices reported by the OS. Check Windows sound settings.")
        } else {
            None
        },
    });

    let _ = app.emit("audio-devices", &payload);
    let _ = app.emit(
        "status-message",
        format!("Found {} audio output option(s)", devices.len()),
    );
    Ok(())
}

pub fn set_audio_output(
    app: &AppHandle,
    device_id: String,
    label: Option<String>,
) -> Result<(), String> {
    let mut cfg = config::load_config();
    cfg.audio_output_id = if device_id.is_empty() {
        None
    } else {
        Some(device_id.clone())
    };
    if device_id.is_empty() {
        cfg.audio_output_label = Some("System default".into());
    } else if let Some(lbl) = label.filter(|s| !s.is_empty()) {
        cfg.audio_output_label = Some(lbl);
    }
    config::save_config(&cfg)?;

    let label_display = cfg
        .audio_output_label
        .clone()
        .unwrap_or_else(|| "System default".into());
    let _ = app.emit(
        "audio-output",
        serde_json::json!({ "id": device_id, "label": label_display }),
    );
    // Chrome player applies sink via setSinkId when supported.
    let _ = app.emit(
        "audio-sink",
        serde_json::json!({ "id": device_id, "label": label_display }),
    );
    Ok(())
}

pub fn get_audio_output() -> Value {
    let cfg = config::load_config();
    serde_json::json!({
        "id": cfg.audio_output_id,
        "label": cfg.audio_output_label,
    })
}

pub fn persist_window_bounds(app: &AppHandle) {
    let Some(window) = app.get_window(WINDOW_LABEL) else {
        return;
    };
    if let (Ok(pos), Ok(size), Ok(scale)) =
        (window.outer_position(), window.outer_size(), window.scale_factor())
    {
        let mut cfg = config::load_config();
        cfg.window.x = pos.x as f64 / scale;
        cfg.window.y = pos.y as f64 / scale;
        cfg.window.width = size.width as f64 / scale;
        cfg.window.height = size.height as f64 / scale;
        let _ = config::save_config(&cfg);
    }
}

#[allow(dead_code)]
pub fn open_external_if_needed(app: &AppHandle, raw: &str) -> Result<(), String> {
    use tauri_plugin_opener::OpenerExt;
    if let Ok(parsed) = Url::parse(raw) {
        if let Some(host) = parsed.host_str() {
            let ok = host == "music.youtube.com"
                || host.ends_with(".youtube.com")
                || host.ends_with(".google.com");
            if ok {
                return Ok(());
            }
        }
    }
    app.opener()
        .open_url(raw, None::<&str>)
        .map_err(|e| e.to_string())
}

/// Legacy RPC path retired — catalog goes through Kodama API.
pub async fn ytm_rpc(
    _app: &AppHandle,
    _rpc: &Arc<RpcHub>,
    method: String,
    _args: Value,
) -> Result<Value, String> {
    Err(format!(
        "ytm_rpc retired ({method}). Use the Kodama catalog API on :9847."
    ))
}
