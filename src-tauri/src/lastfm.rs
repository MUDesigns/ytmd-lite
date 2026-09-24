//! Last.fm Web Services 2.0 client: auth, now-playing, scrobble.

use crate::config::{self, LastFmCredentials};
use crate::player_state::{PlayerState, VideoDetails, VideoState};
use parking_lot::Mutex;
use serde::Deserialize;
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter};
use tauri_plugin_opener::OpenerExt;
use tokio::time::sleep;

const API_ROOT: &str = "https://ws.audioscrobbler.com/2.0/";

fn top_artist_genres(data: &serde_json::Value) -> Vec<String> {
    let Some(tags) = data.pointer("/toptags/tag").and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    let mut genres = Vec::new();
    // Last.fm orders tags by popularity. Omit common personal collection tags.
    for tag in tags {
        let Some(name) = tag.get("name").and_then(|v| v.as_str()) else { continue; };
        let name = name.trim().to_lowercase();
        if name.is_empty() || name.len() > 60 || genres.contains(&name)
            || matches!(name.as_str(), "seen live" | "favorites" | "favourites" | "favorite"
                | "favourite" | "my favorites" | "my favourites" | "owned" | "albums i own") {
            continue;
        }
        genres.push(name);
        if genres.len() == 3 { break; }
    }
    genres
}

#[cfg(test)]
mod genre_tests {
    use super::*;

    #[test]
    fn preserves_popularity_and_skips_duplicate_or_personal_tags() {
        let data = serde_json::json!({"toptags": {"tag": [
            {"name": "seen live"}, {"name": " Shoegaze "}, {"name": "shoegaze"},
            {"name": ""}, {"name": null}, {"name": "dream pop"},
            {"name": "blackgaze"}, {"name": "ambient"}
        ]}});
        assert_eq!(top_artist_genres(&data), vec!["shoegaze", "dream pop", "blackgaze"]);
    }

    #[test]
    fn missing_or_malformed_tags_are_optional() {
        for data in [serde_json::json!({}), serde_json::json!({"toptags": {"tag": null}}),
            serde_json::json!({"toptags": {"tag": [{"name": 42}]}})] {
            assert!(top_artist_genres(&data).is_empty());
        }
    }
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LastFmStatus {
    pub enabled: bool,
    pub authenticated: bool,
    pub username: Option<String>,
    pub signing_in: bool,
    pub scrobble_percent: u32,
    pub has_credentials: bool,
    pub last_error: Option<String>,
    pub now_playing: Option<String>,
    pub last_scrobble: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TokenResponse {
    token: Option<String>,
    error: Option<u32>,
    message: Option<String>,
}

#[derive(Debug, Deserialize)]
struct SessionInner {
    key: String,
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct SessionResponse {
    session: Option<SessionInner>,
    error: Option<u32>,
    #[allow(dead_code)]
    message: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ApiErrorBody {
    error: Option<u32>,
    message: Option<String>,
}

struct Inner {
    enabled: bool,
    scrobble_percent: u32,
    credentials: Option<LastFmCredentials>,
    session_key: Option<String>,
    token: Option<String>,
    username: Option<String>,
    signing_in: bool,
    possible_video_ids: Vec<String>,
    last_error: Option<String>,
    now_playing: Option<String>,
    last_scrobble: Option<String>,
    scrobble_generation: u64,
}

pub struct LastFmService {
    inner: Mutex<Inner>,
    client: reqwest::Client,
    /// Current scrobble timer generation; bumping cancels the previous timer.
    cancel_generation: Mutex<u64>,
    artist_genres: Mutex<BTreeMap<String, (Instant, Vec<String>)>>,
}

impl LastFmService {
    pub fn new() -> Arc<Self> {
        let config = config::load_config();
        let credentials = config::load_lastfm_credentials();

        Arc::new(Self {
            inner: Mutex::new(Inner {
                enabled: config.last_fm_enabled,
                scrobble_percent: config.scrobble_percent.clamp(50, 95),
                credentials,
                session_key: config::get_session_key(),
                token: config::get_token(),
                username: config::get_lastfm_username(),
                signing_in: false,
                possible_video_ids: Vec::new(),
                last_error: None,
                now_playing: None,
                last_scrobble: None,
                scrobble_generation: 0,
            }),
            client: reqwest::Client::new(),
            cancel_generation: Mutex::new(0),
            artist_genres: Mutex::new(BTreeMap::new()),
        })
    }

    /// Public metadata: no Last.fm login or scrobbling opt-in is required.
    pub async fn artist_genres(&self, artist: &str) -> Result<Vec<String>, String> {
        let artist = artist.trim();
        if artist.is_empty() { return Ok(Vec::new()); }
        let key = artist.to_lowercase();
        if let Some((saved, genres)) = self.artist_genres.lock().get(&key) {
            if saved.elapsed() < Duration::from_secs(86400) { return Ok(genres.clone()); }
        }
        let api_key = self.inner.lock().credentials.as_ref().map(|c| c.api_key.clone());
        let Some(api_key) = api_key else { return Ok(Vec::new()); };
        let response = self.client.get(API_ROOT)
            .query(&[("method", "artist.getTopTags"), ("artist", artist),
                ("api_key", api_key.as_str()), ("format", "json"), ("autocorrect", "1")])
            .timeout(Duration::from_secs(8))
            .send().await.map_err(|_| "Artist genres unavailable".to_string())?
            .error_for_status().map_err(|_| "Artist genres unavailable".to_string())?;
        let data: serde_json::Value = response.json().await
            .map_err(|_| "Invalid artist genres response".to_string())?;
        if data.get("error").is_some() { return Err("Artist genres unavailable".into()); }
        let genres = top_artist_genres(&data);
        let mut cache = self.artist_genres.lock();
        if cache.len() >= 256 { cache.clear(); }
        cache.insert(key, (Instant::now(), genres.clone()));
        Ok(genres)
    }

    pub fn status(&self) -> LastFmStatus {
        let g = self.inner.lock();
        LastFmStatus {
            enabled: g.enabled,
            authenticated: g.session_key.is_some(),
            username: g.username.clone(),
            signing_in: g.signing_in,
            scrobble_percent: g.scrobble_percent,
            has_credentials: g.credentials.is_some(),
            last_error: g.last_error.clone(),
            now_playing: g.now_playing.clone(),
            last_scrobble: g.last_scrobble.clone(),
        }
    }

    pub fn set_enabled(&self, enabled: bool) -> Result<(), String> {
        {
            let mut g = self.inner.lock();
            g.enabled = enabled;
            if !enabled {
                g.scrobble_generation = g.scrobble_generation.wrapping_add(1);
                *self.cancel_generation.lock() = g.scrobble_generation;
                g.now_playing = None;
            }
        }
        let mut cfg = config::load_config();
        cfg.last_fm_enabled = enabled;
        config::save_config(&cfg)?;
        Ok(())
    }

    pub fn set_scrobble_percent(&self, percent: u32) -> Result<(), String> {
        let percent = percent.clamp(50, 95);
        {
            let mut g = self.inner.lock();
            g.scrobble_percent = percent;
        }
        let mut cfg = config::load_config();
        cfg.scrobble_percent = percent;
        config::save_config(&cfg)?;
        Ok(())
    }

    pub fn logout(&self) -> Result<(), String> {
        {
            let mut g = self.inner.lock();
            g.session_key = None;
            g.token = None;
            g.username = None;
            g.signing_in = false;
            g.enabled = false;
            g.possible_video_ids.clear();
            g.now_playing = None;
            g.scrobble_generation = g.scrobble_generation.wrapping_add(1);
            *self.cancel_generation.lock() = g.scrobble_generation;
        }
        config::set_session_key(None)?;
        config::set_token(None)?;
        config::set_lastfm_username(None)?;
        let mut cfg = config::load_config();
        cfg.last_fm_enabled = false;
        config::save_config(&cfg)?;
        Ok(())
    }

    pub fn reload_credentials(&self) {
        let mut g = self.inner.lock();
        g.credentials = config::load_lastfm_credentials();
    }

    pub fn refresh_username_if_needed(self: &Arc<Self>, app: AppHandle) {
        let needs = {
            let g = self.inner.lock();
            g.session_key.is_some() && g.username.is_none() && g.credentials.is_some()
        };
        if !needs {
            return;
        }
        let service = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            if let Ok(Some(name)) = service.fetch_username().await {
                {
                    let mut g = service.inner.lock();
                    g.username = Some(name.clone());
                }
                let _ = config::set_lastfm_username(Some(&name));
                let _ = app.emit("lastfm-status", service.status());
            }
        });
    }

    async fn fetch_username(&self) -> Result<Option<String>, String> {
        let (api_key, secret, session_key) = {
            let g = self.inner.lock();
            let creds = g
                .credentials
                .as_ref()
                .ok_or_else(|| "Missing Last.fm credentials".to_string())?;
            let sk = g
                .session_key
                .clone()
                .ok_or_else(|| "Not authenticated".to_string())?;
            (creds.api_key.clone(), creds.secret.clone(), sk)
        };

        let mut params = BTreeMap::new();
        params.insert("method".into(), "user.getInfo".into());
        params.insert("api_key".into(), api_key);
        params.insert("sk".into(), session_key);
        params.insert("format".into(), "json".into());
        let sig = Self::api_sig(&params, &secret);
        params.insert("api_sig".into(), sig);

        let url = reqwest::Url::parse_with_params(API_ROOT, &params).map_err(|e| e.to_string())?;
        let resp = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        #[derive(Deserialize)]
        struct UserInfo {
            user: Option<UserInner>,
        }
        #[derive(Deserialize)]
        struct UserInner {
            name: String,
        }

        let body: UserInfo = resp.json().await.map_err(|e| e.to_string())?;
        Ok(body.user.map(|u| u.name))
    }

    fn api_sig(params: &BTreeMap<String, String>, secret: &str) -> String {
        let mut data = String::new();
        for (k, v) in params {
            if k == "format" || k == "callback" {
                continue;
            }
            data.push_str(k);
            data.push_str(v);
        }
        data.push_str(secret);
        format!("{:x}", md5::compute(data.as_bytes()))
    }

    async fn create_token(&self) -> Result<String, String> {
        let (api_key, secret) = {
            let g = self.inner.lock();
            let creds = g.credentials.as_ref().ok_or_else(|| {
                "Last.fm is not configured in this build.".to_string()
            })?;
            (creds.api_key.clone(), creds.secret.clone())
        };

        let mut params = BTreeMap::new();
        params.insert("method".into(), "auth.gettoken".into());
        params.insert("api_key".into(), api_key);
        params.insert("format".into(), "json".into());
        let sig = Self::api_sig(&params, &secret);
        params.insert("api_sig".into(), sig);

        let url = reqwest::Url::parse_with_params(API_ROOT, &params).map_err(|e| e.to_string())?;
        let resp = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        let body: TokenResponse = resp.json().await.map_err(|e| e.to_string())?;
        if let Some(err) = body.error {
            return Err(body
                .message
                .unwrap_or_else(|| format!("Last.fm error {err}")));
        }
        body.token
            .ok_or_else(|| "No token in Last.fm response".into())
    }

    pub async fn start_auth(&self, app: &AppHandle) -> Result<(), String> {
        let token = self.create_token().await?;
        {
            let mut g = self.inner.lock();
            g.token = Some(token.clone());
            g.last_error = None;
        }
        config::set_token(Some(&token))?;

        let api_key = {
            let g = self.inner.lock();
            g.credentials
                .as_ref()
                .map(|c| c.api_key.clone())
                .ok_or_else(|| "Missing Last.fm credentials".to_string())?
        };

        let auth_url = format!(
            "https://www.last.fm/api/auth/?api_key={}&token={}",
            encode_query(&api_key),
            encode_query(&token)
        );
        app.opener()
            .open_url(auth_url, None::<&str>)
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    pub async fn try_get_session(&self) -> Result<bool, String> {
        let (api_key, secret, token) = {
            let g = self.inner.lock();
            let creds = g
                .credentials
                .as_ref()
                .ok_or_else(|| "Missing Last.fm credentials".to_string())?;
            let token = g
                .token
                .clone()
                .ok_or_else(|| "No auth token — start authorization first".to_string())?;
            (creds.api_key.clone(), creds.secret.clone(), token)
        };

        let mut params = BTreeMap::new();
        params.insert("method".into(), "auth.getSession".into());
        params.insert("api_key".into(), api_key);
        params.insert("token".into(), token);
        params.insert("format".into(), "json".into());
        let sig = Self::api_sig(&params, &secret);
        params.insert("api_sig".into(), sig);

        let url = reqwest::Url::parse_with_params(API_ROOT, &params).map_err(|e| e.to_string())?;
        let resp = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        let body: SessionResponse = resp.json().await.map_err(|e| e.to_string())?;

        if body.error.is_some() {
            return Ok(false);
        }

        if let Some(session) = body.session {
            {
                let mut g = self.inner.lock();
                g.session_key = Some(session.key.clone());
                g.username = session.name.clone();
                g.signing_in = false;
                g.last_error = None;
            }
            config::set_session_key(Some(&session.key))?;
            config::set_lastfm_username(session.name.as_deref())?;
            return Ok(true);
        }

        Ok(false)
    }

    fn set_signing_in(&self, signing_in: bool) {
        let mut g = self.inner.lock();
        g.signing_in = signing_in;
    }

    fn poll_session_after_auth(self: &Arc<Self>, app: AppHandle) {
        let service = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            for _ in 0..90 {
                sleep(Duration::from_secs(2)).await;
                if !service.inner.lock().signing_in {
                    return;
                }
                match service.try_get_session().await {
                    Ok(true) => {
                        let _ = service.set_enabled(true);
                        let status = service.status();
                        let name = status
                            .username
                            .clone()
                            .unwrap_or_else(|| "your account".into());
                        let _ = app.emit("lastfm-status", &status);
                        let _ = app.emit(
                            "status-message",
                            format!("Signed in to Last.fm as {name}"),
                        );
                        return;
                    }
                    Ok(false) => continue,
                    Err(e) => {
                        service.set_signing_in(false);
                        {
                            let mut g = service.inner.lock();
                            g.last_error = Some(e.clone());
                        }
                        let _ = app.emit("lastfm-status", service.status());
                        let _ = app.emit("status-message", e);
                        return;
                    }
                }
            }
            service.set_signing_in(false);
            {
                let mut g = service.inner.lock();
                g.last_error = Some(
                    "Last.fm sign-in timed out — approve the app in your browser and try again."
                        .into(),
                );
            }
            let _ = app.emit("lastfm-status", service.status());
            let _ = app.emit(
                "status-message",
                "Last.fm sign-in timed out — try again",
            );
        });
    }

    /// Open Last.fm in the browser and poll until the user approves access.
    pub async fn sign_in(self: &Arc<Self>, app: &AppHandle) -> Result<(), String> {
        self.reload_credentials();
        if self.inner.lock().credentials.is_none() {
            return Err("Last.fm is not configured in this build.".into());
        }
        if self.status().authenticated {
            self.set_enabled(true)?;
            let _ = app.emit("lastfm-status", self.status());
            return Ok(());
        }
        self.set_signing_in(true);
        let _ = app.emit("lastfm-status", self.status());
        self.start_auth(app).await?;
        self.poll_session_after_auth(app.clone());
        Ok(())
    }

    async fn send_track(
        &self,
        method: &str,
        details: &VideoDetails,
        extra: BTreeMap<String, String>,
    ) -> Result<(), String> {
        let (api_key, secret, session_key) = {
            let g = self.inner.lock();
            let creds = g
                .credentials
                .as_ref()
                .ok_or_else(|| "Missing Last.fm credentials".to_string())?;
            let sk = g
                .session_key
                .clone()
                .ok_or_else(|| "Not authenticated with Last.fm".to_string())?;
            (creds.api_key.clone(), creds.secret.clone(), sk)
        };

        let mut params = BTreeMap::new();
        params.insert("method".into(), method.into());
        params.insert("artist".into(), details.author.clone());
        params.insert("track".into(), details.title.clone());
        if !details.album.is_empty() {
            params.insert("album".into(), details.album.clone());
        }
        params.insert("duration".into(), details.duration_seconds.to_string());
        params.insert("api_key".into(), api_key);
        params.insert("sk".into(), session_key);
        params.insert("format".into(), "json".into());
        for (k, v) in extra {
            params.insert(k, v);
        }
        let sig = Self::api_sig(&params, &secret);
        params.insert("api_sig".into(), sig);

        let resp = self
            .client
            .post(API_ROOT)
            .form(&params)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let status = resp.status();
        let text = resp.text().await.map_err(|e| e.to_string())?;
        if let Ok(err) = serde_json::from_str::<ApiErrorBody>(&text) {
            if let Some(code) = err.error {
                if code == 9 {
                    let mut g = self.inner.lock();
                    g.session_key = None;
                    let _ = config::set_session_key(None);
                    return Err("Last.fm session expired — please re-authorize".into());
                }
                return Err(err
                    .message
                    .unwrap_or_else(|| format!("Last.fm error {code}")));
            }
        }
        if !status.is_success() {
            return Err(format!("Last.fm HTTP {status}: {text}"));
        }
        Ok(())
    }

    fn schedule_scrobble(
        self: &Arc<Self>,
        app: AppHandle,
        details: VideoDetails,
        delay_secs: u64,
        generation: u64,
        timestamp: u64,
    ) {
        let service = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            sleep(Duration::from_secs(delay_secs)).await;
            if *service.cancel_generation.lock() != generation {
                return;
            }
            let mut extra = BTreeMap::new();
            extra.insert("timestamp".into(), timestamp.to_string());
            match service
                .send_track("track.scrobble", &details, extra)
                .await
            {
                Ok(()) => {
                    let label = format!("{} — {}", details.author, details.title);
                    {
                        let mut g = service.inner.lock();
                        g.last_scrobble = Some(label.clone());
                        g.last_error = None;
                    }
                    let _ = app.emit("lastfm-status", service.status());
                    let _ = app.emit("status-message", format!("Scrobbled: {label}"));
                }
                Err(e) => {
                    {
                        let mut g = service.inner.lock();
                        g.last_error = Some(e.clone());
                    }
                    let _ = app.emit("lastfm-status", service.status());
                    let _ = app.emit("status-message", e);
                }
            }
        });
    }
}

fn encode_query(s: &str) -> String {
    url::form_urlencoded::byte_serialize(s.as_bytes()).collect()
}

/// Handle a player state update and schedule scrobble.
pub async fn handle_player_state(service: Arc<LastFmService>, app: AppHandle, state: PlayerState) {
    let should_handle = {
        let g = service.inner.lock();
        g.enabled
    };
    if !should_handle {
        return;
    }

    let Some(details) = state.video_details.clone() else {
        return;
    };
    if state.track_state != VideoState::Playing {
        return;
    }

    // Duration often arrives as 0 on the first Playing event. Do NOT mark the
    // track as processed until we actually have a scrobbleable length — otherwise
    // we permanently skip the real scrobble when metadata updates.
    if details.duration_seconds < 30 {
        return;
    }

    {
        let mut g = service.inner.lock();
        let already = !g.possible_video_ids.is_empty()
            && g.possible_video_ids.iter().any(|id| id == &details.id);
        if already {
            return;
        }
        g.possible_video_ids = if state.related_video_ids.is_empty() {
            vec![details.id.clone()]
        } else {
            state.related_video_ids.clone()
        };
    }

    let has_session = {
        let g = service.inner.lock();
        g.session_key.is_some()
    };
    if !has_session {
        {
            let mut g = service.inner.lock();
            g.last_error = Some("Sign in to Last.fm to scrobble".into());
        }
        let _ = app.emit("lastfm-status", service.status());
        return;
    }

    let label = format!("{} — {}", details.author, details.title);
    {
        let mut g = service.inner.lock();
        g.now_playing = Some(label.clone());
        g.last_error = None;
    }
    let _ = app.emit("lastfm-status", service.status());
    let _ = app.emit(
        "status-message",
        format!("Now playing on Last.fm: {label}"),
    );

    if let Err(e) = service
        .send_track("track.updateNowPlaying", &details, BTreeMap::new())
        .await
    {
        let mut g = service.inner.lock();
        g.last_error = Some(e.clone());
        let _ = app.emit("lastfm-status", service.status());
        let _ = app.emit("status-message", e);
    }

    let percent = {
        let g = service.inner.lock();
        g.scrobble_percent
    };
    let delay_secs = ((details.duration_seconds as f64) * (percent as f64 / 100.0))
        .round()
        .min(240.0) as u64;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);

    let generation = {
        let mut g = service.inner.lock();
        g.scrobble_generation = g.scrobble_generation.wrapping_add(1);
        let gen = g.scrobble_generation;
        *service.cancel_generation.lock() = gen;
        gen
    };

    service.schedule_scrobble(app, details, delay_secs, generation, timestamp);
}
