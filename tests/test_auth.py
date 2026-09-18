"""Exercise auth handlers without starting the server's background jobs or real profiles."""
import ast
import json
import logging
import os
from pathlib import Path
import tempfile
import time
import threading
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

from flask import Flask, jsonify, request


class AuthTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "default.json"
        self.original = {"cookie": "SAPISID=original", "authorization": "original"}
        self.path.write_text(json.dumps(self.original))
        self.client = Mock(base_headers=dict(self.original))
        self.app = Flask(__name__)
        self.ns = dict(
            app=self.app, jsonify=jsonify, request=request, os=os, json=json, time=time,
            _logging=logging.getLogger(__name__), _ytm=self.client,
            _current_profile="default", _playlist_cache={"kept": True}, _adding_account=False,
            _psidts_last_refresh=0, _LAST_AUTHED=None,
            PROFILES_DIR=str(self.root), profile_path=lambda name: str(self.root / f"{name}.json"),
            meta_path=lambda name: str(self.root / f"{name}.meta.json"),
            _SHORT_LIVED_COOKIES={"SIDCC"}, requests=Mock(),
            is_local_profile=lambda name: False, YTMusic=Mock(), threading=Mock(),
            fetch_account_info=Mock(),
            _auth_write_lock=threading.RLock(),
        )
        tree = ast.parse(Path("python-backend/server.py").read_text(encoding="utf-8"))
        names = {"clean_headers_for_storage", "_write_auth_json", "_serialize_auth_write", "cookie_login", "refresh_cookies", "_refresh_ytm_psidts", "logout"}
        selected = ast.Module(body=[n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names], type_ignores=[])
        exec(compile(selected, "server-auth", "exec"), self.ns)
        self.http = self.app.test_client()

    def test_failed_relogin_preserves_saved_and_live_session(self):
        self.ns["YTMusic"].return_value.get_liked_songs.side_effect = TimeoutError("offline")
        response = self.http.post("/auth/cookie-login", json={"cookie": "SAPISID=new"})
        self.assertEqual(response.status_code, 500)
        self.assertEqual(json.loads(self.path.read_text()), self.original)
        self.assertIs(self.ns["_ytm"], self.client)
        self.assertEqual(self.ns["_playlist_cache"], {"kept": True})

    def test_successful_relogin_saves_verified_cookies_and_brand_account(self):
        response = self.http.post("/auth/cookie-login", json={
            "cookie": "SAPISID=new", "delegated_session_id": "brand-id",
        })
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json.loads(self.path.read_text())["cookie"], "SAPISID=new")
        self.assertEqual(self.ns["YTMusic"].call_args.kwargs["user"], "brand-id")
        self.assertEqual(json.loads((self.root / "default.meta.json").read_text())["brandUserId"], "brand-id")
        self.assertIs(self.ns["_ytm"], self.ns["YTMusic"].return_value)

    def test_refresh_rejects_another_profile_and_missing_auth_cookie(self):
        for payload in [
            {"profile_name": "other", "cookie": "SAPISID=new"},
            {"profile_name": "default", "cookie": "notSAPISID=new"},
            {"profile_name": "default", "cookie": "SAPISID="},
        ]:
            with self.subTest(payload=payload):
                self.assertGreaterEqual(self.http.post("/auth/refresh-cookies", json=payload).status_code, 400)
                self.assertEqual(json.loads(self.path.read_text()), self.original)
                self.assertEqual(self.client.base_headers, self.original)

    def test_refresh_persists_cookies_for_restart(self):
        response = self.http.post("/auth/refresh-cookies", json={
            "profile_name": "default", "cookie": "SAPISID=new; __Secure-1PSIDTS=rotated",
        })
        self.assertEqual(response.status_code, 200)
        saved = json.loads(self.path.read_text())
        self.assertEqual(saved["cookie"], self.client.base_headers["cookie"])
        self.assertIn("rotated", saved["cookie"])
        self.assertEqual(self.client.sapisid, "new")

    def test_failed_atomic_write_keeps_previous_file(self):
        with patch.object(os, "replace", side_effect=OSError("disk error")):
            with self.assertRaises(OSError):
                self.ns["_write_auth_json"](str(self.path), {"cookie": "new"})
        self.assertEqual(json.loads(self.path.read_text()), self.original)
        self.assertEqual(list(self.root.glob("*.tmp")), [])

    def test_refresh_write_failure_is_reported_and_preserves_live_session(self):
        with patch.object(os, "replace", side_effect=OSError("disk error")):
            response = self.http.post("/auth/refresh-cookies", json={
                "profile_name": "default", "cookie": "SAPISID=new",
            })
        self.assertEqual(response.status_code, 500)
        self.assertEqual(self.client.base_headers, self.original)

    def test_slow_background_refresh_cannot_overwrite_new_browser_cookies(self):
        session = self.ns["requests"].Session.return_value
        session.cookies = [SimpleNamespace(name="SIDCC", value="stale")]
        def refreshed_while_waiting(*args, **kwargs):
            self.client.base_headers["cookie"] = "SAPISID=new; SIDCC=new"
            return SimpleNamespace(text='"LOGGED_IN":true', status_code=200)
        session.get.side_effect = refreshed_while_waiting
        self.ns["_refresh_ytm_psidts"](force=True)
        self.assertEqual(self.client.base_headers["cookie"], "SAPISID=new; SIDCC=new")
        self.assertEqual(json.loads(self.path.read_text()), self.original)

    def test_explicit_logout_still_removes_credentials_and_rejects_refresh(self):
        self.assertEqual(self.http.post("/auth/logout").status_code, 200)
        self.assertFalse(self.path.exists())
        self.assertIsNone(self.ns["_ytm"])
        self.assertEqual(self.http.post("/auth/refresh-cookies", json={
            "profile_name": "default", "cookie": "SAPISID=new",
        }).status_code, 400)
        self.assertFalse(self.path.exists())


if __name__ == "__main__":
    unittest.main()
