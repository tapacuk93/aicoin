#!/usr/bin/env python3
"""Throwaway mock AI provider for the e2e test: echoes back the auth header
and X-AI header it received (so the test can assert the proxy injected its
own key and stripped X-AI) alongside a canned OpenAI-style usage body."""
import http.server
import json
import sys


class Handler(http.server.BaseHTTPRequestHandler):
    def _respond(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        body = {
            "usage": {"total_tokens": 100},
            "choices": [{"message": "mock response"}],
            "received_authorization": self.headers.get("Authorization"),
            "received_x_ai": self.headers.get("X-AI"),
        }
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self._respond()

    def do_POST(self):
        self._respond()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18090
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
