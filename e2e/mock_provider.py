#!/usr/bin/env python3
"""Throwaway mock AI provider for the e2e test: echoes back the auth
header/query-param it received (so the test can assert the proxy injected
its own key correctly per provider — different providers use different
auth mechanisms — and stripped X-AI) alongside a canned OpenAI-style usage
body. If the request body is JSON with `"simulate_failure": true`, responds
500 instead, so tests can exercise the proxy's debit-refund-on-failure path
deterministically without depending on a real upstream's real failure
modes.

It also stands in for a chat API when the request is a consortium turn (the
proxy writes those itself rather than forwarding a client's body — see
CONTRACT.md's "Consortium" section). Those get a reply in the provider's own
response shape, chosen from the path, so the proxy's per-provider extraction
is exercised for real; what the reply *says* is driven by the turn's system
prompt, so one mock can play drafter, editor and reviewer:

  * draft  -> "DRAFT from <path>"
  * merge  -> "MERGED ANSWER"
  * review -> "NO COMMENTS", unless the request under review carries a marker:
              NEEDS_ONE_ROUND_OF_COMMENTS  comments until the answer has been
                                           revised once, then clears
              ALWAYS_COMMENTS              never clears, so the round cap is
                                           what ends the call
  * revise -> "REVISED ANSWER"

Stateless on purpose: the marker plus the answer under review are enough to
decide, so a reviewer's verdict does not depend on how many requests this
process happened to have served."""
import http.server
import json
import sys
from urllib.parse import urlparse, parse_qs


class Handler(http.server.BaseHTTPRequestHandler):
    def _respond(self):
        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length) if length else b""
        simulate_failure = False
        parsed_body = {}
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

        # A consortium turn: the proxy composed this request itself, so it wants a
        # provider-shaped chat response rather than the echo body below. Recognised by shape
        # rather than by wording — a chat request carrying a system prompt, which is something
        # the proxy only ever writes itself.
        if self._is_consortium_turn(parsed_body):
            self._respond_chat(raw_body)
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

    @staticmethod
    def _is_consortium_turn(body):
        if not isinstance(body, dict):
            return False
        if isinstance(body.get("system"), str):
            return True                      # Anthropic
        if isinstance(body.get("systemInstruction"), dict):
            return True                      # Gemini
        messages = body.get("messages")      # OpenAI-compatible
        return (isinstance(messages, list) and messages
                and isinstance(messages[0], dict) and messages[0].get("role") == "system")

    def _respond_chat(self, raw_body):
        text = self._chat_text(raw_body.decode("utf-8", "replace"))
        path = urlparse(self.path).path
        if path.endswith("/v1/messages"):
            body = {"model": "mock", "content": [{"type": "text", "text": text}],
                    "usage": {"input_tokens": 60, "output_tokens": 40}}
        elif ":generateContent" in path:
            body = {"modelVersion": "mock",
                    "candidates": [{"content": {"parts": [{"text": text}]}}],
                    "usageMetadata": {"promptTokenCount": 60, "candidatesTokenCount": 40}}
        else:
            body = {"model": "mock",
                    "choices": [{"index": 0, "message": {"role": "assistant", "content": text}}],
                    "usage": {"prompt_tokens": 60, "completion_tokens": 40, "total_tokens": 100}}
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    @staticmethod
    def _chat_text(body_text):
        reviewing = "reviewing a candidate answer" in body_text
        editing = "editor of a panel" in body_text
        if reviewing:
            if "ALWAYS_COMMENTS" in body_text:
                return "The answer still does not say what was asked."
            if "NEEDS_ONE_ROUND_OF_COMMENTS" in body_text and "REVISED ANSWER" not in body_text:
                return "The answer is missing the part the request asked for."
            return "NO COMMENTS"
        if editing:
            return "REVISED ANSWER" if "review ===" in body_text else "MERGED ANSWER"
        return "DRAFT from mock"

    def do_GET(self):
        self._respond()

    def do_POST(self):
        self._respond()

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18090
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
