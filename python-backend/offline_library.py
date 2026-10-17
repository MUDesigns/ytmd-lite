"""Downloaded songs and collections for offline listening.

Audio files and a JSON sidecar per song live in one directory; collection manifests
(playlists, albums, artist top songs, saved queues) live in a ``collections`` subfolder.
A song stays on disk while it is pinned (downloaded on its own) or referenced by at least
one collection. Downloads run on a small worker pool so a long playlist cannot start
hundreds of extractions at once.
"""
import json
import logging
import os
import re
import threading
import time
from collections import OrderedDict

AUDIO_EXTS = (".opus", ".m4a", ".webm", ".mp3", ".ogg", ".mp4")
MIME_TYPES = {".opus": "audio/opus", ".m4a": "audio/mp4", ".mp4": "audio/mp4", ".webm": "audio/webm",
              ".mp3": "audio/mpeg", ".ogg": "audio/ogg"}
META_FIELDS = ("videoId", "title", "artists", "artistBrowseId", "artistLinks", "album", "albumBrowseId",
               "thumbnail", "duration")
COLLECTION_FIELDS = ("kind", "id", "title", "subtitle", "thumbnail")
SAFE_KEY = re.compile(r"[^A-Za-z0-9_.-]")


def mime_for(path):
    return MIME_TYPES.get(os.path.splitext(path)[1].lower(), "application/octet-stream")


def collection_key(kind, collection_id):
    return f"{kind}:{collection_id}"


class OfflineLibrary:
    def __init__(self, directory, download, fetch_image=None, workers=2, clock=time.time):
        """``download(video_id, output_template, progress)`` saves the audio and returns its path.
        ``progress(fraction)`` is called while it runs. ``fetch_image(url)`` warms thumbnails."""
        self.dir = directory
        self.collections_dir = os.path.join(directory, "collections")
        os.makedirs(self.collections_dir, exist_ok=True)
        self.download = download
        self.fetch_image = fetch_image
        self.clock = clock
        self.lock = threading.Lock()
        self.wake = threading.Condition(self.lock)
        self.queue = OrderedDict()   # video_id -> meta, in request order
        self.active = {}             # video_id -> {"progress": float, "cancelled": bool}
        self.failed = OrderedDict()  # video_id -> {"meta": meta, "error": str}
        self.generation = 0
        self.workers = [threading.Thread(target=self._worker, name=f"offline-{i}", daemon=True)
                        for i in range(max(1, workers))]
        for worker in self.workers:
            worker.start()

    # ── Paths ─────────────────────────────────────────────────────────────────

    def _safe(self, video_id):
        return SAFE_KEY.sub("_", str(video_id))

    def audio_path(self, video_id):
        safe = self._safe(video_id)
        for ext in AUDIO_EXTS:
            path = os.path.join(self.dir, safe + ext)
            if os.path.isfile(path) and os.path.getsize(path) > 0:
                return path
        return None

    def meta_path(self, video_id):
        return os.path.join(self.dir, self._safe(video_id) + ".json")

    def collection_path(self, key):
        return os.path.join(self.collections_dir, SAFE_KEY.sub("_", key) + ".json")

    # ── Metadata ──────────────────────────────────────────────────────────────

    @staticmethod
    def clean_meta(video_id, data):
        data = data or {}
        meta = {"videoId": video_id}
        for field in META_FIELDS[1:]:
            value = data.get(field)
            if value not in (None, ""):
                meta[field] = value
        if isinstance(meta.get("artists"), list):
            meta["artists"] = ", ".join(a.get("name", "") if isinstance(a, dict) else str(a)
                                        for a in meta["artists"] if a)
        return meta

    def _read_json(self, path):
        try:
            with open(path, encoding="utf-8") as handle:
                return json.load(handle)
        except (OSError, ValueError):
            return None

    def _write_json(self, path, data):
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
        os.replace(tmp, path)

    def read_meta(self, video_id):
        return self._read_json(self.meta_path(video_id))

    def _save_meta(self, video_id, meta, **updates):
        current = self.read_meta(video_id) or {}
        current.update(self.clean_meta(video_id, {**current, **(meta or {})}))
        current.update(updates)
        self._write_json(self.meta_path(video_id), current)
        return current

    def is_downloaded(self, video_id):
        return self.audio_path(video_id) is not None

    # ── Collections ───────────────────────────────────────────────────────────

    def _collections(self):
        out = []
        try:
            names = sorted(os.listdir(self.collections_dir))
        except OSError:
            names = []
        for name in names:
            if name.endswith(".json"):
                data = self._read_json(os.path.join(self.collections_dir, name))
                if data and data.get("key"):
                    out.append(data)
        return out

    def _referenced(self, video_id, ignore_key=None):
        return any(video_id in c.get("videoIds", []) for c in self._collections() if c.get("key") != ignore_key)

    def add_collection(self, kind, collection_id, tracks, **info):
        """Save a manifest and queue every track that is not on disk yet."""
        if not kind or not collection_id:
            raise ValueError("A collection needs a kind and an id")
        key = collection_key(kind, collection_id)
        metas = []
        seen = set()
        for track in tracks or []:
            video_id = (track or {}).get("videoId")
            if video_id and video_id not in seen:
                seen.add(video_id)
                metas.append(self.clean_meta(video_id, track))
        if not metas:
            raise ValueError("This collection has no downloadable tracks")
        manifest = {"key": key, "kind": kind, "id": str(collection_id), "addedAt": int(self.clock()),
                    "videoIds": [m["videoId"] for m in metas], "tracks": metas}
        for field in COLLECTION_FIELDS[2:]:
            if info.get(field):
                manifest[field] = info[field]
        self._write_json(self.collection_path(key), manifest)
        queued = self.enqueue(metas)
        self._bump()
        return {"key": key, "queued": queued, "total": len(metas)}

    def remove_collection(self, key):
        path = self.collection_path(key)
        manifest = self._read_json(path)
        if not manifest:
            return {"removed": 0}
        try:
            os.remove(path)
        except OSError:
            pass
        removed = 0
        for video_id in manifest.get("videoIds", []):
            with self.lock:
                pending = self.queue.get(video_id) or (self.active.get(video_id) or {}).get("meta") or {}
            meta = self.read_meta(video_id) or {}
            if meta.get("pinned") or pending.get("pinned") or self._referenced(video_id, ignore_key=key):
                continue
            self.cancel(video_id)
            removed += self._delete_files(video_id)
        self._bump()
        return {"removed": removed}

    # ── Single songs ──────────────────────────────────────────────────────────

    def pin(self, video_id, meta=None):
        """Download a song on its own; it survives removal of any collection."""
        if not video_id:
            raise ValueError("Missing video id")
        meta = self.clean_meta(video_id, meta)
        if self.is_downloaded(video_id):
            self._save_meta(video_id, meta, pinned=True)
            self._bump()
            return {"status": "done"}
        with self.lock:
            if video_id in self.queue:
                self.queue[video_id]["pinned"] = True
                return {"status": "queued"}
            if video_id in self.active:
                self.active[video_id]["meta"]["pinned"] = True
                return {"status": "downloading"}
        self.enqueue([{**meta, "pinned": True}])
        return {"status": "queued"}

    def unpin(self, video_id):
        """Forget a song downloaded on its own; collections keep their copies."""
        self.cancel(video_id)
        meta = self.read_meta(video_id)
        if meta and self._referenced(video_id):
            self._save_meta(video_id, meta, pinned=False)
            self._bump()
            return {"removed": 0}
        removed = self._delete_files(video_id)
        self._bump()
        return {"removed": removed}

    def _delete_files(self, video_id):
        removed = 0
        for ext in AUDIO_EXTS + (".json", ".part"):
            path = os.path.join(self.dir, self._safe(video_id) + ext)
            if os.path.exists(path):
                try:
                    os.remove(path)
                    removed += 1 if ext != ".json" else 0
                except OSError:
                    pass
        return removed

    def remove_all(self):
        for collection in self._collections():
            try:
                os.remove(self.collection_path(collection["key"]))
            except OSError:
                pass
        with self.lock:
            self.queue.clear()
            self.failed.clear()
            for entry in self.active.values():
                entry["cancelled"] = True
        for song in self._songs_on_disk():
            self._delete_files(song["videoId"])
        self._bump()

    # ── Queue ─────────────────────────────────────────────────────────────────

    def enqueue(self, metas):
        queued = 0
        with self.lock:
            for meta in metas:
                video_id = meta["videoId"]
                if video_id in self.queue or video_id in self.active or self.audio_path(video_id):
                    continue
                self.failed.pop(video_id, None)
                self.queue[video_id] = dict(meta)
                queued += 1
            if queued:
                self.wake.notify_all()
        return queued

    def cancel(self, video_id):
        with self.lock:
            if self.queue.pop(video_id, None) is not None:
                self._bump_locked()
                return True
            if video_id in self.active:
                self.active[video_id]["cancelled"] = True
                return True
            if self.failed.pop(video_id, None) is not None:
                self._bump_locked()
                return True
        return False

    def retry_failed(self):
        with self.lock:
            metas = [entry["meta"] for entry in self.failed.values()]
            self.failed.clear()
        return self.enqueue(metas)

    def _bump_locked(self):
        self.generation += 1

    def _bump(self):
        with self.lock:
            self._bump_locked()

    def _worker(self):
        while True:
            with self.lock:
                while not self.queue:
                    self.wake.wait()
                video_id, meta = self.queue.popitem(last=False)
                self.active[video_id] = {"progress": 0.0, "cancelled": False, "meta": meta}
                self._bump_locked()
            self._run(video_id, meta)

    def _run(self, video_id, meta):
        template = os.path.join(self.dir, self._safe(video_id) + ".%(ext)s")

        def progress(fraction):
            with self.lock:
                entry = self.active.get(video_id)
                if entry:
                    entry["progress"] = max(0.0, min(1.0, float(fraction or 0)))

        error = None
        try:
            self.download(video_id, template, progress)
            if not self.audio_path(video_id):
                raise RuntimeError("Download finished without an audio file")
        except Exception as exc:  # noqa: BLE001 - any extractor failure is reported to the user
            error = str(exc) or type(exc).__name__
            logging.warning("[offline] %s failed: %s", video_id, error)
        with self.lock:
            entry = self.active.pop(video_id)
            cancelled = entry["cancelled"]
            pinned = bool(entry["meta"].get("pinned"))
        if cancelled:
            self._delete_files(video_id)
        elif error:
            self._delete_files(video_id)
            with self.lock:
                self.failed[video_id] = {"meta": meta, "error": error[:300]}
        else:
            existing = self.read_meta(video_id) or {}
            self._save_meta(video_id, meta, pinned=pinned or bool(existing.get("pinned")),
                            downloadedAt=int(self.clock()))
            if self.fetch_image and meta.get("thumbnail"):
                try:
                    self.fetch_image(meta["thumbnail"])
                except Exception as exc:  # noqa: BLE001 - artwork is optional
                    logging.info("[offline] artwork for %s not cached: %s", video_id, exc)
        self._bump()

    # ── Reporting ─────────────────────────────────────────────────────────────

    def status(self):
        with self.lock:
            downloads = {}
            for video_id, entry in self.active.items():
                downloads[video_id] = {"status": "downloading", "progress": round(entry["progress"], 3),
                                       "title": entry["meta"].get("title", "")}
            for video_id, meta in self.queue.items():
                downloads[video_id] = {"status": "queued", "progress": 0.0, "title": meta.get("title", "")}
            for video_id, entry in self.failed.items():
                downloads[video_id] = {"status": "error", "progress": 0.0, "error": entry["error"],
                                       "title": entry["meta"].get("title", "")}
            return {"downloads": downloads, "generation": self.generation,
                    "active": len(self.active), "queued": len(self.queue), "failed": len(self.failed)}

    def song_status(self, video_id):
        if self.is_downloaded(video_id):
            return "done"
        with self.lock:
            if video_id in self.active:
                return "downloading"
            if video_id in self.queue:
                return "queued"
            if video_id in self.failed:
                return "error"
        return "not_found"

    def _songs_on_disk(self):
        songs = []
        try:
            names = os.listdir(self.dir)
        except OSError:
            names = []
        for name in names:
            if not name.endswith(".json"):
                continue
            meta = self._read_json(os.path.join(self.dir, name))
            if not meta or not meta.get("videoId"):
                continue
            path = self.audio_path(meta["videoId"])
            if path:
                songs.append({**meta, "size": os.path.getsize(path)})
        songs.sort(key=lambda s: s.get("downloadedAt", 0), reverse=True)
        return songs

    def library(self):
        songs = self._songs_on_disk()
        downloaded = {s["videoId"] for s in songs}
        status = self.status()["downloads"]
        collections = []
        for manifest in self._collections():
            ids = manifest.get("videoIds", [])
            done = sum(1 for v in ids if v in downloaded)
            failed = sum(1 for v in ids if status.get(v, {}).get("status") == "error")
            collections.append({**manifest, "done": done, "failed": failed, "total": len(ids),
                                "size": sum(s["size"] for s in songs if s["videoId"] in ids)})
        collections.sort(key=lambda c: c.get("addedAt", 0), reverse=True)
        return {"songs": songs, "collections": collections, "downloads": status,
                "totalBytes": sum(s["size"] for s in songs), "dir": self.dir, "generation": self.generation}
