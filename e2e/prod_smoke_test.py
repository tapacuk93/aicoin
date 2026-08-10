"""End-to-end production smoke test against proxy.aicoin.oeaio.com.

Exercises the full real path: generate an Ed25519 wallet, claim free coins with a
live signature, issue an API token, make a genuine Anthropic call through the
proxy (spending 1 real aicoin and real provider credit), and confirm the balance
dropped and the price feed recorded the spend.
"""
import base64, hashlib, json, time, sys
import requests
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

BASE = "https://proxy.aicoin.oeaio.com"
IP = "100.51.144.4"

sess = requests.Session()
# Bypass a stale local resolver without touching /etc/hosts.
from requests.adapters import HTTPAdapter
import urllib3.util.connection as urllib3_cn
_orig = urllib3_cn.create_connection
def patched(address, *a, **kw):
    host, port = address
    if host.endswith("oeaio.com"):
        host = IP
    return _orig((host, port), *a, **kw)
urllib3_cn.create_connection = patched


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


class Wallet:
    def __init__(self):
        self.sk = Ed25519PrivateKey.generate()
        raw = self.sk.public_key().public_bytes(
            encoding=serialization.Encoding.Raw,
            format=serialization.PublicFormat.Raw)
        self.address = raw.hex()

    def live_headers(self, method: str, path: str, body: bytes) -> dict:
        ts = str(int(time.time() * 1000))
        msg = f"{self.address}\n{ts}\n{method}\n{path}\n{hashlib.sha256(body).hexdigest()}"
        sig = self.sk.sign(msg.encode())
        return {
            "X-Api-Key": self.address,
            "X-Api-Signature": sig.hex(),
            "X-Api-Timestamp": ts,
            "Content-Type": "application/json",
        }

    def token(self, ttl_seconds=3600) -> str:
        now = int(time.time())
        payload = json.dumps({"addr": self.address, "iat": now, "exp": now + ttl_seconds},
                              separators=(",", ":"))
        enc = b64u(payload.encode())
        sig = self.sk.sign(enc.encode())
        return f"{enc}.{b64u(sig)}"


def step(label, ok, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + (f" — {detail}" if detail else ""))
    return ok


if __name__ == "__main__":
    results = []
    w = Wallet()
    print(f"wallet address: {w.address}\n")

    # 1. Balance of a brand-new wallet
    r = sess.get(f"{BASE}/wallet/api/balance/{w.address}", timeout=30)
    results.append(step("new wallet balance readable", r.status_code == 200 and r.json().get("balance") == 0,
                        f"{r.status_code} {r.text[:120]}"))

    # 2. Claim free coins (live-signed)
    body = b""
    h = w.live_headers("POST", "/wallet/api/claim", body)
    r = sess.post(f"{BASE}/wallet/api/claim", headers=h, data=body, timeout=30)
    granted = r.status_code == 200 and r.json().get("granted") is True
    results.append(step("free-coin claim (live Ed25519 signature)", granted, f"{r.status_code} {r.text[:160]}"))

    # 3. Balance reflects the claim
    r = sess.get(f"{BASE}/wallet/api/balance/{w.address}", timeout=30)
    bal_after_claim = r.json().get("balance") if r.status_code == 200 else None
    results.append(step("balance credited by claim", bal_after_claim == 10.0, f"balance={bal_after_claim}"))

    # 4. Real Anthropic call through the proxy, paid with a token
    tok = w.token()
    payload = {"model": "claude-haiku-4-5-20251001", "max_tokens": 16,
               "messages": [{"role": "user", "content": "Reply with exactly: aicoin works"}]}
    r = r_call = sess.post(f"{BASE}/v1/messages",
                  headers={"X-AI": "anthropic", "X-Api-Key": tok,
                           "Content-Type": "application/json", "anthropic-version": "2023-06-01"},
                  json=payload, timeout=90)
    ok = r.status_code == 200
    detail = r.text[:200]
    if ok:
        try:
            detail = "".join(c.get("text", "") for c in r.json().get("content", []))
        except Exception:
            pass
    results.append(step("REAL Anthropic call through proxy (proxy's own key)", ok, f"{r.status_code} {detail}"))

    # 5. Exactly 1 coin debited
    time.sleep(2)
    r = sess.get(f"{BASE}/wallet/api/balance/{w.address}", timeout=30)
    bal_final = r.json().get("balance") if r.status_code == 200 else None
    # Not a hardcoded 1: under metered billing (pricing.metered) a call costs what it cost to
    # run, so the proxy reports the charge on X-Aicoin-Charged and the ledger must agree with it.
    charged = float(r_call.headers.get("X-Aicoin-Charged", "1"))
    results.append(step("ledger debit matches the charge the proxy reported",
                        bal_final == bal_after_claim - charged,
                        f"balance {bal_after_claim} -> {bal_final}, X-Aicoin-Charged={charged:g}"))
    results.append(step("a paid call always costs at least 1 aicoin", charged >= 1, f"charged={charged:g}"))

    # 6. Price feed recorded real spend
    r = sess.get(f"{BASE}/price", timeout=30)
    spend = r.json().get("total_spend_usd", 0)
    results.append(step("price feed recorded real USD spend", spend > 0, f"total_spend_usd={spend}"))

    # 7. Insufficient-balance path is NOT triggered yet, and unauthenticated call is rejected
    r = sess.post(f"{BASE}/v1/messages", headers={"X-AI": "anthropic", "Content-Type": "application/json"},
                  json=payload, timeout=30)
    results.append(step("unauthenticated AI call rejected (401)", r.status_code == 401, f"{r.status_code}"))

    print(f"\n{sum(results)}/{len(results)} checks passed")
    sys.exit(0 if all(results) else 1)
