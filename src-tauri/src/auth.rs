//! Google login + hidden session-keeper (Kodama pattern).
//! YTM webviews are for cookies only — not playback or browse.

use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, WebviewUrl};

const PROFILE: &str = "default";
const API: &str = "http://127.0.0.1:9847";

#[cfg(target_os = "macos")]
const LOGIN_USER_AGENT: &str = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15";
#[cfg(not(target_os = "macos"))]
const LOGIN_USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const LOGIN_INIT_JS: &str = r#"
(function () {
  if (location.hostname.indexOf('music.youtube.com') === -1) return;
  var L = window.__YTMD_LABELS__ || { confirm: 'Use this account', hint: 'Switch account via the avatar if needed, then confirm.' };
  function setCookie(name, val) {
    try { document.cookie = name + '=' + (val || '') + ';path=/;max-age=3600'; } catch (e) {}
  }
  function readDsid() {
    try {
      if (window.ytcfg && typeof ytcfg.get === 'function') return ytcfg.get('DELEGATED_SESSION_ID') || '';
    } catch (e) {}
    return '';
  }
  setInterval(function () { setCookie('YTMD_DSID', readDsid()); }, 1000);
  function injectBar() {
    if (!document.body || document.getElementById('ytmd-confirm-bar')) return;
    var bar = document.createElement('div');
    bar.id = 'ytmd-confirm-bar';
    bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:2147483647;display:flex;align-items:center;justify-content:center;gap:18px;padding:14px 20px;background:rgba(15,15,15,0.97);border-top:1px solid rgba(255,255,255,0.14);font-family:system-ui,sans-serif;';
    if (L.hint) {
      var hint = document.createElement('span');
      hint.textContent = L.hint;
      hint.style.cssText = 'color:#b4b4b4;font-size:13px;max-width:520px;';
      bar.appendChild(hint);
    }
    var btn = document.createElement('button');
    btn.textContent = L.confirm;
    btn.style.cssText = 'background:#a8c7fa;color:#062e6f;font-weight:600;font-size:14px;border:none;border-radius:9px;padding:11px 24px;cursor:pointer;';
    btn.onclick = function () {
      setCookie('YTMD_DSID', readDsid());
      setCookie('YTMD_DONE', '1');
      btn.disabled = true;
      btn.style.opacity = '0.6';
    };
    bar.appendChild(btn);
    document.body.appendChild(bar);
  }
  var iv = setInterval(function () { injectBar(); if (document.getElementById('ytmd-confirm-bar')) clearInterval(iv); }, 500);
})();
"#;

fn auth_data_dir(profile: &str) -> PathBuf {
    let safe: String = profile
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect();
    std::env::temp_dir()
        .join("ytmd-lite-auth-webview")
        .join(safe)
}

#[tauri::command]
pub async fn open_login_window(app: AppHandle) -> Result<(), String> {
    if let Some(w) = app.get_webview_window("login") {
        let _ = w.destroy();
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
    if let Some(w) = app.get_webview_window("session-keeper") {
        let _ = w.destroy();
    }

    let login_data_dir = auth_data_dir(PROFILE);
    let _ = std::fs::remove_dir_all(&login_data_dir);

    let labels = serde_json::json!({
        "confirm": "Use this account",
        "hint": "Switch account via the avatar if needed, then confirm."
    });
    let init_script = format!("window.__YTMD_LABELS__ = {labels};\n{LOGIN_INIT_JS}");

    let _win = tauri::WebviewWindowBuilder::new(
        &app,
        "login",
        WebviewUrl::External(
            "https://accounts.google.com/AddSession?service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F&flowName=GlifWebSignIn"
                .parse()
                .map_err(|e: url::ParseError| e.to_string())?,
        ),
    )
    .title("YTMD Lite – Sign in")
    .inner_size(900.0, 680.0)
    .center()
    .decorations(true)
    .user_agent(LOGIN_USER_AGENT)
    .data_directory(login_data_dir)
    .initialization_script(&init_script)
    .build()
    .map_err(|e| e.to_string())?;

    let app_clone = app.clone();
    tauri::async_runtime::spawn(async move {
        let yt_url: url::Url = "https://music.youtube.com".parse().unwrap();
        tokio::time::sleep(std::time::Duration::from_secs(4)).await;
        let mut completed = false;

        for _ in 0..150 {
            tokio::time::sleep(std::time::Duration::from_secs(2)).await;
            let Some(win) = app_clone.get_webview_window("login") else {
                break;
            };

            let current_url = win.url().ok().map(|u| u.to_string()).unwrap_or_default();
            if !current_url.contains("music.youtube.com") {
                continue;
            }

            let (tx, rx) = std::sync::mpsc::channel::<Vec<(String, String)>>();
            let cwin = win.clone();
            let cyt = yt_url.clone();
            let _ = app_clone.run_on_main_thread(move || {
                if cwin.cookies_for_url(cyt.clone()).is_err() && cwin.cookies().is_err() {
                    let _ = tx.send(Vec::new());
                    return;
                }
                let mut cs = cwin.cookies_for_url(cyt).unwrap_or_default();
                if !cs.iter().any(|c| c.name() == "SAPISID") {
                    if let Ok(all) = cwin.cookies() {
                        let yt: Vec<_> = all
                            .into_iter()
                            .filter(|c| c.domain().map(|d| d.contains("youtube.com")).unwrap_or(false))
                            .collect();
                        if yt.iter().any(|c| c.name() == "SAPISID") {
                            cs = yt;
                        }
                    }
                }
                let pairs = cs
                    .iter()
                    .map(|c| (c.name().to_string(), c.value().to_string()))
                    .collect();
                let _ = tx.send(pairs);
            });
            let found = rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap_or_default();

            let has_auth = found.iter().any(|(n, _)| n == "SAPISID");
            let done = found.iter().any(|(n, v)| n == "YTMD_DONE" && v == "1");
            if has_auth && done {
                let dsid = found
                    .iter()
                    .find(|(n, _)| n == "YTMD_DSID")
                    .map(|(_, v)| v.clone())
                    .unwrap_or_default();
                let cookie_str = found
                    .iter()
                    .filter(|(n, _)| !n.starts_with("YTMD_"))
                    .map(|(n, v)| format!("{n}={v}"))
                    .collect::<Vec<_>>()
                    .join("; ");

                let client = reqwest::Client::new();
                let res = client
                    .post(format!("{API}/auth/cookie-login"))
                    .json(&serde_json::json!({
                        "cookie": cookie_str,
                        "profile_name": PROFILE,
                        "user_agent": LOGIN_USER_AGENT,
                        "delegated_session_id": dsid
                    }))
                    .send()
                    .await;

                match res {
                    Ok(r) if r.status().is_success() => {
                        let _ = win.destroy();
                        let _ = app_clone.emit("login-complete", PROFILE);
                        let _ = ensure_session_keeper(app_clone.clone()).await;
                        completed = true;
                        break;
                    }
                    Ok(r) => {
                        let body = r.text().await.unwrap_or_default();
                        log::warn!("cookie-login failed: {body}");
                        let _ = app_clone.emit("status-message", format!("Login failed: {body}"));
                    }
                    Err(e) => {
                        log::warn!("cookie-login error: {e}");
                        let _ = app_clone.emit("status-message", format!("Login error: {e}"));
                    }
                }
            }
        }

        if !completed {
            let _ = app_clone.emit("login-cancelled", ());
        }
    });

    Ok(())
}

#[tauri::command]
pub fn close_login_window(app: AppHandle) {
    if let Some(w) = app.get_webview_window("login") {
        let _ = w.destroy();
    }
}

#[tauri::command]
pub async fn ensure_session_keeper(app: AppHandle) -> Result<(), String> {
    if app.get_webview_window("session-keeper").is_some() {
        return Ok(());
    }
    let dir = auth_data_dir(PROFILE);
    if !dir.exists() {
        return Err("no auth data — sign in first".into());
    }
    tauri::WebviewWindowBuilder::new(
        &app,
        "session-keeper",
        WebviewUrl::External(
            "https://music.youtube.com/"
                .parse()
                .map_err(|e: url::ParseError| e.to_string())?,
        ),
    )
    .title("YTMD session")
    .inner_size(900.0, 680.0)
    .visible(false)
    .skip_taskbar(true)
    .user_agent(LOGIN_USER_AGENT)
    .data_directory(dir)
    .build()
    .map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub async fn rotate_session_cookies(app: AppHandle) -> Result<(), String> {
    let Some(win) = app.get_webview_window("session-keeper") else {
        return Err("session-keeper not running".into());
    };
    let _ = win.navigate(
        "https://music.youtube.com/"
            .parse()
            .map_err(|e: url::ParseError| e.to_string())?,
    );
    tokio::time::sleep(std::time::Duration::from_secs(4)).await;

    let yt_url: url::Url = "https://music.youtube.com".parse().unwrap();
    let (tx, rx) = std::sync::mpsc::channel::<Vec<(String, String)>>();
    let cwin = win.clone();
    let _ = app.run_on_main_thread(move || {
        let mut cs = cwin.cookies_for_url(yt_url).unwrap_or_default();
        if !cs.iter().any(|c| c.name() == "SAPISID") {
            if let Ok(all) = cwin.cookies() {
                cs = all
                    .into_iter()
                    .filter(|c| c.domain().map(|d| d.contains("youtube.com")).unwrap_or(false))
                    .collect();
            }
        }
        let pairs = cs
            .iter()
            .map(|c| (c.name().to_string(), c.value().to_string()))
            .collect();
        let _ = tx.send(pairs);
    });
    let found = rx.recv_timeout(std::time::Duration::from_secs(5)).unwrap_or_default();
    if found.is_empty() {
        return Err("no cookies from session-keeper".into());
    }
    let cookie_str = found
        .iter()
        .filter(|(n, _)| !n.starts_with("YTMD_"))
        .map(|(n, v)| format!("{n}={v}"))
        .collect::<Vec<_>>()
        .join("; ");

    let client = reqwest::Client::new();
    let res = client
        .post(format!("{API}/auth/refresh-cookies"))
        .json(&serde_json::json!({
            "cookie": cookie_str,
            "profile_name": PROFILE,
            "user_agent": LOGIN_USER_AGENT,
        }))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !res.status().is_success() {
        return Err(res.text().await.unwrap_or_else(|_| "refresh failed".into()));
    }
    Ok(())
}

#[tauri::command]
pub async fn google_logout(app: AppHandle) -> Result<(), String> {
    if let Some(w) = app.get_webview_window("login") {
        let _ = w.destroy();
    }
    if let Some(w) = app.get_webview_window("session-keeper") {
        let _ = w.destroy();
    }

    let client = reqwest::Client::new();
    let res = client
        .post(format!("{API}/auth/logout"))
        .send()
        .await
        .map_err(|e| format!("logout request failed: {e}"))?;
    // 400 = already logged out / no profile — treat as success for UI
    if !res.status().is_success() && res.status().as_u16() != 400 {
        let body = res.text().await.unwrap_or_default();
        return Err(if body.is_empty() {
            "logout failed".into()
        } else {
            body
        });
    }

    let dir = auth_data_dir(PROFILE);
    let _ = std::fs::remove_dir_all(&dir);
    let _ = app.emit("auth-signed-out", ());
    Ok(())
}

#[tauri::command]
pub async fn api_status() -> Result<serde_json::Value, String> {
    let client = reqwest::Client::new();
    let res = client
        .get(format!("{API}/auth/validate"))
        .send()
        .await
        .map_err(|e| format!("API not reachable: {e}"))?;
    let status = res.status();
    let body = res
        .json::<serde_json::Value>()
        .await
        .unwrap_or_else(|_| serde_json::json!({}));
    if !status.is_success() {
        return Ok(serde_json::json!({ "ok": false, "body": body }));
    }
    Ok(body)
}
