"""Every paid call Infinite AI Radio can produce, checked against the live ledger.

CONTRACT.md's promise is "1 aicoin is worth 1 paid AI call — enforced, not just
a tagline." `prod_smoke_test.py` proves that for one Anthropic call; this proves
it for the *whole surface the app can actually reach*, which is what determines
whether a listener's balance matches what they were charged for.

Every request below is the real endpoint, method and body shape the app sends —
see the reference in each case's `source` field. For each one the balance is
read before and after, so the assertion is on the ledger itself rather than on
the proxy agreeing with itself.

Two things it deliberately checks together:

- **Paid calls debit exactly 1.** Not 0 (a call the proxy forgot to bill, i.e.
  free provider spend), and not 2 (a listener charged twice for one segment).
- **Free targets debit exactly 0.** The app lists ElevenLabs voices on every
  Settings visit; billing that would charge people for opening a picker.

A failed upstream call is refunded by design, so a paid case that comes back
non-2xx would also show a delta of 0. Those are reported as ERROR rather than
FAIL — the accounting is right, but the case proved nothing, and the upstream
credential needs looking at.

**This spends real money**: one coin per paid case, plus the provider's own
charge — the two image cases are the expensive ones (roughly $0.04 for DALL-E
and $0.03 for Stability at the time of writing). Pass --no-images to skip them.

    python3 app_call_accounting_test.py [--no-images]
"""
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from prod_smoke_test import Wallet, sess, BASE  # noqa: E402

SKIP_IMAGES = "--no-images" in sys.argv

# ElevenLabs' own stock voice, so the case doesn't depend on this account
# having any custom voice saved.
VOICE = "21m00Tcm4TlvDq8ikWAM"

# (name, provider, method, path, json body, files/data body, expected delta, source)
CASES = [
    ("anthropic segment", "anthropic", "POST", "/v1/messages",
     {"model": "claude-sonnet-5", "max_tokens": 8,
      "messages": [{"role": "user", "content": "Say hi."}]},
     None, 1, "ClaudeClient.call — every broadcast segment"),

    ("anthropic web search", "anthropic", "POST", "/v1/messages",
     {"model": "claude-haiku-4-5-20251001", "max_tokens": 16,
      "messages": [{"role": "user", "content": "Search for a photo of the Eiffel Tower."}],
      "tools": [{"type": "web_search_20250305", "name": "web_search", "max_uses": 1}]},
     None, 1, "ClaudeClient.findImageCandidates / suggestStations"),

    ("openai narration", "openai", "POST", "/v1/chat/completions",
     {"model": "gpt-4o", "max_tokens": 8,
      "messages": [{"role": "user", "content": "Say hi."}]},
     None, 1, "AIProviderClient — OpenAI as main narrator"),

    ("gemini narration", "google", "POST", "/v1beta/models/gemini-flash-latest:generateContent",
     {"contents": [{"role": "user", "parts": [{"text": "Say hi."}]}]},
     None, 1, "AIProviderClient — Gemini as main narrator"),

    ("elevenlabs narration", "elevenlabs", "POST",
     f"/v1/text-to-speech/{VOICE}/with-timestamps",
     {"text": "One short line.", "model_id": "eleven_multilingual_v2",
      "voice_settings": {"stability": 0.75, "similarity_boost": 0.75}},
     None, 1, "ElevenLabsSpeechService.synthesize — every narrated segment"),

    ("elevenlabs voice list", "elevenlabs", "GET", "/v1/voices",
     None, None, 0, "ElevenLabsSpeechService — free target, listed on every Settings visit"),
]

IMAGE_CASES = [
    ("gpt-image-1 image", "openai", "POST", "/v1/images/generations",
     {"model": "gpt-image-1", "prompt": "A lighthouse at dusk.", "n": 1, "size": "1024x1024"},
     None, 1, "DalleClient.generateImage"),

    ("stability image", "stability", "POST", "/v2beta/stable-image/generate/core",
     None,
     {"prompt": "A lighthouse at dusk.", "aspect_ratio": "1:1", "output_format": "png"},
     1, "StabilityClient.generateImage — multipart, as the app sends it"),
]


def balance(address):
    r = sess.get(f"{BASE}/wallet/api/balance/{address}", timeout=30)
    r.raise_for_status()
    return r.json()["balance"]


def run_case(w, tok, case):
    name, provider, method, path, body, form, expected, source = case
    before = balance(w.address)
    headers = {"X-AI": provider, "X-Api-Key": tok}
    if provider == "anthropic":
        headers["anthropic-version"] = "2023-06-01"
    if provider == "stability":
        # Stability rejects a request that doesn't say what it will accept
        # ("expected image/* or application/json"); the app sends exactly this.
        headers["Accept"] = "image/*"

    t0 = time.time()
    if method == "GET":
        r = sess.get(f"{BASE}{path}", headers=headers, timeout=120)
    elif form is not None:
        # Multipart, the way StabilityClient builds it — `files` makes requests
        # emit multipart/form-data with a generated boundary.
        r = sess.post(f"{BASE}{path}", headers=headers,
                      files={k: (None, v) for k, v in form.items()}, timeout=120)
    else:
        headers["Content-Type"] = "application/json"
        r = sess.post(f"{BASE}{path}", headers=headers, json=body, timeout=120)
    elapsed = time.time() - t0

    # The debit is applied around the upstream call; give the ledger a moment
    # to settle before reading it back.
    time.sleep(1.5)
    after = balance(w.address)
    delta = round(before - after, 6)

    ok_http = 200 <= r.status_code < 300
    if not ok_http and expected > 0:
        # Refund-on-failure means the delta is 0 here whatever the accounting
        # does, so this case can't prove anything either way.
        print(f"[ERROR] {name}: upstream returned {r.status_code} in {elapsed:.1f}s "
              f"— delta {delta} proves nothing. {r.text[:160]}")
        print(f"        source: {source}")
        return None

    passed = delta == expected
    print(f"[{'PASS' if passed else 'FAIL'}] {name}: {r.status_code} in {elapsed:5.1f}s, "
          f"balance {before} -> {after} (delta {delta}, expected {expected})")
    if not passed:
        print(f"        source: {source}")
        print(f"        body: {r.text[:200]}")
    return passed


if __name__ == "__main__":
    cases = CASES + ([] if SKIP_IMAGES else IMAGE_CASES)
    w = Wallet()
    print(f"wallet: {w.address}")
    h = w.live_headers("POST", "/wallet/api/claim", b"")
    claim = sess.post(f"{BASE}/wallet/api/claim", headers=h, data=b"", timeout=30)
    if claim.status_code != 200 or not claim.json().get("granted"):
        sys.exit(f"could not fund a test wallet: {claim.status_code} {claim.text[:160]}")
    paid_cases = sum(1 for c in cases if c[6] > 0)
    print(f"funded with {claim.json().get('amount')} aicoin; {paid_cases} paid calls to make\n")

    results = [run_case(w, w.token(), c) for c in cases]
    checked = [r for r in results if r is not None]
    skipped = len(results) - len(checked)

    print(f"\n{sum(1 for r in checked if r)}/{len(checked)} accounting checks passed"
          + (f", {skipped} inconclusive (upstream error)" if skipped else ""))
    print(f"final balance: {balance(w.address)}")
    sys.exit(0 if checked and all(checked) else 1)
