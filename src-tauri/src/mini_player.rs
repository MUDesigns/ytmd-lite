use tauri::{AppHandle, Emitter, LogicalSize, Manager, PhysicalPosition, PhysicalSize, WebviewUrl, WebviewWindowBuilder, WindowEvent};

const LABEL: &str = "mini-player";
const WIDTH: f64 = 220.0;
const HEIGHT: f64 = 180.0;

fn restore_main(app: &AppHandle) {
    if let Some(main) = app.get_window(crate::ytm::WINDOW_LABEL) {
        let _ = main.show();
        let _ = main.unminimize();
        let _ = main.set_focus();
    }
}

fn bottom_right(x: i32, y: i32, width: u32, height: u32, window_width: u32, window_height: u32, gap: i32) -> PhysicalPosition<i32> {
    PhysicalPosition::new(
        x + (width as i32 - window_width as i32 - gap).max(0),
        y + (height as i32 - window_height as i32 - gap).max(0),
    )
}

#[tauri::command]
pub async fn open_mini_player(app: AppHandle) -> Result<(), String> {
    let main = app.get_window(crate::ytm::WINDOW_LABEL).ok_or("Main window missing")?;
    let monitor = main.current_monitor().map_err(|e| e.to_string())?
        .or(main.primary_monitor().map_err(|e| e.to_string())?);
    let mini = if let Some(window) = app.get_webview_window(LABEL) {
        window
    } else {
        let url = if cfg!(debug_assertions) {
            WebviewUrl::External("http://localhost:1420/mini/".parse().map_err(|e: url::ParseError| e.to_string())?)
        } else {
            // Use the router URL; Tauri resolves its assets to mini/index.html.
            // Opening that filename directly makes Svelte route to a 404 page.
            WebviewUrl::App("mini/".into())
        };
        let window = WebviewWindowBuilder::new(&app, LABEL, url)
            .title("YTMD Lite — Mini player")
            .inner_size(WIDTH, HEIGHT)
            .decorations(false)
            .resizable(false)
            .maximizable(false)
            .always_on_top(true)
            .visible(false)
            .build().map_err(|e| e.to_string())?;
        let handle = app.clone();
        window.on_window_event(move |event| match event {
            WindowEvent::CloseRequested { .. } => restore_main(&handle),
            WindowEvent::Destroyed => { let _ = handle.emit("mini-player-closed", ()); }
            _ => {}
        });
        window
    };
    if let Some(monitor) = monitor {
        let area = monitor.work_area();
        let scale = monitor.scale_factor();
        let size = PhysicalSize::new((WIDTH * scale).round() as u32, (HEIGHT * scale).round() as u32);
        let position = bottom_right(area.position.x, area.position.y, area.size.width, area.size.height, size.width, size.height, (16.0 * scale).round() as i32);
        mini.set_position(position).map_err(|e| e.to_string())?;
        mini.set_size(size).map_err(|e| e.to_string())?;
        // Windows can retain invisible resize borders on an undecorated window.
        // Align its actual outer bounds, not just the requested content size.
        let outer = mini.outer_size().map_err(|e| e.to_string())?;
        mini.set_position(bottom_right(area.position.x, area.position.y, area.size.width, area.size.height, outer.width, outer.height, (16.0 * scale).round() as i32))
            .map_err(|e| e.to_string())?;
    } else {
        mini.set_size(LogicalSize::new(WIDTH, HEIGHT)).map_err(|e| e.to_string())?;
        mini.center().map_err(|e| e.to_string())?;
    }
    mini.show().map_err(|e| e.to_string())?;
    mini.set_focus().map_err(|e| e.to_string())?;
    main.hide().map_err(|e| e.to_string())?;
    Ok(())
}

#[tauri::command]
pub fn close_mini_player(app: AppHandle) -> Result<(), String> {
    restore_main(&app);
    if let Some(mini) = app.get_webview_window(LABEL) {
        mini.close().map_err(|e| e.to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub fn pin_mini_player(app: AppHandle, pinned: bool) -> Result<(), String> {
    app.get_webview_window(LABEL).ok_or("Mini player missing")?
        .set_always_on_top(pinned).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn placement_respects_work_area_and_negative_monitor_origins() {
        assert_eq!(bottom_right(0, 0, 1920, 1040, 420, 194, 16), PhysicalPosition::new(1484, 830));
        assert_eq!(bottom_right(-2560, -200, 2560, 1400, 630, 291, 24), PhysicalPosition::new(-654, 885));
        assert_eq!(bottom_right(30, 40, 320, 160, 420, 194, 16), PhysicalPosition::new(30, 40));
    }
}
