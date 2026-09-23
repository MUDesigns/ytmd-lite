"""Bounded, shared stream extraction for foreground playback and prewarming."""
from concurrent.futures import Future, TimeoutError
import threading
import time
from urllib.parse import parse_qs, urlparse


class StreamResolver:
    def __init__(self, extract, timeout=25, capacity=2, clock=time.time):
        self.extract = extract
        self.timeout = timeout
        self.capacity = capacity
        self.clock = clock
        self.lock = threading.Lock()
        self.pending = {}
        self.cache = {}

    def invalidate(self, key):
        with self.lock:
            self.cache.pop(key, None)

    def resolve(self, key):
        with self.lock:
            now = self.clock()
            self.cache = {k: v for k, v in self.cache.items() if v[1] > now}
            if key in self.cache:
                return {**self.cache[key][0], "cached": True}
            future = self.pending.get(key)
            if future is None:
                # Do not build an unbounded backlog when the user skips rapidly.
                if len(self.pending) >= self.capacity:
                    return {"error": "Stream resolver busy; please try again", "busy": True}
                future = Future()
                self.pending[key] = future
                threading.Thread(target=self._run, args=(key, future), daemon=True).start()
        try:
            return future.result(timeout=self.timeout)
        except TimeoutError:
            # Python cannot interrupt an extractor safely. Keep its slot occupied
            # until it exits, and let subsequent requests join that same work.
            return {"error": "Timed out resolving audio stream", "timeout": True}

    def _run(self, key, future):
        try:
            result = self.extract(key)
        except Exception as exc:
            result = {"error": str(exc)}
        now = self.clock()
        expiry = now + 5  # Briefly coalesce failures as well as successes.
        if result.get("url"):
            expiry = now + 3600
            try:
                signed_expiry = float(parse_qs(urlparse(result["url"]).query)["expire"][0])
                expiry = min(expiry, signed_expiry - 60)
            except (KeyError, ValueError, TypeError):
                pass
        with self.lock:
            if len(self.cache) >= 128:
                self.cache.pop(next(iter(self.cache)))
            self.cache[key] = (result, expiry)
            self.pending.pop(key, None)
            future.set_result(result)
