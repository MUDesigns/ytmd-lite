//! Tauri commands exposed to the Material IDE chrome.

use crate::config;
use crate::discord::{DiscordService, DiscordStatus};
use crate::lastfm::{LastFmService, LastFmStatus};
use crate::player_state::{PlayerState, SharedPlayerState};
use crate::rpc::RpcHub;
use crate::ytm;
use serde_json::Value;
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager, State};

pub struct AppState {
    pub player: SharedPlayerState,
    pub lastfm: Arc<LastFmService>,
    pub discord: Arc<DiscordService>,
    pub rpc: Arc<RpcHub>,
}

#[tauri::command]
pub fn get_lastfm_status(state: State<'_, AppState>) -> LastFmStatus {
    state.lastfm.status()
}

#[tauri::command]
pub async fn get_artist_genres(state: State<'_, AppState>, artist: String) -> Result<Vec<String>, String> {
    state.lastfm.artist_genres(&artist).await
}

#[tauri::command]
pub fn get_player_snapshot(state: State<'_, AppState>) -> PlayerState {
    state.player.read().clone()
}

#[tauri::command]
pub fn get_engine_status(state: State<'_, AppState>) -> Value {
    state.rpc.status_json()
}

#[tauri::command]
pub fn ytm_show_auth(app: AppHandle, state: State<'_, AppState>, visible: bool) -> Result<(), String> {
    ytm::set_auth_visible(&app, &state.rpc, visible)
}

#[tauri::command]
pub async fn ytm_rpc(
    app: AppHandle,
    state: State<'_, AppState>,
    method: String,
    args: Option<Value>,
) -> Result<Value, String> {
    ytm::ytm_rpc(&app, &state.rpc, method, args.unwrap_or(Value::Object(Default::default()))).await
}

#[tauri::command]
pub fn set_lastfm_enabled(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<LastFmStatus, String> {
    if enabled && !state.lastfm.status().authenticated {
        return Err("Sign in to Last.fm first".into());
    }
    state.lastfm.set_enabled(enabled)?;
    let status = state.lastfm.status();
    let _ = app.emit("lastfm-status", &status);
    Ok(status)
}

#[tauri::command]
pub async fn lastfm_sign_in(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<LastFmStatus, String> {
    Arc::clone(&state.lastfm).sign_in(&app).await?;
    let status = state.lastfm.status();
    let _ = app.emit("lastfm-status", &status);
    let _ = app.emit(
        "status-message",
        "Approve YTMD Lite in your browser to finish signing in",
    );
    Ok(status)
}

#[tauri::command]
pub fn set_scrobble_percent(
    app: AppHandle,
    state: State<'_, AppState>,
    percent: u32,
) -> Result<LastFmStatus, String> {
    state.lastfm.set_scrobble_percent(percent)?;
    let status = state.lastfm.status();
    let _ = app.emit("lastfm-status", &status);
    Ok(status)
}

#[tauri::command]
pub async fn lastfm_authorize(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<LastFmStatus, String> {
    lastfm_sign_in(app, state).await
}

#[tauri::command]
pub async fn lastfm_complete_auth(
    app: AppHandle,
    state: State<'_, AppState>,
) -> Result<LastFmStatus, String> {
    let ok = state.lastfm.try_get_session().await?;
    if !ok {
        return Err(
            "Authorization not complete yet — approve the app in your browser, then try again."
                .into(),
        );
    }
    state.lastfm.set_enabled(true)?;
    let status = state.lastfm.status();
    let _ = app.emit("lastfm-status", &status);
    let _ = app.emit("status-message", "Last.fm connected".to_string());
    Ok(status)
}

#[tauri::command]
pub fn lastfm_logout(app: AppHandle, state: State<'_, AppState>) -> Result<LastFmStatus, String> {
    state.lastfm.logout()?;
    let status = state.lastfm.status();
    let _ = app.emit("lastfm-status", &status);
    Ok(status)
}

#[tauri::command]
pub fn media_control(app: AppHandle, command: String) -> Result<(), String> {
    ytm::media_command(&app, &command)
}

#[tauri::command]
pub fn resize_ytm_webview(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    ytm::resize_ytm(&app, &state.rpc)
}

#[tauri::command]
pub fn create_ytm_webview(app: AppHandle, state: State<'_, AppState>) -> Result<(), String> {
    if app.get_webview(ytm::YTM_LABEL).is_none() {
        ytm::create_main_window(
            &app,
            state.player.clone(),
            Arc::clone(&state.lastfm),
            Arc::clone(&state.rpc),
        )?;
    } else {
        ytm::resize_ytm(&app, &state.rpc)?;
    }
    Ok(())
}

#[tauri::command]
pub fn refresh_audio_outputs(app: AppHandle) -> Result<(), String> {
    ytm::refresh_audio_outputs(&app)
}

#[tauri::command]
pub fn set_audio_output(
    app: AppHandle,
    device_id: String,
    label: Option<String>,
) -> Result<(), String> {
    ytm::set_audio_output(&app, device_id, label)
}

#[tauri::command]
pub fn get_audio_output() -> Value {
    ytm::get_audio_output()
}

#[tauri::command]
pub fn set_ytm_visible(app: AppHandle, state: State<'_, AppState>, visible: bool) -> Result<(), String> {
    ytm::set_ytm_visible(&app, &state.rpc, visible)
}

#[tauri::command]
pub fn get_secrets_path() -> String {
    config::secrets_path().display().to_string()
}

#[tauri::command]
pub fn get_config_path() -> String {
    config::config_path().display().to_string()
}

#[tauri::command]
pub fn reload_lastfm_credentials(state: State<'_, AppState>) -> LastFmStatus {
    state.lastfm.reload_credentials();
    state.lastfm.status()
}

#[tauri::command]
pub fn window_minimize(app: AppHandle) -> Result<(), String> {
    app.get_window(ytm::WINDOW_LABEL)
        .ok_or_else(|| "window missing".to_string())?
        .minimize()
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_toggle_maximize(app: AppHandle) -> Result<bool, String> {
    let w = app
        .get_window(ytm::WINDOW_LABEL)
        .ok_or_else(|| "window missing".to_string())?;
    let maximized = w.is_maximized().map_err(|e| e.to_string())?;
    if maximized {
        w.unmaximize().map_err(|e| e.to_string())?;
        Ok(false)
    } else {
        w.maximize().map_err(|e| e.to_string())?;
        Ok(true)
    }
}

#[tauri::command]
pub fn window_hide(app: AppHandle) -> Result<(), String> {
    ytm::persist_window_bounds(&app);
    app.get_window(ytm::WINDOW_LABEL)
        .ok_or_else(|| "window missing".to_string())?
        .hide()
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_is_maximized(app: AppHandle) -> Result<bool, String> {
    app.get_window(ytm::WINDOW_LABEL)
        .ok_or_else(|| "window missing".to_string())?
        .is_maximized()
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn window_start_dragging(app: AppHandle) -> Result<(), String> {
    app.get_window(ytm::WINDOW_LABEL)
        .ok_or_else(|| "window missing".to_string())?
        .start_dragging()
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub fn get_discord_rpc_status(state: State<'_, AppState>) -> DiscordStatus {
    state.discord.status()
}

#[tauri::command]
pub fn set_discord_rpc_enabled(
    app: AppHandle,
    state: State<'_, AppState>,
    enabled: bool,
) -> Result<DiscordStatus, String> {
    let _ = state.discord.set_enabled(enabled)?;
    if enabled {
        let snapshot = state.player.read().clone();
        state.discord.handle_player_state(&snapshot);
    }
    let status = state.discord.status();
    let _ = app.emit("discord-rpc-status", &status);
    let msg = if !enabled {
        "Discord Rich Presence disabled".to_string()
    } else if let Some(err) = &status.last_error {
        format!("Discord RPC: {err}")
    } else if status.connected {
        "Discord Rich Presence enabled".to_string()
    } else {
        "Discord Rich Presence enabled — waiting for Discord".to_string()
    };
    let _ = app.emit("status-message", msg);
    Ok(status)
}

#[tauri::command]
pub fn get_accent_color() -> String {
    let c = config::load_config().accent_color;
    if c.trim().is_empty() {
        "#a8c7fa".into()
    } else {
        c
    }
}

#[tauri::command]
pub fn set_accent_color(app: AppHandle, color: String) -> Result<String, String> {
    let trimmed = color.trim().to_string();
    if trimmed.is_empty() {
        return Err("Accent color is empty".into());
    }
    // Light validation — full parse lives in the UI.
    let ok = trimmed.starts_with('#')
        || trimmed.chars().all(|c| c.is_ascii_hexdigit())
        || trimmed.to_ascii_lowercase().starts_with("rgb");
    if !ok && !trimmed.contains(',') {
        return Err("Invalid accent color".into());
    }
    let mut cfg = config::load_config();
    cfg.accent_color = trimmed.clone();
    config::save_config(&cfg)?;
    let _ = app.emit("accent-color", &trimmed);
    Ok(trimmed)
}

#[tauri::command]
pub fn ingest_player_state(app: AppHandle, state: State<'_, AppState>, payload: PlayerState) {
    ytm::ingest_player_payload(&app, &state.player, &state.lastfm, &state.discord, payload);
}

#[tauri::command]
pub fn ingest_rpc_reply(state: State<'_, AppState>, reply: Value) {
    let id = reply
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();
    if id.is_empty() {
        return;
    }
    let ok = reply.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    if ok {
        state
            .rpc
            .complete(&id, Ok(reply.get("payload").cloned().unwrap_or(Value::Null)));
    } else {
        let err = reply
            .get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("RPC failed")
            .to_string();
        state.rpc.complete(&id, Err(err));
    }
}

#[tauri::command]
pub fn ingest_audio_devices(app: AppHandle, payload: Value) {
    let count = payload
        .get("devices")
        .and_then(|d| d.as_array())
        .map(|a| a.len())
        .unwrap_or(0);
    let _ = app.emit("audio-devices", &payload);
    let _ = app.emit(
        "status-message",
        format!("Found {count} audio output option(s)"),
    );
}
