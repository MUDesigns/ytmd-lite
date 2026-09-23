from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path("python-backend").resolve()))
from audio_proxy import AudioStream, AudioProxyError


class Reply:
    def __init__(self, start, end, data, status=206, total=None, broken=False):
        self.status_code = status
        self.headers = {"Content-Range": f"bytes {start}-{end}/{total or len(data)}",
                        "Content-Type": "audio/webm"}
        self.data = data[start:end + 1]
        self.broken = broken
        self.closed = False

    def iter_content(self, _size):
        if self.broken:
            yield self.data[:1]
            raise requests.exceptions.ChunkedEncodingError("truncated")
        yield self.data

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        self.closed = True


class AudioProxyTests(unittest.TestCase):
    def setUp(self):
        self.data = b"abcdefghijklmn"
        self.calls = []
        self.replies = []
        self.session = Mock()
        self.session.get.side_effect = self.get
        self.chunk_patch = patch.object(AudioStream, "CHUNK_SIZE", 4)
        self.chunk_patch.start()
        self.addCleanup(self.chunk_patch.stop)

    def get(self, _url, headers, **_kwargs):
        start, end = map(int, headers["Range"][6:].split("-"))
        self.calls.append((start, end))
        reply = Reply(start, min(end, len(self.data) - 1), self.data)
        self.replies.append(reply)
        return reply

    def stream(self, header=None):
        return AudioStream("https://example.test/audio", header, session=self.session)

    def test_full_response_uses_small_ranges_and_exact_length(self):
        stream = self.stream()
        self.assertEqual(stream.status, 200)
        self.assertNotIn("Content-Range", stream.headers)
        self.assertEqual(b"".join(stream.chunks()), self.data)
        self.assertEqual(int(stream.headers["Content-Length"]), len(self.data))
        self.assertEqual(self.calls, [(0, 3), (4, 7), (8, 11), (12, 13)])
        self.assertTrue(all(reply.closed for reply in self.replies))
        self.session.close.assert_called_once()

    def test_truncated_middle_chunk_is_retried_without_duplicate_or_missing_bytes(self):
        def get(url, headers, **kwargs):
            reply = self.get(url, headers, **kwargs)
            if self.calls == [(0, 3), (4, 7)]:
                reply.broken = True
            return reply
        self.session.get.side_effect = get
        stream = self.stream("bytes=0-")
        self.assertEqual(b"".join(stream.chunks()), self.data)
        self.assertEqual(self.calls[:3], [(0, 3), (4, 7), (4, 7)])

    def test_seeks_closed_ranges_and_suffixes(self):
        for header, expected in [("bytes=8-", self.data[8:]), ("bytes=3-8", self.data[3:9]),
                                 ("bytes=-3", self.data[-3:]), ("bytes=12-99", self.data[12:])]:
            with self.subTest(header=header):
                stream = self.stream(header)
                self.assertEqual(stream.status, 206)
                self.assertEqual(b"".join(stream.chunks()), expected)
                self.assertEqual(int(stream.headers["Content-Length"]), len(expected))

    def test_mismatched_range_never_yields_wrong_bytes(self):
        self.session.get.side_effect = lambda *_args, **_kwargs: Reply(0, 3, self.data)
        with self.assertRaises(AudioProxyError):
            self.stream("bytes=8-")
        self.assertEqual(self.session.get.call_count, 3)
        self.session.close.assert_called_once()

    def test_retries_are_bounded_and_http_errors_are_not_audio(self):
        self.session.get.side_effect = lambda *_args, **_kwargs: Reply(0, 0, b"", status=500)
        with self.assertRaises(AudioProxyError):
            self.stream()
        self.assertEqual(self.session.get.call_count, 3)

    def test_representation_change_aborts_instead_of_splicing_audio(self):
        self.session.get.side_effect = [Reply(0, 3, self.data), Reply(4, 7, self.data, total=99)]
        stream = self.stream()
        iterator = stream.chunks()
        self.assertEqual(next(iterator), self.data[:4])
        with self.assertRaises(AudioProxyError):
            next(iterator)
        self.session.close.assert_called_once()

    def test_cancelled_seek_closes_session_without_fetching_more_audio(self):
        stream = self.stream()
        iterator = stream.chunks()
        next(iterator)
        iterator.close()
        self.assertEqual(self.calls, [(0, 3)])
        self.session.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
