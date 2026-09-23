"""Build the pinned token provider and stage its runtime for dev and installers."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import socket
import time
from urllib.request import ProxyHandler, build_opener, urlopen

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.3.1"  # Must match python-backend/requirements.txt.


def run(args, cwd=ROOT):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def check_generator(runtime, server):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    opener = build_opener(ProxyHandler({}))
    log_path = ROOT / "python-backend/build_tmp/potgen-check.log"
    with log_path.open("w+") as log:
        process = subprocess.Popen([str(runtime), str(server / "build/main.js"), "--port", str(port)],
                                   cwd=server, stdout=log, stderr=log,
                                   **({"creationflags": 0x08000000} if sys.platform == "win32" else {}))
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline and process.poll() is None:
                try:
                    with opener.open(f"http://127.0.0.1:{port}/ping", timeout=0.5) as response:
                        if json.load(response).get("version") == VERSION:
                            print("Staged token generator health check passed", flush=True)
                            return
                except OSError:
                    pass
                time.sleep(0.1)
            raise RuntimeError(f"Token generator did not become ready; see {log_path}")
        finally:
            if process.poll() is None:
                process.terminate()
            process.wait(timeout=10)


def main():
    node = shutil.which("node")
    npm = shutil.which("npm.cmd" if sys.platform == "win32" else "npm")
    if not node or not npm:
        raise RuntimeError("Install Node 22 or newer (including npm) first")
    version = subprocess.check_output([node, "--version"], text=True).strip()
    if int(version.lstrip("v").split(".")[0]) < 22:
        raise RuntimeError("Playback requires Node 22 or newer")
    checkout = ROOT / "python-backend" / "build_tmp" / f"potgen-{VERSION}"
    if not checkout.exists():
        checkout.parent.mkdir(parents=True, exist_ok=True)
        run(["git", "clone", "--depth", "1", "--branch", VERSION,
             "https://github.com/Brainicism/bgutil-ytdlp-pot-provider.git", checkout])
    tag = subprocess.check_output(["git", "describe", "--tags", "--exact-match"], cwd=checkout, text=True).strip()
    if tag != VERSION:
        raise RuntimeError(f"Unexpected token provider checkout: {tag}")
    server = checkout / "server"
    # This bundled helper is local to the desktop app. Upstream 1.x binds all
    # interfaces; restrict both its primary and fallback listener to loopback.
    main_path = server / "src/main.ts"
    main_source = main_path.read_text()
    main_path.write_text(main_source.replace('host: "::"', 'host: "127.0.0.1"')
                         .replace('host: "0.0.0.0"', 'host: "127.0.0.1"'))
    run([npm, "ci"], server)
    run([node, server / "node_modules/typescript/bin/tsc"], server)
    run([npm, "prune", "--omit=dev"], server)
    resources = ROOT / "src-tauri/resources"
    destination = resources / "potgen/server"
    destination.mkdir(parents=True, exist_ok=True)
    for folder in ("build", "node_modules"):
        shutil.copytree(server / folder, destination / folder, dirs_exist_ok=True)
    for name in ("package.json", "package-lock.json", "tsconfig.json"):
        shutil.copy2(server / name, destination / name)
    for license_file in checkout.glob("LICENSE*"):
        shutil.copy2(license_file, destination / license_file.name)
    shutil.copytree(server / "src", destination / "src", dirs_exist_ok=True)
    (destination / "YTMD-NOTICE.txt").write_text(
        f"bgutil-ytdlp-pot-provider {VERSION}, GPL-3.0-only.\n"
        "Source: https://github.com/Brainicism/bgutil-ytdlp-pot-provider\n"
        "YTMD Lite modifies src/main.ts to bind only to 127.0.0.1.\n")
    runtime = Path(subprocess.check_output([node, "-p", "process.execPath"], text=True).strip())
    runtime_name = "node.exe" if sys.platform == "win32" else "node"
    shutil.copy2(runtime, resources / runtime_name)
    check_generator(resources / runtime_name, destination)
    with urlopen(f"https://raw.githubusercontent.com/nodejs/node/{version}/LICENSE", timeout=30) as response:
        (resources / "NODE-LICENSE").write_bytes(response.read())
    (resources / "playback-runtime.json").write_text(json.dumps({
        "node": version, "provider": VERSION,
    }, indent=2) + "\n")
    config_path = ROOT / "src-tauri/tauri.conf.json"
    config = json.loads(config_path.read_text())
    entries = config["bundle"].setdefault("resources", [])
    entries[:] = [entry for entry in entries if entry.lower() != "resources/node.exe"]
    for entry in (f"resources/{runtime_name}", "resources/potgen/server/**/*", "resources/playback-runtime.json", "resources/NODE-LICENSE"):
        if entry not in entries:
            entries.append(entry)
    config_path.write_text(json.dumps(config, indent=2) + "\n")
    print(f"Playback runtime ready: Node {version}, token provider {VERSION}")


if __name__ == "__main__":
    main()
