"""Exercise playback routes without starting backend background services."""
import ast
import logging
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from flask import Flask, Response, jsonify, request


class PlaybackBackendTests(unittest.TestCase):
    def setUp(self):
        self.app = Flask(__name__)
        self.clock = Mock(return_value=0)
        self.resolver = Mock()
        self.ns = dict(app=self.app, Response=Response, jsonify=jsonify, request=request,
                       time=SimpleNamespace(monotonic=self.clock, time=lambda: 100),
                       _current_profile="account", _POT_AVAILABLE=True, _NODE22="node",
                       _pot_opts=lambda: {"extractor_args": {"youtube": {"player_client": ["web_music"]}}},
                       _AUDIO_FMT="audio", _STREAM_ATTEMPTS=[("audio", None, True)] * 9,
                       _ydl_extract_url=Mock(), _stream_url_from_info=lambda info: info.get("url"),
                       _logging=logging.getLogger(__name__), _stream_resolver=self.resolver)
        tree = ast.parse(Path("python-backend/server.py").read_text(encoding="utf-8"))
        names = {"_extract_playback_stream", "audio_stream", "audio_stream_warm", "stream_url",
                 "_is_hard_error", "_is_unavailable"}
        selected = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names], type_ignores=[])
        exec(compile(selected, "playback-routes", "exec"), self.ns)
        self.http = self.app.test_client()

    def test_extraction_stops_starting_attempts_after_budget(self):
        def fail(*args, **kwargs):
            self.clock.return_value += 11
            raise RuntimeError("no formats")
        self.ns["_ydl_extract_url"].side_effect = fail
        result = self.ns["_extract_playback_stream"](("account", "song"))
        self.assertIn("Timed out", result["error"])
        self.assertEqual(self.ns["_ydl_extract_url"].call_count, 2)
        opts = self.ns["_ydl_extract_url"].call_args.kwargs["extra_opts"]
        self.assertEqual(opts["extractor_retries"], 0)
        self.assertEqual(opts["socket_timeout"], 6)

    def test_account_change_does_not_resolve_with_other_accounts_cookies(self):
        result = self.ns["_extract_playback_stream"](("old", "song"))
        self.assertIn("Account changed", result["error"])
        self.ns["_ydl_extract_url"].assert_not_called()

    def test_warm_reports_errors_without_leaking_signed_url(self):
        self.resolver.resolve.return_value = {"url": "secret-url", "resolve_ms": 123}
        response = self.http.get("/audio-stream/song/warm")
        self.assertEqual(response.json, {"ok": True, "resolve_ms": 123})
        self.resolver.resolve.return_value = {"error": "busy", "busy": True}
        response = self.http.get("/audio-stream/song/warm")
        self.assertEqual(response.status_code, 503)
        self.assertFalse(response.json["ok"])

    def test_expired_url_retries_once_preserving_range_and_closing_connections(self):
        self.resolver.resolve.side_effect = [{"url": "expired"}, {"url": "fresh"}]
        old = Mock(status_code=403)
        upstream = Mock(status_code=206, headers={"Content-Range": "bytes 0-2/3", "Content-Length": "3"})
        upstream.iter_content.return_value = iter([b"abc"])
        with patch("requests.get", side_effect=[old, upstream]) as get:
            response = self.http.get("/audio-stream/song", headers={"Range": "bytes=0-2"})
            self.assertEqual(response.status_code, 206)
            self.assertEqual(response.data, b"abc")
            response.close()
        self.resolver.invalidate.assert_called_once_with(("account", "song"))
        old.close.assert_called_once()
        self.assertTrue(upstream.close.called)
        self.assertEqual(get.call_args.kwargs["headers"]["Range"], "bytes=0-2")
        upstream.iter_content.assert_called_once_with(chunk_size=16384)


if __name__ == "__main__":
    unittest.main()
