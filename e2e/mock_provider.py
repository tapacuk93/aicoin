#!/usr/bin/env python3
"""Throwaway mock AI provider for the e2e test: echoes back the auth
header/query-param it received (so the test can assert the proxy injected
its own key correctly per provider — different providers use different
auth mechanisms — and stripped X-AI) alongside a canned OpenAI-style usage
body. If the request body is JSON with `"simulate_failure": true`, responds
500 instead, so tests can exercise the proxy's debit-refund-on-failure path
deterministically without depending on a real upstream's real failure
modes."""
import http.server
import json
import sys
from urllib.parse import urlparse, parse_qs


class Handler(http.server.BaseHTTPRequestHandler):
    def _respond(self):
        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length) if length else b""
        simulate_failure = False
        try:
            parsed_body = json.loads(raw_body) if raw_body else {}
            simulate_failure = bool(parsed_body.get("simulate_failure"))
        except Exception:
            pass

        if simulate_failure:
            payload = json.dumps({"error": "simulated upstream failure"}).encode()
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        query = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
        headers_lower = {k.lower(): v for k, v in self.headers.items()}
        body = {
            "usage": {"total_tokens": 100},
            "choices": [{"message": "mock response"}],
            "received_authorization": self.headers.get("Authorization"),
            "received_x_ai": self.headers.get("X-AI"),
            "received_headers": headers_lower,
            "received_query": query,
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
