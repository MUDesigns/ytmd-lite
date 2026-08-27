//! Playback + queue state received from the injected YouTube Music hooks.

use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Default)]
pub enum VideoState {
    #[default]
    Unknown = -1,
    Paused = 0,
    Playing = 1,
    Buffering = 2,
}

impl<'de> Deserialize<'de> for VideoState {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: serde::Deserializer<'de>,
    {
        struct Visitor;
        impl<'de> serde::de::Visitor<'de> for Visitor {
            type Value = VideoState;
            fn expecting(&self, f: &mut std::fmt::Formatter) -> std::fmt::Result {
                f.write_str("VideoState as string or int")
            }
            fn visit_i64<E: serde::de::Error>(self, v: i64) -> Result<VideoState, E> {
                Ok(match v {
                    0 => VideoState::Paused,
                    1 => VideoState::Playing,
                    2 => VideoState::Buffering,
                    _ => VideoState::Unknown,
                })
            }
            fn visit_u64<E: serde::de::Error>(self, v: u64) -> Result<VideoState, E> {
                self.visit_i64(v as i64)
            }
            fn visit_str<E: serde::de::Error>(self, v: &str) -> Result<VideoState, E> {
                Ok(match v.to_ascii_lowercase().as_str() {
                    "paused" => VideoState::Paused,
                    "playing" => VideoState::Playing,
                    "buffering" => VideoState::Buffering,
                    _ => VideoState::Unknown,
                })
            }
        }
        deserializer.deserialize_any(Visitor)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct VideoDetails {
    pub id: String,
    pub title: String,
    pub author: String,
    #[serde(default)]
    pub album: String,
    #[serde(default)]
    pub album_id: String,
    #[serde(default)]
    pub channel_id: String,
    #[serde(default)]
    pub duration_seconds: u64,
    #[serde(default)]
    pub is_live: bool,
    #[serde(default)]
    pub thumbnails: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct QueueItem {
    pub video_id: String,
    pub title: String,
    pub author: String,
    #[serde(default)]
    pub thumbnails: Vec<String>,
    #[serde(default)]
    pub selected: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct PlayerState {
    pub video_details: Option<VideoDetails>,
    pub track_state: VideoState,
    #[serde(default)]
    pub related_video_ids: Vec<String>,
    #[serde(default)]
    pub playlist_id: String,
    #[serde(default)]
    pub video_progress: f64,
    #[serde(default)]
    pub volume: u32,
    #[serde(default)]
    pub muted: bool,
    #[serde(default)]
    pub like_status: String,
    #[serde(default)]
    pub queue: Vec<QueueItem>,
    #[serde(default)]
    pub queue_index: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct EngineStatus {
    pub signed_in: bool,
    pub auth_visible: bool,
    pub ready: bool,
}

pub type SharedPlayerState = Arc<RwLock<PlayerState>>;

pub fn new_shared() -> SharedPlayerState {
    Arc::new(RwLock::new(PlayerState::default()))
}
