"""Android host for the shared python-backend (server.py).

The desktop app runs server.py as a separate process on :9847. On Android it runs
in-process under Chaquopy instead, on a random loopback port. Any app on the device
can reach loopback ports, so every request must carry the per-launch token the
Kotlin side generated; this keeps the signed-in YouTube session private to YTMD Lite.
"""

import hmac
import os
import threading

_server = None


class _TokenGate:
    def __init__(self, app, token):
        self._app = app
        self._token = token.encode()

    def __call__(self, environ, start_response):
        sent = environ.get("HTTP_X_YTMD_TOKEN", "").encode()
        if not hmac.compare_digest(sent, self._token):
            start_response("403 Forbidden", [("Content-Type", "application/json")])
            return [b'{"error": "forbidden"}']
        return self._app(environ, start_response)


def start(data_dir, token):
    """Start the API once and return its port."""
    global _server
    if _server is None:
        os.environ["YTMD_DATA_DIR"] = data_dir
        import server
        from werkzeug.serving import make_server

        _server = make_server("127.0.0.1", 0, _TokenGate(server.app, token), threaded=True)
        threading.Thread(target=_server.serve_forever, name="ytmd-api", daemon=True).start()
    return _server.server_port
