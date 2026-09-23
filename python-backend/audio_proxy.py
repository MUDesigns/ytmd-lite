"""Serve complete media ranges using small, independently retryable CDN reads."""
import logging
import re

import requests


class AudioProxyError(Exception):
    def __init__(self, message, status=502, total=None):
        super().__init__(message)
        self.status = status
        self.total = total


class AudioStream:
    CHUNK_SIZE = 256 * 1024

    def __init__(self, url, range_header=None, session=None):
        self.url = url
        self.session = session or requests.Session()
        self.total = None
        self.content_type = None
        self.etag = None
        self.closed = False
        try:
            start, end = 0, None
            if range_header:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header.strip())
                if not match or not any(match.groups()):
                    raise AudioProxyError("Unsupported audio byte range", 416)
                first, last = match.groups()
                if not first:
                    # A suffix needs the full length before its first offset is known.
                    self._read(0, 0)
                    length = int(last)
                    if length == 0:
                        raise AudioProxyError("Empty audio byte range", 416, self.total)
                    start = max(0, self.total - length)
                else:
                    start = int(first)
                    end = int(last) if last else None
                    if end is not None and end < start:
                        raise AudioProxyError("Invalid audio byte range", 416)
            self.first = self._read(start, min(end, start + self.CHUNK_SIZE - 1)
                                    if end is not None else start + self.CHUNK_SIZE - 1)
            self.start = start
            self.end = min(end, self.total - 1) if end is not None else self.total - 1
            self.status = 206 if range_header else 200
            self.headers = {"Content-Length": str(self.end - start + 1),
                            "Content-Type": self.content_type, "Accept-Ranges": "bytes",
                            "Cache-Control": "no-store"}
            if range_header:
                self.headers["Content-Range"] = f"bytes {start}-{self.end}/{self.total}"
        except Exception:
            self.close()
            raise

    def _read(self, start, end):
        for attempt in range(3):
            try:
                with self.session.get(self.url, headers={
                    "User-Agent": "Mozilla/5.0", "Accept-Encoding": "identity",
                    "Range": f"bytes={start}-{end}",
                }, stream=True, timeout=(6, 10)) as response:
                    if response.status_code in (403, 410):
                        raise AudioProxyError("Audio URL expired or blocked", response.status_code)
                    if response.status_code == 416:
                        total = re.fullmatch(r"bytes \*/(\d+)", response.headers.get("Content-Range", ""))
                        raise AudioProxyError("Audio byte range outside the file", 416,
                                              int(total[1]) if total else None)
                    if response.status_code != 206:
                        raise requests.RequestException(f"Unexpected audio HTTP status {response.status_code}")
                    match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
                    if not match:
                        raise requests.RequestException("Missing audio Content-Range")
                    actual_start, actual_end, total = map(int, match.groups())
                    if actual_start != start or actual_end != min(end, total - 1) or total <= actual_end or actual_end < start:
                        raise requests.RequestException("Incorrect audio Content-Range")
                    content_type = response.headers.get("Content-Type", "audio/mp4")
                    etag = response.headers.get("ETag")
                    if self.total is not None and (total != self.total or content_type != self.content_type or etag != self.etag):
                        raise AudioProxyError("Audio representation changed during playback")
                    expected = actual_end - start + 1
                    data = bytearray()
                    for chunk in response.iter_content(16384):
                        data.extend(chunk)
                        if len(data) > expected:
                            raise requests.RequestException("Audio range exceeded its declared length")
                    if len(data) != expected:
                        raise requests.RequestException("Audio range ended early")
                    self.total, self.content_type, self.etag = total, content_type, etag
                    return bytes(data)
            except requests.RequestException as exc:
                logging.warning("[audio proxy] retry %d/3 for bytes %d-%d (%s)",
                                attempt + 1, start, end, type(exc).__name__)
        raise AudioProxyError(f"Audio transfer failed at byte {start} after three attempts")

    def chunks(self):
        try:
            position = self.start
            chunk, self.first = self.first, None
            while not self.closed:
                yield chunk
                position += len(chunk)
                if position > self.end:
                    break
                chunk = self._read(position, min(self.end, position + self.CHUNK_SIZE - 1))
        finally:
            self.close()

    def close(self):
        if not self.closed:
            self.closed = True
            self.session.close()
