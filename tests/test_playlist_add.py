"""Playlist writes must not report success for a rejected YouTube response."""
import ast
from pathlib import Path
import unittest
from unittest.mock import Mock
from flask import Flask, jsonify, request


class PlaylistAddTests(unittest.TestCase):
    def setUp(self):
        app = Flask(__name__)
        self.ytmusic = Mock()
        self.purge = Mock()
        ns = dict(app=app, jsonify=jsonify, request=request, _current_profile="account",
                  is_local_profile=lambda _: False, get_ytmusic=lambda: self.ytmusic,
                  _purge_playlist_cache=self.purge)
        tree = ast.parse(Path("python-backend/server.py").read_text(encoding="utf-8"))
        selected = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef)
                                    and n.name == "playlist_add_tracks"], type_ignores=[])
        exec(compile(selected, "playlist-add", "exec"), ns)
        self.http = app.test_client()

    def test_success_invalidates_cache(self):
        self.ytmusic.add_playlist_items.return_value = {"status": "STATUS_SUCCEEDED"}
        response = self.http.post("/playlist/PL-owned/add", json={"videoIds": ["song"]})
        self.assertEqual(response.json, {"ok": True})
        self.ytmusic.add_playlist_items.assert_called_once_with("PL-owned", ["song"])
        self.purge.assert_called_once_with("PL-owned")

    def test_rejections_and_missing_status_are_errors(self):
        for result in [{"status": "STATUS_FAILED"}, {}, None]:
            self.ytmusic.add_playlist_items.return_value = result
            response = self.http.post("/playlist/PL-owned/add", json={"videoIds": ["song"]})
            self.assertEqual(response.status_code, 400)
            self.assertIn("error", response.json)
        self.purge.assert_not_called()
