#!/usr/bin/env python3
"""Throwaway mock AI provider for the e2e test: echoes back the auth
header/query-param it received (so the test can assert the proxy injected
its own key correctly per provider — different providers use different
auth mechanisms — and stripped X-AI) alongside a canned OpenAI-style usage
body. If the request body is JSON with `"simulate_failure": true`, responds
500 instead, so tests can exercise the proxy's debit-refund-on-failure path
deterministically without depending on a real upstream's real failure
modes. A GET to a path under /unavailable always answers 503, which is what the
liveness prober is pointed at when a test needs a provider to look down.

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

# A 1x1 PNG, and five bytes that are not JSON. Both stand in for media this suite never looks at
# beyond checking that what arrived is what the provider sent.
MOCK_IMAGE_BASE64 = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmM"
                     "IQAAAABJRU5ErkJggg==")
MOCK_AUDIO_BYTES = bytes([0xFF, 0xFB, 0x10, 0x00, 0x42])


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

        # A path reserved for the liveness probe to fail on, so the suite can watch a provider
        # be reported down without taking the whole mock away from the other seven.
        if urlparse(self.path).path.startswith("/unavailable"):
            payload = json.dumps({"error": "provider unavailable"}).encode()
            self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        if simulate_failure:
            payload = json.dumps({"error": "simulated upstream failure"}).encode()
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return

        # The media endpoints POST /image and POST /audio reach a provider on its own image or
        # speech path. Each provider answers in its own shape, and MediaAdapter's whole job is to
        # read all three, so the mock has to speak all three too.
        path_only = urlparse(self.path).path
        if path_only == "/v1/images/generations":
            self._respond_json({"created": 1, "data": [{"b64_json": MOCK_IMAGE_BASE64}],
                                "usage": {"total_tokens": 0}})
            return
        if path_only.startswith("/v1/generation/") and path_only.endswith("/text-to-image"):
            self._respond_json({"artifacts": [{"base64": MOCK_IMAGE_BASE64, "finishReason": "SUCCESS"}]})
            return
        if path_only.startswith("/v1/text-to-speech/") or path_only == "/v1/audio/speech":
            # Raw bytes, as both speech APIs answer. Deliberately not JSON: reading these as text
            # is the bug the audio path has to not have.
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(MOCK_AUDIO_BYTES)))
            self.end_headers()
            self.wfile.write(MOCK_AUDIO_BYTES)
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

    def _respond_json(self, body):
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

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
        # POST /text: one model answering alone, with the escape hatch. It takes it only when the
        # request carries the marker, so escalation is exercised without being unavoidable.
        if "answering a request on your own" in body_text:
            return "NEEDS CONSORTIUM" if "ESCALATE_ME" in body_text else "SINGLE ANSWER from mock"
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
