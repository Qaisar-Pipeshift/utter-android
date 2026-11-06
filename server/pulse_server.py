#!/usr/bin/env python3
"""Utter Pulse reference collector — one endpoint, stdlib only, no database.

Receives the phone's batches (POST /v1/pulse, gzip NDJSON, X-API-Key) and appends every
valid record to a daily log file. The log IS the store; insights are computed offline
against it. Designed to be started and stopped whenever wanted — the phone queues locally
while this is down and backfills on the next trigger.

Usage:
    PULSE_KEY=<same key as the app> python3 pulse_server.py

Environment:
    PULSE_KEY      required — API key, must match the app's key
    PULSE_BIND     host:port to bind (default localhost:8907 — put a TLS reverse proxy in front)
    PULSE_LOG_DIR  where the .ndjson logs go (default ./pulse-logs, created if missing)
    PULSE_MAX_MB   max accepted body size in MiB (default 64)
"""
import gzip
import io
import json
import os
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

KEY = os.environ.get("PULSE_KEY", "")
BIND = os.environ.get("PULSE_BIND", "localhost:8907")
LOG_DIR = os.environ.get("PULSE_LOG_DIR", os.path.join(os.path.dirname(os.path.abspath(__file__)), "pulse-logs"))
MAX_BODY = int(os.environ.get("PULSE_MAX_MB", "64")) * 1024 * 1024

TYPES = {
    "usage_event", "usage_daily", "net_daily", "notif_posted",
    "notif_removed", "listener_gap", "dnd", "store_dropped",
}

_lock = threading.Lock()
_seen_ids: set[str] = set()


def load_seen_ids() -> None:
    """Seed the dedup set from existing logs so a restart cannot duplicate records."""
    os.makedirs(LOG_DIR, exist_ok=True)
    for name in sorted(os.listdir(LOG_DIR)):
        if not name.endswith(".ndjson"):
            continue
        try:
            with open(os.path.join(LOG_DIR, name), encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rid = json.loads(line).get("id")
                    except ValueError:
                        continue
                    if isinstance(rid, str):
                        _seen_ids.add(rid)
        except OSError as e:
            print(f"pulse: could not read {name}: {e}", file=sys.stderr)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # one line, includes client + status
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"ok": True, "records": len(_seen_ids)})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/v1/pulse":
            self._send(404, {"error": "not found"})
            return
        if not KEY or self.headers.get("X-API-Key") != KEY:
            self._send(401, {"error": "unauthorized"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            self._send(413 if length > MAX_BODY else 400, {"error": "bad length"})
            return
        raw = self.rfile.read(length)
        enc = (self.headers.get("Content-Encoding") or "identity").lower()
        try:
            data = gzip.decompress(raw) if enc == "gzip" else raw
        except (OSError, EOFError, gzip.BadGzipFile):
            self._send(400, {"error": "bad gzip"})
            return

        accepted = duplicates = rejected = 0
        now_day = datetime.now(timezone.utc).strftime("%Y%m%d")
        records = []
        for line in io.TextIOWrapper(io.BytesIO(data), encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except ValueError:
                rejected += 1
                continue
            rid, rtype = rec.get("id"), rec.get("type")
            if not isinstance(rid, str) or not rid or rtype not in TYPES:
                rejected += 1
                continue
            records.append((rid, line))

        with _lock:
            fresh = []
            for rid, line in records:
                if rid in _seen_ids:
                    continue
                _seen_ids.add(rid)  # before append, so two copies in one batch dedup too
                fresh.append(line)
            duplicates = len(records) - len(fresh)
            accepted = len(fresh)
            if fresh:
                path = os.path.join(LOG_DIR, f"pulse-{now_day}.ndjson")
                with open(path, "a", encoding="utf-8") as f:
                    for line in fresh:
                        f.write(line + "\n")
                    f.flush()
                    os.fsync(f.fileno())
        self._send(200, {"accepted": accepted, "duplicates": duplicates, "rejected": rejected})


def main() -> None:
    if not KEY:
        sys.exit("pulse: PULSE_KEY is required")
    load_seen_ids()
    host, _, port = BIND.rpartition(":")
    server = ThreadingHTTPServer((host or "localhost", int(port)), Handler)
    print(f"pulse: listening on {BIND}, logging to {LOG_DIR} ({len(_seen_ids)} ids loaded)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
