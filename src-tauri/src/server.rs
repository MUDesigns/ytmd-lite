//! Spawn / attach to the local YT Music API (ytmusicapi + yt-dlp) on :9847.
//! Prefer the bundled `ytmd-backend` sidecar in release builds; fall back to
//! `python-backend/server.py` for local development.

use std::path::{Path, PathBuf};
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

fn push_unique(out: &mut Vec<PathBuf>, path: PathBuf) {
    if !out.iter().any(|p| p == &path) {
        out.push(path);
    }
}

fn push_sidecars(out: &mut Vec<PathBuf>, dir: &Path) {
    #[cfg(windows)]
    {
        push_unique(out, dir.join("ytmd-backend.exe"));
        push_unique(out, dir.join("kodama-server.exe"));
        push_unique(out, dir.join("resources").join("ytmd-backend.exe"));
        push_unique(out, dir.join("resources").join("kodama-server.exe"));
    }
    #[cfg(not(windows))]
    {
        push_unique(out, dir.join("ytmd-backend"));
        push_unique(out, dir.join("kodama-server"));
        push_unique(out, dir.join("resources").join("ytmd-backend"));
        push_unique(out, dir.join("resources").join("kodama-server"));
    }
}

fn push_python_sources(out: &mut Vec<PathBuf>, dir: &Path) {
    push_unique(out, dir.join("python-backend").join("server.py"));
    push_unique(out, dir.join("_up_").join("python-backend").join("server.py"));
    push_unique(out, dir.join("server.py"));
}

fn server_candidates(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    // Sidecars first (self-contained release), then Python sources (dev).
    let mut bins = Vec::new();
    let mut pys = Vec::new();

    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            push_sidecars(&mut bins, dir);
            push_python_sources(&mut pys, dir);
            // NSIS / MSI sometimes nest resources one level up from a versioned folder
            if let Some(parent) = dir.parent() {
                push_sidecars(&mut bins, parent);
                push_python_sources(&mut pys, parent);
            }
        }
    }

    if let Some(res) = resource_dir {
        push_sidecars(&mut bins, res);
        push_python_sources(&mut pys, res);
        if let Some(parent) = res.parent() {
            push_sidecars(&mut bins, parent);
            push_python_sources(&mut pys, parent);
        }
    }

    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    // Dev layouts
    push_sidecars(&mut bins, &manifest.join("binaries"));
    push_python_sources(&mut pys, &manifest.join(".."));
    push_python_sources(
        &mut pys,
        &manifest.join("..").join("..").join("Kodama"),
    );

    bins.extend(pys);
    bins
}

fn python_cmds(server_py: &Path) -> Vec<Command> {
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
/// Returns Ok(true) when the port is ready, Ok(false) when already running,
/// or Err with a user-facing reason.
pub fn ensure_server(state: &ServerProcess, resource_dir: Option<PathBuf>) -> Result<bool, String> {
    if port_open() {
        log::info!("YTMD API already running on :9847");
        return Ok(false);
    }

    let candidates = server_candidates(resource_dir.as_deref());
    let existing: Vec<_> = candidates.iter().filter(|p| p.exists()).cloned().collect();
    log::info!(
        "Backend candidates (existing): {:?}",
        existing.iter().map(|p| p.display().to_string()).collect::<Vec<_>>()
    );

    let target = existing.first().cloned().map(|p| {
        p.canonicalize().unwrap_or(p)
    });
    let Some(path) = target else {
        let msg = format!(
            "Catalog backend not found. Looked for ytmd-backend next to the app (resources/) and python-backend/server.py."
        );
        log::error!("{msg} candidates={candidates:?}");
        return Err(msg);
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
                let msg = format!(
                    "Failed to start Python backend ({:?}). Install Python 3 or use a release build with ytmd-backend.exe.",
                    last_err
                );
                log::error!("{msg}");
                return Err(msg);
            }
        }
    } else {
        match spawn_one(Command::new(&path)) {
            Ok(c) => c,
            Err(e) => {
                let msg = format!("Failed to start backend {}: {e}", path.display());
                log::error!("{msg}");
                return Err(msg);
            }
        }
    };

    *state.0.lock().unwrap() = Some(child);
    for _ in 0..120 {
        if port_open() {
            log::info!("YTMD API ready on :9847 (log: {})", log_path.display());
            return Ok(true);
        }
        std::thread::sleep(Duration::from_millis(250));
    }
    let msg = format!(
        "Backend started but :9847 never came up — see {}",
        log_path.display()
    );
    log::warn!("{msg}");
    Err(msg)
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
