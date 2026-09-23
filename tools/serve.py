#!/usr/bin/env python3
"""Serve the sample over HTTP so the origin is a real, controllable one.

Why bother: the plan's S1 exit requires a REAL persistence origin and forbids
treating an in-memory array as proof. Opening index.html from file:// mostly works,
but `fetch()` of the PDF asset is blocked there, so the "original bytes unchanged"
check silently degrades. Over HTTP it actually runs.

  python tools/serve.py [port]     # default 8777, binds 127.0.0.1 only
"""
from __future__ import annotations
import functools
import http.server
import os
import socketserver
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stderr.write("  %s - %s\n" % (self.address_string(), fmt % args))

    def end_headers(self):
        # a sample should not be cached in a way that hides a rebuild
        self.send_header("Cache-Control", "no-store")
        super().end_headers()


def main() -> int:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8777
    handler = functools.partial(Handler, directory=ROOT)
    with socketserver.TCPServer(("127.0.0.1", port), handler) as httpd:
        print(f"InkWeft S1 sample serving {ROOT}")
        print(f"  open http://127.0.0.1:{port}/index.html")
        print("  Ctrl+C to stop")
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
