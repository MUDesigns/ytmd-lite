mod auth;
mod commands;
mod config;
mod discord;
mod lastfm;
mod player_state;
mod rpc;
mod server;
mod ytm;

use commands::AppState;
use discord::DiscordService;
use lastfm::LastFmService;
use player_state::{new_shared, PlayerState};
use rpc::RpcHub;
use server::ServerProcess;
use std::sync::Arc;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Listener, Manager, RunEvent, WindowEvent,
};
use tauri_plugin_global_shortcut::{Builder as ShortcutBuilder, GlobalShortcutExt, ShortcutState};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let _ = env_logger::try_init();

    let player = new_shared();
    let lastfm = LastFmService::new();
    let discord = DiscordService::new();
    let rpc = RpcHub::new();
    let server = ServerProcess::new();

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_process::init())
        .plugin(
            ShortcutBuilder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state != ShortcutState::Pressed {
                        return;
                    }
                    let _ = app;
                    let _ = event;
                })
                .build(),
        )
        .manage(AppState {
            player: player.clone(),
            lastfm: Arc::clone(&lastfm),
            discord: Arc::clone(&discord),
            rpc: Arc::clone(&rpc),
        })
        .manage(server)
        .invoke_handler(tauri::generate_handler![
            commands::get_lastfm_status,
            commands::get_player_snapshot,
            commands::set_lastfm_enabled,
            commands::set_scrobble_percent,
            commands::lastfm_sign_in,
            commands::lastfm_authorize,
            commands::lastfm_complete_auth,
            commands::lastfm_logout,
            commands::get_discord_rpc_status,
            commands::set_discord_rpc_enabled,
            commands::get_accent_color,
            commands::set_accent_color,
            commands::media_control,
            commands::ytm_rpc,
            commands::get_engine_status,
            commands::ytm_show_auth,
            commands::resize_ytm_webview,
            commands::create_ytm_webview,
            commands::set_ytm_visible,
            commands::get_secrets_path,
            commands::get_config_path,
            commands::reload_lastfm_credentials,
            commands::ingest_player_state,
            commands::ingest_rpc_reply,
            commands::ingest_audio_devices,
            commands::refresh_audio_outputs,
            commands::set_audio_output,
            commands::get_audio_output,
            commands::window_minimize,
            commands::window_toggle_maximize,
            commands::window_hide,
            commands::window_is_maximized,
            commands::window_start_dragging,
            auth::open_login_window,
            auth::close_login_window,
            auth::ensure_session_keeper,
            auth::rotate_session_cookies,
            auth::google_logout,
            auth::api_status,
        ])
        .setup(move |app| {
            #[cfg(any(windows, target_os = "macos", target_os = "linux"))]
            {
                app.handle()
                    .plugin(tauri_plugin_updater::Builder::new().build())?;
            }

            let handle = app.handle().clone();

            if let Err(e) = config::ensure_lastfm_credentials(&handle) {
                log::warn!("Could not install bundled Last.fm credentials: {e}");
            }
            lastfm.reload_credentials();
            lastfm.refresh_username_if_needed(handle.clone());

            {
                let sp = app.state::<ServerProcess>();
                let resource_dir = app.path().resource_dir().ok();
                match server::ensure_server(&sp, resource_dir) {
                    Ok(started) => {
                        log::info!("Catalog backend ready (spawned={started})");
                    }
                    Err(e) => {
                        log::error!("Catalog backend failed to start: {e}");
                    }
                }
            }

            ytm::create_main_window(
                &handle,
                player.clone(),
                Arc::clone(&lastfm),
                Arc::clone(&rpc),
            )
            .map_err(|e| {
                log::error!("Failed to create main window: {e}");
                e
            })?;

            // Try restoring session-keeper if cookies already exist
            let keeper = handle.clone();
            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                let _ = auth::ensure_session_keeper(keeper).await;
            });

            let player_for_listen = player.clone();
            let lastfm_for_listen = Arc::clone(&lastfm);
            let discord_for_listen = Arc::clone(&discord);
            let handle_for_listen = handle.clone();
            app.listen("ytm-player-state", move |event| {
                let raw = event.payload();
                let parsed = serde_json::from_str::<PlayerState>(raw).or_else(|_| {
                    #[derive(serde::Deserialize)]
                    struct Wrap {
                        payload: PlayerState,
                    }
                    serde_json::from_str::<Wrap>(raw).map(|w| w.payload)
                });
                match parsed {
                    Ok(payload) => {
                        ytm::ingest_player_payload(
                            &handle_for_listen,
                            &player_for_listen,
                            &lastfm_for_listen,
                            &discord_for_listen,
                            payload,
                        );
                    }
                    Err(e) => log::warn!("ytm-player-state parse error: {e} raw={raw}"),
                }
            });

            if let Some(window) = app.get_window(ytm::WINDOW_LABEL) {
                let app_for_resize = handle.clone();
                window.on_window_event(move |event| match event {
                    WindowEvent::Resized(_) | WindowEvent::ScaleFactorChanged { .. } => {
                        let _ = ytm::resize_chrome(&app_for_resize);
                    }
                    WindowEvent::Moved(_) => {
                        ytm::persist_window_bounds(&app_for_resize);
                    }
                    WindowEvent::CloseRequested { api, .. } => {
                        api.prevent_close();
                        ytm::persist_window_bounds(&app_for_resize);
                        if let Some(w) = app_for_resize.get_window(ytm::WINDOW_LABEL) {
                            let _ = w.hide();
                        }
                    }
                    _ => {}
                });
            }

            // Ensure chrome fills after first layout
            let _ = ytm::resize_chrome(&handle);

            let show_i = MenuItem::with_id(app, "show", "Show", true, None::<&str>)?;
            let play_i = MenuItem::with_id(app, "playPause", "Play / Pause", true, None::<&str>)?;
            let next_i = MenuItem::with_id(app, "next", "Next", true, None::<&str>)?;
            let prev_i = MenuItem::with_id(app, "previous", "Previous", true, None::<&str>)?;
            let quit_i = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show_i, &play_i, &next_i, &prev_i, &quit_i])?;

            let tray_handle = handle.clone();
            let _tray = TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .menu(&menu)
                .tooltip("YTMD Lite")
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(w) = app.get_window(ytm::WINDOW_LABEL) {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                    "playPause" => {
                        let _ = ytm::media_command(app, "playPause");
                    }
                    "next" => {
                        let _ = ytm::media_command(app, "next");
                    }
                    "previous" => {
                        let _ = ytm::media_command(app, "previous");
                    }
                    "quit" => {
                        ytm::persist_window_bounds(app);
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(move |_tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        if let Some(w) = tray_handle.get_window(ytm::WINDOW_LABEL) {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                })
                .build(app)?;

            let app_play = handle.clone();
            let _ = app.global_shortcut().on_shortcut("MediaPlayPause", move |_app, _s, e| {
                if e.state == ShortcutState::Pressed {
                    let _ = ytm::media_command(&app_play, "playPause");
                }
            });
            let app_next = handle.clone();
            let _ = app.global_shortcut().on_shortcut("MediaTrackNext", move |_app, _s, e| {
                if e.state == ShortcutState::Pressed {
                    let _ = ytm::media_command(&app_next, "next");
                }
            });
            let app_prev = handle.clone();
            let _ = app.global_shortcut().on_shortcut("MediaTrackPrevious", move |_app, _s, e| {
                if e.state == ShortcutState::Pressed {
                    let _ = ytm::media_command(&app_prev, "previous");
                }
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building YTMD Lite")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(state) = app.try_state::<AppState>() {
                    state.discord.shutdown();
                }
                if let Some(sp) = app.try_state::<ServerProcess>() {
                    server::stop_server(&sp);
                }
            }
        });
}
