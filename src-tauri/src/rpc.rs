//! Pending RPC requests from chrome → YTM webview.

use parking_lot::Mutex;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::oneshot;

pub struct RpcHub {
    pending: Mutex<HashMap<String, oneshot::Sender<Result<Value, String>>>>,
    pub signed_in: AtomicBool,
    pub auth_visible: AtomicBool,
    pub ready: AtomicBool,
}

impl RpcHub {
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            pending: Mutex::new(HashMap::new()),
            signed_in: AtomicBool::new(false),
            auth_visible: AtomicBool::new(false),
            ready: AtomicBool::new(false),
        })
    }

    pub fn complete(&self, id: &str, result: Result<Value, String>) {
        if let Some(tx) = self.pending.lock().remove(id) {
            let _ = tx.send(result);
        }
    }

    pub fn status_json(&self) -> Value {
        serde_json::json!({
            "signedIn": self.signed_in.load(Ordering::Relaxed),
            "authVisible": self.auth_visible.load(Ordering::Relaxed),
            "ready": self.ready.load(Ordering::Relaxed),
        })
    }
}
