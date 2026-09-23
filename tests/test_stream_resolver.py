import importlib.util
from pathlib import Path
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor

spec = importlib.util.spec_from_file_location("stream_resolver", Path("python-backend/stream_resolver.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
StreamResolver = module.StreamResolver


class StreamResolverTests(unittest.TestCase):
    def test_prewarm_and_play_share_extraction(self):
        entered, release = threading.Event(), threading.Event()
        calls = []
        def extract(key):
            calls.append(key)
            entered.set()
            release.wait(2)
            return {"url": "https://example.test/audio"}
        resolver = StreamResolver(extract)
        with ThreadPoolExecutor(2) as pool:
            first = pool.submit(resolver.resolve, ("account", "song"))
            self.assertTrue(entered.wait(1))
            second = pool.submit(resolver.resolve, ("account", "song"))
            release.set()
            self.assertEqual(first.result()["url"], second.result()["url"])
        self.assertEqual(len(calls), 1)

    def test_timeout_keeps_work_shared_and_capacity_bounded(self):
        release = threading.Event()
        calls = []
        def extract(key):
            calls.append(key)
            release.wait(2)
            return {"url": "https://example.test/audio"}
        resolver = StreamResolver(extract, timeout=0.01, capacity=1)
        try:
            self.assertTrue(resolver.resolve("one")["timeout"])
            self.assertTrue(resolver.resolve("one")["timeout"])
            self.assertTrue(resolver.resolve("two")["busy"])
            self.assertEqual(calls, ["one"])
        finally:
            release.set()

    def test_signed_expiry_and_accounts_are_respected(self):
        now = [1000]
        calls = []
        def extract(key):
            calls.append(key)
            return {"url": "https://example.test/audio?expire=1100"}
        resolver = StreamResolver(extract, clock=lambda: now[0])
        resolver.resolve(("a", "song"))
        self.assertTrue(resolver.resolve(("a", "song"))["cached"])
        resolver.resolve(("b", "song"))
        now[0] = 1041
        resolver.resolve(("a", "song"))
        self.assertEqual(len(calls), 3)

    def test_failure_is_briefly_cached_and_invalidation_reextracts(self):
        calls = []
        def extract(key):
            calls.append(key)
            raise RuntimeError("offline")
        resolver = StreamResolver(extract)
        self.assertEqual(resolver.resolve("song")["error"], "offline")
        self.assertTrue(resolver.resolve("song")["cached"])
        resolver.invalidate("song")
        resolver.resolve("song")
        self.assertEqual(len(calls), 2)


if __name__ == "__main__":
    unittest.main()
