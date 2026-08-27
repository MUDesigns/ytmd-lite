//! Discord Rich Presence — listening activity for the current track.

use crate::config;
use crate::player_state::{PlayerState, VideoState};
use discord_rich_presence::{activity, DiscordIpc, DiscordIpcClient};
use parking_lot::Mutex;
use serde::Serialize;
use std::sync::Arc;
use std::time::{Duration, Instant};

/// Official YouTube Music Desktop Discord application.
const CLIENT_ID: &str = "1143202598460076053";
const RECONNECT_EVERY: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DiscordStatus {
    pub enabled: bool,
    pub connected: bool,
    pub last_error: Option<String>,
}

pub struct DiscordService {
    inner: Mutex<Inner>,
}

struct Inner {
    enabled: bool,
    client: Option<DiscordIpcClient>,
    last_fingerprint: String,
    last_error: Option<String>,
    last_connect_attempt: Option<Instant>,
    /// Last player snapshot so reconnect can re-push presence.
    last_state: Option<PlayerState>,
}

impl DiscordService {
    pub fn new() -> Arc<Self> {
        let enabled = config::load_config().discord_rpc_enabled;
        Arc::new(Self {
            inner: Mutex::new(Inner {
                enabled,
                client: None,
                last_fingerprint: String::new(),
                last_error: None,
                last_connect_attempt: None,
                last_state: None,
            }),
        })
    }

    pub fn status(&self) -> DiscordStatus {
        let g = self.inner.lock();
        DiscordStatus {
            enabled: g.enabled,
            connected: g.client.is_some(),
            last_error: g.last_error.clone(),
        }
    }

    pub fn set_enabled(&self, enabled: bool) -> Result<DiscordStatus, String> {
        {
            let mut cfg = config::load_config();
            cfg.discord_rpc_enabled = enabled;
            config::save_config(&cfg)?;
        }
        {
            let mut g = self.inner.lock();
            g.enabled = enabled;
            g.last_fingerprint.clear();
            g.last_error = None;
            g.last_connect_attempt = None;
            if !enabled {
                clear_and_close(&mut g.client);
                g.last_state = None;
            } else if let Err(e) = ensure_connected(&mut g) {
                g.last_error = Some(e);
            }
        }
        Ok(self.status())
    }

    pub fn handle_player_state(&self, state: &PlayerState) {
        let mut g = self.inner.lock();
        if !g.enabled {
            return;
        }
        g.last_state = Some(state.clone());

        if g.client.is_none() {
            let should_try = g
                .last_connect_attempt
                .map(|t| t.elapsed() >= RECONNECT_EVERY)
                .unwrap_or(true);
            if should_try {
                if let Err(e) = ensure_connected(&mut g) {
                    g.last_error = Some(e);
                    return;
                }
            } else {
                return;
            }
        }

        if let Err(e) = push_activity(&mut g, state) {
            g.last_error = Some(e.clone());
            log::warn!("Discord RPC update failed: {e}");
            // Drop client so the next tick reconnects
            clear_and_close(&mut g.client);
            g.last_fingerprint.clear();
        } else {
            g.last_error = None;
        }
    }

    pub fn shutdown(&self) {
        let mut g = self.inner.lock();
        clear_and_close(&mut g.client);
        g.last_fingerprint.clear();
        g.last_state = None;
    }
}

fn ensure_connected(g: &mut Inner) -> Result<(), String> {
    g.last_connect_attempt = Some(Instant::now());
    if g.client.is_some() {
        return Ok(());
    }
    match connect_client() {
        Ok(c) => {
            g.client = Some(c);
            g.last_error = None;
            Ok(())
        }
        Err(e) => Err(e),
    }
}

fn push_activity(g: &mut Inner, state: &PlayerState) -> Result<(), String> {
    let Some(details) = state.video_details.as_ref() else {
        g.last_fingerprint.clear();
        if let Some(c) = g.client.as_mut() {
            let _ = c.clear_activity();
        }
        return Ok(());
    };

    let paused = !matches!(state.track_state, VideoState::Playing);
    let fingerprint = format!("{}|{:?}|{}", details.id, state.track_state, details.title);
    if fingerprint == g.last_fingerprint {
        return Ok(());
    }

    let title = clamp_field(&details.title);
    let artist = clamp_field(&details.author);
    let album = clamp_field(&details.album);
    let video_id = details.id.clone();
    let yt_url = format!("https://music.youtube.com/watch?v={video_id}");

    let thumb = details
        .thumbnails
        .iter()
        .rev()
        .find(|u| {
            let t = u.trim();
            !t.is_empty()
                && t.len() <= 256
                && t.starts_with("http")
                && !t.contains("127.0.0.1")
                && !t.contains("localhost")
                && !t.contains("/imgproxy")
        })
        .cloned()
        .unwrap_or_else(|| format!("https://i.ytimg.com/vi/{video_id}/hqdefault.jpg"));

    // Discord expects unix timestamps in **milliseconds**.
    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as i64;
    let elapsed_ms = (state.video_progress.max(0.0) * 1000.0) as i64;
    let duration_ms = (details.duration_seconds as i64).saturating_mul(1000);

    let state_str = if paused {
        if artist.chars().count() >= 2 {
            format!("{artist} · paused")
        } else {
            String::from("Paused")
        }
    } else if artist.chars().count() >= 2 {
        artist.clone()
    } else {
        String::from("YouTube Music")
    };

    let details_str = if title.chars().count() >= 2 {
        title.clone()
    } else {
        String::from("Unknown track")
    };

    // Try full activity first; fall back to a minimal payload Discord always accepts.
    let attempts: [bool; 2] = [true, false];
    let mut last_err = String::from("set_activity failed");

    for full in attempts {
        let mut assets = activity::Assets::new().large_image(if full {
            thumb.as_str()
        } else {
            "ytmd-logo"
        });
        if full && album.chars().count() >= 2 {
            assets = assets.large_text(&album);
        }

        let mut act = activity::Activity::new()
            .activity_type(activity::ActivityType::Listening)
            .details(&details_str)
            .state(&state_str)
            .assets(assets);

        if full {
            let button = activity::Button::new("Listen on YouTube Music", &yt_url);
            act = act.buttons(vec![button]);
        }

        if !paused {
            let start = now_ms - elapsed_ms;
            let mut ts = activity::Timestamps::new().start(start);
            if duration_ms > 0 {
                ts = ts.end(start + duration_ms);
            }
            act = act.timestamps(ts);
        }

        let client = g
            .client
            .as_mut()
            .ok_or_else(|| "Discord not connected".to_string())?;

        match client.set_activity(act) {
            Ok(()) => {
                g.last_fingerprint = fingerprint;
                return Ok(());
            }
            Err(e) => {
                last_err = e.to_string();
                let _ = client.reconnect();
            }
        }
    }

    Err(last_err)
}

fn connect_client() -> Result<DiscordIpcClient, String> {
    let mut client = DiscordIpcClient::new(CLIENT_ID);
    client
        .connect()
        .map_err(|e| {
            format!(
                "Cannot reach Discord IPC ({e}). Is Discord open? Enable Activity Privacy → \
                 “Share your activity with others”. YTMD Lite requests Administrator so its \
                 integrity level can match Discord for named-pipe access — accept the UAC prompt."
            )
        })?;
    Ok(client)
}

fn clear_and_close(client: &mut Option<DiscordIpcClient>) {
    if let Some(mut c) = client.take() {
        let _ = c.clear_activity();
        std::thread::sleep(Duration::from_millis(80));
        let _ = c.close();
    }
}

fn clamp_field(s: &str) -> String {
    s.trim().chars().take(128).collect()
}
