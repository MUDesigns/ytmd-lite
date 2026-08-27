//! Spawn / attach to the local YT Music API (ytmusicapi + yt-dlp) on :9847.
//! Backend source lives in `python-backend/` (adapted from Kodama).

use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;

pub struct ServerProcess(pub Mutex<Option<Child>>);

impl ServerProcess {
    pub fn new() -> Self {
        Self(Mutex::new(None))
    }
}

pub const API_BASE: &str = "http://127.0.0.1:9847";

fn port_open() -> bool {
    std::net::TcpStream::connect_timeout(
        &"127.0.0.1:9847".parse().unwrap(),
        Duration::from_millis(200),
    )
    .is_ok()
}

fn server_candidates(resource_dir: Option<&std::path::Path>) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));

    // Dev: lite/python-backend/server.py (repo root next to src-tauri)
    out.push(manifest.join("..").join("python-backend").join("server.py"));
    // Legacy monorepo layouts
    out.push(
        manifest
            .join("..")
            .join("..")
            .join("Kodama")
            .join("python-backend")
            .join("server.py"),
    );

    if let Some(res) = resource_dir {
        out.push(res.join("python-backend").join("server.py"));
        out.push(res.join("server.py"));
        #[cfg(windows)]
        {
            out.push(res.join("ytmd-backend.exe"));
            out.push(res.join("kodama-server.exe"));
        }
        #[cfg(not(windows))]
        {
            out.push(res.join("ytmd-backend"));
            out.push(res.join("kodama-server"));
        }
    }

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            #[cfg(windows)]
            {
                out.push(dir.join("ytmd-backend.exe"));
                out.push(dir.join("kodama-server.exe"));
            }
            #[cfg(not(windows))]
            {
                out.push(dir.join("ytmd-backend"));
                out.push(dir.join("kodama-server"));
            }
            out.push(dir.join("python-backend").join("server.py"));
            out.push(
                dir.join("..")
                    .join("..")
                    .join("..")
                    .join("python-backend")
                    .join("server.py"),
            );
        }
    }

    // Sidecar next to src-tauri during `tauri dev` / local builds
    out.push(manifest.join("binaries").join("ytmd-backend.exe"));
    out.push(manifest.join("binaries").join("ytmd-backend"));
    out.push(manifest.join("binaries").join("kodama-server.exe"));
    out.push(manifest.join("binaries").join("kodama-server"));

    out
}

fn python_cmds(server_py: &std::path::Path) -> Vec<Command> {
    let mut cmds = Vec::new();
    if let Some(dir) = server_py.parent() {
        #[cfg(windows)]
        let venv_py = dir.join(".venv").join("Scripts").join("python.exe");
        #[cfg(not(windows))]
        let venv_py = dir.join(".venv").join("bin").join("python");
        if venv_py.exists() {
            cmds.push(Command::new(venv_py));
        }
    }
    #[cfg(windows)]
    {
        let mut py = Command::new("py");
        py.args(["-3"]);
        cmds.push(py);
    }
    cmds.push(Command::new("python"));
    cmds.push(Command::new("python3"));
    cmds
}

/// Start the local API if nothing is listening on 9847.
pub fn ensure_server(state: &ServerProcess, resource_dir: Option<PathBuf>) {
    if port_open() {
        log::info!("YTMD API already running on :9847");
        return;
    }

    let candidates = server_candidates(resource_dir.as_deref());
    let target = candidates.iter().find(|p| p.exists()).map(|p| {
        p.canonicalize().unwrap_or_else(|_| p.clone())
    });
    let Some(path) = target else {
        log::error!("YTMD backend not found. Looked for: {candidates:?}");
        return;
    };

    log::info!("Starting YTMD backend: {}", path.display());

    let log_path = std::env::temp_dir().join("ytmd-lite-backend.log");
    let log_file = std::fs::File::create(&log_path).ok();

    let is_py = path.extension().and_then(|e| e.to_str()) == Some("py");

    let spawn_one = |mut cmd: Command| -> Result<Child, std::io::Error> {
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        if let Some(ref f) = log_file {
            cmd.stdout(Stdio::from(f.try_clone()?));
            cmd.stderr(Stdio::from(f.try_clone()?));
        } else {
            cmd.stdout(Stdio::null()).stderr(Stdio::null());
        }
        cmd.spawn()
    };

    let child = if is_py {
        let mut last_err = None;
        let mut spawned = None;
        for mut base in python_cmds(&path) {
            base.arg(&path);
            if let Some(dir) = path.parent() {
                base.current_dir(dir);
            }
            match spawn_one(base) {
                Ok(c) => {
                    spawned = Some(c);
                    break;
                }
                Err(e) => last_err = Some(e),
            }
        }
        match spawned {
            Some(c) => c,
            None => {
                log::error!("Failed to start backend with python: {:?}", last_err);
                return;
            }
        }
    } else {
        match spawn_one(Command::new(&path)) {
            Ok(c) => c,
            Err(e) => {
                log::error!("Failed to start backend: {e}");
                return;
            }
        }
    };

    *state.0.lock().unwrap() = Some(child);
    for _ in 0..80 {
        if port_open() {
            log::info!("YTMD API ready on :9847 (log: {})", log_path.display());
            return;
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    log::warn!(
        "YTMD API spawn timed out waiting for :9847 — see {}",
        log_path.display()
    );
}

pub fn stop_server(state: &ServerProcess) {
    let Some(mut child) = state.0.lock().unwrap().take() else {
        return;
    };
    let _ = ureq_shutdown();
    let _ = child.kill();
}

fn ureq_shutdown() {
    use std::io::Write;
    if let Ok(mut stream) = std::net::TcpStream::connect_timeout(
        &"127.0.0.1:9847".parse().unwrap(),
        Duration::from_millis(300),
    ) {
        let _ = stream.write_all(
            b"POST /shutdown HTTP/1.0\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n",
        );
    }
}
