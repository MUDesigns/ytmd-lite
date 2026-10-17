"""Downloaded songs stay on disk exactly as long as something still wants them."""
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path("python-backend").resolve()))
from offline_library import OfflineLibrary, collection_key, mime_for


class FakeDownloader:
    """Writes a tiny .opus file; downloads can be held open to observe progress and cancellation."""

    def __init__(self):
        self.calls = []
        self.fail = set()
        self.gate = {}

    def __call__(self, video_id, template, progress):
        self.calls.append(video_id)
        gate = self.gate.get(video_id)
        if gate:
            progress(0.5)
            gate.wait(5)
        if video_id in self.fail:
            raise RuntimeError(f"no formats for {video_id}")
        path = template % {"ext": "opus"}
        with open(path, "wb") as handle:
            handle.write(b"audio-" + video_id.encode())
        progress(1.0)
        return path


def wait_until(predicate, timeout=5):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.01)
    return False


class OfflineLibraryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.downloader = FakeDownloader()
        self.art = []
        self.lib = OfflineLibrary(self.tmp.name, self.downloader, fetch_image=self.art.append, workers=1)

    def tearDown(self):
        self.tmp.cleanup()

    def idle(self):
        return wait_until(lambda: self.lib.status()["active"] == 0 and self.lib.status()["queued"] == 0)

    def test_pinned_song_downloads_with_metadata_and_artwork(self):
        result = self.lib.pin("song1", {"title": "One", "artists": [{"name": "A"}, {"name": "B"}],
                                        "thumbnail": "https://img/1", "ignored": "x"})
        self.assertEqual(result["status"], "queued")
        self.assertTrue(self.idle())
        self.assertTrue(self.lib.is_downloaded("song1"))
        meta = self.lib.read_meta("song1")
        self.assertEqual(meta["artists"], "A, B")
        self.assertTrue(meta["pinned"])
        self.assertNotIn("ignored", meta)
        self.assertEqual(self.art, ["https://img/1"])
        self.assertEqual(self.lib.pin("song1")["status"], "done")
        self.assertEqual(self.lib.song_status("song1"), "done")
        library = self.lib.library()
        self.assertEqual([s["videoId"] for s in library["songs"]], ["song1"])
        self.assertEqual(library["totalBytes"], len(b"audio-song1"))
        self.assertEqual(mime_for(self.lib.audio_path("song1")), "audio/opus")

    def test_collection_shares_songs_and_removal_keeps_pinned_or_shared_tracks(self):
        tracks = [{"videoId": "a", "title": "A"}, {"videoId": "b", "title": "B"}, {"videoId": "a"}]
        result = self.lib.add_collection("playlist", "PL1", tracks, title="Mix", thumbnail="https://img/pl")
        self.assertEqual(result, {"key": "playlist:PL1", "queued": 2, "total": 2})
        self.lib.add_collection("album", "AL1", [{"videoId": "b"}, {"videoId": "c"}], title="Album")
        self.lib.pin("a")
        self.assertTrue(self.idle())
        self.assertEqual(sorted(self.downloader.calls), ["a", "b", "c"])
        library = self.lib.library()
        self.assertEqual({c["key"]: (c["done"], c["total"]) for c in library["collections"]},
                         {"playlist:PL1": (2, 2), "album:AL1": (2, 2)})

        self.lib.remove_collection("playlist:PL1")
        # "a" is pinned and "b" belongs to the album: both survive.
        self.assertTrue(self.lib.is_downloaded("a"))
        self.assertTrue(self.lib.is_downloaded("b"))
        self.assertEqual(self.lib.unpin("a"), {"removed": 1})
        self.assertFalse(self.lib.is_downloaded("a"))
        # Unpinning a track that an album still needs only drops the pin.
        self.lib.pin("b")
        self.assertEqual(self.lib.unpin("b"), {"removed": 0})
        self.assertTrue(self.lib.is_downloaded("b"))
        self.assertFalse(self.lib.read_meta("b")["pinned"])
        self.lib.remove_collection("album:AL1")
        self.assertFalse(self.lib.is_downloaded("b"))
        self.assertFalse(self.lib.is_downloaded("c"))
        self.assertEqual(self.lib.library()["collections"], [])

    def test_failures_are_reported_and_can_be_retried(self):
        self.downloader.fail.add("bad")
        self.lib.add_collection("playlist", "PL2", [{"videoId": "bad", "title": "Bad"}, {"videoId": "ok"}])
        self.assertTrue(self.idle())
        status = self.lib.status()
        self.assertEqual(status["downloads"]["bad"]["status"], "error")
        self.assertIn("no formats", status["downloads"]["bad"]["error"])
        self.assertEqual(status["failed"], 1)
        collection = self.lib.library()["collections"][0]
        self.assertEqual((collection["done"], collection["failed"], collection["total"]), (1, 1, 2))
        self.downloader.fail.clear()
        self.assertEqual(self.lib.retry_failed(), 1)
        self.assertTrue(self.idle())
        self.assertTrue(self.lib.is_downloaded("bad"))
        self.assertEqual(self.lib.status()["downloads"], {})

    def test_cancelling_an_active_download_discards_its_file(self):
        gate = self.downloader.gate["slow"] = threading.Event()
        self.lib.pin("slow", {"title": "Slow"})
        self.assertTrue(wait_until(lambda: self.lib.status()["downloads"].get("slow", {}).get("progress") == 0.5))
        before = self.lib.generation
        self.assertTrue(self.lib.cancel("slow"))
        gate.set()
        self.assertTrue(self.idle())
        self.assertFalse(self.lib.is_downloaded("slow"))
        self.assertGreater(self.lib.generation, before)
        self.assertEqual(self.lib.song_status("slow"), "not_found")

    def test_invalid_requests_and_remove_all(self):
        with self.assertRaises(ValueError):
            self.lib.add_collection("playlist", "PL3", [{"title": "no id"}])
        with self.assertRaises(ValueError):
            self.lib.pin("")
        self.lib.add_collection("queue", "123", [{"videoId": "q1"}], title="Queue")
        self.assertTrue(self.idle())
        self.lib.remove_all()
        self.assertEqual(self.lib.library()["songs"], [])
        self.assertEqual(os.listdir(os.path.join(self.tmp.name, "collections")), [])
        self.assertEqual(collection_key("album", "x"), "album:x")

    def test_manifest_survives_restart(self):
        self.lib.add_collection("album", "AL9", [{"videoId": "z", "title": "Z"}], title="Nine", subtitle="Band")
        self.assertTrue(self.idle())
        reopened = OfflineLibrary(self.tmp.name, self.downloader, workers=1)
        collection = reopened.library()["collections"][0]
        self.assertEqual((collection["title"], collection["subtitle"], collection["done"]), ("Nine", "Band", 1))
        with open(reopened.collection_path("album:AL9"), encoding="utf-8") as handle:
            self.assertEqual(json.load(handle)["tracks"][0]["title"], "Z")


if __name__ == "__main__":
    unittest.main()
