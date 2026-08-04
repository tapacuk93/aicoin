#!/usr/bin/env bash
# End-to-end test for aicoin-proxy (Java/Netty): a single process that both
# reverse-proxies AI calls and owns the coin ledger, backed by Redis. See
# CONTRACT.md for the exact API/behavior this asserts against.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# jenv resolves the `java` on PATH by searching for a .java-version file
# upward from the current directory, falling back to a global default if
# none is found; pin CWD to the repo root (whose .java-version is 17.0.16)
# so the proxy binary launched below doesn't inherit whatever directory
# this script happened to be invoked from.
cd "$REPO_ROOT"
WORKDIR="$(mktemp -d /tmp/aicoin-e2e.XXXXXX)"
FAIL=0
PIDS=()
REDIS_CONTAINER=""

log() { echo "[e2e] $*"; }
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; FAIL=1; }

cleanup() {
  for pid in "${PIDS[@]:-}"; do kill -9 "$pid" >/dev/null 2>&1 || true; done
  wait >/dev/null 2>&1 || true
  # kill/wait above only reaches the process we forked directly; the
  # generated Gradle "application" start script forks the JVM as a child
  # that can survive the parent's death — sweep any listener still
  # squatting on our ports as a backstop.
  for p in "$MOCK_PORT" "$PROXY_PORT"; do
    pids_on_port=$(lsof -t -nP -iTCP:"$p" -sTCP:LISTEN 2>/dev/null || true)
    [ -n "$pids_on_port" ] && kill -9 $pids_on_port 2>/dev/null || true
  done
  [ -n "$REDIS_CONTAINER" ] && docker stop "$REDIS_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_for() {
  local url="$1" tries=0 body
  while true; do
    body=$(curl -s -m 1 "$url" 2>/dev/null || true)
    # This sandbox mediates all loopback HTTP through a local proxy that
    # returns a synthetic {"error":{"type":"proxy_error",...}} body (a
    # real, curl-success HTTP response) when it can't/won't reach the
    # real destination — so a plain curl-exit-code check isn't enough;
    # reject that specific synthetic body and require real content.
    if [ -n "$body" ] && [[ "$body" != *proxy_error* ]]; then
      return 0
    fi
    tries=$((tries + 1))
    if [ "$tries" -gt 30 ]; then
      log "timed out waiting for $url (last response: $body)"
      return 1
    fi
    sleep 0.3
  done
}

MOCK_PORT=18090
PROXY_PORT=18080
REDIS_PORT=16379

PROXY_BIN="$REPO_ROOT/aicoin-proxy/build/install/aicoin-proxy/bin/aicoin-proxy"
if [ -x "$PROXY_BIN" ]; then
  log "reusing existing aicoin-proxy build at $PROXY_BIN (delete build/install to force a rebuild)"
else
  log "building aicoin-proxy"
  (cd "$REPO_ROOT/aicoin-proxy" && ./gradlew installDist -q --no-daemon) || { log "gradle build failed"; exit 1; }
fi

log "starting Redis on :$REDIS_PORT"
REDIS_CONTAINER=$(docker run -d --rm -p "$REDIS_PORT:6379" redis:7-alpine) || { log "docker run redis failed"; exit 1; }

log "starting mock AI provider on :$MOCK_PORT"
python3 "$REPO_ROOT/e2e/mock_provider.py" "$MOCK_PORT" > "$WORKDIR/mock.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$MOCK_PORT/" || true

echo "3" > "$WORKDIR/free-coins-counter.txt"

log "starting aicoin-proxy on :$PROXY_PORT"
AICOIN_PROXY_PORT="$PROXY_PORT" \
AICOIN_PROXY_OPENAI_BASEURL="http://127.0.0.1:$MOCK_PORT" \
AICOIN_PROXY_OPENAI_APIKEY="test-secret-key" \
AICOIN_PROXY_REDIS_HOST="127.0.0.1" \
AICOIN_PROXY_REDIS_PORT="$REDIS_PORT" \
AICOIN_PROXY_FREE_COINS_COUNTER_FILE="$WORKDIR/free-coins-counter.txt" \
  "$PROXY_BIN" > "$WORKDIR/proxy.log" 2>&1 &
PIDS+=($!)

wait_for "http://127.0.0.1:$PROXY_PORT/health" || { log "proxy never came up"; cat "$WORKDIR/proxy.log"; exit 1; }

log "--- test 1: missing X-Api-Key on a proxied call ---"
code=$(curl -s -o "$WORKDIR/t1.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-AI: openai" -d '{}')
if [ "$code" = "401" ] && grep -q "X-Api-Key" "$WORKDIR/t1.json"; then pass "401 missing X-Api-Key"; else fail "expected 401/X-Api-Key error, got $code: $(cat "$WORKDIR/t1.json")"; fi

log "--- test 2: missing X-AI header ---"
code=$(curl -s -o "$WORKDIR/t2.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: alice" -d '{}')
if [ "$code" = "400" ]; then pass "400 missing X-AI"; else fail "expected 400, got $code: $(cat "$WORKDIR/t2.json")"; fi

log "--- test 3: full happy-path proxied call (routing + key injection + passthrough) ---"
log "granting carol a free coin first — the proxy gates on a positive balance (kept separate from alice, who tests 7/8 exercise below)"
curl -s -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/claim" -H "Content-Type: application/json" -d '{"user_id":"carol"}' > /dev/null
code=$(curl -s -o "$WORKDIR/t3.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/v1/chat/completions" -H "X-Api-Key: carol" -H "X-AI: openai" -d '{"model":"gpt-4"}')
if [ "$code" = "200" ]; then
  auth=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_authorization'))")
  xai=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('received_x_ai'))")
  tokens=$(python3 -c "import json;print(json.load(open('$WORKDIR/t3.json')).get('usage',{}).get('total_tokens'))")
  [ "$auth" = "Bearer test-secret-key" ] && pass "proxy injected its own key ($auth)" || fail "expected injected key, mock saw: $auth"
  [ "$xai" = "None" ] && pass "X-AI stripped before forwarding" || fail "X-AI leaked upstream: $xai"
  [ "$tokens" = "100" ] && pass "passthrough body intact (usage.total_tokens=100)" || fail "unexpected body: tokens=$tokens"
else
  fail "expected 200, got $code: $(cat "$WORKDIR/t3.json")"
fi

log "--- test 4: proxy /health lists all 7 providers ---"
health=$(curl -s "http://127.0.0.1:$PROXY_PORT/health")
count=$(echo "$health" | python3 -c "import json,sys;print(len(json.load(sys.stdin)['providers']))")
[ "$count" = "7" ] && pass "7 providers listed" || fail "expected 7 providers, got: $health"

log "--- test 5: proxy /price reflects the paid call ---"
sleep 1
price=$(curl -s "http://127.0.0.1:$PROXY_PORT/price")
price_usd=$(echo "$price" | python3 -c "import json,sys;print(json.load(sys.stdin)['price_usd'])")
python3 -c "import sys; sys.exit(0 if float('$price_usd') > 0 else 1)" && pass "price_usd > 0 ($price_usd) after paid call" || fail "expected positive price, got: $price"

log "--- test 6: proxy /free-coins/available reads the counter file ---"
avail=$(curl -s "http://127.0.0.1:$PROXY_PORT/free-coins/available")
echo "$avail" | grep -q '"available":3' && pass "available:3 from counter file" || fail "unexpected: $avail"

log "--- test 7: wallet claim mints exactly 1 free coin ---"
out=$(curl -s -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/claim" -H "Content-Type: application/json" -d '{"user_id":"alice"}')
bal=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
[ "$bal" = "1" ] && pass "alice balance is 1 after claim (priced calls did not mint): $out" || fail "expected balance 1, got $bal. claim response: $out"

log "--- test 8: second claim within the hour is rejected ---"
code=$(curl -s -o "$WORKDIR/t8.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/claim" -H "Content-Type: application/json" -d '{"user_id":"alice"}')
bal2=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
[ "$code" = "429" ] && [ "$bal2" = "1" ] && pass "429 not eligible yet, balance still 1 (no double-claim)" || fail "expected 429/balance 1, got code=$code balance=$bal2: $(cat "$WORKDIR/t8.json")"

log "--- test 9: peer transfer (buy/sell) ---"
code=$(curl -s -o "$WORKDIR/t9.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/transfer" -H "Content-Type: application/json" -d '{"from_user_id":"alice","to_user_id":"bob","amount":0.4}')
bal_alice=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/balance/alice" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
bal_bob=$(curl -s "http://127.0.0.1:$PROXY_PORT/wallet/api/balance/bob" | python3 -c "import json,sys;print(json.load(sys.stdin)['balance'])")
if [ "$code" = "200" ] && [ "$bal_alice" = "0.6" ] && [ "$bal_bob" = "0.4" ]; then pass "transfer moved balance (alice=0.6, bob=0.4)"; else fail "transfer mismatch: code=$code alice=$bal_alice bob=$bal_bob"; fi

log "--- test 10: overdraft transfer is rejected ---"
code=$(curl -s -o "$WORKDIR/t10.json" -w "%{http_code}" -X POST "http://127.0.0.1:$PROXY_PORT/wallet/api/transfer" -H "Content-Type: application/json" -d '{"from_user_id":"bob","to_user_id":"alice","amount":999}')
[ "$code" = "400" ] && pass "400 insufficient balance" || fail "expected 400, got $code: $(cat "$WORKDIR/t10.json")"

echo
if [ "$FAIL" -eq 0 ]; then
  echo "=== ALL E2E CHECKS PASSED ==="
  exit 0
else
  echo "=== E2E FAILURES ABOVE — logs in $WORKDIR ==="
  echo "--- proxy.log ---"; tail -60 "$WORKDIR/proxy.log"
  exit 1
fi
